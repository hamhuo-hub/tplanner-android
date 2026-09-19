package com.hamhuo.tplanner.diagnostics

import android.util.Log

/**
 * logcat 出口：把同一条诊断事件再渲染成一行给人看的文本。
 *
 * 缓冲区（`com.hamhuo.tplanner.DiagnosticsStore`）才是权威记录，这里只是它的只读投影，所以本类不
 * 持有状态、不写盘、不抛异常。契约 §8 的"诊断绝不参与事务"同样适用：调用点已经在诊断线程上，打印
 * 只发生在那一根线程里，同步调用线程不受影响。
 *
 * 打印的不是原始 JSON 行。logcat 单条消息约 4 KB 就会被截断，而 §4 的完整信封（二十多个字段）轻易
 * 超过这个长度；被截断的行恰好会丢掉行尾的 `result` / `errorCode`，那是排障最需要的两个字段。因此
 * 这里显式挑出相关性字段、顺序固定、缺失即省略；完整信封永远在 JSONL 文件与同步日志面板里。
 *
 * 契约 §9 是硬约束：只允许不透明 id、枚举、计数器和时长。任务文本、位置、凭据、jCal、AI 内容永远
 * 不经过本类 —— [DiagnosticsEvent] 本身就没有能携带它们的字段，§9 因此不依赖调用点自觉。
 */
internal object DiagnosticsConsole {

    /** 与其余 TAG 同前缀，`adb logcat -s TplannerDiag:*` 一条命令即可只看诊断。 */
    private const val TAG = "TplannerDiag"

    /**
     * API 26+ 起 `Log.println` 每次调用都会在 VM 里取一次调用栈来推断 sourceTag。诊断事件是高频写入，
     * 这里显式传入 tag 跳过那段开销；值为 null 也就是"已经显式给过 tag"，正是框架要的约定。
     */
    private val TAG_SOURCE = ThreadLocal.withInitial<String?> { null }

    /**
     * 手机与手表是两个进程，日志却在同一条 logcat 流里交错，只凭 `component` 分不出端到端的两段是
     * 否来自同一次运行。pid 在一个进程里不会变，取一次就够。
     */
    private val pid: String by lazy { android.os.Process.myPid().toString() }

    /** 永不抛出：一条诊断输出失败只该少一行日志，不该影响写盘。 */
    fun log(event: DiagnosticsEvent) {
        runCatching { Log.println(priorityOf(event.level), TAG, render(event)) }
    }

    /** 一行一条事件，字段顺序固定；`component` 与 pid 在前，便于按端比对。 */
    private fun render(event: DiagnosticsEvent): String = buildString {
        put("component", event.component)
        put("pid", pid)
        put("event", event.event)
        put("level", event.level)
        put("result", event.result)
        put("traceId", event.span.traceId)
        put("spanId", event.span.spanId)
        put("parentSpanId", event.span.parentSpanId)
        put("operation", event.syncOperationId)
        put("attempt", event.attempt)
        put("device", event.deviceId)
        put("sequence", event.sequence)
        put("expectedSequence", event.expectedSequence)
        put("revision", event.revision)
        put("localRevision", event.localRevision)
        put("transport", event.transport)
        put("errorCode", event.errorCode)
        put("durationMs", event.durationMs)
        put("queueDepth", event.queueDepth)
        put("inFlightSequence", event.inFlightSequence)
    }

    /** 前导空格由本方法负责，行首字段因此不会因为自己的字段缺失而变形。 */
    private fun StringBuilder.put(key: String, value: Any?) {
        if (value != null) append(' ').append(key).append('=').append(value)
    }

    /** `level` 是 §4 的枚举；出现未知值时按 INFO 打印，而不是整行丢掉。 */
    private fun priorityOf(level: String): Int = when (level) {
        DiagnosticsLevel.DEBUG -> Log.DEBUG
        DiagnosticsLevel.WARN -> Log.WARN
        DiagnosticsLevel.ERROR -> Log.ERROR
        else -> Log.INFO
    }
}
