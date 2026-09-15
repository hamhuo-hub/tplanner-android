package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.syncv5.JcalCheckItem
import com.hamhuo.tplanner.syncv5.JcalDocument
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The watch's local display state.
 *
 * Every value here is computed on this device from the canonical jCal documents in the watch's V5
 * store: occurrences, the eight-day tick table and ordering are all local reads. Nothing is a wire
 * model, and no content is truncated, capped or hashed to agree with a phone.
 */
object WatchEventMarks {
    /** One display occurrence of a canonical document. Series members share [uid] until folded. */
    data class NextTask(
        val uid: String,
        val title: String,
        val kind: String,
        val startEpochMs: Long?,
        val endEpochMs: Long?,
        val occurrenceEpochMs: Long?,
        val completed: Boolean,
        val checklist: List<JcalCheckItem>,
        val recurring: Boolean,
        val timed: Boolean,
    ) {
        val isTask: Boolean get() = kind == "vtodo"
        val scheduled: Boolean get() = startEpochMs != null
    }

    data class Marks(val days: List<WatchDay>, val items: List<NextTask>) {
        /** Today's ticks, read from the locally built eight-day table. */
        val todayMinutes: List<Int> get() = days.firstOrNull()?.minutes.orEmpty()
        val next: NextTask? get() = items.firstOrNull()
    }

    val EMPTY = Marks(emptyList(), emptyList())

    fun load(context: Context): Marks = load(WatchV5Store.documents(context))

    fun load(documents: List<JcalDocument>): Marks {
        val now = Instant.now()
        return Marks(
            days = WatchDayTable.build(documents, LocalDate.now(APP_ZONE)),
            items = documents.flatMap { it.displayOccurrences(now) }
                .sortedWith(watchDisplayOrder(now.toEpochMilli())),
        )
    }

    /** An unscheduled document keeps its place in the list; no date is invented for it. */
    private fun JcalDocument.displayOccurrences(now: Instant): List<NextTask> {
        val start = displayStart()
        val end = displayEnd() ?: start
        val duration = if (start != null && end != null && end.isAfter(start)) {
            Duration.between(start, end)
        } else {
            Duration.ZERO
        }
        val rule = safeRecurrence()
        val timed = startIsTimed()
        fun entry(at: Instant?, completion: Boolean) = NextTask(
            uid = uid,
            title = title,
            kind = kind,
            startEpochMs = at?.toEpochMilli(),
            endEpochMs = at?.plus(duration)?.toEpochMilli(),
            occurrenceEpochMs = at?.toEpochMilli(),
            completed = completion,
            checklist = checklist,
            recurring = rule != null,
            timed = timed,
        )
        if (start == null) return listOf(entry(null, completed))
        if (rule == null) return listOf(entry(start, completed))

        val windowStart = now.minusSeconds(WATCH_DISPLAY_WINDOW_DAYS * 86_400)
        val windowEnd = now.plusSeconds(WATCH_DISPLAY_WINDOW_DAYS * 86_400)
        val occurrences = occurrenceInstants(windowStart, windowEnd)
        if (occurrences.isEmpty()) return listOf(entry(start, completed))
        val completion = occurrenceCompletion()
        return occurrences.map { at -> entry(at, completion[at] ?: false) }
    }
}
