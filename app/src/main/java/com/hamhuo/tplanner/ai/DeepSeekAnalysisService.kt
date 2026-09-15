package com.hamhuo.tplanner

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger

internal fun parseScheduleLocalDateTime(value: String): LocalDateTime? {
    val candidate = value.trim()
    if (candidate.isBlank()) return null
    return runCatching { LocalDateTime.parse(candidate) }.getOrNull()
        ?: runCatching {
            OffsetDateTime.parse(candidate).atZoneSameInstant(APP_ZONE).toLocalDateTime()
        }.getOrNull()
        ?: runCatching {
            ZonedDateTime.parse(candidate).withZoneSameInstant(APP_ZONE).toLocalDateTime()
        }.getOrNull()
        ?: runCatching {
            Instant.parse(candidate).atZone(APP_ZONE).toLocalDateTime()
        }.getOrNull()
}

internal fun scheduleTemporalContext(now: LocalDateTime, timestamp: String): String {
    val weekday = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[now.dayOfWeek.value - 1]
    val zonedNow = now.atZone(APP_ZONE)
    return buildString {
        append("当前基准时间（唯一基准）：$zonedNow，$weekday\n")
        append("相对日期：今天=${now.toLocalDate()}，明天=${now.toLocalDate().plusDays(1)}，后天=${now.toLocalDate().plusDays(2)}\n")
        if (timestamp.isNotBlank()) {
            append("原始记录时间：$timestamp（仅在文字明确相对记录时刻时作为锚点）\n")
        }
    }
}

private val WHITESPACE = Regex("\\s+")
private val COLOR_IDS = 0..7

/**
 * DeepSeek-backed task extractor.
 *
 * Single-turn: user text in → the list of independent goal/theme tasks it contains. No QA, no
 * clarifying questions, no event/status/task classification and no alarm decisions. A task with no
 * explicit time keeps a null time: the client never invents one.
 */
class DeepSeekAnalysisService(private val apiKey: String) {

    /**
     * One independent goal/theme extracted from the text. Sub-steps of the same goal belong to
     * [checklist]; independent goals become separate records.
     */
    data class ProposedTask(
        val title: String,
        val checklist: List<String> = emptyList(),
        val startIso: String? = null,
        val endIso: String? = null,
        val note: String = "",
        val colorId: Int = 0,
    )

