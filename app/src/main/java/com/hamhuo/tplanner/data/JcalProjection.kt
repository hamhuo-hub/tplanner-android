package com.hamhuo.tplanner

import com.hamhuo.tplanner.syncv5.JcalCheckItem
import com.hamhuo.tplanner.syncv5.JcalDocument
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Read-only projection between the canonical jCal document and the UI's [ScheduleItem].
 *
 * The document stays the only source of task facts. Everything here derives display values and
 * maps edits back onto the document; nothing in this file is persisted as a second model.
 */

internal const val MAX_TASK_RECURRENCE_COUNT = 50

/** How far recurrence is expanded for display in each direction; the document itself is unbounded. */
internal const val RECURRENCE_WINDOW_DAYS = 180L

private const val RECURRENCE_TYPE = "recurrenceType"
private const val RECURRENCE_COUNT = "recurrenceCount"
private const val TIMEZONE = "timezone"

/** Marks a rule this build can read but not edit; it must survive an unrelated edit untouched. */
internal const val RECURRENCE_UNSUPPORTED = "unsupported"

private val FREQUENCIES = mapOf("daily" to "DAILY", "weekly" to "WEEKLY", "monthly" to "MONTHLY")

/** True for a synthetic occurrence id produced by [expandOccurrences]. */
internal val ScheduleItem.occurrenceInstant: Instant?
    get() = id.substringAfter('@', "").takeIf { it.isNotEmpty() }?.let { runCatching { Instant.ofEpochSecond(it.toLong()) }.getOrNull() }

internal val ScheduleItem.uid: String get() = id.substringBefore('@')

private fun occurrenceId(uid: String, at: Instant) = "$uid@${at.epochSecond}"

// ── Document → UI ─────────────────────────────────────────────────────────────

/**
 * The editor's view of the rule. Empty when there is no rule, a frequency name when this build can
 * edit it, and [RECURRENCE_UNSUPPORTED] when the document carries a rule the editor cannot express.
 */
private fun recurrenceTypeOf(document: JcalDocument): String {
    val rule = document.recurrence ?: return ""
    val freq = rule.optString("freq").orEmpty().uppercase()
    return FREQUENCIES.entries.firstOrNull { it.value == freq }?.key ?: RECURRENCE_UNSUPPORTED
}

private fun recurrenceCountOf(document: JcalDocument): Int =
    document.recurrence?.optInt("count", 0)?.takeIf { it > 0 } ?: MAX_TASK_RECURRENCE_COUNT

/** A projection cannot express a recurrence decision when it carries no rule information at all. */
private fun ScheduleItem.declaresRecurrence(): Boolean = extras.containsKey(RECURRENCE_TYPE)

/** Master projection: one [ScheduleItem] per live document. */
internal fun JcalDocument.toMasterItem(): ScheduleItem {
    val start = start
    val duration = if (start != null && due != null) java.time.Duration.between(start, due) else java.time.Duration.ZERO
    val recurrence = recurrenceTypeOf(this)
    val extras = linkedMapOf<String, Any?>(TIMEZONE to APP_TIME_ZONE_ID)
    if (recurrence.isNotEmpty()) {
        extras[RECURRENCE_TYPE] = recurrence
        extras[RECURRENCE_COUNT] = recurrenceCountOf(this).coerceIn(1, MAX_TASK_RECURRENCE_COUNT)
    }
    return ScheduleItem(
        id = uid,
        title = title,
        type = "task",
        start = start ?: Instant.EPOCH,
        end = due ?: start?.plus(duration) ?: Instant.EPOCH,
        completed = completed,
        checklist = checklist.map { CheckItem(it.id, it.text, it.completed) },
        colorId = colorId,
        note = description,
        deletedAt = 0L,
        updatedAt = runCatching { Instant.parse(JcalDocument.property(masterComponent(), "dtstamp")!!.getString(3)).toEpochMilli() }
            .getOrDefault(0L),
        listId = listId,
        lat = geoLat,
        lng = geoLng,
        scheduled = start != null,
        extras = extras,
    )
}

