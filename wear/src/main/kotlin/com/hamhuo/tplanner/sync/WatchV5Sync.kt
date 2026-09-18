package com.hamhuo.tplanner

import android.content.Context
import android.util.Log
import com.hamhuo.tplanner.diagnostics.DiagnosticsComponent
import com.hamhuo.tplanner.diagnostics.DiagnosticsErrorCode
import com.hamhuo.tplanner.diagnostics.DiagnosticsRun
import com.hamhuo.tplanner.syncv5.SEQUENCE_GAP_CODE
import com.hamhuo.tplanner.syncv5.SyncRejectedException
import com.hamhuo.tplanner.syncv5.V5Store
import org.json.JSONObject

/**
 * What one watch synchronization pass actually achieved.
 *
 * "Did synchronization finish" and "is retrying worth anything" are different questions, and one
 * boolean cannot answer both: a conflict is unfinished work that must never be shown as success,
 * but it also must not make the outbox retry forever.
 */
internal sealed interface WatchSyncOutcome {
    /** Everything queued was uploaded, confirmed and installed; nothing is pending or unresolved. */
    data object Converged : WatchSyncOutcome

    /** A receipt came back conflict/rejected, or an earlier one is still unresolved. */
    data class NeedsAttention(val code: String?) : WatchSyncOutcome

    /** The phone or the network was unreachable; the work stays queued and another pass may help. */
    data class Deferred(val code: String?) : WatchSyncOutcome

    /** The pass failed for another reason and is worth retrying. */
    data class Failed(val code: String) : WatchSyncOutcome
}

/**
 * One V5 synchronization pass for the watch.
 *
 * The sequence mirrors every other V5 client: install the full snapshot, then send the store's one
 * immutable in-flight command and accept its receipt, then install the snapshot that confirms it.
 * A command is only retired by that confirmation, never by a successful send.
 *
 * The pass is also one diagnostics run (`docs/observability-v1.md` §5): it opens a local trace with
 * `sync.run.started`, records the store and transport transitions through the same shared
 * [DiagnosticsRun] the phone uses, and ends with exactly one terminal event. The trace is local for
 * now — carrying it to the phone through the relay envelope is §10 step 4 — and diagnostics can
 * never fail, block or shorten a synchronization.
 */
internal object WatchV5Sync {
    private const val TAG = "TplannerV5Sync"
    private const val MAX_BATCH_ROUNDS = 100

    private val lock = Any()