    /** Extracts every independent goal/theme in [text]; empty when nothing could be extracted. */
    suspend fun extractTasks(
        text: String,
        timestamp: String = "",
        location: String = "",
        requestId: String = "",
    ): List<ProposedTask> = withContext(Dispatchers.IO) {
        val logRequestId = requestIdForLog(requestId)
        val startedAt = SystemClock.elapsedRealtime()
        val referenceNow = LocalDateTime.now(APP_ZONE).withNano(0)
        Log.i(
            TAG,
            "request=$logRequestId phase=extract_start inputChars=${text.length} " +
                "timestampProvided=${timestamp.isNotBlank()} locationProvided=${location.isNotBlank()}",
        )
        val prompt = buildString {
            append(scheduleTemporalContext(referenceNow, timestamp))
            if (location.isNotBlank()) append("地点：$location\n")
            append("用户写下的文字：\n\"\"\"\n$text\n\"\"\"\n\n")
            append("严格按上述时间基准解析今天、明天、周几、稍后、今晚等表达，然后立即调用 create_tasks。")
        }
        try {
            val tasks = normalize(callDeepSeek(prompt, logRequestId), text, logRequestId)
            Log.i(
                TAG,
                "request=$logRequestId phase=extract_result " +
                    "result=${if (tasks.isEmpty()) "no_task" else "proposal"} taskCount=${tasks.size} " +
                    "scheduledCount=${tasks.count { it.startIso != null }} " +
                    "checklistCount=${tasks.sumOf { it.checklist.size }} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            tasks
        } catch (e: Exception) {
            Log.e(
                TAG,
                "request=$logRequestId phase=extract_failed errorType=${e.javaClass.simpleName} " +
                    "at=${exceptionLocationForLog(e)} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            emptyList()
        }
    }

    // ── defaults ─────────────────────────────────────────────────────────────

    /**
     * Normalizes what the model returned without inventing facts: a missing time stays missing,
     * only an end that the user did state (or that follows from a stated start) is kept.
     */
    private fun normalize(raw: List<ProposedTask>, text: String, requestId: String): List<ProposedTask> {
        var tasks = raw.mapNotNull { task ->
            val title = task.title.trim().take(MAX_TITLE_CHARS)
            title.takeIf { it.isNotEmpty() }?.let { task.normalized(it) }
        }
        // A model that omitted the only title still gets one derived from the user's own words.
        if (tasks.isEmpty() && raw.isNotEmpty()) {
            val title = text.trim().replace(WHITESPACE, " ").take(MAX_TITLE_CHARS).ifBlank { UNTITLED_TASK }
            tasks = listOf(raw.first().normalized(title))
        }
        Log.d(
            TAG,
            "request=$requestId phase=defaults rawTaskCount=${raw.size} keptTaskCount=${tasks.size} " +
                "droppedTitles=${raw.size - tasks.size} " +
                "scheduledCount=${tasks.count { it.startIso != null }} " +
                "checklistCount=${tasks.sumOf { it.checklist.size }}",
        )
        return tasks
    }

    private fun ProposedTask.normalized(title: String): ProposedTask {
        val start = parseScheduleLocalDateTime(startIso.orEmpty())
        // No stated start → no time at all; never substitute a default slot.
        val end = parseScheduleLocalDateTime(endIso.orEmpty())
            ?.takeIf { start != null && it.isAfter(start) }
            ?: start?.plusHours(1)
        return ProposedTask(
            title = title,
            checklist = checklist.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            startIso = start?.toString(),
            endIso = end?.toString(),
            note = note,
            colorId = colorId.takeIf { it in COLOR_IDS } ?: 0,
        )
    }

    // ── API call ─────────────────────────────────────────────────────────────

    private fun callDeepSeek(userMessage: String, requestId: String): List<ProposedTask> {
        val conn = URL(DEEPSEEK_URL).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", SYSTEM_PROMPT)
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }
        val body = JSONObject().apply {
            put("model", MODEL)
            put("thinking", JSONObject().put("type", "disabled"))
            put("messages", messages)
            put("tools", buildTools())
            put("tool_choice", "required")
            put("max_tokens", MAX_TOKENS)
            put("temperature", 0.1)
        }.toString()

        val startedAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "request=$requestId phase=http_request model=$MODEL promptChars=${userMessage.length} " +
                "bodyChars=${body.length} tool=$CREATE_TASKS_TOOL toolChoice=required " +
                "connectTimeoutMs=${conn.connectTimeout} readTimeoutMs=${conn.readTimeout}",
        )
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val responseCode = conn.responseCode
            Log.i(
                TAG,
                "request=$requestId phase=http_response status=$responseCode " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            if (responseCode !in 200..299) {
                val error = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.readText()
                    ?: "HTTP $responseCode"
                Log.w(
                    TAG,
                    "request=$requestId phase=http_error status=$responseCode " +
                        "bodyChars=${error.length}",
                )
                throw Exception("DeepSeek API error: HTTP $responseCode")
            }
            val response = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val choices = JSONObject(response).optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                Log.w(
                    TAG,
                    "request=$requestId phase=response_parse result=missing_choices responseChars=${response.length}",
                )
                return emptyList()
            }
            val choice = choices.getJSONObject(0)
            val message = choice.getJSONObject("message")
            val calls = message.optJSONArray("tool_calls")
            val finishReason = choice.optString("finish_reason")
                .takeIf { it in KNOWN_FINISH_REASONS }
                ?: "other"
            Log.d(
                TAG,
                "request=$requestId phase=response_parse finishReason=$finishReason " +
                    "responseChars=${response.length} contentChars=${message.optString("content", "").length} " +
                    "reasoningChars=${message.optString("reasoning_content", "").length} " +
                    "toolCallCount=${calls?.length() ?: 0}",
            )
            if (calls == null || calls.length() == 0) {
                Log.w(TAG, "request=$requestId phase=tool_call result=missing")
                return emptyList()
            }

            val rawCall = calls.getJSONObject(0)
            val function = rawCall.getJSONObject("function")
            val toolName = function.optString("name")
            val rawArguments = function.optString("arguments", "{}")
            if (toolName != CREATE_TASKS_TOOL) {
                Log.w(
                    TAG,
                    "request=$requestId phase=tool_call result=unexpected_tool",
                )
                return emptyList()
            }

            val args = JSONObject(rawArguments)
            val rawTasks = args.optJSONArray("tasks") ?: JSONArray()
            val knownArgumentCount = rawTasks.optJSONObject(0)
                ?.let { first -> TASK_ARGUMENT_KEYS.count(first::has) }
                ?: 0
            Log.i(
                TAG,
                "request=$requestId phase=tool_call result=received tool=$toolName " +
                    "argumentChars=${rawArguments.length} taskCount=${rawTasks.length()} " +
                    "knownArgumentCount=$knownArgumentCount",
            )
            val proposal = parseTasks(rawTasks)
            Log.d(
                TAG,
                "request=$requestId phase=tool_parse taskCount=${proposal.size} " +
                    "titledCount=${proposal.count { it.title.isNotBlank() }} " +
                    "startPresentCount=${proposal.count { it.startIso.orEmpty().isNotBlank() }} " +
                    "checklistCount=${proposal.sumOf { it.checklist.size }}",
            )
            return proposal
        } finally {
            conn.disconnect()
        }
    }

    private fun parseTasks(rawTasks: JSONArray): List<ProposedTask> = buildList {
        for (index in 0 until rawTasks.length()) {
            val task = rawTasks.optJSONObject(index) ?: continue
            add(
                ProposedTask(
                    title = task.optNullableString("title"),
                    checklist = parseChecklist(task),
                    startIso = task.optNullableString("start_at"),
                    endIso = task.optNullableString("end_at"),
                    note = task.optString("note", ""),
                    colorId = task.optInt("color_id", 0),
                ),
            )
        }
    }

    private fun parseChecklist(task: JSONObject): List<String> {
        val items = mutableListOf<String>()
        task.optJSONArray("checklist")?.let { array ->
            for (i in 0 until array.length()) {
                array.optString(i).trim().takeIf { it.isNotBlank() }?.let { items += it }
            }
        }
        return items
    }

    /** `JSONObject.optString` renders a JSON null as the text `null`; absent means empty here. */
    private fun JSONObject.optNullableString(name: String): String =
        if (!has(name) || isNull(name)) "" else optString(name, "").trim()

    // ── tool definition ─────────────────────────────────────────────────────

    private fun buildTools(): JSONArray = JSONArray().put(
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", CREATE_TASKS_TOOL)
                put(
                    "description",
                    "从用户输入文字中提取独立的目标/主题任务列表。一句话可以产生多个独立任务；" +
                        "同一个目标下的子步骤放进该任务的 checklist，不要拆成多个任务，" +
                        "也不要把两个独立目标合并成一个任务。没有明确时间时 start_at 传 null。",
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("tasks", JSONObject().apply {
                            put("type", "array")
                            put("minItems", 1)
                            put(
                                "description",
                                "独立目标/主题任务列表，按文字中出现的顺序排列。每个元素是一个目标，" +
                                    "不是一句话或一个动词。",
                            )
                            put("items", JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("title", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "该目标的简短标题，最多 40 字。如果用户没说具体标题，从文字中提炼核心目标作为标题")
                                    })
                                    put("checklist", JSONObject().apply {
                                        put("type", "array")
                                        put("items", JSONObject().put("type", "string"))
                                        put("description", "同一个目标的子步骤/清单项；独立目标必须另开一个任务。没有子步骤时传空数组")
                                    })
                                    put("start_at", JSONObject().apply {
                                        put("type", JSONArray().put("string").put("null"))
                                        put("description", "只有用户明确给出时间时才填写：北京时间（Asia/Shanghai）的 YYYY-MM-DDTHH:mm:ss，依据用户消息中的当前基准时间解析相对日期；只给时分且今天已过时顺延到明天。用户没有给出时间时必须传 null，绝不推测、绝不使用默认时间")
                                    })
                                    put("end_at", JSONObject().apply {
                                        put("type", JSONArray().put("string").put("null"))
                                        put("description", "只有用户明确给出结束时间时才填写（北京时间 ISO 8601，且晚于 start_at）；否则传 null")
                                    })
                                    put("note", JSONObject().apply {
                                        put("type", "string")
                                        put("description", "备注；用户未指定时传空字符串")
                                    })
                                    put("color_id", JSONObject().apply {
                                        put("type", "integer")
                                        put("minimum", 0)
                                        put("maximum", 7)
                                        put("description", "0蓝、1金、2粉、3绿、4紫、5橙、6青、7灰。未指定时默认0")
                                    })
                                })
                                put("required", JSONArray(listOf(
                                    "title", "checklist", "start_at", "end_at",
                                )))
                                put("additionalProperties", false)
                            })
                        })
                    })
                    put("required", JSONArray(listOf("tasks")))
                    put("additionalProperties", false)
                })
            })
        },
    )

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun requestIdForLog(requestId: String): String {
        val safe = requestId
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(MAX_REQUEST_ID_CHARS)
        return safe.ifBlank { "service-${REQUEST_SEQUENCE.incrementAndGet()}" }
    }

    private fun exceptionLocationForLog(error: Throwable): String {
        val frame = error.stackTrace.firstOrNull { it.className.startsWith("com.hamhuo.tplanner") }
            ?: error.stackTrace.firstOrNull()
            ?: return "unknown"
        return "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
    }

    companion object {
        private const val TAG = "TplannerLLM"
        private const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"
        private const val MODEL = "deepseek-v4-flash"
        private const val CREATE_TASKS_TOOL = "create_tasks"
        private const val MAX_REQUEST_ID_CHARS = 48
        private const val MAX_TITLE_CHARS = 40
        private const val MAX_TOKENS = 4096
        private const val UNTITLED_TASK = "未命名事项"
        private val TASK_ARGUMENT_KEYS = setOf(
            "title",
            "checklist",
            "start_at",
            "end_at",
            "note",
            "color_id",
        )
        private val KNOWN_FINISH_REASONS = setOf(
            "stop",
            "length",
            "tool_calls",
            "content_filter",
            "insufficient_system_resource",
        )
        private val REQUEST_SEQUENCE = AtomicInteger(0)

        private const val SYSTEM_PROMPT =
            "你是 tPlanner 的任务提取助手。你的唯一任务是：把用户输入的文字提取成独立的目标/主题任务列表，调用 create_tasks。" +
            "必须始终调用 create_tasks。" +
            "提取单位是「独立目标/主题」，不是句子数量，也不是动词数量：" +
            "一段话可以产生多个独立任务；同一个目标下的多个步骤属于同一个任务，放进该任务的 checklist。" +
            "不要把一个目标拆成多个任务，也不要把两个独立目标合并成一个任务。" +
            "示例：用户写「明天晚上8点火车，在车上完成申论，完成后发给老师」时，必须提取两个任务：" +
            "任务1 坐火车（start_at=明天20:00，checklist 为空）；" +
            "任务2 完成申论（checklist=[写完作文, 发给老师]，因为没有明确时间，start_at=null）。" +
            "对于每个任务的字段：\n" +
            "- title: 该目标的简短标题，最多 40 字。如果文字本身很短，直接用原文\n" +
            "- checklist: 同一个目标的子步骤数组；没有子步骤时传空数组。独立目标不要塞进这里\n" +
            "- 时间基准: 只能使用用户消息中的“当前基准时间”，绝不能使用模型训练时间或自行猜测今天日期\n" +
            "- 相对时间: 今天/明天/后天/本周/下周/周几/稍后/今晚都从当前基准时间计算；“原始记录时间”仅在文字明确相对记录时刻时使用\n" +
            "- 只给时分: 优先安排在今天；若该时分已过且用户未明确描述过去，则顺延到明天。用户明确说昨天、刚才、之前时才允许过去时间\n" +
            "- 周几: 没说本周或下周时选择严格晚于当前基准时间的最近一次该周几；不得落到已过去日期\n" +
            "- start_at: 输出北京时间 YYYY-MM-DDTHH:mm:ss。用户没有给出时间时必须传 null，" +
            "绝不推测，绝不用“下一个整点/半点”之类的默认时间补位\n" +
            "- end_at: 只在用户明确给出结束时间时填写，且晚于 start_at；否则传 null\n" +
            "- note: 提取补充说明。没有则传空字符串\n" +
            "- color_id: 用户指定了颜色就填入，否则默认 0\n" +
            "你不需要判断事件/状态/任务类型，也不需要设置提醒或闹钟。" +
            "不要反问用户。不要输出内容（content 可以为空）。直接调用工具。"
    }
}
