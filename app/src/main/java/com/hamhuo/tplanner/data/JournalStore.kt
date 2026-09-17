package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.drafts.DraftCommitResult
import com.hamhuo.tplanner.drafts.DraftConflict
import com.hamhuo.tplanner.drafts.DraftEntityKind
import com.hamhuo.tplanner.drafts.DraftTarget
import com.hamhuo.tplanner.syncv5.JcalDocument
import com.hamhuo.tplanner.syncv5.V5Store
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import java.time.LocalDate

/** A day's note. The canonical form is one VJOURNAL document with UID `journal:YYYY-MM-DD`. */
data class JournalEntry(
    val text: String,
    val updatedAt: Long = 0L,
    val deletedAt: Long = 0L,
)

sealed interface JournalDraftRecovery {
    data object None : JournalDraftRecovery
    data class Recovered(val text: String) : JournalDraftRecovery
    data class Conflict(val details: DraftConflict) : JournalDraftRecovery {
        val text: String get() = details.draftContent
    }
}

data class DatedJournalDraftRecovery(
    val date: String,
    val recovery: JournalDraftRecovery,
)

/**
 * Daily notes travel through exactly the same document pipeline as tasks.
 *
 * A note draft is not a separate record: it is the local VJOURNAL document that the server has
 * not accepted yet, so these methods read and write the V5 queue rather than a draft table.
 */