    /**
     * Blocking; call from a worker.
     *
     * Only [WatchSyncOutcome.Converged] may become the UI's "synchronized": a transport success, a
     * received response, and a pass that merely did not re-send an older conflict are all
     * insufficient.
     */
    fun synchronize(context: Context): WatchSyncOutcome {
        val appContext = context.applicationContext
        val store = WatchV5Store.instance(appContext)
        synchronized(lock) {
            val run = DiagnosticsStore.beginRun(DiagnosticsComponent.WATCH, store.deviceId)
            run.runStarted(queueDepth = store.pendingCount, inFlightSequence = store.inFlightSequence)
            var unresolvedCode: String? = null
            return try {
                install(appContext, store, WatchV5Link.snapshot(appContext, store.deviceId, run), run)
                var rounds = 0
                while (rounds++ < MAX_BATCH_ROUNDS) {
                    val batch = store.prepareBatch() ?: break
                    val command = batch.getJSONArray("commands").getJSONObject(0)
                    val commandId = command.getString("commandId")
                    val sequence = command.getLong("sequence")
                    run.commandPrepared(commandId, sequence)

                    val response = WatchV5Link.batch(appContext, store.deviceId, batch, run)
                    store.acceptReceipt(response)
                    val receipt = response.getJSONArray("receipts").getJSONObject(0)
                    if (receipt.getString("status") == "applied") {
                        run.receiptAccepted(commandId, sequence, receipt.getLong("revision"))
                    } else {
                        val code = receipt.optString("code").takeIf(String::isNotBlank)
                        run.receiptRetained(commandId, sequence, code)
                        // Safely stored as a conflict, but this pass did not converge: §7 forbids
                        // sync.run.completed once a command was prepared without an accepted receipt.
                        if (unresolvedCode == null) unresolvedCode = code
                    }

                    install(appContext, store, WatchV5Link.snapshot(appContext, store.deviceId, run), run)
                    // The command left the in-flight slot only after the snapshot that confirms it.
                    if (store.inFlightCommandId == null) run.inflightReleased(commandId, sequence, store.revision)
                }
                val stored = unresolvedCode
                // An earlier conflict is unfinished work too: a pass that re-sends nothing must not
                // report success while a decision is still outstanding.
                val openConflict = if (stored == null) openConflictCode(store) else null
                when {
                    stored != null -> {
                        store.setError(stored)
                        run.runFailed(stored)
                        WatchSyncOutcome.NeedsAttention(stored)
                    }
                    openConflict != null || store.conflicts().isNotEmpty() -> {
                        val code = openConflict ?: DiagnosticsErrorCode.SYNC_FAILED
                        store.setError(code)
                        run.runFailed(code)
                        WatchSyncOutcome.NeedsAttention(openConflict)
                    }
                    store.pendingCount > 0 -> {
                        // The per-pass send budget ran out; the rest stays queued for the next pass.
                        run.runDeferred(null)
                        WatchSyncOutcome.Deferred(null)
                    }
                    else -> {
                        store.setError(null)
                        run.runCompleted()
                        WatchSyncOutcome.Converged
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "V5 synchronization failed; local changes stay queued", error)
                store.setError(error.message ?: "同步失败，修改保留在本机")
                // 服务器已经消费过我们仍以为未使用的 sequence：协议规定的恢复是换设备身份，
                // 保留本地内容，下一轮用新的 deviceId 从 sequence 1 重新发。
                if (error is SyncRejectedException && error.code == SEQUENCE_GAP_CODE) {
                    store.rotateDeviceIdentity()
                }
                // A failed pass keeps the same immutable command for the next pass.
                val pendingId = store.inFlightCommandId
                val pendingSequence = store.inFlightSequence
                if (pendingId != null && pendingSequence != null) run.inflightRetained(pendingId, pendingSequence)
                val code = DiagnosticsErrorCode.of(error)
                if (DiagnosticsErrorCode.isDeferral(error)) {
                    run.runDeferred(code)
                    WatchSyncOutcome.Deferred(code)
                } else {
                    run.runFailed(code)
                    WatchSyncOutcome.Failed(code)
                }
            } finally {
                // Only a converged pass ends the durable work item; conflicts stay unresolved work.
                DiagnosticsStore.finishRun(converged = run.converged)
            }
        }
    }

    /** The code of the oldest stored conflict, when the server provided one. */
    private fun openConflictCode(store: V5Store): String? = store.conflicts().firstOrNull()
        ?.optJSONObject("receipt")
        ?.optString("code")
        ?.takeIf(String::isNotBlank)

    private fun install(context: Context, store: V5Store, snapshot: JSONObject, run: DiagnosticsRun) {
        // Only the full logical snapshot endpoint is ever installed. The watch never merges a
        // partial page, so absence from this snapshot is the server's complete answer and a real
        // removal still arrives as an explicit tombstone record.
        val localRevision = store.revision
        store.installSnapshot(snapshot)
        run.snapshotInstalled(store.revision, localRevision)
        // Report delivery after the snapshot is durable, so the phone records an installed
        // revision rather than a merely transported packet. Losing this report is harmless.
        runCatching {
            WatchV5Link.reportInstalled(
                context,
                store.deviceId,
                snapshot.getString("serverId"),
                snapshot.getLong("revision"),
                run,
            )
        }.onFailure { Log.d(TAG, "Watch delivery watermark not recorded", it) }
    }
}
