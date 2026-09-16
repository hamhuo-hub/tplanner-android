package com.hamhuo.tplanner.syncv5

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

interface V5Transport {
    fun snapshot(): JSONObject
    fun send(batch: JSONObject): JSONObject
}

class V5Http(url: String, private val token: String) : V5Transport {
    private val base = url.trim().trimEnd('/')
    init {
        val uri = URI(base)
        require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2"))) { "同步地址必须使用 HTTPS" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null && token.isNotBlank()) { "请配置同步地址和令牌" }
    }
    override fun snapshot() = request("snapshot", null)
    override fun send(batch: JSONObject) = request("batch", batch)
    private fun request(path: String, body: JSONObject?): JSONObject {
        val connection = URI("$base/tplanner/v5/$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    require(output.size() + n <= 16 * 1024 * 1024) { "快照超过大小限制" }; output.write(buffer, 0, n)
                }; output.toByteArray()
            } ?: byteArrayOf()
            val response = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrElse { error("同步响应无效 (HTTP $code)") }
            require(code in 200..299) { "同步失败：${response.optString("code", "HTTP_$code")}" }
            return response
        } finally { connection.disconnect() }
    }
}

class V5SyncClient(private val store: V5Store, private val transport: V5Transport) {
    /** Blocking; callers run this on their worker dispatcher. One sync per store at a time. */
    fun synchronize() = synchronized(syncLocks.getOrPut(store.deviceId) { Any() }) {
        try {
            store.installSnapshot(transport.snapshot())
            var attempts = 0
            while (attempts++ < 100) {
                val batch = store.prepareBatch() ?: break
                store.acceptReceipt(transport.send(batch))
                store.installSnapshot(transport.snapshot())
            }
            store.setError(null)
        } catch (error: Exception) {
            store.setError(error.message ?: "同步失败，修改保留在本机")
            throw error
        }
    }
    companion object { private val syncLocks = java.util.concurrent.ConcurrentHashMap<String, Any>() }
}

class V5Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tplanner_v5_settings", Context.MODE_PRIVATE)
    var url: String get() = prefs.getString("url", "").orEmpty()
        set(value) { check(prefs.edit().putString("url", value.trim().trimEnd('/')).commit()) }
    /** The bare access token. A pasted `Bearer ` prefix is accepted and stripped, never doubled. */
    var token: String get() = prefs.getString("token", "").orEmpty()
        set(value) {
            val normalized = value.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "").trim()
            check(prefs.edit().putString("token", normalized).commit())
        }
    var calendarEnabled: Boolean get() = prefs.getBoolean("calendarEnabled", false)
        set(value) { check(prefs.edit().putBoolean("calendarEnabled", value).commit()) }
}
