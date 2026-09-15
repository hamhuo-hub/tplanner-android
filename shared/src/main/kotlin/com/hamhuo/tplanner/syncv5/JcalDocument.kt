package com.hamhuo.tplanner.syncv5

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

data class JcalCheckItem(val id: String, val text: String, val completed: Boolean)

/** Immutable handle to one RFC 7265 document. Accessors never maintain a second task model. */
class JcalDocument(calendar: JSONArray) {
    private val encoded = calendar.toString()
    val calendar: JSONArray get() = JSONArray(encoded)
    init { validate(calendar) }
    private fun master(root: JSONArray = calendar): JSONArray = root.getJSONArray(2).arrays()
        .first { it.getString(0) in setOf("vtodo", "vjournal") && property(it, "recurrence-id") == null }
    private fun prop(name: String): JSONArray? = property(master(), name)
    val uid: String get() = prop("uid")!!.getString(3)
    val kind: String get() = master().getString(0)
    val title: String get() = prop("summary")?.optString(3).orEmpty()
    val description: String get() = prop("description")?.optString(3).orEmpty()
    val completed: Boolean get() = prop("status")?.optString(3) == "COMPLETED"
    val start: Instant? get() = prop("dtstart")?.let(::instant)
    val due: Instant? get() = prop("due")?.let(::instant)
    val date: LocalDate? get() = prop("dtstart")?.takeIf { it.optString(2) == "date" }
        ?.getString(3)?.let(LocalDate::parse)
    val colorId: Int get() = prop("x-tplanner-color")?.optInt(3, 0)?.coerceIn(0, 7) ?: 0
    val listId: String get() = prop("x-tplanner-list-id")?.optString(3).orEmpty()
    val recurrence: JSONObject? get() = prop("rrule")?.getJSONObject(3)
    val checklist: List<JcalCheckItem> get() {
        val value = prop("x-tplanner-checklist")?.getString(3) ?: return emptyList()
        val rows = JSONArray(value)
        return (0 until rows.length()).map { rows.getJSONObject(it) }.map {
            JcalCheckItem(it.getString("id"), it.getString("text"), it.getBoolean("completed"))
        }
    }
    fun withTitle(value: String) = edit { set(it, "summary", "text", value.trim()) }
    fun withDescription(value: String) = edit { set(it, "description", "text", value) }
    fun withColorId(value: Int) = edit { set(it, "x-tplanner-color", "integer", value.coerceIn(0, 7)) }
    fun withCompleted(value: Boolean) = edit {
        set(it, "status", "text", if (value) "COMPLETED" else "NEEDS-ACTION")
        if (value) set(it, "completed", "date-time", utc(Instant.now())) else remove(it, "completed")
    }
    fun withSchedule(start: Instant?, due: Instant?) = edit {
        require((start == null && due == null) || (start != null && due != null && due.isAfter(start))) { "结束时间必须晚于开始时间" }
        if (start == null) {
            remove(it, "dtstart"); remove(it, "due"); remove(it, "duration"); remove(it, "rrule")
        } else {
            set(it, "dtstart", "date-time", utc(start)); set(it, "due", "date-time", utc(due!!))
            remove(it, "duration")
        }
    }
    fun withChecklist(items: List<JcalCheckItem>) = edit {
        val array = JSONArray()
        items.forEach { row -> array.put(JSONObject().put("id", row.id).put("text", row.text).put("completed", row.completed)) }
        set(it, "x-tplanner-checklist", "text", array.toString())
    }
    fun withRecurrence(rule: JSONObject?) = edit {
        if (rule == null) remove(it, "rrule") else {
            require(start != null) { "重复任务需要开始时间" }
            set(it, "rrule", "recur", rule)
        }
    }
    private fun edit(change: (JSONArray) -> Unit): JcalDocument {
        val next = calendar
        val component = master(next)
        change(component)
        set(component, "dtstamp", "date-time", utc(Instant.now()))
        return JcalDocument(next)
    }
    override fun toString(): String = encoded

