package com.hamhuo.tplanner

import java.time.LocalDate

/** [Inbox] and [Today] filter the complete dataset without changing stored list IDs. */
sealed class TaskView(val key: String) {
    data object Inbox : TaskView("inbox")
    data object Today : TaskView("today")

    fun filter(items: List<ScheduleItem>, date: LocalDate = appToday()): List<ScheduleItem> =
        when (this) {
            Inbox -> items.filter { it.deletedAt == 0L }
            Today -> {
                val dayStart = date.atStartOfDay(APP_ZONE).toInstant()
                val overdue = items.filter { item ->
                    item.deletedAt == 0L && item.type == "task" && !item.completed &&
                        item.end.isBefore(dayStart)
                }
                // Keep overdue tasks reachable in Past until they are completed.
                (overdue + items.forDate(date)).sortedBy { it.start }
            }
        }

    /** Recover from the complete dataset before filtering; the series root may be on another day. */
    fun listItems(items: List<ScheduleItem>, date: LocalDate = appToday()): List<ScheduleItem> =
        collapseRecurringTaskSeries(filter(recoverRecurringTaskSeries(items), date))

    companion object {
        val FILTERS: List<TaskView>
            get() = listOf(Inbox, Today)

        /** A restored custom-list selection falls back to all items. */
        fun fromKey(key: String): TaskView =
            when (key) {
                Inbox.key -> Inbox
                Today.key -> Today
                else -> Inbox
            }
    }
}
