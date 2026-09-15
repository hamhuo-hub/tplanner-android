package com.hamhuo.tplanner.drafts

import com.hamhuo.tplanner.ScheduleItem
import com.hamhuo.tplanner.syncv5.JcalDocument

/**
 * Draft / conflict vocabulary for the V5 pipeline.
 *
 * There is no separate draft database any more. A local edit IS a version of the same canonical
 * jCal document: it lives in the outgoing queue, in the single in-flight slot, or in the
 * unresolved-conflict list. These types are only the read-only vocabulary the UI uses to present
 * that state — they never store task facts of their own.
 */

enum class DraftEntityKind { EVENT, JOURNAL }

data class DraftTarget(val kind: DraftEntityKind, val entityId: String) {
    companion object {
        fun event(id: String) = DraftTarget(DraftEntityKind.EVENT, id)
        fun journal(date: String) = DraftTarget(DraftEntityKind.JOURNAL, date)
    }
}

/**
 * A local revision that the server refused because another device wrote first.
 * [document] is the local canonical document, kept so it can be re-applied or discarded; it is
 * null when the refused operation was a deletion, which has no document.
 */
data class DraftConflict(
    val target: DraftTarget,
    val document: JcalDocument?,
    val code: String,
    val serverRevision: Long,
    val draftUpdatedAt: Long,
) {
    /** Text the user edited, for conflict prompts. Never a stored task fact. */
    val draftContent: String
        get() = when {
            document == null -> ""
            target.kind == DraftEntityKind.JOURNAL -> document.description
            else -> document.title
        }
}

sealed interface DraftCommitResult {
    data object Saved : DraftCommitResult
    data object AlreadySaved : DraftCommitResult
    data class Conflict(val details: DraftConflict) : DraftCommitResult
}

enum class EventEditStage { NAMING, DETAIL }

sealed interface EventDraftRecovery {
    data object None : EventDraftRecovery

    data class Recovered(
        val event: ScheduleItem,
        val isNew: Boolean = false,
        val stage: EventEditStage = EventEditStage.DETAIL,
    ) : EventDraftRecovery

    data class Conflict(
        val details: DraftConflict,
        val event: ScheduleItem? = null,
        val stage: EventEditStage = EventEditStage.DETAIL,
    ) : EventDraftRecovery
}