/** GEO is a standard-layer FLOAT value: [latitude, longitude]. */
private val JcalDocument.geoLat: Double
    get() = geoPair()?.first ?: 0.0

private val JcalDocument.geoLng: Double
    get() = geoPair()?.second ?: 0.0

private fun JcalDocument.geoPair(): Pair<Double, Double>? {
    val prop = JcalDocument.property(masterComponent(), "geo") ?: return null
    val values = prop.optJSONArray(3) ?: return null
    if (values.length() < 2) return null
    return values.optDouble(0) to values.optDouble(1)
}

/** Writes or removes GEO without touching any other standard property. */
private fun JcalDocument.withGeo(lat: Double, lng: Double): JcalDocument {
    val calendar = calendar
    val master = calendar.getJSONArray(2).let { components ->
        (0 until components.length()).map { components.getJSONArray(it) }
    }.first { JcalDocument.property(it, "recurrence-id") == null && it.getString(0) in setOf("vtodo", "vjournal") }
    val props = master.getJSONArray(1)
    for (index in props.length() - 1 downTo 0) if (props.getJSONArray(index).optString(0) == "geo") props.remove(index)
    if (lat != 0.0 || lng != 0.0) {
        props.put(JcalDocument.p("geo", "float", JSONArray().put(lat).put(lng)))
    }
    return JcalDocument(calendar)
}

private fun JcalDocument.masterComponent(): JSONArray = calendar.getJSONArray(2)
    .let { components -> (0 until components.length()).map { components.getJSONArray(it) } }
    .first { it.getString(0) in setOf("vtodo", "vjournal") && JcalDocument.property(it, "recurrence-id") == null }

/** Exception components keyed by the occurrence instant they override. */
private fun JcalDocument.exceptions(): Map<Instant, Boolean> {
    val components = calendar.getJSONArray(2)
    val result = mutableMapOf<Instant, Boolean>()
    for (index in 0 until components.length()) {
        val part = components.getJSONArray(index)
        if (part.getString(0) !in setOf("vtodo", "vjournal")) continue
        val recurrenceId = JcalDocument.property(part, "recurrence-id") ?: continue
        val at = runCatching { JcalDocument.instant(recurrenceId) }.getOrNull() ?: continue
        result[at] = JcalDocument.property(part, "status")?.optString(3) == "COMPLETED"
    }
    return result
}

private fun JcalDocument.excludedInstants(): Set<Instant> {
    val excluded = mutableSetOf<Instant>()
    val master = masterComponent()
    for (index in 0 until master.getJSONArray(1).length()) {
        val prop = master.getJSONArray(1).getJSONArray(index)
        if (prop.optString(0) != "exdate") continue
        for (slot in 3 until prop.length()) {
            runCatching { Instant.parse(prop.getString(slot)) }.getOrNull()?.let(excluded::add)
        }
    }
    return excluded
}

/**
 * Occurrence instants of a recurring master inside a window, inclusive of both ends.
 * Unsupported rules yield only the master's own DTSTART so nothing is invented.
 */
internal fun JcalDocument.occurrenceInstants(windowStart: Instant, windowEnd: Instant): List<Instant> {
    val anchor = start ?: return emptyList()
    val rule = recurrence ?: return if (anchor in windowStart..windowEnd) listOf(anchor) else emptyList()
    val interval = rule.optInt("interval", 1).coerceAtLeast(1)
    val limit = rule.optInt("count", 0).takeIf { it > 0 }?.coerceAtMost(MAX_TASK_RECURRENCE_COUNT)
    val until = rule.optString("until").takeIf { it.isNotBlank() }
        ?.let { runCatching { Instant.parse(if (it.endsWith("Z")) it else "${it}Z") }.getOrNull() }
    val excluded = excludedInstants()
    val zone = APP_ZONE
    val local = anchor.atZone(zone)
    val result = mutableListOf<Instant>()
    for (index in 0 until MAX_TASK_RECURRENCE_COUNT) {
        if (limit != null && index >= limit) break
        val at = when (rule.optString("freq").uppercase()) {
            "DAILY" -> local.plusDays(index.toLong() * interval)
            "WEEKLY" -> local.plusWeeks(index.toLong() * interval)
            "MONTHLY" -> local.plusMonths(index.toLong() * interval)
            else -> if (index == 0) local else break
        }.toInstant()
        if (until != null && at.isAfter(until)) break
        if (at.isAfter(windowEnd)) break
        if (at in windowStart..windowEnd && at !in excluded) result += at
    }
    return result
}

