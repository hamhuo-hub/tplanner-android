package com.hamhuo.tplanner

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.hamhuo.tplanner.diagnostics.Diagnostics
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
 * 手机与手表共用的结构化诊断缓冲区（契约 §8）：最新 5000 条或 5 MB，先到先算，JSON Lines，
 * 独立命名空间。
 *
 * 它与 canonical store（`tplanner_v5`）完全分离，写失败只丢一条事件并计数，绝不阻塞、回滚或
 * 延迟同步。两端共用同一份实现，Watch 与 Phone 的事件语义不会各自漂移；手机上的同步日志面板
 * 只是这份缓冲区的投影。
 *
 * 落盘是分段环形：追加只写当前 segment，segment 写满 500 条或 1 MB 就开新的一段；超过 5000 条
 * 或累计 5 MB 时整段删除最旧的一段。因此磁盘与内存任何时刻都同时受这两个上限约束，既不需要
 * 为了追加一条事件重写整个文件，也不会出现"压缩后仍然超限"的情况。
 */
object DiagnosticsStore : DiagnosticsSink {
    private const val TAG = "TplannerDiagnostics"
    private const val MAX_EVENTS = 5000
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val SEGMENT_EVENTS = 500
    private const val SEGMENT_BYTES = 1L * 1024 * 1024
    private const val PREFS = "tplanner_diagnostics"
    private const val DIRECTORY = "diagnostics"
    private const val SEGMENT_NAME = "observability-v1-%04d.jsonl"
    private const val LEGACY_FILE = "observability-v1.jsonl"
    private const val KEY_OPERATION = "syncOperationId"
    private const val KEY_ATTEMPT = "syncAttempt"

    private val SEGMENT_PATTERN = Regex("observability-v1-(\\d+)\\.jsonl")

