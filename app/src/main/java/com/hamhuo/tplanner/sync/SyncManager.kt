package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.syncv5.V5Settings
import com.hamhuo.tplanner.syncv5.V5Store
import java.net.URI

/** Configures and drives the V5 endpoint. There is no second protocol behind it. */
class SyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val settings = V5Settings(appContext)

    suspend fun getServerUrl(): String = settings.url.ifBlank { DEFAULT_SERVER_URL }

    suspend fun saveServerUrl(url: String) {
        settings.url = normalizeServerUrl(url)
    }

    fun isConfigured(): Boolean = settings.url.isNotBlank()

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
