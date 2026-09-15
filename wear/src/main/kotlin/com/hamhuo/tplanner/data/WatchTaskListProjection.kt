package com.hamhuo.tplanner

import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure list rules over the watch's own display occurrences.
 *
 * They read canonical documents (through [WatchEventMarks]) and never a phone projection: the
 * time window, the fold of one recurring series into one row and the display order are all decided
 * on this device.
 */

/** A half-open local time window; a moment is inside [start, end). */
internal data class WatchWindow(val startEpochMs: Long, val endEpochMs: Long)

internal fun watchDayWindow(date: LocalDate, zone: ZoneId = APP_ZONE): WatchWindow = WatchWindow(
    startEpochMs = date.atStartOfDay(zone).toInstant().toEpochMilli(),
    endEpochMs = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
)

/** Unscheduled items never fall inside a time window; they stay reachable in Inbox. */
internal fun watchTaskFallsInWindow(task: WatchEventMarks.NextTask, window: WatchWindow): Boolean {
    val start = task.startEpochMs ?: return false
    val end = task.endEpochMs ?: start
    return if (start == end) {
        start >= window.startEpochMs && start < window.endEpochMs
    } else {
        end > window.startEpochMs && start < window.endEpochMs
    }
}

/** Today plus earlier unfinished tasks, which stay reachable until they are completed. */
internal fun watchTodayList(
    tasks: List<WatchEventMarks.NextTask>,
    date: LocalDate = LocalDate.now(APP_ZONE),
): List<WatchEventMarks.NextTask> {
    val window = watchDayWindow(date)
    val overdue = tasks.filter { task ->
        val end = task.endEpochMs ?: return@filter false
        task.isTask && !task.completed && end < window.startEpochMs
    }
    return overdue + tasks.filter { watchTaskFallsInWindow(it, window) }
}

/**
 * One row per canonical record. Members of one recurring series share a UID, so the row shows the
 * occurrence the user should act on next: the earliest unfinished one, or the latest completed one
 * when the whole series is done.
 */
internal fun collapseWatchTaskSeries(
    tasks: List<WatchEventMarks.NextTask>,
): List<WatchEventMarks.NextTask> {
    val byUid = linkedMapOf<String, WatchEventMarks.NextTask>()
    tasks.forEach { task ->
        val current = byUid[task.uid]
        byUid[task.uid] = when {
            current == null -> task
            current.completed != task.completed -> if (task.completed) current else task
            task.completed -> maxOf(current, task, watchOccurrenceOrder)
            else -> minOf(current, task, watchOccurrenceOrder)
        }
    }
    val chosen = byUid.values.toSet()
    return tasks.filter { it in chosen }.distinct()
}

/** Current work first, then what is coming, then the recent past, then unscheduled items. */
internal fun watchDisplayOrder(nowEpochMs: Long): Comparator<WatchEventMarks.NextTask> =
    Comparator { left, right ->
        val leftRank = displayRank(left, nowEpochMs)
        val rightRank = displayRank(right, nowEpochMs)
        if (leftRank != rightRank) return@Comparator leftRank - rightRank
        val leftStart = left.startEpochMs ?: Long.MAX_VALUE
        val rightStart = right.startEpochMs ?: Long.MAX_VALUE
        val leftEnd = left.endEpochMs ?: leftStart
        val rightEnd = right.endEpochMs ?: rightStart
        val primary = if (leftRank == 2) rightEnd.compareTo(leftEnd) else leftStart.compareTo(rightStart)
        if (primary != 0) primary else left.uid.compareTo(right.uid)
    }

private val watchOccurrenceOrder = compareBy<WatchEventMarks.NextTask>(
    { it.occurrenceEpochMs == null },
    { it.occurrenceEpochMs ?: 0L },
    { it.uid },
)

private fun displayRank(task: WatchEventMarks.NextTask, nowEpochMs: Long): Int {
    val start = task.startEpochMs ?: return 3
    val end = task.endEpochMs ?: start
    return when {
        start <= nowEpochMs && nowEpochMs < end -> 0
        start > nowEpochMs -> 1
        else -> 2
    }
}
