package com.hamhuo.tplanner.ai

import java.time.Instant
import java.time.OffsetDateTime

/**
 * 把模型的 `create_plan` 参数解析成 [PlanProposal]。
 *
 * 这里做两类事，都很克制：
 * 1. **归一化**：截断超长标题、过滤空项、把越界 `color_id` 夹回 0..7、去掉重复子任务。
 * 2. **执行契约里 JSON Schema 表达不了的硬约束**：`start`/`end` 同时给或同时为 null、
 *    `end > start`、`source=none` 时三个时间字段为 null、`depends_on` 必须指向存在的动作。
 *    违反约束时**降级而不是丢弃**——把 `stated`/`inferred` 降为 `none`（丢掉那个可疑的时间），
 *    动作与子任务照常保留。用户宁可看到一条没有时间的任务，也不想看到它凭空消失。
 *
 * 解析失败抛 [AiJsonException] 或 [PlanParseException]，由调用方决定是否走本地兜底。
 */
internal class PlanParseException(message: String) : Exception(message)

internal object PlanResponseParser {

    /** 解析工具参数（原始 JSON 文本）。 */
    fun parseToolArguments(raw: String): PlanProposal {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) throw PlanParseException("工具参数为空")
        val document = try {
            AiJson.parse(trimmed)
        } catch (error: AiJsonException) {
            throw PlanParseException("工具参数不是合法 JSON：${error.message}")
        }
        return parse(document)
    }

    fun parse(document: AiJson): PlanProposal {
        val root = document as? AiJson.Obj ?: throw PlanParseException("工具参数的根必须是对象")

        // 模型偶尔会把整个计划塞进一个包装键；只认已知的那个，不做通用深搜。
        val body = when {
            root.array("topics") != null -> root
            root.obj("plan")?.array("topics") != null -> root.obj("plan")!!
            else -> throw PlanParseException("工具参数里没有 topics 数组")
        }

        val assumptions = strings(body.array("assumptions"))
        val clarification = strings(body.array("needs_clarification"))

        val topics = mutableListOf<PlanTopic>()
        body.array("topics")?.items?.forEachIndexed { topicIndex, element ->
            val topicObject = element as? AiJson.Obj ?: return@forEachIndexed
            val title = text(topicObject, "title", MAX_TITLE_CHARS)
            if (title.isBlank()) return@forEachIndexed
            val topicColor = colorId(topicObject)
            val actions = parseActions(topicObject.array("actions"), title, topicIndex, topicColor)
            topics += PlanTopic(
                title = title,
                evidence = text(topicObject, "evidence", MAX_EVIDENCE_CHARS),
                colorId = topicColor,
                actions = actions,
            )
        }

        if (topics.isEmpty() && assumptions.isEmpty() && clarification.isEmpty()) {
            throw PlanParseException("模型既没有给出主题，也没有说明原因")
        }
        return PlanProposal(
            understanding = text(body, "understanding", MAX_UNDERSTANDING_CHARS),
            topics = topics,
            assumptions = assumptions,
            needsClarification = clarification,
        )
    }

    private fun parseActions(
        array: AiJson.Arr?,
        topicTitle: String,
        topicIndex: Int,
        topicColor: Int,
    ): List<PlannedAction> {
        if (array == null) return emptyList()
        val staged = array.items.mapIndexedNotNull { actionIndex, element ->
            val actionObject = element as? AiJson.Obj ?: return@mapIndexedNotNull null
            val title = text(actionObject, "title", MAX_TITLE_CHARS)
            val subtasks = subtasks(actionObject.array("subtasks"))
            // 没有标题但有子任务时用第一条子任务兜底：宁可标题粗糙，也不要丢掉整件事。
            val effectiveTitle = title.ifBlank { subtasks.firstOrNull()?.text.orEmpty() }
            if (effectiveTitle.isBlank()) return@mapIndexedNotNull null
            StagedAction(
                index = actionIndex,
                draft = PlannedAction(
                    id = "t${topicIndex + 1}a${actionIndex + 1}",
                    topicTitle = topicTitle,
                    title = effectiveTitle.take(MAX_TITLE_CHARS),
                    kind = ActionKind.fromWire(actionObject.string("kind")),
                    dependsOn = strings(actionObject.array("depends_on")),
                    subtasks = subtasks,
                    time = parseTime(actionObject.obj("time")),
                    note = text(actionObject, "note", MAX_NOTE_CHARS),
                    colorId = topicColor,
                ),
            )
        }

        // depends_on 只能指向同一主题内、且排在自己前面的动作；其余一律丢掉（不许出现环）。
        val keptIds = staged.map { it.draft.id }.toSet()
        val positionById = staged.associate { it.draft.id to it.index }
        return staged.map { stagedAction ->
            val clean = stagedAction.draft.dependsOn.filter { dependency ->
                dependency in keptIds && (positionById[dependency] ?: Int.MAX_VALUE) < stagedAction.index
            }
            stagedAction.draft.copy(dependsOn = clean.distinct())
        }
    }

    private fun parseTime(timeObject: AiJson.Obj?): ActionTime {
        if (timeObject == null) return ActionTime()
        val declared = TimeSource.fromWire(timeObject.string("source"))
        val rawStart = timeObject.string("start")?.trim().orEmpty()
        val rawEnd = timeObject.string("end")?.trim().orEmpty()
        val basis = timeObject.string("basis")?.trim()?.take(MAX_BASIS_CHARS)?.takeIf { it.isNotEmpty() }
        val confidence = (timeObject.double("confidence") ?: 0.0).coerceIn(0.0, 1.0)

        // source=none 时三个时间字段必须为空：模型说"没时间"却给了时间，以"没时间"为准。
        if (declared == TimeSource.NONE || (rawStart.isEmpty() && rawEnd.isEmpty())) return ActionTime()

        if (declared == TimeSource.INFERRED && (basis == null || confidence <= 0.0)) {
            // 推断必须有依据；没有依据的推断等于编造，降级为"没时间"。
            return ActionTime()
        }

        val start = if (rawStart.isEmpty()) null else parseInstant(rawStart)
        val end = if (rawEnd.isEmpty()) null else parseInstant(rawEnd)
        // 给了开始却没给结束（或结束解析不出来）时**不补时长**：宁可只留开始时间，
        // 也不让模型顺手编出的 1 小时变成用户日历上的一格。
        if (rawStart.isNotEmpty() && start == null) return ActionTime()
        // 只有 end 时（"周五前交周报"）保留这条截止期限；两者都有时 end 必须晚于 start。
        val effectiveEnd = when {
            end == null -> null
            start == null -> end
            end.isAfter(start) -> end
            else -> null
        }
        return ActionTime(
            source = declared,
            start = start?.toString(),
            end = effectiveEnd?.toString(),
            confidence = if (declared == TimeSource.STATED) 1.0 else confidence,
            basis = basis,
        )
    }

    private fun subtasks(array: AiJson.Arr?): List<ActionSubtask> {
        if (array == null) return emptyList()
        val seen = mutableSetOf<String>()
        return array.items
            .mapNotNull { (it as? AiJson.Obj)?.string("text") ?: (it as? AiJson.Str)?.value }
            .map { it.trim().take(MAX_SUBTASK_CHARS) }
            .filter { it.isNotEmpty() && seen.add(it) }
            .map(::ActionSubtask)
    }

    private fun colorId(object0: AiJson.Obj): Int {
        val value = object0.int("color_id") ?: 0
        return if (value in COLOR_ID_RANGE) value else 0
    }

    /** 取字符串字段并截断；显式 `null` 与缺失都当作空串。 */
    private fun text(source: AiJson.Obj, key: String, limit: Int): String =
        source.string(key)?.trim()?.take(limit).orEmpty()

    private fun strings(array: AiJson.Arr?): List<String> {
        if (array == null) return emptyList()
        val seen = mutableSetOf<String>()
        return array.items
            .mapNotNull { (it as? AiJson.Str)?.value }
            .map { it.trim().take(MAX_BASIS_CHARS) }
            .filter { it.isNotEmpty() && seen.add(it) }
    }

    /**
     * 契约里的时间是**带偏移量**的 ISO 8601（`2026-09-17T19:30:00+08:00`）。
     * 不接受缺偏移量的写法：`2026-09-17T19:30:00` 在两端会解析成不同时刻，
     * 这种"看起来对"的时间比没有时间更危险。
     */
    private fun parseInstant(value: String): Instant? {
        if (!OFFSET_SUFFIX.containsMatchIn(value)) return null
        return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
    }

    private val OFFSET_SUFFIX = Regex("""(?:Z|[+-]\d{2}:?\d{2})$""")

    private data class StagedAction(val index: Int, val draft: PlannedAction)

    private const val MAX_UNDERSTANDING_CHARS = 200
    private const val MAX_NOTE_CHARS = 200
    private const val MAX_BASIS_CHARS = 120
}
