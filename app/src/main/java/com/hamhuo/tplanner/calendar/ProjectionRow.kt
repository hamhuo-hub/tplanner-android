package com.hamhuo.tplanner.calendar

import android.content.ContentValues
import android.provider.CalendarContract.Events
import com.hamhuo.tplanner.syncv5.JcalDocument
import java.security.MessageDigest

/**
 * The provider `Events` row this app wants for one canonical record.
 *
 * Every field is derived from [JcalDocument] accessors only, so the system calendar stays a
 * read-only interpretation of the canonical document and never grows a second task model.
 */
internal data class ProjectionRow(
    val uid: String,
    val title: String,
    val description: String,
    val allDay: Boolean,
    val startMillis: Long,
    val endMillis: Long?,
    val durationSeconds: Long?,
    val rrule: String?,
    val status: Int = Events.STATUS_CONFIRMED,
) {
    val recurring: Boolean get() = rrule != null

    /** Content revision kept in the device-local mapping: equal canonical facts hash equally. */
    val revision: String
        get() = digest(
            "uid=$uid\u0000title=$title\u0000description=$description\u0000allDay=$allDay" +
                "\u0000start=$startMillis\u0000end=${endMillis ?: -1L}" +
                "\u0000duration=${durationSeconds ?: -1L}\u0000rrule=${rrule.orEmpty()}",
        )

    /**
     * Comparison against a row read back from the provider. It is deliberately tolerant about
     * duration and rule spelling, because the provider normalizes both: a mismatch means the row
     * really disagrees with the canonical document and has to be rewritten.
     */
    fun matches(other: ProjectionRow): Boolean =
        uid == other.uid && allDay == other.allDay && startMillis == other.startMillis &&
            endMillis == other.endMillis && durationSeconds == other.durationSeconds &&
            title == other.title && description == other.description && status == other.status &&
            normalizedRule(rrule) == normalizedRule(other.rrule)

    /**
     * Provider values for insert and update. `DTEND` and `DURATION` are mutually exclusive, so
     * whichever does not apply is explicitly nulled: an update is merged with the existing row and
     * a stale value would otherwise keep the row in the previous shape.
     */
    fun values(calendarId: Long): ContentValues = ContentValues().apply {
        put(Events.CALENDAR_ID, calendarId)
        put(Events.TITLE, title)
        put(Events.DESCRIPTION, description)
        put(Events.DTSTART, startMillis)
        put(Events.ALL_DAY, if (allDay) 1 else 0)
        put(Events.EVENT_TIMEZONE, PROJECTION_TIME_ZONE)
        put(Events.EVENT_END_TIMEZONE, PROJECTION_TIME_ZONE)
        put(Events.STATUS, status)
        // Ownership key: the record UID travels inside the provider row, so an interrupted run can
        // find its own row again and repair it instead of inserting a duplicate.
        put(Events._SYNC_ID, uid)
        put(Events.SYNC_DATA1, ownershipMarker(uid))
        put(Events.DIRTY, 0)
        if (rrule == null) {
            put(Events.DTEND, endMillis ?: startMillis)
            putNull(Events.RRULE)
            putNull(Events.DURATION)
        } else {
            put(Events.RRULE, rrule)
            put(Events.DURATION, CalendarDurations.text(durationSeconds ?: 1L, allDay))
            putNull(Events.DTEND)
        }
    }

    private fun digest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}

/** What the projection decided for one canonical document. */
internal sealed class ProjectionDecision {
    /** Scheduled and incomplete: this row belongs to the provider. */
    data class Project(val row: ProjectionRow) : ProjectionDecision()

    /** Notes, completed tasks and unscheduled tasks stay inside TPlanner and get no provider row. */
    object InsideOnly : ProjectionDecision()

    /** Scheduled and incomplete, but this rule has no provider representation; [rule] is local. */
    data class Unsupported(val rule: String) : ProjectionDecision()
}

/**
 * Maps one canonical document onto the provider row it should own, using only document accessors:
 * a note or an unscheduled/completed task is [ProjectionDecision.InsideOnly] and no date is ever
 * invented to force an export.
 */
internal fun project(document: JcalDocument, untitledFallback: String): ProjectionDecision {
    if (document.kind != "vtodo") return ProjectionDecision.InsideOnly
    if (document.completed) return ProjectionDecision.InsideOnly
    val start = document.start ?: return ProjectionDecision.InsideOnly
    val due = document.due ?: return ProjectionDecision.InsideOnly
    if (!due.isAfter(start)) return ProjectionDecision.InsideOnly
    val allDay = document.date != null
    val rule = document.recurrence
    val rrule = if (rule == null) null
    else providerRecurrence(rule) ?: return ProjectionDecision.Unsupported(JcalDocument.ruleText(rule))
    val spanMillis = due.toEpochMilli() - start.toEpochMilli()
    val durationSeconds = when {
        rrule == null -> null
        allDay -> CalendarDurations.secondsFromDays(CalendarDurations.daysFromMillis(spanMillis))
        else -> CalendarDurations.secondsFromMillis(spanMillis)
    }
    return ProjectionDecision.Project(
        ProjectionRow(
            uid = document.uid,
            title = document.title.ifBlank { untitledFallback },
            description = document.description,
            allDay = allDay,
            // Date-only values are already UTC midnight in the canonical instant; a date is never
            // pushed through the device zone.
            startMillis = start.toEpochMilli(),
            endMillis = if (rrule == null) due.toEpochMilli() else null,
            durationSeconds = durationSeconds,
            rrule = rrule,
        ),
    )
}

/** Rebuilds the row a provider row represents, for idempotency and drift checks only. */
internal fun providerRow(
    uid: String,
    title: String?,
    description: String?,
    allDay: Boolean,
    startMillis: Long,
    endMillis: Long?,
    durationText: String?,
    rrule: String?,
    status: Int,
): ProjectionRow {
    val rule = rrule?.takeIf { it.isNotBlank() }
    return ProjectionRow(
        uid = uid,
        title = title.orEmpty(),
        description = description.orEmpty(),
        allDay = allDay,
        startMillis = startMillis,
        endMillis = if (rule == null) endMillis else null,
        durationSeconds = if (rule == null) null else (CalendarDurations.seconds(durationText) ?: -1L),
        rrule = rule,
        status = status,
    )
}