/** Expands one document into the items the UI renders, overlaying occurrence exceptions. */
internal fun JcalDocument.expandForDisplay(windowStart: Instant, windowEnd: Instant): List<ScheduleItem> {
    val master = toMasterItem()
    if (recurrence == null) return listOf(master)
    val exceptions = exceptions()
    val duration = if (master.scheduled && due != null) java.time.Duration.between(start!!, due) else java.time.Duration.ZERO
    val occurrences = occurrenceInstants(windowStart, windowEnd)
    // Never hide a record that exists: a rule this build cannot expand, or occurrences that all
    // fall outside the window, still show the record's own row.
    if (occurrences.isEmpty()) return listOf(master)
    return occurrences.map { at ->
        val id = occurrenceId(uid, at)
        master.copy(
            id = id,
            start = at,
            end = at.plus(duration),
            // An explicit exception wins; otherwise the record's own state applies.
            completed = exceptions[at] ?: master.completed,
            listId = master.listId,
        )
    }
}

/** Display projection for a whole dataset. Unscheduled tasks are always included. */
internal fun List<JcalDocument>.toDisplayItems(
    windowStart: Instant = Instant.now().minusSeconds(RECURRENCE_WINDOW_DAYS * 86_400),
    windowEnd: Instant = Instant.now().plusSeconds(RECURRENCE_WINDOW_DAYS * 86_400),
): List<ScheduleItem> = filter { it.kind == "vtodo" }.flatMap { document ->
    // 只有 VTODO 是任务。VJOURNAL 是别处写下的日记：它不是待办，也绝不能以"未命名"
    // 的样子出现在时间轴或收件箱里。
    if (document.start == null) listOf(document.toMasterItem()) else document.expandForDisplay(windowStart, windowEnd)
}

// ── UI → document ─────────────────────────────────────────────────────────────

private fun ScheduleItem.recurrenceRule(): JSONObject? {
    val frequency = FREQUENCIES[extras[RECURRENCE_TYPE]?.toString()?.lowercase()] ?: return null
    val count = (extras[RECURRENCE_COUNT] as? Number)?.toInt()
        ?: extras[RECURRENCE_COUNT]?.toString()?.toIntOrNull()
        ?: return null
    return JSONObject().put("freq", frequency).put("count", count.coerceIn(1, MAX_TASK_RECURRENCE_COUNT)).put("interval", 1)
}

/** Applies the edited item onto its canonical document, leaving unknown fields untouched. */
internal fun JcalDocument.applyEdit(item: ScheduleItem): JcalDocument {
    var next = withTitle(item.title)
        .withDescription(item.note)
        .withColorId(item.colorId)
        .withChecklist(item.checklist.map { JcalCheckItem(it.id, it.text, it.completed) })
    next = if (item.scheduled && !item.start.equals(Instant.EPOCH)) {
        next.withSchedule(item.start, if (item.end.isAfter(item.start)) item.end else item.start.plusSeconds(3_600))
    } else {
        next.withSchedule(null, null)
    }
    // Recurrence is applied last because a schedule is required before a rule can be attached.
    // An edit that says nothing about recurrence, or that comes from a build which cannot express
    // the stored rule, must leave the rule exactly as it is: silently dropping it would destroy a
    // fact this client cannot represent.
    val declared = item.extras[RECURRENCE_TYPE]?.toString()
    val rule = item.recurrenceRule()
    next = when {
        next.start == null -> next.withRecurrence(null)
        declared == null -> next
        declared == RECURRENCE_UNSUPPORTED -> next
        else -> next.withRecurrence(rule)
    }
    return next.withCompleted(item.completed).withGeo(item.lat, item.lng)
}

