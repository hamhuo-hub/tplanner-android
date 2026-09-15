package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.syncv5.JcalDocument
import com.hamhuo.tplanner.syncv5.V5Store

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

    /** Watch faces and activities repaint from the same durable state, never from a projection. */
    fun subscribe(context: Context, listener: () -> Unit): () -> Unit =
        instance(context).subscribe(listener)
}
