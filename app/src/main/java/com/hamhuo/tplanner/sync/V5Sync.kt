package com.hamhuo.tplanner

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hamhuo.tplanner.calendar.TPlannerCalendarProjection
import com.hamhuo.tplanner.syncv5.V5Http
import com.hamhuo.tplanner.syncv5.V5Settings
import com.hamhuo.tplanner.syncv5.V5Store
import com.hamhuo.tplanner.syncv5.V5SyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single V5 synchronization runtime.
 *
 * Local state is already durable before anything here runs, so every failure path simply leaves
 * the change queued on this device. There is no delta codec, no notification channel and no
 * second protocol: a run fetches the latest full snapshot, drains the queue one immutable command
 * at a time, and installs the snapshot that carries each applied receipt.
 */
object V5Sync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = AtomicBoolean(false)

    /** Fire-and-forget after a local commit. Safe to call on every save. */
    fun request(context: Context) {
        val app = context.applicationContext
        SyncFeedbackBus.publish(SyncFeedbackEvent.Sending)
        scope.launch { runCatching { synchronize(app) } }
        enqueue(app)
    }

    /** Blocking convergence used by manual refresh, startup and the WorkManager safety net. */
    suspend fun synchronize(context: Context) {
        val settings = V5Settings(context.applicationContext)
        val url = settings.url
        require(url.isNotBlank()) { "请先配置同步地址" }
        if (!inFlight.compareAndSet(false, true)) return
        try {
            val store = V5Store(context.applicationContext)
            V5SyncClient(store, V5Http(url)).synchronize()
            // A freshly installed snapshot changes the desired calendar state. This is a queued
            // side effect: its failure never affects the synchronization that just succeeded.
            runCatching { TPlannerCalendarProjection.reconcile(context, store.documents()) }
            SyncFeedbackBus.publish(SyncFeedbackEvent.CloudAccepted(runCatching { URI(url).host }.getOrDefault(url)))
            SyncLog.info("sync", "同步完成")
        } catch (error: Exception) {
            SyncFeedbackBus.publish(SyncFeedbackEvent.FailedLocally(error.message))
            SyncLog.warn("sync", "同步失败，修改仍安全保存在本机", error.message)
            throw error
        } finally {
            inFlight.set(false)
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
            onFailure = { if (runAttemptCount < 5) Result.retry() else Result.failure() },
        )
}
