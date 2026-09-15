package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.calendar.TPlannerCalendarProjection
import com.hamhuo.tplanner.drafts.DraftConflict
import com.hamhuo.tplanner.drafts.DraftCommitResult
import com.hamhuo.tplanner.drafts.DraftEntityKind
import com.hamhuo.tplanner.drafts.DraftTarget
import com.hamhuo.tplanner.drafts.EventDraftRecovery
import com.hamhuo.tplanner.drafts.EventEditStage
import com.hamhuo.tplanner.syncv5.JcalDocument
import com.hamhuo.tplanner.syncv5.V5Store
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Canonical task store.
 *
 * The only persisted representation of a task is its jCal document inside [V5Store]. Everything
 * this class hands to the UI is a read-only projection of that document, and every mutation is
 * applied back onto the document before it is queued for synchronization.
 */
class ScheduleItemStore(
    context: Context,
    private val store: V5Store = V5Store(context.applicationContext),
) {
    private val appContext = context.applicationContext

    fun observeAll(): Flow<List<ScheduleItem>> = callbackFlow {
        fun push() {
            trySend(store.documents().toDisplayItems())
        }
        val dispose = store.subscribe { push() }
        push()
        awaitClose { dispose() }
    }.distinctUntilChanged()

    suspend fun getAll(): List<ScheduleItem> = store.documents().toDisplayItems()

    /**
     * Persists an edit. The document is written before this returns, so a report of success always
     * means the change survives process death.
     */
    suspend fun save(event: ScheduleItem, original: ScheduleItem? = null) {
        val uid = event.uid
        val base = store.documents().firstOrNull { it.uid == uid }
        val occurrence = event.occurrenceInstant
        when {
            // An occurrence is not a record of its own: only its own instance state is written.
            occurrence != null && base != null && event.deletedAt != 0L ->
                store.put(base.withOccurrenceExcluded(occurrence))
            occurrence != null && base != null ->
                store.put(base.withOccurrenceCompleted(occurrence, event.completed))
            // The series is gone locally, so there is no master left to carve an exception out of.
            occurrence != null -> Unit
            event.deletedAt != 0L -> store.delete(uid)
            base != null -> store.put(base.applyEdit(event))
            else -> store.put(event.toNewDocument())
        }
        V5Sync.request(appContext)
        TPlannerCalendarProjection.onDocumentsChanged(appContext)
    }

    /** Explicit deletion. A tombstone is created so a delayed create cannot revive the record. */
    suspend fun delete(id: String) {
        store.delete(id.substringBefore('@'))
        V5Sync.request(appContext)
        TPlannerCalendarProjection.onDocumentsChanged(appContext)
    }

    /** Commits several new documents in one local transaction; used by the AI proposal flow. */
    suspend fun saveAll(events: List<ScheduleItem>): DraftCommitResult {
        val documents = events.map { it.toNewDocument() }
        store.putAll(documents)
        V5Sync.request(appContext)
        TPlannerCalendarProjection.onDocumentsChanged(appContext)
        return DraftCommitResult.Saved
    }

    // ── Editor drafts ─────────────────────────────────────────────────────────
    // A draft is not a separate record: it is exactly the local document still waiting for the
    // server. These methods therefore only read and write the V5 queue.

    /** Captures the authoritative base before the editor can diverge from it. */
    suspend fun beginEventEdit(event: ScheduleItem): ScheduleItem = itemFor(event.uid) ?: event

    suspend fun saveEventDraft(event: ScheduleItem, stage: EventEditStage = EventEditStage.DETAIL) {
        enqueueEventDraft(event, stage)
    }

    fun enqueueEventDraft(event: ScheduleItem, stage: EventEditStage = EventEditStage.DETAIL) {
        val uid = event.uid
        val base = store.documents().firstOrNull { it.uid == uid }
        if (conflictFor(uid) != null) return
        store.put(base?.applyEdit(event) ?: event.toNewDocument())
    }

    suspend fun recoverEventDraft(eventId: String): EventDraftRecovery {
        val uid = eventId.substringBefore('@')
        val conflict = conflictFor(uid)
        if (conflict != null) {
            return EventDraftRecovery.Conflict(conflict, itemFor(uid), stageOf(uid))
        }
        if (!store.isPending(uid)) return EventDraftRecovery.None
        val item = itemFor(uid) ?: return EventDraftRecovery.None
        return EventDraftRecovery.Recovered(item, isNew = !store.isInstalled(uid), stage = stageOf(uid))
    }

    suspend fun latestEventDraftRecovery(): EventDraftRecovery? {
        store.pendingUids().forEach { uid ->
            val recovery = recoverEventDraft(uid)
            if (recovery != EventDraftRecovery.None) return recovery
        }
        return null
    }

    suspend fun discardEventDraft(eventId: String, expected: DraftConflict? = null): Boolean {
        store.discard(eventId.substringBefore('@'))
        return true
    }

    suspend fun saveAndClearEventDraft(
        event: ScheduleItem,
        additionalEvents: List<ScheduleItem> = emptyList(),
    ): DraftCommitResult {
        val uid = event.uid
        conflictFor(uid)?.let { return DraftCommitResult.Conflict(it) }
        val base = store.documents().firstOrNull { it.uid == uid }
        val documents = buildList {
            add(base?.applyEdit(event) ?: event.toNewDocument())
            additionalEvents.forEach { add(it.toNewDocument()) }
        }
        store.putAll(documents)
        V5Sync.request(appContext)
        TPlannerCalendarProjection.onDocumentsChanged(appContext)
        return DraftCommitResult.Saved
    }

    /** Keeps both sides: the local edit is stored under a fresh UID, then the conflict is cleared. */
    suspend fun saveConflictAsCopy(event: ScheduleItem, conflict: DraftConflict): ScheduleItem? {
        val copy = event.copy(
            id = UUID.randomUUID().toString(),
            title = event.title.ifBlank { conflict.draftContent.ifBlank { "冲突副本" } },
            updatedAt = System.currentTimeMillis(),
        )
        store.put(copy.toNewDocument())
        store.resolveConflict(conflict.target.entityId, reapply = false)
        V5Sync.request(appContext)
        return copy
    }

    /** Explicit resolution of a conflict: discard the local edit, or re-apply it on the new base. */
    fun resolveConflict(uid: String, reapply: Boolean) {
        store.resolveConflict(uid, reapply)
        if (reapply) V5Sync.request(appContext)
    }

    fun conflicts(): List<DraftConflict> = store.conflicts().mapNotNull { entry ->
        val command = entry.optJSONObject("command") ?: return@mapNotNull null
        val uid = V5Store.commandUid(command)
        val document = if (command.optString("operation") == "delete") null
        else runCatching { JcalDocument(command.getJSONArray("calendar")) }.getOrNull()
        DraftConflict(
            target = DraftTarget(DraftEntityKind.EVENT, uid),
            document = document,
            code = entry.optJSONObject("receipt")?.optString("code").orEmpty().ifBlank { "REVISION_CONFLICT" },
            serverRevision = entry.optJSONObject("receipt")?.optLong("revision", 0L) ?: 0L,
            draftUpdatedAt = System.currentTimeMillis(),
        )
    }

    fun pendingCount(): Int = store.pendingCount

    fun lastError(): String? = store.lastError

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun conflictFor(uid: String): DraftConflict? = conflicts().firstOrNull { it.target.entityId == uid }

    private fun itemFor(uid: String): ScheduleItem? = store.documents().firstOrNull { it.uid == uid }?.toMasterItem()

    /** A document without a title is still being named; anything else was left mid-edit. */
    private fun stageOf(uid: String): EventEditStage =
        if (itemFor(uid)?.title.isNullOrBlank()) EventEditStage.NAMING else EventEditStage.DETAIL
}

/** Live, unfinished tasks that are due before today, plus everything on [date]. */
fun List<ScheduleItem>.forToday(): List<ScheduleItem> = forDate(appToday())

fun List<ScheduleItem>.forDate(date: LocalDate): List<ScheduleItem> = filter { event ->
    if (!event.scheduled) return@filter false
    val startDate = event.startedDay
    val endDate = event.endedDay
    !startDate.isAfter(date) && !endDate.isBefore(date)
}.sortedBy { it.start }
