package com.hamhuo.tplanner

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobService
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.hamhuo.tplanner.syncv5.JcalDocument
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Durable watch outbox.
 *
 * Durability itself belongs to [WatchV5Store]: the canonical document and its outgoing operation
 * are committed to disk before this object reports a successful local save. This object only
 * schedules transport and keeps a persisted job alive until the store is empty, so the queue
 * survives process death and reboot. A send is never treated as acceptance.
 */
object WatchTaskOutbox {
    private const val TAG = "TplannerTaskOutbox"
    private const val JOB_ID = 0x545054

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-v5-outbox").apply { isDaemon = true }
    }
    private val flushQueued = AtomicBoolean(false)

    /** Commits the uncommitted form draft as one canonical document, then schedules transport. */
    fun enqueue(context: Context, draft: WatchTaskDraft): Boolean =
        enqueueDocuments(context, listOf(draft.toDocument()))

    fun enqueueDocuments(context: Context, documents: List<JcalDocument>): Boolean {
        val appContext = context.applicationContext
        val committed = runCatching { WatchV5Store.instance(appContext).putAll(documents) }
            .onFailure { Log.e(TAG, "Unable to persist the new document", it) }
            .isSuccess
        if (!committed) return false
        schedulePersistentJob(appContext)
        flushAsync(appContext)
        return true
    }

    /** A delete is a durable tombstone command, not the absence of a record. */
    fun enqueueDelete(context: Context, uid: String): Boolean {
        val appContext = context.applicationContext
        val committed = runCatching { WatchV5Store.instance(appContext).delete(uid) }
            .onFailure { Log.e(TAG, "Unable to persist the delete", it) }
            .isSuccess
        if (!committed) return false
        schedulePersistentJob(appContext)
        flushAsync(appContext)
        return true
    }

    /** Called after boot, package replacement, Bluetooth enabling and app start. */
    fun resumePending(context: Context) {
        val appContext = context.applicationContext
        if (WatchV5Store.pendingCount(appContext) <= 0) return
        schedulePersistentJob(appContext)
        flushAsync(appContext)
    }

    /** Unsent canonical operations, used by the UI to explain that local changes are safe. */
    fun pendingCount(context: Context): Int = WatchV5Store.pendingCount(context)

    internal fun flushFromJob(context: Context): Boolean {
        val synchronized = WatchV5Sync.synchronize(context.applicationContext)
        return !synchronized || WatchV5Store.pendingCount(context) > 0
    }

    private fun flushAsync(context: Context) {
        val appContext = context.applicationContext
        if (!flushQueued.compareAndSet(false, true)) return
        worker.execute {
            flushQueued.set(false)
            WatchV5Sync.synchronize(appContext)
        }
    }

    private fun schedulePersistentJob(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, WatchTaskOutboxJobService::class.java),
        ).setPersisted(true)
            .setMinimumLatency(5_000L)
            .setBackoffCriteria(10_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        val result = runCatching { scheduler.schedule(job) }
            .onFailure { Log.e(TAG, "Unable to schedule persistent retry", it) }
            .getOrDefault(JobScheduler.RESULT_FAILURE)
        if (result != JobScheduler.RESULT_SUCCESS) {
            Log.e(TAG, "Persistent retry scheduling failed result=$result")
        }
    }

}

/** System-owned retry entry point for commands that no snapshot has retired yet. */
class WatchTaskOutboxJobService : JobService() {
    private val runLock = Any()
    private var activeThread: Thread? = null
    private var activeParams: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val thread = Thread({
            val shouldRetry = runCatching {
                WatchTaskOutbox.flushFromJob(applicationContext)
            }.onFailure { Log.e(TAG, "flush failed", it) }
                .getOrDefault(true)
            val shouldFinish = synchronized(runLock) {
                if (activeThread === Thread.currentThread() && activeParams === params) {
                    activeThread = null
                    activeParams = null
                    true
                } else {
                    false
                }
            }
            if (shouldFinish) jobFinished(params, shouldRetry)
        }, "tplanner-v5-outbox-job")
        val previous = synchronized(runLock) {
            val old = activeThread
            activeThread = thread
            activeParams = params
            old
        }
        previous?.interrupt()
        thread.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val thread = synchronized(runLock) {
            if (activeParams === params) {
                activeThread.also {
                    activeThread = null
                    activeParams = null
                }
            } else {
                null
            }
        }
        thread?.interrupt()
        return true
    }

    private companion object {
        const val TAG = "TplannerTaskJob"
    }
}
