package com.hamhuo.tplanner

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hamhuo.tplanner.diagnostics.Diagnostics
import com.hamhuo.tplanner.diagnostics.DiagnosticsComponent
import com.hamhuo.tplanner.diagnostics.DiagnosticsEvent
import com.hamhuo.tplanner.diagnostics.DiagnosticsRun
import com.hamhuo.tplanner.diagnostics.DiagnosticsSink
import com.hamhuo.tplanner.diagnostics.TraceContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手机上的结构化诊断缓冲区：最新 5000 条或 5 MB，JSON Lines，独立命名空间。
 *
 * 它与 canonical store（`tplanner_v5`）完全分离，写失败只丢一条事件并计数，绝不阻塞、回滚或
 * 延迟同步（契约 §8）。同步日志面板只是这份 buffer 的投影。
 *
 * 事件在单线程后台队列里落盘，所以调用方（同步线程或主线程）不会被文件读写拖住。
 * 条目数上限是压缩后的保留量：文件达到两倍上限时才重写回收，避免每条事件都重写整个文件。
 */
object DiagnosticsStore : DiagnosticsSink {
    private const val TAG = "TplannerDiagnostics"
    private const val MAX_EVENTS = 5000
    private const val COMPACT_AT_LINES = MAX_EVENTS * 2
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val PREFS = "tplanner_diagnostics"
    private const val DIRECTORY = "diagnostics"
    private const val FILE_NAME = "observability-v1.jsonl"
    private const val KEY_OPERATION = "syncOperationId"
    private const val KEY_ATTEMPT = "syncAttempt"

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-diagnostics").apply { isDaemon = true }
    }
    private val mutableEvents = MutableStateFlow<List<DiagnosticsEvent>>(emptyList())

    /** Newest first, capped at the retention bound. The UI observes exactly this. */
    val events: StateFlow<List<DiagnosticsEvent>> = mutableEvents.asStateFlow()

    private val droppedEvents = AtomicInteger()

    /** Events this process failed to persist. Visible in the panel, never fatal. */
    val dropped: Int get() = droppedEvents.get()

    private var prefs: SharedPreferences? = null
    private var file: File? = null

    /** Lines currently in the file; touched only by the diagnostics worker. */
    private var lines = 0

    fun init(context: Context) {
        synchronized(this) {
            if (prefs != null) return
            val app = context.applicationContext
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            file = File(File(app.filesDir, DIRECTORY), FILE_NAME)
        }
        Diagnostics.install(this)
        worker.execute { runCatching { load() }.onFailure { Log.w(TAG, "Unreadable diagnostics buffer", it) } }
    }

    override fun append(event: DiagnosticsEvent) {
        worker.execute {
            runCatching { write(event) }.onFailure {
                droppedEvents.incrementAndGet()
                Log.w(TAG, "Dropped diagnostics event ${event.event}", it)
            }
        }
    }

    fun clear() {
        mutableEvents.value = emptyList()
        worker.execute {
            runCatching { file?.writeText(""); lines = 0 }
                .onFailure { Log.w(TAG, "Unable to clear the diagnostics buffer", it) }
        }
    }

    /**
     * 一次同步尝试的运行上下文。`syncOperationId` 属于"这件待办工作"，跨尝试、跨进程重启都复用
     * 同一个值（契约 §2）；它在诊断命名空间里，不在 canonical store 里。
     */
    fun beginRun(deviceId: String?): DiagnosticsRun {
        val store = prefs
        val existing = store?.getString(KEY_OPERATION, null)?.takeIf { it.isNotBlank() }
        val operationId = existing ?: UUID.randomUUID().toString()
        val attempt = (store?.getInt(KEY_ATTEMPT, 0) ?: 0) + 1
        // Editor.apply(): 异步写盘，不会把同步路径拖到磁盘上。
        store?.edit()?.putString(KEY_OPERATION, operationId)?.putInt(KEY_ATTEMPT, attempt)?.apply()
        return DiagnosticsRun(
            syncOperationId = operationId,
            attempt = attempt,
            deviceId = deviceId,
            component = DiagnosticsComponent.PHONE,
            context = TraceContext.root(),
        )
    }

    /** 本机已经没有待上传内容：下次本地修改算一件新工作，attempt 重新从 1 开始。 */
    fun finishRun(converged: Boolean) {
        if (!converged) return
        prefs?.edit()?.remove(KEY_OPERATION)?.remove(KEY_ATTEMPT)?.apply()
    }

    private fun write(event: DiagnosticsEvent) {
        val target = file ?: return
        val line = event.toJson().toString()
        require(line.length <= MAX_BYTES) { "diagnostics event too large" }
        target.parentFile?.mkdirs()
        target.appendText(line + "\n")
        lines += 1
        mutableEvents.value = (listOf(event) + mutableEvents.value).take(MAX_EVENTS)
        if (lines > COMPACT_AT_LINES || target.length() > MAX_BYTES) compact(target)
    }

    private fun load() {
        val target = file ?: return
        val raw = if (target.isFile) target.readLines() else emptyList()
        lines = raw.size
        mutableEvents.value = raw.asReversed().mapNotNull { line ->
            runCatching { JSONObject(line) }.getOrNull()?.let(DiagnosticsEvent::fromJson)
        }.take(MAX_EVENTS)
        if (lines > COMPACT_AT_LINES || target.length() > MAX_BYTES) compact(target)
    }

    /** Rewrites the file with the retained events only (oldest first, so append order is preserved). */
    private fun compact(target: File) {
        val retained = mutableEvents.value.asReversed()
        target.writeText(buildString { retained.forEach { append(it.toJson().toString()).append('\n') } })
        lines = retained.size
    }
}
