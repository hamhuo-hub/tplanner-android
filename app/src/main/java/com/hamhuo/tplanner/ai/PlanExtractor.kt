package com.hamhuo.tplanner.ai

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 业务门面：把"用户写了一段话"变成"可以写进本地存储的提案"。
 *
 * 它在模型路径与本地兜底之间做取舍：
 * - 模型给出可用提案 → 用它；
 * - 模型不可用（没 key、断网、429、余额不足、结构跑偏）→ 用 [LocalPlanFallback]，并标记
 *   [PlanExtraction.usedFallback]，界面必须如实告诉用户"这是本地规则识别的"；
 * - 两者都没有结果 → 返回 null，界面提示"没识别到待办"。
 *
 * 写入本地记录、稳定 UID、默认勾选策略都不在这里——那是界面与存储层的事。
 */
class PlanExtractor(
    private val assets: AiSkillAssets?,
    private val transport: AiChatTransport?,
    private val model: String = DeepSeekChatTransport.MODEL_FLASH,
    private val userId: String? = null,
    private val strictToolMode: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var client: AiSkillClient? = null

    val available: Boolean get() = assets != null && transport != null

    /**
     * 多轮对话的入口：同一个 [PlanExtractor] 实例就是一条对话线程。
     * 上一轮的提案通过 [previousProposal] 传入，下一轮就是"修改"而不是重新提取。
     */
    fun extract(
        text: String,
        now: ZonedDateTime,
        requestId: String = "",
        locationHint: String = "",
        knownSchedule: List<ScheduleFact> = emptyList(),
        previousProposal: PlanProposal? = null,
        threadId: String = requestId,
        onTelemetry: (PlanTelemetry) -> Unit = {},
    ): PlanExtraction {
        val localContext = timeContextOf(now)
        val body = text.trim()
        if (body.isEmpty()) return PlanExtraction(proposal = null, usedFallback = false, reason = "empty_input")

        val activeAssets = assets
        val activeTransport = transport
        if (activeAssets != null && activeTransport != null) {
            val activeClient = client ?: AiSkillClient(
                transport = activeTransport,
                assets = activeAssets,
                model = model,
                userId = userId,
                strictToolMode = strictToolMode,
                clock = clock,
            ).also { client = it }
            activeClient.threadId = threadId

            when (
                val outcome = activeClient.sendTurn(
                    userText = body,
                    context = localContext,
                    locationHint = locationHint,
                    knownSchedule = knownSchedule,
                    previousProposal = previousProposal,
                )
            ) {
                is PlanOutcome.Propose -> {
                    onTelemetry(outcome.telemetry)
                    return PlanExtraction(outcome.proposal, usedFallback = false, reason = "")
                }

                is PlanOutcome.Empty -> {
                    onTelemetry(outcome.telemetry)
                    // 模型答了但没有可用计划：仍然给本地兜底一次机会，用户不该因此一无所获。
                    val fallback = LocalPlanFallback.extract(body, localContext)
                    return if (fallback.isEmpty) {
                        PlanExtraction(null, usedFallback = false, reason = outcome.reason)
                    } else {
                        PlanExtraction(fallback, usedFallback = true, reason = outcome.reason)
                    }
                }

                is PlanOutcome.Unavailable -> {
                    val fallback = LocalPlanFallback.extract(body, localContext)
                    return if (fallback.isEmpty) {
                        PlanExtraction(null, usedFallback = false, reason = outcome.reason)
                    } else {
                        PlanExtraction(fallback, usedFallback = true, reason = outcome.reason)
                    }
                }
            }
        }

        val fallback = LocalPlanFallback.extract(body, localContext)
        return if (fallback.isEmpty) {
            PlanExtraction(null, usedFallback = false, reason = "unavailable")
        } else {
            PlanExtraction(fallback, usedFallback = true, reason = "unavailable")
        }
    }

    /** 开一条新的对话：上一轮的上下文到此为止（确认、放弃、或用户重新开始）。 */
    fun resetThread() {
        client?.reset()
        client = null
    }

    companion object {
        /** 客户端给的时间基准。模型不许自己算日期，这些字段就是它唯一能用的锚点。 */
        fun timeContextOf(now: ZonedDateTime): TimeContext {
            val weekday = WEEKDAYS[now.dayOfWeek.value - 1]
            val date = now.toLocalDate()
            return TimeContext(
                instantIso = now.format(INSTANT_FORMAT),
                timeZoneId = now.zone.id,
                weekday = weekday,
                today = date.toString(),
                tomorrow = date.plusDays(1).toString(),
                dayAfter = date.plusDays(2).toString(),
            )
        }

        fun timeContextOf(now: Instant, zone: ZoneId): TimeContext = timeContextOf(now.atZone(zone))

        private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        /** 基准时间必须带时区偏移：两端拿到同一串才能解析出同一时刻。 */
        private val INSTANT_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX")
    }
}

data class PlanExtraction(
    val proposal: PlanProposal?,
    val usedFallback: Boolean,
    val reason: String,
) {
    val isEmpty: Boolean get() = proposal == null || proposal.isEmpty
}
