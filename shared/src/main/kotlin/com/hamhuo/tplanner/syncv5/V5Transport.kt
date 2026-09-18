package com.hamhuo.tplanner.syncv5

import android.content.Context
import com.hamhuo.tplanner.diagnostics.DiagnosticsErrorCode
import com.hamhuo.tplanner.diagnostics.DiagnosticsRun
import com.hamhuo.tplanner.diagnostics.DiagnosticsTransport
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * 与服务端 `sync-server/src/v5/api.js` 的 SYNC_PASSWORD 必须一致。
 *
 * 它写死在两端，用户不需要配置任何东西。它只用于挡住"扫到域名就乱写"的流量，
 * 不是安全边界：真正的保护是这个域名没有被公开传播。
 */
internal const val SYNC_PASSWORD = "2004"

/**
 * 服务器拒绝了这一批。信封本身是完整可读的，所以这是协议层失败而不是传输层失败：
 * `transport.request.completed` 已经发出，run 用服务器自己的 code 结束
 * （`SEQUENCE_GAP`、`COMMAND_ID_REUSE` 等），诊断码永远不会被替换成笼统的失败。
 */
class SyncRejectedException(val code: String, val status: Int) : IllegalStateException("同步失败：$code")

/**
 * 本次尝试没有收敛，但没有任何东西丢失：要么服务器把命令判为 conflict/rejected（已经安全落在
 * 本机冲突列表里，等用户决定），要么一轮的发送上限用完了、剩下的仍排队。
 *
 * 抛出来是为了让上层不会把它记成成功：`sync.run.completed` 只在 §7 的链条完整时允许出现。
 */
class SyncUnresolvedException(
    val code: String?,
    /** True when another attempt can still make progress on its own (work is queued, not stuck). */
    val retryable: Boolean,
) : IllegalStateException(code ?: "同步未完成，修改仍安全保存在本机")

/** V5 协议码的形状：`SEQUENCE_GAP`、`REVISION_CONFLICT`、`INVALID_DOCUMENT`…… */
private val V5_ERROR_CODE = Regex("[A-Z][A-Z0-9_]{2,63}")

private const val V5_PROTOCOL_VERSION = 5

interface V5Transport {
    fun snapshot(): JSONObject
    fun send(batch: JSONObject): JSONObject
}

class V5Http(url: String, private val run: DiagnosticsRun? = null) : V5Transport {
    private val base = url.trim().trimEnd('/')
    init {
        val uri = URI(base)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) { "同步地址必须使用 HTTPS" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "请配置同步地址" }
    }
    override fun snapshot() = request("snapshot", null)
    override fun send(batch: JSONObject) = request("batch", batch)
    private fun request(path: String, body: JSONObject?): JSONObject {
        // One exchange, one span: the three-part boundary of contract §5.3 is recorded here because
        // this is the only place that can tell "no carrier" from "carrier, but unusable".
        val exchange = run?.exchange(DiagnosticsTransport.HTTPS)
        exchange?.started()
        var carrierArrived = false
        val connection = URI("$base/tplanner/v5/$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $SYNC_PASSWORD")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            carrierArrived = true
            exchange?.responseReceived()
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    if (output.size() + n > 16 * 1024 * 1024) {
                        exchange?.responseFailed(DiagnosticsErrorCode.RESPONSE_UNREADABLE)
                        error("快照超过大小限制")
                    }
                    output.write(buffer, 0, n)
                }; output.toByteArray()
            } ?: byteArrayOf()
            val response = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse {
                exchange?.responseFailed(DiagnosticsErrorCode.RESPONSE_UNREADABLE)
                error("同步响应无效 (HTTP $code)")
            }
            if (code in 200..299) {
                // A 200 from a captive portal or a proxy is still not our server talking.
                if (response.optInt("protocolVersion", 0) != V5_PROTOCOL_VERSION) {
                    exchange?.responseFailed(DiagnosticsErrorCode.INVALID_RESPONSE)
                    error("同步响应不是 V5 信封 (HTTP $code)")
                }
                exchange?.completed()
                return response
            }
            // 4xx/5xx:只有带合法 V5 协议码的错误信封才算"服务器看懂了并拒绝"。
            // 反代/CDN 的 JSON（{"message":"Bad Gateway"}）不是我们的信封，
            // 它既不能升级成协议拒绝，也不允许编造 HTTP_502 这种契约外的 code。
            val protocolCode = response.optString("code").takeIf(V5_ERROR_CODE::matches)
            if (protocolCode == null) {
                exchange?.responseFailed(DiagnosticsErrorCode.INVALID_RESPONSE)
                error("同步响应不是 V5 错误信封 (HTTP $code)")
            }
            exchange?.completed()
            throw SyncRejectedException(protocolCode, code)
        } catch (error: Exception) {
            val errorCode = DiagnosticsErrorCode.of(error)
            if (carrierArrived) exchange?.responseFailed(errorCode) else exchange?.failed(errorCode)
            throw error
        } finally { connection.disconnect() }
    }
}

