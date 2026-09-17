package com.hamhuo.tplanner.ai

import java.net.HttpURLConnection
import java.net.URL

/** 一条对话消息。顺序即内容——DeepSeek 是无状态接口，每轮都要原样重发。 */
data class ChatMessage(
    val role: String,
    val content: String = "",
    /**
     * 思考模式的推理内容。**带 `tools` 的请求里，历史轮的 `reasoning_content` 必须原样回传**，
     * 否则接口直接返回 400（见 docs/ai-skill.md §2.1）。丢掉它就等于丢掉多轮能力。
     */
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
) {
    val isToolResult: Boolean get() = role == "tool"
}

data class ToolCall(val id: String, val name: String, val arguments: String)

data class ChatUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val cacheHitTokens: Int = 0,
    val cacheMissTokens: Int = 0,
    val reasoningTokens: Int = 0,
)

data class ChatResult(
    val content: String = "",
    val reasoningContent: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String = "",
    val usage: ChatUsage = ChatUsage(),
) {
    /** 模型选择了"说话"而不是调用工具——思考模式下 `tool_choice=auto` 时完全可能发生。 */
    val hasToolCall: Boolean get() = toolCalls.isNotEmpty()
}

/** 调用参数。默认值就是契约推荐值（见 docs/ai-skill.md §4.5）。 */
data class ChatOptions(
    /** 思考模式下不能强制 `tool_choice`，所以这里必须是 `auto`。 */
    val toolChoiceAuto: Boolean = true,
    val thinkingEnabled: Boolean = true,
    /** `low` 足够做提取与时间推理，并且明显更便宜。 */
    val reasoningEffort: String = "low",
    val maxTokens: Int = 4096,
)

/** 失败分类：决定上层是重试、走本地兜底，还是明确报错。 */
sealed interface ChatFailure {
    /** 网络层失败（连不上、超时、DNS）：可重试，且适合走本地兜底。 */
    val retryable: Boolean

    data class Transport(override val retryable: Boolean, val detail: String) : ChatFailure

    data class Http(val status: Int, val detail: String) : ChatFailure {
        override val retryable: Boolean get() = status == 429 || status >= 500
    }

    data class Malformed(val detail: String) : ChatFailure {
        override val retryable: Boolean get() = false
    }
}

sealed interface ChatOutcome {
    data class Success(val result: ChatResult) : ChatOutcome
    data class Failure(val failure: ChatFailure) : ChatOutcome
}

class ChatRequest(
    val model: String,
    val systemPrompt: String,
    val contextBlock: String,
    val messages: List<ChatMessage>,
    val toolSchema: AiJson.Obj,
    val options: ChatOptions,
    /** 前缀缓存与限流分组用；不含用户隐私内容。 */
    val userId: String?,
    /** 仅 beta 端点（`/beta`）支持 `strict` 工具参数校验。 */
    val strictToolMode: Boolean,
)

/** HTTP 传输。抽成接口是为了让上层编排能在没有网络的单元测试里被完整验证。 */
interface AiChatTransport {
    fun send(request: ChatRequest): ChatOutcome
}

