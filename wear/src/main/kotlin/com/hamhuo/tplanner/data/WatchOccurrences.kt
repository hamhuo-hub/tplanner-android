package com.hamhuo.tplanner

import com.hamhuo.tplanner.syncv5.JcalDocument
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Read-only interpretation of a canonical document for watch display.
 *
 * The jCal document stays the only source of task facts: everything here derives local display
 * instants, occurrence instants and per-occurrence completion, and nothing is persisted as a
 * second model.
 */

/** Recurrence is expanded for display only; the canonical document itself stays unbounded. */
internal const val WATCH_DISPLAY_WINDOW_DAYS = 180L

/** A bound on one display expansion, never on the number of synchronized records. */
private const val OCCURRENCE_SCAN_LIMIT = 512

/** The standard component; UID and shared fields live here, exceptions reference it. */
internal fun JcalDocument.masterComponent(): JSONArray {
    val components = calendar.getJSONArray(2)
    for (index in 0 until components.length()) {
        val component = components.getJSONArray(index)
        if (component.getString(0) !in setOf("vtodo", "vjournal")) continue
        if (JcalDocument.property(component, "recurrence-id") == null) return component
    }
    error("Canonical document has no master component")
}

internal fun JcalDocument.safeRecurrence(): JSONObject? = runCatching { recurrence }.getOrNull()

internal fun JcalDocument.displayStart(): Instant? = displayInstant("dtstart")

internal fun JcalDocument.displayEnd(): Instant? = displayInstant("due") ?: displayStart()

/** A date-only value stays date-only: it has no time of day to show. */
internal fun JcalDocument.startIsTimed(): Boolean =
    JcalDocument.property(masterComponent(), "dtstart")?.getString(2) == "date-time"

/** Date-only values stay date-only and are read in the watch's own zone. */
private fun JcalDocument.displayInstant(name: String): Instant? =
    JcalDocument.property(masterComponent(), name)?.let { instantOf(it, 3) }

/** Per-occurrence completion, carried by RECURRENCE-ID components of the same UID and kind. */
internal fun JcalDocument.occurrenceCompletion(): Map<Instant, Boolean> {
    val result = mutableMapOf<Instant, Boolean>()
    val components = calendar.getJSONArray(2)
    for (index in 0 until components.length()) {
        val component = components.getJSONArray(index)
        if (component.getString(0) !in setOf("vtodo", "vjournal")) continue
        val recurrenceId = JcalDocument.property(component, "recurrence-id") ?: continue
        val at = instantOf(recurrenceId, 3) ?: continue
        result[at] = JcalDocument.property(component, "status")?.optString(3) == "COMPLETED"
    }
    return result
}

private fun JcalDocument.excludedInstants(): Set<Instant> {
    val excluded = mutableSetOf<Instant>()
    val properties = masterComponent().getJSONArray(1)
    for (index in 0 until properties.length()) {
        val property = properties.getJSONArray(index)
        if (property.optString(0) != "exdate") continue
        for (slot in 3 until property.length()) instantOf(property, slot)?.let(excluded::add)
    }
    return excluded
}

/**
 * Occurrence instants of one document inside a window, inclusive of both ends.
 * A rule this build cannot read yields the master's own DTSTART, so nothing is invented.
 */
internal fun JcalDocument.occurrenceInstants(windowStart: Instant, windowEnd: Instant): List<Instant> {
    val anchor = start ?: return emptyList()
    val rule = safeRecurrence()
        ?: return if (anchor in windowStart..windowEnd) listOf(anchor) else emptyList()
    val unit = when (rule.optString("freq").uppercase()) {
        "DAILY" -> ChronoUnit.DAYS
        "WEEKLY" -> ChronoUnit.WEEKS
        "MONTHLY" -> ChronoUnit.MONTHS
        else -> return if (anchor in windowStart..windowEnd) listOf(anchor) else emptyList()
    }
    val interval = rule.optInt("interval", 1).coerceAtLeast(1).toLong()
    val count = rule.optInt("count", 0).takeIf { it > 0 }?.toLong()
    val until = rule.optString("until").takeIf { it.isNotBlank() }
        ?.let { runCatching { Instant.parse(if (it.endsWith("Z")) it else "${it}Z") }.getOrNull() }
    val excluded = excludedInstants()
    val anchorLocal = anchor.atZone(APP_ZONE)

    // Whole periods are skipped, so an old rule still reaches today without walking every
    // earlier occurrence.
    var index = 0L
    if (windowStart.isAfter(anchor)) {
        val localStart = windowStart.atZone(APP_ZONE)
        val elapsed = when (unit) {
            ChronoUnit.DAYS -> ChronoUnit.DAYS.between(anchorLocal, localStart)
            ChronoUnit.WEEKS -> ChronoUnit.WEEKS.between(anchorLocal, localStart)
            else -> ChronoUnit.MONTHS.between(anchorLocal, localStart)
        }
        index = ((elapsed / interval) - 1L).coerceAtLeast(0L)
    }

    val result = mutableListOf<Instant>()
    var scanned = 0
    while (scanned++ < OCCURRENCE_SCAN_LIMIT) {
        if (count != null && index >= count) break
        val at = anchorLocal.plus(index * interval, unit).toInstant()
        if (until != null && at.isAfter(until)) break
        if (at.isAfter(windowEnd)) break
        if (!at.isBefore(windowStart) && at !in excluded) result += at
        index++
    }
    return result
}

private fun instantOf(property: JSONArray, slot: Int): Instant? = runCatching {
    when (property.getString(2)) {
        "date" -> LocalDate.parse(property.getString(slot)).atStartOfDay(APP_ZONE).toInstant()
        "date-time" -> {
            val value = property.getString(slot)
            if (value.endsWith("Z")) Instant.parse(value)
            else java.time.LocalDateTime.parse(value)
                .atZone(ZoneId.of(property.getJSONObject(1).getString("tzid")))
                .toInstant()
        }
        else -> null
    }
}.getOrNull()
