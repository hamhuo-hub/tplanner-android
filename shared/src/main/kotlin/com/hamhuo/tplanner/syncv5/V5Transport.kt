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
                    require(output.size() + n <= 16 * 1024 * 1024) { "快照超过大小限制" }; output.write(buffer, 0, n)
                }; output.toByteArray()
            } ?: byteArrayOf()
            val response = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse {
                exchange?.responseFailed(DiagnosticsErrorCode.RESPONSE_UNREADABLE)
                error("同步响应无效 (HTTP $code)")
            }
            // The envelope is readable and well-formed either way; a 4xx/5xx is the server's answer,
            // so the exchange completed and the protocol code travels on the run instead.
            exchange?.completed()
            if (code !in 200..299) throw SyncRejectedException(response.optString("code", "HTTP_$code"), code)
            return response
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
                    run?.receiptRetained(commandId, sequence, receipt.optString("code").takeIf(String::isNotBlank))
                }

                localRevision = store.revision
                store.installSnapshot(transport.snapshot())
                run?.snapshotInstalled(store.revision, localRevision)
                // The command left the in-flight slot only after the snapshot that confirms it.
                if (store.inFlightCommandId == null) run?.inflightReleased(commandId, sequence, store.revision)
            }
            store.setError(null)
        } catch (error: Exception) {
            store.setError(error.message ?: "同步失败，修改保留在本机")
            // A failed attempt keeps the same immutable command for the next attempt.
            val pendingId = store.inFlightCommandId
            val pendingSequence = store.inFlightSequence
            if (pendingId != null && pendingSequence != null) run?.inflightRetained(pendingId, pendingSequence)
            throw error
        }
    }
    companion object { private val syncLocks = java.util.concurrent.ConcurrentHashMap<String, Any>() }
}

class V5Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tplanner_v5_settings", Context.MODE_PRIVATE)
    var url: String get() = prefs.getString("url", "").orEmpty()
        set(value) { check(prefs.edit().putString("url", value.trim().trimEnd('/')).commit()) }
    var calendarEnabled: Boolean get() = prefs.getBoolean("calendarEnabled", false)
        set(value) { check(prefs.edit().putBoolean("calendarEnabled", value).commit()) }
}
