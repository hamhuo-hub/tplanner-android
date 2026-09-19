package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.syncv5.JcalDocument
import com.hamhuo.tplanner.syncv5.V5Store

/**
 * One conflict waiting for the user's decision, in UI terms only.
 *
 * The protocol JSON stays inside the store: an activity never parses a command or a receipt.
 *
 * [localTitle] is what this device tried to save, or null when the local side was a deletion.
 */
internal data class WatchConflict(
    val uid: String,
    val localTitle: String?,
    val operation: String,
    val code: String?,
)

/**
 * The watch's one durable V5 store.
 *
 * It owns its own device identity and its own contiguous command sequences, keeps the unchanged
 * jCal document plus its outgoing operation on disk before a local save is reported, and holds one
 * immutable in-flight command until a snapshot at or beyond its applied receipt is installed.
 * Nothing else on the watch may keep a second copy of task facts.
 */
internal object WatchV5Store {
    /** Phone and watch must never share a device identity, so the namespace is watch-specific. */
    const val NAMESPACE = "tplanner_watch_v5"

    @Volatile private var cached: V5Store? = null

    fun instance(context: Context): V5Store = cached ?: synchronized(this) {
        cached ?: V5Store(context.applicationContext, NAMESPACE).also { cached = it }
    }

    /** Live records with still-pending local documents overlaid, in canonical form. */
    fun documents(context: Context): List<JcalDocument> = instance(context).documents()

    fun pendingCount(context: Context): Int = instance(context).pendingCount

    /** Conflicts the user still has to decide about, oldest first. */
    fun conflicts(context: Context): List<WatchConflict> = instance(context).conflicts().mapNotNull { entry ->
        runCatching {
            val command = entry.getJSONObject("command")
            val operation = command.getString("operation")
            WatchConflict(
                uid = V5Store.commandUid(command),
                localTitle = if (operation == "delete") null else JcalDocument(command.getJSONArray("calendar")).title,
                operation = operation,
                code = entry.optJSONObject("receipt")?.optString("code")?.takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    /**
     * The user's decision on one conflict.
     *
     * `reapply = true` keeps this device's intent: the old command identity is abandoned and the
     * document is queued again as an ordinary command at the next sequence, so it may conflict
     * again if the other device wins the next round. `reapply = false` abandons the local intent and
     * lets the installed server record become the fact.
     */
    fun resolveConflict(context: Context, uid: String, reapply: Boolean) =
        instance(context).resolveConflict(uid, reapply)

    /** Watch faces and activities repaint from the same durable state, never from a projection. */
    fun subscribe(context: Context, listener: () -> Unit): () -> Unit =
        instance(context).subscribe(listener)
}
