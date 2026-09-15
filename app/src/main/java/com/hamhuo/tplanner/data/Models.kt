package com.hamhuo.tplanner

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One checklist row. Checklist items are not independent tasks; they live inside the document. */
data class CheckItem(val id: String, val text: String, val completed: Boolean)

/**
 * Read-only projection of one canonical jCal document for the UI.
 *
 * Every field here is derived on read from the document, and every edit is mapped back onto the
 * document by [ScheduleItemStore]. An id containing `@` is a generated occurrence of a recurring
 * task; its [uid] is the record identity used for synchronization.
 */
data class ScheduleItem(
    val id: String,
    val title: String,
    val type: String,
    val start: Instant,
    val end: Instant,
    val completed: Boolean,
    val checklist: List<CheckItem>,
    val colorId: Int,
    val note: String,
    val deletedAt: Long,
    val updatedAt: Long = 0L,
    /** False for a task with no DTSTART: it has no time and none is invented for it. */
    val scheduled: Boolean = true,
    /** Retained so a list identifier set by another client round-trips unchanged. */
    val listId: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** Derived display hints only; the canonical facts live in the document. */
    val extras: Map<String, Any?> = emptyMap(),
)

internal val ScheduleItem.startedDay: LocalDate get() = start.atZone(APP_ZONE).toLocalDate()
internal val ScheduleItem.endedDay: LocalDate get() = end.atZone(APP_ZONE).toLocalDate()
internal fun ZoneId.startOf(date: LocalDate): Instant = date.atStartOfDay(this).toInstant()
