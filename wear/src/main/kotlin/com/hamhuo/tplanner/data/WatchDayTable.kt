package com.hamhuo.tplanner

import com.hamhuo.tplanner.syncv5.JcalDocument
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One locally computed day of the watch faces' eight-day tick table. */
data class WatchDay(val date: LocalDate, val minutes: List<Int>)

/**
 * The eight-day tick table the watch faces draw.
 *
 * It is computed here, on the watch, from canonical documents and the current zone, so a day or
 * timezone rollover never needs the phone to regenerate anything. The phone has no concept of the
 * face geometry or of this table.
 */
internal object WatchDayTable {
    const val DAY_COUNT = 8

    fun build(
        documents: List<JcalDocument>,
        today: LocalDate,
        zone: ZoneId = APP_ZONE,
    ): List<WatchDay> {
        val dates = (0 until DAY_COUNT).map { today.plusDays(it.toLong()) }
        val windowStart = dates.first().atStartOfDay(zone).toInstant()
        val windowEnd = dates.last().plusDays(1).atStartOfDay(zone).toInstant()
        val ticks = List(DAY_COUNT) { sortedSetOf<Int>() }

        documents.forEach { document ->
            val start = document.displayStart() ?: return@forEach
            val end = document.displayEnd() ?: start
            val duration = if (end.isAfter(start)) Duration.between(start, end) else Duration.ZERO
            document.occurrenceInstants(windowStart, windowEnd).forEach { at ->
                val finish = if (duration.isZero) at else at.plus(duration)
                dates.forEachIndexed { index, date ->
                    val dayStart = date.atStartOfDay(zone).toInstant()
                    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
                    val visible = if (finish == at) {
                        !at.isBefore(dayStart) && at.isBefore(dayEnd)
                    } else {
                        finish.isAfter(dayStart) && at.isBefore(dayEnd)
                    }
                    if (!visible) return@forEachIndexed
                    // An interval already running at midnight is marked at the day's first minute.
                    val minute = if (at.isBefore(dayStart)) 0 else at.atZone(zone).let { it.hour * 60 + it.minute }
                    ticks[index] += minute
                }
            }
        }
        return dates.mapIndexed { index, date -> WatchDay(date, ticks[index].toList()) }
    }
}
