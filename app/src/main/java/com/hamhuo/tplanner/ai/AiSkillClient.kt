package com.hamhuo.tplanner.ai

/**
 * 一次提取的结果。**"模型没给结果"和"模型给了但结构不可用"必须分开**，
 * 否则界面只能对两种情况都说"AI 服务不可用"，用户永远不知道发生了什么。
 */
sealed interface PlanOutcome {
    /** 模型给出了可用提案。 */
    data class Propose(val proposal: PlanProposal, val telemetry: PlanTelemetry) : PlanOutcome

    /** 模型（或网络）不可用：可以用 [LocalPlanFallback] 顶上。 */
    data class Unavailable(val reason: String, val detail: String) : PlanOutcome

    /** 模型答了，但不是可用的计划（例如它在闲聊、或结构跑偏）。 */
    data class Empty(val reason: String, val understanding: String, val telemetry: PlanTelemetry) : PlanOutcome
}

/** 一次调用的遥测。字段名与桌面端保持一致，便于对照两端的日志。 */
data class PlanTelemetry(
    val skillVersion: String,
    val threadId: String,
    val turn: Int,
    val model: String,
    val topicCount: Int = 0,
    val actionCount: Int = 0,
    val statedCount: Int = 0,
    val inferredCount: Int = 0,
    val noneCount: Int = 0,
    val finishReason: String = "",
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val elapsedMs: Long = 0,
    val toolCallUsed: Boolean = true,
    val degraded: Boolean = false,
) {
    /** 一行可读的日志正文：不包含用户原文，也不包含任务标题。 */
    fun logLine(): String = buildString {
        append("skill=$skillVersion thread=$threadId turn=$turn model=$model ")
        append("topics=$topicCount actions=$actionCount ")
        append("time_sources=stated:$statedCount,inferred:$inferredCount,none:$noneCount ")
        append("finish=$finishReason toolCall=$toolCallUsed degraded=$degraded ")
        append("cache_hit=$cacheHitTokens cache_miss=$cacheMissTokens reasoning=$reasoningTokens ")
        append("elapsedMs=$elapsedMs")
    }

    companion object {
        fun counts(proposal: PlanProposal): Triple<Int, Int, Int> {
            val actions = proposal.actions
            return Triple(
                actions.count { it.time.source == TimeSource.STATED },
                actions.count { it.time.source == TimeSource.INFERRED },
                actions.count { it.time.source == TimeSource.NONE },
            )
        }
    }
}

/**
 * 多轮对话客户端：负责消息累积、工具调用循环、`reasoning_content` 回传，以及把模型输出解析成契约。
 *
 * 它不做业务判断（不决定写哪条记录、不决定默认勾选），那是 [PlanExtractor] 与界面层的事。
 *
 * **多轮不是可选项**：带 `tools` 的请求里，历史轮的 `reasoning_content` 必须原样回传，
 * 否则接口返回 400。所以每一轮的 assistant 消息连同它的推理内容一起留在 [thread] 里。
 */