class DeepSeekChatTransport(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMs: Int = 30_000,
    /**
     * 思考模式 + 工具调用的返回时间明显长于非思考；60 秒会把"正常但慢"的请求误判成失败。
     */
    private val readTimeoutMs: Int = 180_000,
    private val maxAttempts: Int = 2,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : AiChatTransport {

    override fun send(request: ChatRequest): ChatOutcome {
        var lastFailure: ChatFailure? = null
        var attempt = 0
        while (attempt < maxAttempts) {
            if (attempt > 0) sleep(500L * attempt)
            attempt++
            when (val outcome = attemptSend(request)) {
                is ChatOutcome.Success -> return outcome
                is ChatOutcome.Failure -> {
                    lastFailure = outcome.failure
                    if (!outcome.failure.retryable) return outcome
                }
            }
        }
        return ChatOutcome.Failure(lastFailure ?: ChatFailure.Malformed("未知失败"))
    }

    private fun attemptSend(request: ChatRequest): ChatOutcome {
        val body = buildBody(request).render()
        val connection = try {
            URL(endpoint(request)).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            return ChatOutcome.Failure(ChatFailure.Transport(true, "无法建立连接：${error.javaClass.simpleName}"))
        }
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(body.utf8Bytes()) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = runCatching { readFully(connection.errorStream, ERROR_BODY_LIMIT) }.getOrDefault("")
                return ChatOutcome.Failure(ChatFailure.Http(status, detail.take(ERROR_DETAIL_CHARS)))
            }
            val payload = readFully(connection.inputStream)
            ChatOutcome.Success(parseResponse(payload))
        } catch (error: AiJsonException) {
            ChatOutcome.Failure(ChatFailure.Malformed("响应解析失败：${error.message}"))
        } catch (error: Exception) {
            ChatOutcome.Failure(ChatFailure.Transport(true, error.javaClass.simpleName))
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** 测试可见：strict 模式与普通模式的端点不同，这是契约的一部分。 */
    internal fun endpointForTest(request: ChatRequest): String = endpoint(request)

    private fun endpoint(request: ChatRequest): String {
        val base = baseUrl.trimEnd('/')
        return if (request.strictToolMode) "$base/beta/chat/completions" else "$base/chat/completions"
    }

    /**
     * 请求体只有一个来源，端到端逐字可控：消息、工具、思考开关、`max_tokens`、`user_id`。
     * 不传 `temperature` —— 思考模式会忽略它，传了只会误导后来读代码的人。
     */
    internal fun buildBody(request: ChatRequest): AiJson.Obj {
        val messages = mutableListOf<AiJson>()
        messages.add(renderMessage(ChatMessage(role = "system", content = request.systemPrompt)))
        // 时间基准单独成一条：system 前缀因此跨请求稳定，能吃到前缀缓存；
        // 模型也不会把客户端注入的元数据误当成用户说的话。
        if (request.contextBlock.isNotBlank()) {
            messages.add(renderMessage(ChatMessage(role = "system", content = request.contextBlock)))
        }
        request.messages.forEach { messages.add(renderMessage(it)) }

        val fields = linkedMapOf<String, AiJson>(
            "model" to AiJson.of(request.model),
            "messages" to AiJson.arrayOf(messages),
            "thinking" to AiJson.objOf(
                "type" to AiJson.of(if (request.options.thinkingEnabled) "enabled" else "disabled"),
            ),
            "max_tokens" to AiJson.of(request.options.maxTokens),
            // 思考模式下 `required` / 指定工具名都会 400；要么关思考，要么只用 auto。
            "tool_choice" to AiJson.of(if (request.options.toolChoiceAuto) "auto" else "required"),
        )
        if (request.options.thinkingEnabled) {
            fields["reasoning_effort"] = AiJson.of(request.options.reasoningEffort)
        }
        val tool = LinkedHashMap<String, AiJson>(request.toolSchema.entries)
        fields["tools"] = AiJson.arrayOf(listOf(AiJson.objOf("type" to AiJson.of("function"), "function" to AiJson.objOf(tool))))
        request.userId?.takeIf { it.isNotBlank() }?.let { fields["user_id"] = AiJson.of(it) }
        return AiJson.objOf(fields)
    }

    private fun renderMessage(message: ChatMessage): AiJson.Obj {
        val fields = linkedMapOf<String, AiJson>("role" to AiJson.of(message.role))
        if (message.isToolResult) {
            fields["content"] = AiJson.of(message.content)
            message.toolCallId?.let { fields["tool_call_id"] = AiJson.of(it) }
            return AiJson.objOf(fields)
        }
        fields["content"] = AiJson.of(message.content)
        // 只有非空才算"历史轮里有推理内容"，空串回传没有意义。
        message.reasoningContent?.takeIf { it.isNotEmpty() }?.let { fields["reasoning_content"] = AiJson.of(it) }
        if (message.toolCalls.isNotEmpty()) {
            fields["tool_calls"] = AiJson.arrayOf(
                message.toolCalls.map { call ->
                    AiJson.objOf(
                        "id" to AiJson.of(call.id),
                        "type" to AiJson.of("function"),
                        "function" to AiJson.objOf(
                            "name" to AiJson.of(call.name),
                            "arguments" to AiJson.of(call.arguments),
                        ),
                    )
                },
            )
        }
        return AiJson.objOf(fields)
    }

    /** 测试可见：只读叶子字段，不整体持有响应对象。 */
    internal fun parseResponseForTest(payload: String): ChatResult = parseResponse(payload)

    /** 只读叶子字段，不整体持有响应对象。 */
    private fun parseResponse(payload: String): ChatResult {
        val root = AiJson.parse(payload) as? AiJson.Obj
            ?: throw AiJsonException("响应根不是对象", 0)
        root.obj("error")?.let { error ->
            throw AiJsonException("接口返回错误：${error.string("message").orEmpty()}", 0)
        }
        val choice = root.array("choices")?.items?.firstOrNull() as? AiJson.Obj
            ?: throw AiJsonException("响应里没有 choices", 0)
        val message = choice.obj("message")
        val calls = message?.array("tool_calls")?.items.orEmpty().mapNotNull { element ->
            val call = element as? AiJson.Obj ?: return@mapNotNull null
            val function = call.obj("function") ?: return@mapNotNull null
            ToolCall(
                id = call.string("id").orEmpty(),
                name = function.string("name").orEmpty(),
                arguments = function.string("arguments").orEmpty(),
            )
        }
        val usage = root.obj("usage")
        return ChatResult(
            content = message?.string("content").orEmpty(),
            reasoningContent = message?.string("reasoning_content")?.takeIf { it.isNotEmpty() },
            toolCalls = calls,
            finishReason = choice.string("finish_reason").orEmpty(),
            usage = ChatUsage(
                promptTokens = usage?.int("prompt_tokens") ?: 0,
                completionTokens = usage?.int("completion_tokens") ?: 0,
                totalTokens = usage?.int("total_tokens") ?: 0,
                cacheHitTokens = usage?.int("prompt_cache_hit_tokens") ?: 0,
                cacheMissTokens = usage?.int("prompt_cache_miss_tokens") ?: 0,
                reasoningTokens = usage?.obj("completion_tokens_details")?.int("reasoning_tokens") ?: 0,
            ),
        )
    }

    companion object {
        /** 官方 base；路径按文档是 `/chat/completions`。 */
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        /** 只有 flash 与 v4-pro 两个 id；`deepseek-v4-flash` 已是被下线模型的路由别名。 */
        const val MODEL_FLASH = "deepseek-flash"

        private const val ERROR_BODY_LIMIT = 64 * 1024
        private const val ERROR_DETAIL_CHARS = 400
    }
}