class JournalStore(
    context: Context,
    private val store: V5Store = V5Store(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val ledgers = context.applicationContext.getSharedPreferences("tplanner_v5_journal_tokens", Context.MODE_PRIVATE)

    fun observe(date: String): Flow<JournalEntry?> = callbackFlow {
        fun push() {
            trySend(entryFor(date))
        }
        val dispose = store.subscribe { push() }
        push()
        awaitClose { dispose() }
    }.distinctUntilChanged()

    suspend fun getAll(): Map<String, JournalEntry> = journalDocuments()
        .associate { it.dateKey() to JournalEntry(it.description) }

    suspend fun getToday(): String = get(appToday().toString())

    suspend fun get(date: String): String = entryFor(date)?.text.orEmpty()

    fun getTodayDraftRecovery(): JournalDraftRecovery = getDraftRecovery(appToday().toString())

    /** Finds work from a previous date that a post-midnight process restart must not hide. */
    fun latestDraftRecovery(): DatedJournalDraftRecovery? = store.pendingUids()
        .filter { it.startsWith(JOURNAL_PREFIX) }
        .firstNotNullOfOrNull { uid ->
            val date = uid.removePrefix(JOURNAL_PREFIX)
            getDraftRecovery(date).takeIf { it !is JournalDraftRecovery.None }?.let { DatedJournalDraftRecovery(date, it) }
        }

    fun getDraftRecovery(date: String): JournalDraftRecovery {
        val uid = journalUid(date)
        conflictFor(uid)?.let { return JournalDraftRecovery.Conflict(it) }
        if (!store.isPending(uid)) return JournalDraftRecovery.None
        val document = store.documents().firstOrNull { it.uid == uid } ?: return JournalDraftRecovery.None
        return JournalDraftRecovery.Recovered(document.description)
    }

    /** Compatibility accessor used by the editor. */
    fun getTodayDraft(): String? = getDraft(appToday().toString())

    fun getDraft(date: String): String? = when (val recovery = getDraftRecovery(date)) {
        JournalDraftRecovery.None -> null
        is JournalDraftRecovery.Recovered -> recovery.text
        is JournalDraftRecovery.Conflict -> recovery.text
    }

    fun beginTodayDraft(): JournalDraftRecovery = beginDraft(appToday().toString())

    fun beginDraft(date: String): JournalDraftRecovery = getDraftRecovery(date)

    fun saveTodayDraft(text: String) = saveDraft(appToday().toString(), text)

    fun enqueueDraft(date: String, text: String) = saveDraft(date, text)

    fun saveDraft(date: String, text: String) {
        store.put(JcalDocument.journal(LocalDate.parse(date), text))
        V5Sync.request(appContext)
    }

    suspend fun commitTodayDraft(text: String): DraftCommitResult = commitDraft(appToday().toString(), text)

    suspend fun commitDraft(date: String, text: String): DraftCommitResult {
        val uid = journalUid(date)
        conflictFor(uid)?.let { return DraftCommitResult.Conflict(it) }
        store.put(JcalDocument.journal(LocalDate.parse(date), text))
        V5Sync.request(appContext)
        return DraftCommitResult.Saved
    }

    /** Re-applies the retained local note on top of the version that won on the server. */
    suspend fun overwriteDraft(conflict: DraftConflict): Boolean {
        store.resolveConflict(conflict.target.entityId, reapply = true)
        V5Sync.request(appContext)
        return true
    }

    /**
     * 丢弃某天尚未被服务器接受的本地改动，让已安装的记录重新成为唯一事实。
     *
     * 草稿不是第二份记录，它就是队列里的同一个文档，所以丢弃是把它从队列里拿掉，
     * 不需要再写一条"回退"命令，也不会改动服务器上的版本。
     */
    fun discardDraft(date: String) {
        store.discard(journalUid(date))
    }

    suspend fun discardDraft(conflict: DraftConflict): Boolean {
        store.resolveConflict(conflict.target.entityId, reapply = false)
        return true
    }

    suspend fun saveToday(text: String) = saveDraft(appToday().toString(), text)

    suspend fun appendToday(line: String) = appendOnce(appToday().toString(), "", line)

    suspend fun appendTodayOnce(idempotencyToken: String, line: String): Boolean =
        appendOnce(appToday().toString(), idempotencyToken, line)

    /**
     * Appends [line] unless [idempotencyToken] was already applied to this date. The token
     * ledger is device-local UI bookkeeping and never becomes part of the note document.
     */
    suspend fun appendOnce(date: String, idempotencyToken: String, line: String): Boolean {
        if (idempotencyToken.isNotBlank()) {
            val applied = ledgers.getStringSet(date, emptySet()).orEmpty()
            if (idempotencyToken in applied) return false
        }
        val current = get(date)
        val next = if (current.isBlank()) line else "$current\n$line"
        saveDraft(date, next)
        if (idempotencyToken.isNotBlank()) {
            val applied = ledgers.getStringSet(date, emptySet()).orEmpty().toMutableSet()
            applied += idempotencyToken
            ledgers.edit().putStringSet(date, applied).commit()
        }
        return true
    }

    /** Replaces the first line equal to [target]. Returns false when nothing matched. */
    suspend fun replaceInToday(target: String, replacement: String): Boolean {
        val date = appToday().toString()
        val lines = get(date).lines().toMutableList()
        val index = lines.indexOfFirst { it.trim() == target.trim() }
        if (index < 0) return false
        lines[index] = replacement
        saveDraft(date, lines.joinToString("\n"))
        return true
    }

    private fun entryFor(date: String): JournalEntry? = journalDocuments()
        .firstOrNull { it.dateKey() == date }
        ?.let { JournalEntry(it.description) }

    private fun journalDocuments(): List<JcalDocument> =
        store.documents().filter { it.kind == "vjournal" }

    private fun conflictFor(uid: String): DraftConflict? = store.conflicts()
        .mapNotNull { entry ->
            val command = entry.optJSONObject("command") ?: return@mapNotNull null
            val commandUid = V5Store.commandUid(command)
            if (commandUid != uid) return@mapNotNull null
            val document = runCatching { JcalDocument(command.getJSONArray("calendar")) }.getOrNull()
            DraftConflict(
                target = DraftTarget(DraftEntityKind.JOURNAL, uid),
                document = document,
                code = entry.optJSONObject("receipt")?.optString("code").orEmpty().ifBlank { "REVISION_CONFLICT" },
                serverRevision = entry.optJSONObject("receipt")?.optLong("revision", 0L) ?: 0L,
                draftUpdatedAt = System.currentTimeMillis(),
            )
        }
        .firstOrNull()

    private fun JcalDocument.dateKey(): String = date?.toString() ?: uid.removePrefix(JOURNAL_PREFIX)

    private fun journalUid(date: String) = "$JOURNAL_PREFIX$date"

    private companion object {
        const val JOURNAL_PREFIX = "journal:"
    }
}
