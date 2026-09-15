package com.hamhuo.tplanner

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Manual pull-to-refresh: one blocking V5 pass on a worker thread. */
internal object WatchManualSync {
    internal enum class Result {
        COMPLETED,
        FAILED,
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-manual-sync").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    fun request(context: Context, onComplete: (Result) -> Unit) {
        val appContext = context.applicationContext
        worker.execute {
            val result = if (WatchV5Sync.synchronize(appContext)) Result.COMPLETED else Result.FAILED
            mainHandler.post { onComplete(result) }
        }
    }
}
