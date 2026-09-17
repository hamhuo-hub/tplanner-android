package com.hamhuo.tplanner.ai

/**
 * 全客户端通用 AI Skill 的契约（`tplanner.plan-extract@1`）。
 *
 * 唯一权威定义在 `design-assets/ai-skill/`：提示词是 `system-prompt.md`，输出结构是
 * `tools/create-plan.json`，语义见 `docs/ai-skill.md`。本文件只是那份契约在 Kotlin 侧的落地，
 * **不要在这里发明新字段**——加字段要先改源资产，再跑
 * `python scripts/generate-ai-skill.py --android`。
 *
 * 三层结构：主题（[PlanTopic]）→ 动作（[PlannedAction]）→ 子任务（[ActionSubtask]）。
 * 时间只有三种来源（[TimeSource]），`inferred` 必须带依据，且永远不会被当成用户陈述。
 */
internal const val AI_SKILL_VERSION = "tplanner.plan-extract@1"
internal const val AI_SKILL_TOOL_NAME = "create_plan"

enum class TimeSource(val wire: String) {
    /** 用户明确说了时间。 */
    STATED("stated"),

    /** 用户没明说，模型按依据推断——必须带 basis。 */
    INFERRED("inferred"),

    /** 推不出来。宁可没有时间，也不要编一个。 */
    NONE("none"),
    ;

    companion object {
        fun fromWire(value: String?): TimeSource =
            entries.firstOrNull { it.wire == value } ?: NONE
    }
}

enum class ActionKind(val wire: String) {
    DO("do"),
    PREPARE("prepare"),
    COMMUNICATE("communicate"),
    TRAVEL("travel"),
    WAIT("wait"),
    REVIEW("review"),
    ;

    companion object {
        fun fromWire(value: String?): ActionKind =
            entries.firstOrNull { it.wire == value } ?: DO
    }
}

/**
 * 一个动作的时间。四个字段共同表达"这个时间是从哪来的、凭什么"。
 *
 * [start]/[end] 保持 **ISO 8601 带偏移量的原样字符串**，只在写入本地记录时才解析成 Instant；
 * 界面上要显示"明天 19:30"，那是各端自己的事（[com.hamhuo.tplanner.ai.describeTimeSource]）。
 */
data class ActionTime(
    val source: TimeSource = TimeSource.NONE,
    val start: String? = null,
    val end: String? = null,
    val confidence: Double = 0.0,
    val basis: String? = null,
) {
    /**
     * 有时间。[start] 可以为空而 [end] 非空——那是一条**截止期限**（"周五前交周报"），
     * 它同样是一份真实的时间约束，没有理由丢掉。反过来（有开始没结束）不允许：
     * 时长是模型最容易顺手编出来的东西，编了就一定写进日历。
     */
    val scheduled: Boolean
        get() = source != TimeSource.NONE && (start != null || end != null)

    /**
     * 复核界面默认是否勾选。[TimeSource.INFERRED] 且置信度低于 [LOW_CONFIDENCE] 时不勾——
     * 没有把握的猜测不能默认落盘。
     */
    val selectedByDefault: Boolean
        get() = when (source) {
            TimeSource.STATED -> true
            TimeSource.INFERRED -> confidence >= LOW_CONFIDENCE
            TimeSource.NONE -> true
        }

    companion object {
        internal const val LOW_CONFIDENCE = 0.5
    }
}

data class ActionSubtask(val text: String)

data class PlannedAction(
    val id: String,
    val topicTitle: String,
    val title: String,
    val kind: ActionKind = ActionKind.DO,
    val dependsOn: List<String> = emptyList(),
    val subtasks: List<ActionSubtask> = emptyList(),
    val time: ActionTime = ActionTime(),
    val note: String = "",
    /** 主题的类别色号；动作继承主题的颜色，避免同一件事的几个动作颜色不一致。 */
    val colorId: Int = 0,
) {
    val checklist: List<String> get() = subtasks.map { it.text }
}

data class PlanTopic(
    val title: String,
    val evidence: String = "",
    val colorId: Int = 0,
    val actions: List<PlannedAction> = emptyList(),
)

data class PlanProposal(
    val understanding: String = "",
    val topics: List<PlanTopic> = emptyList(),
    val assumptions: List<String> = emptyList(),
    val needsClarification: List<String> = emptyList(),
) {
    /** 全部主题的动作打平，顺序即写入顺序。 */
    val actions: List<PlannedAction> get() = topics.flatMap { it.actions }

    val isEmpty: Boolean get() = topics.isEmpty()
}

/** 时间基准：客户端给，模型不许自己算日期。缺少它就没有任何相对日期是可解析的。 */
data class TimeContext(
    val instantIso: String,
    val timeZoneId: String,
    val weekday: String,
    val today: String,
    val tomorrow: String,
    val dayAfter: String,
) {
    val relative: Map<String, String>
        get() = linkedMapOf("today" to today, "tomorrow" to tomorrow, "day_after" to dayAfter)
}

data class ScheduleFact(val title: String, val start: String, val end: String)

data class PlanLimits(
    val maxTopics: Int = 8,
    val maxActions: Int = 24,
    val maxStepsPerAction: Int = 8,
)

/** 允许的类别色号；与 `x-tplanner-color` 的 0..7 一致。 */
internal val COLOR_ID_RANGE = 0..7

/** 标题 / 子任务 / 依据的长度上限，两端一致（见系统提示词的字段纪律）。 */
internal const val MAX_TITLE_CHARS = 40
internal const val MAX_SUBTASK_CHARS = 30
internal const val MAX_EVIDENCE_CHARS = 30

/** 时间依据、假设等一句话说明的长度上限。 */
internal const val MAX_BASIS_CHARS = 120

/** 一句话说清这个动作的时间是怎么来的，给日志与复核界面用。 */
fun describeTimeSource(time: ActionTime): String = when (time.source) {
    TimeSource.STATED -> "stated"
    TimeSource.INFERRED -> "inferred(${"%.2f".format(time.confidence)})"
    TimeSource.NONE -> "none"
}
