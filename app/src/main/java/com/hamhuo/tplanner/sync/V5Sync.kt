package com.hamhuo.tplanner

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hamhuo.tplanner.calendar.TPlannerCalendarProjection
import com.hamhuo.tplanner.diagnostics.DiagnosticsComponent
import com.hamhuo.tplanner.diagnostics.DiagnosticsErrorCode
import com.hamhuo.tplanner.syncv5.V5Http
import com.hamhuo.tplanner.syncv5.V5Settings
import com.hamhuo.tplanner.syncv5.V5Store
import com.hamhuo.tplanner.syncv5.V5SyncClient
import com.hamhuo.tplanner.syncv5.SyncUnresolvedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicLong

/**
 * The single V5 synchronization runtime.
 *
 * Local state is already durable before anything here runs, so every failure path simply leaves
 * the change queued on this device. There is no delta codec, no notification channel and no
 * second protocol: a run fetches the latest full snapshot, drains the queue one immutable command
 * at a time, and installs the snapshot that carries each applied receipt.
 */
object V5Sync {
    private const val TAG = "TplannerSync"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A second request waits for the pass in progress instead of reporting a success it never
     * performed: the WorkManager safety net must observe the real outcome, not a skipped run.
     */
    private val syncMutex = Mutex()

    /** Completed converged passes; a waiting call uses it to notice that its work is already done. */
    private val convergedPasses = AtomicLong()

    /** Fire-and-forget after a local commit. Safe to call on every save. */
    fun request(context: Context) {
        val app = context.applicationContext
        SyncFeedbackBus.publish(SyncFeedbackEvent.Sending)
        scope.launch { runCatching { synchronize(app) } }
        enqueue(app)
    }

    /**
     * Blocking convergence used by manual refresh, startup and the WorkManager safety net.
     *
     * The pass itself is blocking network and store work, so it never runs on the caller's
     * dispatcher: the sync coordinator calls this from the main thread, and waiting for the lock is
     * part of that work. Without this, a manual refresh while another pass was running would do DNS
     * and HTTP on the main thread (`NetworkOnMainThreadException`).
     */
    suspend fun synchronize(context: Context) {
        val app = context.applicationContext
        val url = V5Settings(app).url
        // 未配置服务器时保持既有语义：调用方永远看到这个异常，而不是静默返回。
        if (url.isBlank()) {
            val store = V5Store(app)
            val run = DiagnosticsStore.beginRun(DiagnosticsComponent.PHONE, store.deviceId)
            run.runStarted(queueDepth = store.pendingCount, inFlightSequence = store.inFlightSequence)
            run.runDeferred(DiagnosticsErrorCode.NOT_CONFIGURED)
            DiagnosticsStore.finishRun(converged = false)
            throw IllegalArgumentException("请先配置同步地址")
        }
        val seenPasses = convergedPasses.get()
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                val store = V5Store(app)
                // A pass that converged while this call was waiting has already done this call's
                // work; running a second identical pass would only double the traffic. A pass that
                // failed does not count, so the safety net still observes the real outcome.
                if (convergedPasses.get() != seenPasses &&
                    store.pendingCount == 0 && store.conflicts().isEmpty()
                ) {
                    Log.d(TAG, "Skipping a duplicate pass: the pass this call waited for converged")
                    return@withLock
                }
                converge(app, url)
            }
        }
    }

    private suspend fun converge(app: Context, url: String) {
        val store = V5Store(app)
        val run = DiagnosticsStore.beginRun(DiagnosticsComponent.PHONE, store.deviceId)
        run.runStarted(queueDepth = store.pendingCount, inFlightSequence = store.inFlightSequence)
        try {
            V5SyncClient(store, V5Http(url, run), run).synchronize()
            // A freshly installed snapshot changes the desired calendar state. This is a queued
            // side effect: its failure never affects the synchronization that just succeeded.
            runCatching { TPlannerCalendarProjection.reconcile(app, store.documents()) }
            SyncFeedbackBus.publish(SyncFeedbackEvent.CloudAccepted(runCatching { URI(url).host }.getOrDefault(url)))
            run.runCompleted()
            convergedPasses.incrementAndGet()
        } catch (error: Exception) {
            SyncFeedbackBus.publish(SyncFeedbackEvent.FailedLocally(error.message))
            val unresolved = error as? SyncUnresolvedException
            if (unresolved != null && unresolved.retryable) {
                // The per-attempt send budget ran out: the rest stays queued for the next attempt.
                run.runDeferred(null)
                Log.w(TAG, "Sync attempt deferred: work stays queued", error)
            } else if (DiagnosticsErrorCode.isDeferral(error)) {
                val code = DiagnosticsErrorCode.of(error)
                run.runDeferred(code)
                Log.w(TAG, "Sync attempt deferred ($code)", error)
            } else {
                val code = DiagnosticsErrorCode.of(error)
                run.runFailed(code)
                Log.w(TAG, "Sync attempt failed ($code)", error)
            }
            throw error
        } finally {
            // Only a converged run ends the durable work item; a conflict is still unresolved work.
            DiagnosticsStore.finishRun(converged = run.converged)
        }
    }

    private fun enqueue(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<V5SyncWorker>().build(),
            )
        }
    }

    private const val WORK_NAME = "tplanner-v5-sync"
}

class V5SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching { V5Sync.synchronize(applicationContext) }
        .fold(
            onSuccess = { Result.success() },
            onFailure = { error ->
                // A conflict or rejection waits for the user; retrying it would only re-download.
                val stuck = (error as? SyncUnresolvedException)?.retryable == false
                if (!stuck && runAttemptCount < 5) Result.retry() else Result.failure()
            },
        )
}