class V5SyncClient(
    private val store: V5Store,
    private val transport: V5Transport,
    private val run: DiagnosticsRun? = null,
) {
    /** Blocking; callers run this on their worker dispatcher. One sync per store at a time. */
    fun synchronize() = synchronized(syncLocks.getOrPut(store.deviceId) { Any() }) {
        try {
            var localRevision = store.revision
            store.installSnapshot(transport.snapshot())
            run?.snapshotInstalled(store.revision, localRevision)
            var unresolvedCode: String? = null
            var attempts = 0
            while (attempts++ < 100) {
                val batch = store.prepareBatch() ?: break
                val command = batch.getJSONArray("commands").getJSONObject(0)
                val commandId = command.getString("commandId")
                val sequence = command.getLong("sequence")
                run?.commandPrepared(commandId, sequence)

                val response = transport.send(batch)
                store.acceptReceipt(response)
                val receipt = response.getJSONArray("receipts").getJSONObject(0)
                if (receipt.getString("status") == "applied") {
                    run?.receiptAccepted(commandId, sequence, receipt.getLong("revision"))
                } else {
                    val code = receipt.optString("code").takeIf(String::isNotBlank)
                    run?.receiptRetained(commandId, sequence, code)
                    // Safely stored as a conflict, but this run did not converge: §7 forbids
                    // sync.run.completed once a command was prepared without an accepted receipt.
                    if (unresolvedCode == null) unresolvedCode = code
                }

                localRevision = store.revision
                store.installSnapshot(transport.snapshot())
                run?.snapshotInstalled(store.revision, localRevision)
                // The command left the in-flight slot only after the snapshot that confirms it.
                if (store.inFlightCommandId == null) run?.inflightReleased(commandId, sequence, store.revision)
            }
            when {
                unresolvedCode != null ->
                    throw SyncUnresolvedException(unresolvedCode, retryable = false)
                // An earlier conflict is unfinished work too: a pass that re-sends nothing must not
                // report success while the user still has a decision to make.
                store.conflicts().isNotEmpty() ->
                    throw SyncUnresolvedException(openConflictCode(), retryable = false)
                store.pendingCount > 0 ->
                    // The per-attempt send budget ran out; the rest stays queued and the next
                    // attempt continues it, which is a deferral rather than a failure.
                    throw SyncUnresolvedException(code = null, retryable = true)
                else -> store.setError(null)
            }
        } catch (error: Exception) {
            store.setError(error.message ?: "同步失败，修改保留在本机")
            // A failed attempt keeps the same immutable command for the next attempt.
            val pendingId = store.inFlightCommandId
            val pendingSequence = store.inFlightSequence
            if (pendingId != null && pendingSequence != null) run?.inflightRetained(pendingId, pendingSequence)
            throw error
        }
    }

    /** The server's code for the oldest stored conflict, when it provided one. */
    private fun openConflictCode(): String? = store.conflicts().firstOrNull()
        ?.optJSONObject("receipt")
        ?.optString("code")
        ?.takeIf(String::isNotBlank)

    companion object { private val syncLocks = java.util.concurrent.ConcurrentHashMap<String, Any>() }
}

class V5Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tplanner_v5_settings", Context.MODE_PRIVATE)
    var url: String get() = prefs.getString("url", "").orEmpty()
        set(value) { check(prefs.edit().putString("url", value.trim().trimEnd('/')).commit()) }
    var calendarEnabled: Boolean get() = prefs.getBoolean("calendarEnabled", false)
        set(value) { check(prefs.edit().putBoolean("calendarEnabled", value).commit()) }
}