    companion object {
        private val UTC_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)
        fun utc(value: Instant): String = UTC_FORMAT.format(value)
        fun parse(value: String) = JcalDocument(JSONArray(value))
        fun task(title: String, start: Instant? = null, due: Instant? = null,
                 checklist: List<String> = emptyList(), uid: String = UUID.randomUUID().toString()): JcalDocument {
            require(title.isNotBlank()) { "任务标题不能为空" }
            val doc = create("vtodo", uid, JSONArray().put(p("summary", "text", title.trim()))
                .put(p("status", "text", "NEEDS-ACTION")))
            return doc.withSchedule(start, due).withChecklist(checklist.map { JcalCheckItem(UUID.randomUUID().toString(), it, false) })
        }
        fun journal(date: LocalDate, text: String): JcalDocument = create("vjournal", "journal:$date", JSONArray()
            .put(p("dtstart", "date", date.toString())).put(p("description", "text", text)))
        private fun create(kind: String, uid: String, props: JSONArray): JcalDocument {
            props.put(p("uid", "text", uid)).put(p("dtstamp", "date-time", utc(Instant.now())))
            return JcalDocument(JSONArray().put("vcalendar").put(JSONArray()
                .put(p("version", "text", "2.0")).put(p("prodid", "text", "-//TPlanner//Sync V5//EN")))
                .put(JSONArray().put(JSONArray().put(kind).put(props).put(JSONArray()))))
        }
        fun property(component: JSONArray, name: String): JSONArray? = component.getJSONArray(1).arrays().firstOrNull { it.optString(0) == name }
        fun p(name: String, type: String, value: Any, parameters: JSONObject = JSONObject()): JSONArray =
            JSONArray().put(name).put(parameters).put(type).put(value)
        fun instant(prop: JSONArray): Instant = when (prop.getString(2)) {
            "date" -> LocalDate.parse(prop.getString(3)).atStartOfDay(ZoneOffset.UTC).toInstant()
            "date-time" -> {
                val value = prop.getString(3)
                if (value.endsWith("Z")) Instant.parse(value) else
                    LocalDateTime.parse(value).atZone(ZoneId.of(prop.getJSONObject(1).getString("tzid"))).toInstant()
            }
            else -> error("Unsupported calendar time")
        }
        fun ruleText(rule: JSONObject): String = rule.keys().asSequence().map { name ->
            val value = rule.get(name)
            val text = if (value is JSONArray) (0 until value.length()).joinToString(",") { value.get(it).toString() } else value.toString()
            name.uppercase() + "=" + if (name == "until") text.replace("-", "").replace(":", "") else text
        }.joinToString(";")
        private fun set(component: JSONArray, name: String, type: String, value: Any) {
            remove(component, name); component.getJSONArray(1).put(p(name, type, value))
        }
        private fun remove(component: JSONArray, name: String) {
            val props = component.getJSONArray(1)
            for (i in props.length() - 1 downTo 0) if (props.getJSONArray(i).optString(0) == name) props.remove(i)
        }
        fun validate(root: JSONArray) {
            fun component(value: JSONArray, depth: Int) {
                require(depth <= 8 && value.length() == 3 && value.getString(0).matches(Regex("[a-z0-9-]+"))) { "Invalid jCal component" }
                value.getJSONArray(1).arrays().forEach { prop ->
                    require(prop.length() >= 4 && prop.getString(0).matches(Regex("[a-z0-9-]+"))) { "Invalid jCal property" }
                    val params = prop.getJSONObject(1)
                    params.keys().forEach { require(it.matches(Regex("[a-z0-9-]+")) && it != "value") { "Invalid jCal parameter" } }
                    require(prop.getString(2).matches(Regex("[a-z0-9-]+")))
                }
                value.getJSONArray(2).arrays().forEach { component(it, depth + 1) }
            }
            component(root, 0)
            require(root.getString(0) == "vcalendar" && property(root, "version")?.optString(3) == "2.0" && property(root, "prodid") != null)
            val content = root.getJSONArray(2).arrays().filter { it.getString(0) in setOf("vtodo", "vjournal") }
            require(content.isNotEmpty()) { "Missing task or note" }
            val masters = content.filter { property(it, "recurrence-id") == null }
            require(masters.size == 1) { "Exactly one master component is required" }
            val uid = property(masters.single(), "uid")?.getString(3).orEmpty()
            require(uid.isNotBlank() && uid.length <= 1024) { "Invalid UID" }
            content.forEach { part ->
                require(property(part, "uid")?.getString(3) == uid && part.getString(0) == masters.single().getString(0))
                val stamp = property(part, "dtstamp") ?: error("Missing DTSTAMP")
                require(stamp.getString(2) == "date-time" && stamp.getString(3).endsWith("Z"))
                Instant.parse(stamp.getString(3))
                require(property(part, "dtend") == null) { "VTODO uses DUE, not DTEND" }
                val names = part.getJSONArray(1).arrays().map { it.getString(0) }
                setOf("uid", "dtstamp", "summary", "description", "dtstart", "due", "status", "completed", "rrule", "x-tplanner-checklist", "x-tplanner-list-id", "x-tplanner-color").forEach { name ->
                    require(names.count { it == name } <= 1) { "Duplicate property: $name" }
                }
                val start = property(part, "dtstart")
                val due = property(part, "due")
                listOfNotNull(start, due).forEach { instant(it) }
                if (start != null && due != null) require(start.getString(2) == due.getString(2) && instant(due).isAfter(instant(start))) { "Invalid schedule" }
                property(part, "x-tplanner-checklist")?.let { prop ->
                    require(prop.getString(2) == "text")
                    val ids = mutableSetOf<String>()
                    val rows = JSONArray(prop.getString(3))
                    for (i in 0 until rows.length()) { val row = rows.getJSONObject(i)
                        require(row.getString("id").isNotBlank() && ids.add(row.getString("id")))
                        row.getString("text"); row.getBoolean("completed")
                    }
                }
            }
        }
    }
}

internal fun JSONArray.arrays(): List<JSONArray> = (0 until length()).map { getJSONArray(it) }
