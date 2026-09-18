package com.hamhuo.tplanner.diagnostics

/**
 * Where structured diagnostics go. Implementations must not block the caller and must not throw:
 * a diagnostics failure may drop an event, never a synchronization (contract §8).
 */
fun interface DiagnosticsSink {
    fun append(event: DiagnosticsEvent)
}

/**
 * The one installation point for a platform's diagnostics buffer.
 *
 * Nothing installed (the default, which is also the watch until it grows its own buffer in §10
 * step 3) means every emission is a no-op.
 */
object Diagnostics {
    @Volatile
    private var sink: DiagnosticsSink = DiagnosticsSink { }

    fun install(sink: DiagnosticsSink) {
        this.sink = sink
    }

    fun emit(event: DiagnosticsEvent) {
        runCatching { sink.append(event) }
    }
}

/** Transport and local-failure codes that are not V5 protocol codes (§6). */
object DiagnosticsErrorCode {
    const val NETWORK_UNAVAILABLE = "NETWORK_UNAVAILABLE"
    const val NETWORK_TIMEOUT = "NETWORK_TIMEOUT"
    const val NOT_CONFIGURED = "NOT_CONFIGURED"
    const val RESPONSE_UNREADABLE = "RESPONSE_UNREADABLE"
    const val INVALID_RESPONSE = "INVALID_RESPONSE"
    const val LOCAL_STATE_REJECTED = "LOCAL_STATE_REJECTED"
    const val SYNC_FAILED = "SYNC_FAILED"

    /**
     * A stable code for one throwable. A protocol rejection keeps the server's own code, which is
     * why this never invents a "close enough" value.
     */
    fun of(error: Throwable): String = when (error) {
        is com.hamhuo.tplanner.syncv5.SyncRejectedException -> error.code
        is com.hamhuo.tplanner.syncv5.SyncUnresolvedException -> error.code ?: SYNC_FAILED
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
            NETWORK_UNAVAILABLE
        is java.net.SocketTimeoutException -> NETWORK_TIMEOUT
        is java.net.SocketException -> NETWORK_UNAVAILABLE
        is IllegalArgumentException -> LOCAL_STATE_REJECTED
        else -> SYNC_FAILED
    }

    /** True when the attempt stopped because the server was unreachable, not because it refused. */
    fun isDeferral(error: Throwable): Boolean = when (error) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException ->
            true
        is java.net.SocketTimeoutException, is java.net.SocketException -> true
        else -> false
    }
}
