package com.hamhuo.tplanner

import android.content.Context
import android.util.Log
import com.hamhuo.tplanner.syncv5.V5Store
import org.json.JSONObject

/**
 * One V5 synchronization pass for the watch.
 *
 * The sequence mirrors every other V5 client: install the full snapshot, then send the store's one
 * immutable in-flight command and accept its receipt, then install the snapshot that confirms it.
 * A command is only retired by that confirmation, never by a successful send.
 */
internal object WatchV5Sync {
    private const val TAG = "TplannerV5Sync"
    private const val MAX_BATCH_ROUNDS = 100

    private val lock = Any()

    /** Blocking; call from a worker. Returns true when the watch converged with the server. */
    fun synchronize(context: Context): Boolean {
        val appContext = context.applicationContext
        val store = WatchV5Store.instance(appContext)
        synchronized(lock) {
            return try {
                install(appContext, store, WatchV5Link.snapshot(appContext, store.deviceId))
                var rounds = 0
                while (rounds++ < MAX_BATCH_ROUNDS) {
                    val batch = store.prepareBatch() ?: break
                    store.acceptReceipt(WatchV5Link.batch(appContext, store.deviceId, batch))
                    install(appContext, store, WatchV5Link.snapshot(appContext, store.deviceId))
                }
                store.setError(null)
                true
            } catch (error: Exception) {
                Log.w(TAG, "V5 synchronization failed; local changes stay queued", error)
                store.setError(error.message ?: "同步失败，修改保留在本机")
                false
            }
        }
    }

    private fun install(context: Context, store: V5Store, snapshot: JSONObject) {
        // Only the full logical snapshot endpoint is ever installed. The watch never merges a
        // partial page, so absence from this snapshot is the server's complete answer and a real
        // removal still arrives as an explicit tombstone record.
        store.installSnapshot(snapshot)
        // Report delivery after the snapshot is durable, so the phone records an installed
        // revision rather than a merely transported packet. Losing this report is harmless.
        runCatching {
            WatchV5Link.reportInstalled(
                context,
                store.deviceId,
                snapshot.getString("serverId"),
                snapshot.getLong("revision"),
            )
        }.onFailure { Log.d(TAG, "Watch delivery watermark not recorded", it) }
    }
}