    private class Segment(val file: File, val index: Int, var lines: Int, var bytes: Long)

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-diagnostics").apply { isDaemon = true }
    }
    private val mutableEvents = MutableStateFlow<List<DiagnosticsEvent>>(emptyList())

    /** Newest first; exactly the union of the retained segments, so it is bounded by both caps. */
    val events: StateFlow<List<DiagnosticsEvent>> = mutableEvents.asStateFlow()

    private val droppedEvents = AtomicInteger()

    /** Events this process failed to persist. Visible in the panel, never fatal. */
    val dropped: Int get() = droppedEvents.get()

    private var prefs: SharedPreferences? = null
    private var directory: File? = null

    /** Diagnostics-worker thread only, oldest segment first. */
    private val segments = mutableListOf<Segment>()

    /** Diagnostics-worker thread only, newest event first. Mirrors [segments]. */
    private val retained = mutableListOf<DiagnosticsEvent>()
    private var nextIndex = 1

    fun init(context: Context) {
        synchronized(this) {
            if (prefs != null) return
            val app = context.applicationContext
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            directory = File(app.filesDir, DIRECTORY)
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
            runCatching {
                segments.forEach { it.file.delete() }
                segments.clear()
                retained.clear()
                // 段号继续递增：删除失败时残留的旧段不会再被当成"最新的一段"。
            }.onFailure { Log.w(TAG, "Unable to clear the diagnostics buffer", it) }
        }
    }

    /**
     * 一次同步尝试的运行上下文。`syncOperationId` 属于"这件待办工作"，跨尝试、跨进程重启都复用
     * 同一个值（契约 §2）；它在诊断命名空间里，不在 canonical store 里。
     *
     * `component` 决定事件归属（`phone` / `watch`），不能由调用点随手写字符串。
     */
    fun beginRun(component: String, deviceId: String?): DiagnosticsRun {
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
            component = component,
            context = TraceContext.root(),
        )
    }

    /** 只有真正收敛的那次运行才结束这件工作：冲突与排队中的修改都算未完成。 */
    fun finishRun(converged: Boolean) {
        if (!converged) return
        prefs?.edit()?.remove(KEY_OPERATION)?.remove(KEY_ATTEMPT)?.apply()
    }

    private fun write(event: DiagnosticsEvent) {
        if (directory == null) return
        val line = event.toJson().toString()
        if (line.length > MAX_BYTES) {
            droppedEvents.incrementAndGet()
            Log.w(TAG, "Dropped oversized diagnostics event ${event.event}")
            return
        }
        val segment = currentSegment(line.length + 1)
        segment.file.appendText(line + "\n")
        segment.lines += 1
        segment.bytes += line.length + 1
        retained.add(0, event)
        enforceRing()
        publish()
    }

    /** The newest segment, rolled when it is full so appends never rewrite an old one. */
    private fun currentSegment(nextLineBytes: Int): Segment {
        val dir = directory ?: error("diagnostics directory unavailable")
        if (segments.isEmpty()) {
            // 目录里可能还有本次没能读进来的段（例如读到一半失败）：段号必须接在它们后面，
            // 否则新事件会被追加进旧段文件，段计数与内存就不再对应。
            dir.listFiles { file -> SEGMENT_PATTERN.matches(file.name) }?.forEach {
                nextIndex = maxOf(nextIndex, indexOf(it) + 1)
            }
        }
        val newest = segments.lastOrNull()
        if (newest != null && newest.lines < SEGMENT_EVENTS && newest.bytes + nextLineBytes <= SEGMENT_BYTES) {
            return newest
        }
        dir.mkdirs()
        val index = nextIndex++
        return Segment(File(dir, SEGMENT_NAME.format(index)), index, 0, 0).also { segments += it }
    }

    /** Drops whole oldest segments until both caps hold, so disk and memory stay in step. */
    private fun enforceRing() {
        while (segments.isNotEmpty() && (totalLines() > MAX_EVENTS || totalBytes() > MAX_BYTES)) {
            val oldest = segments.removeAt(0)
            oldest.file.delete()
            repeat(oldest.lines) { if (retained.isNotEmpty()) retained.removeAt(retained.size - 1) }
        }
    }

    private fun totalLines(): Int = segments.sumOf { it.lines }
    private fun totalBytes(): Long = segments.sumOf { it.bytes }

    private fun publish() {
        mutableEvents.value = retained.toList()
    }

    private fun load() {
        val dir = directory ?: return
        dir.mkdirs()
        segments.clear()
        retained.clear()
        importLegacy(dir)
        segments.clear()
        val files = dir.listFiles { file -> SEGMENT_PATTERN.matches(file.name) }?.sortedBy(::indexOf).orEmpty()
        val chronological = mutableListOf<DiagnosticsEvent>()
        files.forEach { file ->
            val raw = file.readLines()
            val valid = raw.mapNotNull { line ->
                runCatching { JSONObject(line) }.getOrNull()?.let(DiagnosticsEvent::fromJson)
            }
            // 半行或损坏的行留在文件里只会让段计数与内存不一致，读一次就顺手清掉。
            if (valid.size != raw.size) {
                file.writeText(buildString { valid.forEach { append(it.toJson().toString()).append('\n') } })
            }
            val index = indexOf(file)
            segments += Segment(file, index, valid.size, file.length())
            chronological += valid
            nextIndex = maxOf(nextIndex, index + 1)
        }
        retained.addAll(chronological.asReversed())
        enforceRing()
        publish()
    }

    /** 旧版本写的是单个 `observability-v1.jsonl`：按新分段重写一遍再删除，不留读不到的占用。 */
    private fun importLegacy(dir: File) {
        val legacy = File(dir, LEGACY_FILE)
        if (!legacy.isFile) return
        legacy.readLines().forEach { line ->
            val event = runCatching { JSONObject(line) }.getOrNull()?.let(DiagnosticsEvent::fromJson)
                ?: return@forEach
            val text = event.toJson().toString()
            val segment = currentSegment(text.length + 1)
            segment.file.appendText(text + "\n")
            segment.lines += 1
            segment.bytes += text.length + 1
        }
        legacy.delete()
    }

    private fun indexOf(file: File): Int =
        SEGMENT_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