/** New document for an item that has never been synced. */
internal fun ScheduleItem.toNewDocument(): JcalDocument =
    JcalDocument.task(
        title = title.ifBlank { "\u65b0\u4e8b\u9879" },
        start = start.takeIf { scheduled && !it.equals(Instant.EPOCH) },
        due = end.takeIf { scheduled && !it.equals(Instant.EPOCH) && end.isAfter(start) },
        uid = uid.ifBlank { UUID.randomUUID().toString() },
    ).applyEdit(this)

/**
 * Marks a single occurrence complete (or not) by adding a RECURRENCE-ID exception of the same
 * UID and kind, which is what RFC 5545 uses for per-instance state on a recurring task.
 */
internal fun JcalDocument.withOccurrenceCompleted(occurrence: Instant, completed: Boolean): JcalDocument {
    val calendar = calendar
    val components = calendar.getJSONArray(2)
    val master = components.let { list -> (0 until list.length()).map { list.getJSONArray(it) } }
        .first { JcalDocument.property(it, "recurrence-id") == null && it.getString(0) in setOf("vtodo", "vjournal") }
    val stamp = JcalDocument.property(master, "dtstamp")!!.getString(3)
    val kept = JSONArray()
    for (index in 0 until components.length()) {
        val part = components.getJSONArray(index)
        val recurrenceId = JcalDocument.property(part, "recurrence-id")
        if (recurrenceId != null && runCatching { JcalDocument.instant(recurrenceId) }.getOrNull() == occurrence) continue
        kept.put(part)
    }
    val exception = JSONArray().put(master.getString(0)).put(JSONArray()
        .put(JcalDocument.p("uid", "text", JcalDocument.property(master, "uid")!!.getString(3)))
        .put(JcalDocument.p("dtstamp", "date-time", stamp))
        .put(JcalDocument.p("recurrence-id", "date-time", JcalDocument.utc(occurrence)))
        .put(JcalDocument.p("status", "text", if (completed) "COMPLETED" else "NEEDS-ACTION"))
        .put(JcalDocument.p("completed", "date-time", JcalDocument.utc(Instant.now()))))
        .put(JSONArray())
    kept.put(exception)
    calendar.put(2, kept)
    return JcalDocument(calendar)
}

internal fun ScheduleItem.toLocalDate(): LocalDate = start.atZone(APP_ZONE).toLocalDate()

/**
 * List views show one entry per record: the next unfinished occurrence, or the last one when the
 * series is complete. The timeline deliberately keeps every occurrence.
 */
internal fun collapseRecurringTaskSeries(items: List<ScheduleItem>): List<ScheduleItem> =
    items.groupBy { it.uid }.values.map { members ->
        members.filter { !it.completed }.minWithOrNull(compareBy({ it.start }, { it.id }))
            ?: members.maxWithOrNull(compareBy({ it.start }, { it.id }))!!
    }

/**
 * Removes one occurrence of a recurring task with EXDATE, which is the standard way to cancel a
 * single instance. The rule and every other occurrence stay intact.
 */
internal fun JcalDocument.withOccurrenceExcluded(occurrence: Instant): JcalDocument {
    val calendar = calendar
    val components = calendar.getJSONArray(2)
    val master = (0 until components.length()).map { components.getJSONArray(it) }
        .first { JcalDocument.property(it, "recurrence-id") == null && it.getString(0) in setOf("vtodo", "vjournal") }
    master.getJSONArray(1).put(JSONArray()
        .put("exdate").put(JSONObject()).put("date-time").put(JcalDocument.utc(occurrence)))
    return JcalDocument(calendar)
}
