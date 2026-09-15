package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.syncv5.V5Settings
import com.hamhuo.tplanner.syncv5.V5Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** One durable log line. Kept for support/diagnostics, never a source of task facts. */
data class SyncLogEntry(
    val id: String,
    val level: String,
    val source: String,
    val message: String,
    val detail: String? = null,
    val errorCode: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A bounded, crash-safe diagnostic log.
 *
 * It is deliberately independent of the canonical store: logging must never block, fail or
 * participate in a task transaction. Entries live in their own preferences file so a backup or
 * a store reset cannot lose or corrupt them.
 */
object SyncLog {
    const val LEVEL_INFO = "info"
    const val LEVEL_WARN = "warn"
    const val LEVEL_ERROR = "error"
    private const val MAX_ENTRIES = 500
    private const val PREFS = "tplanner_v5_log"

    private val lock = Any()
    private val mutableEntries = MutableStateFlow<List<SyncLogEntry>>(emptyList())
    val entries: StateFlow<List<SyncLogEntry>> = mutableEntries.asStateFlow()

    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            mutableEntries.value = read()
        }
    }

    fun info(source: String, message: String, detail: String? = null) = record(LEVEL_INFO, source, message, detail, null)
    fun warn(source: String, message: String, detail: String? = null) = record(LEVEL_WARN, source, message, detail, null)
    fun error(source: String, message: String, detail: String? = null, errorCode: String? = null) =
        record(LEVEL_ERROR, source, message, detail, errorCode)

    fun clear() = synchronized(lock) {
        val store = prefs ?: return
        store.edit().putString("entries", "[]").commit()
        mutableEntries.value = emptyList()
    }

    private fun record(level: String, source: String, message: String, detail: String?, errorCode: String?) {
        synchronized(lock) {
            val store = prefs ?: return
            val next = (listOf(SyncLogEntry(UUID.randomUUID().toString(), level, source, message, detail, errorCode)) + read())
                .take(MAX_ENTRIES)
            val array = JSONArray()
            next.forEach { entry ->
                array.put(JSONObject().put("id", entry.id).put("level", entry.level).put("source", entry.source)
                    .put("message", entry.message).put("createdAt", entry.createdAt).apply {
                        entry.detail?.let { put("detail", it) }
                        entry.errorCode?.let { put("errorCode", it) }
                    })
            }
            store.edit().putString("entries", array.toString()).commit()
            mutableEntries.value = next
        }
    }

    private fun read(): List<SyncLogEntry> {
        val raw = prefs?.getString("entries", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val row = array.getJSONObject(index)
                SyncLogEntry(
                    id = row.getString("id"),
                    level = row.optString("level", LEVEL_INFO),
                    source = row.optString("source"),
                    message = row.optString("message"),
                    detail = row.optString("detail").takeIf { it.isNotBlank() },
                    errorCode = row.optString("errorCode").takeIf { it.isNotBlank() },
                    createdAt = row.optLong("createdAt"),
                )
            }
        }.getOrDefault(emptyList())
    }
}

/** Configures and drives the V5 endpoint. There is no second protocol behind it. */
class SyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val settings = V5Settings(appContext)

    suspend fun getServerUrl(): String = settings.url.ifBlank { DEFAULT_SERVER_URL }

    suspend fun getToken(): String = settings.token

    suspend fun saveServerUrl(url: String) {
        settings.url = normalizeServerUrl(url)
    }

    suspend fun saveToken(token: String) {
        settings.token = token.trim()
    }

    fun isConfigured(): Boolean = settings.url.isNotBlank() && settings.token.isNotBlank()

    /** Full convergence: snapshot down, every queued command up, then its snapshot back down. */
    suspend fun syncAllOrThrow(serverUrl: String? = null) {
        serverUrl?.let { saveServerUrl(it) }
        V5Sync.synchronize(appContext)
    }

    /**
     * Explicit reconnect. A changed or rebuilt server is never adopted silently: the caller must
     * ask for this, and it discards the local mirror, queue and conflicts before resynchronizing.
     */
    suspend fun resetConnection() {
        V5Store(appContext).reset()
        V5Sync.synchronize(appContext)
    }

    fun hasServerConflict(): Boolean = V5Store(appContext).lastError?.contains("服务器已更换") == true

    fun pendingCount(): Int = V5Store(appContext).pendingCount

    fun lastError(): String? = V5Store(appContext).lastError

    companion object {
        const val DEFAULT_SERVER_URL = "https://sync.hamhuo.top"

        /** Accepts a bare host, a trailing slash and an explicit path; rejects anything else. */
        fun normalizeServerUrl(url: String): String {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return DEFAULT_SERVER_URL
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            val uri = URI(withScheme)
            require(uri.host != null) { "同步地址无效" }
            require(uri.scheme == "https" || uri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")) {
                "同步地址必须使用 HTTPS"
            }
            return withScheme.trimEnd('/')
        }
    }
}
