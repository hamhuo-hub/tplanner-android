package com.hamhuo.tplanner.calendar

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Durable retry path for the projection. One unique work item exists at a time; a run that could
 * not finish (permission, missing provider, provider error) asks to be retried later with
 * WorkManager's own backoff, and the queue of intents lives in [CalendarProjectionStore], not here.
 *
 * Nothing about saving a task, syncing with the server or acknowledging the watch waits for this.
 */
class CalendarProjectionWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : Worker(appContext, parameters) {

    override fun doWork(): Result =
        when (CalendarProjectionEngine.runFromCanonicalStore(applicationContext)) {
            ProjectionOutcome.SUCCESS -> Result.success()
            ProjectionOutcome.RETRY -> Result.retry()
        }
}

/** Queues durable projection work; never throws and never blocks the caller. */
internal object CalendarProjectionScheduler {

    private const val WORK_NAME = "tplanner-calendar-projection"

    fun enqueue(context: Context) {
        runCatching {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<CalendarProjectionWorker>().build(),
            )
        }
    }
}

/**
 * Immediate, coalescing, off-thread pass so the provider is usually current right after a save.
 *
 * A request that arrives while a pass is running is folded into that pass or starts exactly one
 * more; nothing here runs on the caller's thread, so a slow or broken provider cannot delay the
 * save that triggered it.
 */
internal object CalendarProjectionPump {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-calendar-projection").apply { isDaemon = true }
    }
    private val requested = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    fun request(context: Context) {
        val app = context.applicationContext
        requested.set(true)
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                while (requested.getAndSet(false)) {
                    runCatching { CalendarProjectionEngine.runFromCanonicalStore(app) }
                }
            } finally {
                running.set(false)
                if (requested.get()) request(app)
            }
        }
    }
}