internal class AiSkillClient(
    private val transport: AiChatTransport,
    private val assets: AiSkillAssets,
    private val model: String = DeepSeekChatTransport.MODEL_FLASH,
    private val userId: String? = null,
    private val strictToolMode: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val thread = mutableListOf<ChatMessage>()

    var turn: Int = 0
        private set

    /**
     * 这条对话的标识。由调用方（界面/存储）给，用于遥测与稳定 UID 的命名空间；
     * 它**不发给模型**，也不进同步。
     */
    var threadId: String = "local"

    /** 供持久化与排查用：当前对话的消息数。 */
    val threadSize: Int get() = thread.size

    fun reset() {
        thread.clear()
        turn = 0
    }

    /**
     * 发一轮。成功时把这一轮完整地追加进 [thread]（含推理内容与全部工具结果），
     * 失败时**不追加**——半截的对话历史比没有历史更危险，下一轮会 400。
     */
    fun sendTurn(
        userText: String,
        context: TimeContext,
        locationHint: String = "",
        knownSchedule: List<ScheduleFact> = emptyList(),
        previousProposal: PlanProposal? = null,
        options: ChatOptions = ChatOptions(),
    ): PlanOutcome {
        val threadId = this.threadId
        val startedAt = clock()
        val turnNumber = turn + 1
        val userMessage = ChatMessage(role = "user", content = userText)
        val outgoing = thread + userMessage

        val request = ChatRequest(
            model = model,
            systemPrompt = assets.systemPrompt,
            contextBlock = buildContextBlock(context, locationHint, knownSchedule, previousProposal),
            messages = outgoing,
            toolSchema = assets.toolSchema,
            options = options,
            userId = userId,
            strictToolMode = strictToolMode,
        )

        val outcome = try {
            transport.send(request)
        } catch (error: Exception) {
            return PlanOutcome.Unavailable("transport_exception", error.javaClass.simpleName)
        }

        when (outcome) {
            is ChatOutcome.Failure -> {
                val detail = when (val failure = outcome.failure) {
                    is ChatFailure.Http -> "http_${failure.status}:${failure.detail}"
                    is ChatFailure.Transport -> "transport:${failure.detail}"
                    is ChatFailure.Malformed -> "malformed:${failure.detail}"
                }
                val reason = when (val failure = outcome.failure) {
                    is ChatFailure.Http -> if (failure.status == 401 || failure.status == 402) "auth_or_quota" else "http_error"
                    is ChatFailure.Transport -> "network"
                    is ChatFailure.Malformed -> "bad_response"
                }
                return PlanOutcome.Unavailable(reason, detail)
            }

            is ChatOutcome.Success -> {
                val result = outcome.result
                if (result.finishReason == "insufficient_system_resource" || result.finishReason == "aborted") {
                    return PlanOutcome.Unavailable("interrupted", result.finishReason)
                }
                // 这一轮怎么答的，要完整留下来，否则下一轮无法回传。
                thread += userMessage
                thread += ChatMessage(
                    role = "assistant",
                    content = result.content,
                    reasoningContent = result.reasoningContent,
                    toolCalls = result.toolCalls,
                )
                result.toolCalls.forEach { call ->
                    thread += ChatMessage(
                        role = "tool",
                        content = toolResultFor(call),
                        toolCallId = call.id,
                    )
                }
                turn = turnNumber

                val usage = result.usage
                val base = PlanTelemetry(
                    skillVersion = assets.skillVersion,
                    threadId = threadId,
                    turn = turnNumber,
                    model = model,
                    finishReason = result.finishReason,
                    cacheHitTokens = usage.cacheHitTokens,
                    cacheMissTokens = usage.cacheMissTokens,
                    reasoningTokens = usage.reasoningTokens,
                    elapsedMs = clock() - startedAt,
                    toolCallUsed = result.hasToolCall,
                )

                return interpret(result, base)
            }
        }
    }

    private fun interpret(result: ChatResult, base: PlanTelemetry): PlanOutcome {
        val named = result.toolCalls.firstOrNull { it.name == AI_SKILL_TOOL_NAME }
        if (named != null) {
            return try {
                val proposal = PlanResponseParser.parseToolArguments(named.arguments)
                finish(proposal, base, degraded = false)
            } catch (error: PlanParseException) {
                PlanOutcome.Empty("tool_arguments_unparsable", error.message.orEmpty(), base)
            } catch (error: AiJsonException) {
                PlanOutcome.Empty("tool_arguments_unparsable", error.message.orEmpty(), base)
            }
        }
        if (result.hasToolCall) {
            // 调了别的工具（或工具名被模型改写）：当成"没给出计划"，但保留 finish_reason 供排查。
            return PlanOutcome.Empty("unexpected_tool", result.toolCalls.joinToString { it.name }, base)
        }
        // 思考模式下 tool_choice=auto，模型有可能选择"说话"。若它说的就是契约 JSON，照样接受。
        val content = result.content.trim()
        if (content.startsWith("{")) {
            return try {
                val proposal = PlanResponseParser.parse(AiJson.parse(content))
                finish(proposal, base, degraded = false)
            } catch (_: Exception) {
                PlanOutcome.Empty("content_not_json", content.take(CONTENT_SAMPLE_CHARS), base)
            }
        }
        return PlanOutcome.Empty("no_tool_call", content.take(CONTENT_SAMPLE_CHARS), base)
    }

    private fun finish(proposal: PlanProposal, base: PlanTelemetry, degraded: Boolean): PlanOutcome {
        val (stated, inferred, none) = PlanTelemetry.counts(proposal)
        val telemetry = base.copy(
            topicCount = proposal.topics.size,
            actionCount = proposal.actions.size,
            statedCount = stated,
            inferredCount = inferred,
            noneCount = none,
            degraded = degraded,
        )
        return if (proposal.isEmpty) {
            PlanOutcome.Empty("no_topics", proposal.understanding, telemetry)
        } else {
            PlanOutcome.Propose(proposal, telemetry)
        }
    }

    /** 工具结果要回给模型：把这一轮"已接受"的结构原样告诉它，多轮才不会重复提取。 */
    private fun toolResultFor(call: ToolCall): String = when (call.name) {
        AI_SKILL_TOOL_NAME -> """{"accepted":true,"note":"plan received"}"""
        else -> """{"accepted":false,"note":"unknown tool"}"""
    }

    /**
     * 时间基准与上下文，放在 system 前缀之后单独成条。
     *
     * 前缀缓存按"完整匹配的缓存单元"命中：只要 [AiSkillAssets.systemPrompt] 逐字不变，
     * 多轮追加就能吃到缓存；把动态内容塞进提示词开头会让缓存永远不命中。
     */
    internal fun buildContextBlock(
        context: TimeContext,
        locationHint: String,
        knownSchedule: List<ScheduleFact>,
        previousProposal: PlanProposal?,
    ): String = buildString {
        appendLine("当前基准时间（唯一基准）：${context.instantIso}，${context.weekday}，时区 ${context.timeZoneId}")
        appendLine("相对日期：今天=${context.today}，明天=${context.tomorrow}，后天=${context.dayAfter}")
        if (locationHint.isNotBlank()) appendLine("地点：$locationHint")
        if (knownSchedule.isNotEmpty()) {
            appendLine("已有日程（不要改动、不要重复安排，尽量避开）：")
            knownSchedule.take(MAX_SCHEDULE_FACTS).forEach { fact ->
                appendLine("- ${fact.title.take(MAX_TITLE_CHARS)}：${fact.start} ~ ${fact.end}")
            }
        }
        previousProposal?.let { previous ->
            appendLine("上一轮已经给出的计划（用户正在此基础上修改，请只做用户要求的调整）：")
            appendLine(renderProposal(previous))
        }
    }

    private fun renderProposal(proposal: PlanProposal): String = buildString {
        proposal.topics.forEachIndexed { topicIndex, topic ->
            appendLine("主题 ${topicIndex + 1}：${topic.title}")
            topic.actions.forEach { action ->
                val time = when (action.time.source) {
                    TimeSource.STATED -> "时间=${action.time.start ?: "-"}~${action.time.end ?: "-"}（用户明确）"
                    TimeSource.INFERRED -> "时间=${action.time.start ?: "-"}~${action.time.end ?: "-"}（推测）"
                    TimeSource.NONE -> "时间=未定"
                }
                appendLine("  - 动作：${action.title}；$time")
            }
        }
    }

    private companion object {
        const val MAX_SCHEDULE_FACTS = 20
        const val CONTENT_SAMPLE_CHARS = 120
    }
}
