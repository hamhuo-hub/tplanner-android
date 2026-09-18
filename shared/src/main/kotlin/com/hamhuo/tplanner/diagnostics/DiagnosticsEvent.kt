package com.hamhuo.tplanner.diagnostics

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The fixed vocabulary of `docs/observability-v1.md`.
 *
 * These strings are a cross-repository contract, not local labels: a name that is not here is a
 * change to that document first. Only the events TPlanner currently emits are declared; the relay,
 * watch and server names arrive together with their emitters (§10 steps 3-6).
 */
object DiagnosticsComponent {
    const val WATCH = "watch"
    const val PHONE = "phone"
    const val RELAY = "relay"
    const val SERVER = "server"
    const val DESKTOP = "desktop"
}

object DiagnosticsLevel {
    const val DEBUG = "DEBUG"
    const val INFO = "INFO"
    const val WARN = "WARN"
    const val ERROR = "ERROR"
}

object DiagnosticsResult {
    const val STARTED = "started"
    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val REPLAYED = "replayed"
    const val CONFLICTED = "conflicted"
    const val REJECTED = "rejected"
    const val DEFERRED = "deferred"
    const val RETAINED = "retained"
}

object DiagnosticsTransport {
    const val DATALAYER = "datalayer"
    const val RFCOMM = "rfcomm"
    const val HTTPS = "https"
}

object DiagnosticsEvents {
    const val SYNC_RUN_STARTED = "sync.run.started"
    const val SYNC_RUN_COMPLETED = "sync.run.completed"
    const val SYNC_RUN_FAILED = "sync.run.failed"
    const val SYNC_RUN_DEFERRED = "sync.run.deferred"

    const val STORE_BATCH_PREPARED = "store.batch.prepared"
    const val STORE_RECEIPT_ACCEPTED = "store.receipt.accepted"
    const val STORE_RECEIPT_RETAINED = "store.receipt.retained"
    const val STORE_SNAPSHOT_INSTALLED = "store.snapshot.installed"
    const val STORE_INFLIGHT_RELEASED = "store.inflight.released"
    const val STORE_INFLIGHT_RETAINED = "store.inflight.retained"

    const val TRANSPORT_REQUEST_STARTED = "transport.request.started"
    const val TRANSPORT_RESPONSE_RECEIVED = "transport.response.received"
    const val TRANSPORT_REQUEST_COMPLETED = "transport.request.completed"
    const val TRANSPORT_REQUEST_FAILED = "transport.request.failed"
    const val TRANSPORT_RESPONSE_FAILED = "transport.response.failed"
    const val TRANSPORT_FALLBACK_STARTED = "transport.fallback.started"
}

/**
 * One state transition, shaped exactly like §4 of the contract.
 *
 * Ids, counters, enum values and error codes only: task text, notes, jCal and credentials are never
 * a field here, and there is deliberately no free-text message (§9).
 */
data class DiagnosticsEvent(
    val event: String,
    val component: String,
    val level: String,
    val result: String,
    val span: TraceContext,
    val time: Long = System.currentTimeMillis(),
    val syncOperationId: String? = null,
    val attempt: Int? = null,
    val deviceId: String? = null,
    val requestId: String? = null,
    val commandId: String? = null,
    val sequence: Long? = null,
    val expectedSequence: Long? = null,
    val serverId: String? = null,
    val revision: Long? = null,
    val localRevision: Long? = null,
    val queueDepth: Int? = null,
    val inFlightSequence: Long? = null,
    val transport: String? = null,
    val errorCode: String? = null,
    val durationMs: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("time", TIME_FORMAT.format(Instant.ofEpochMilli(time)))
        .put("level", level)
        .put("event", event)
        .put("component", component)
        .put("traceId", span.traceId)
        .put("spanId", span.spanId)
        .putIfPresent("parentSpanId", span.parentSpanId)
        .putIfPresent("syncOperationId", syncOperationId)
        .putIfPresent("attempt", attempt)
        .putIfPresent("deviceId", deviceId)
        .putIfPresent("requestId", requestId)
        .putIfPresent("commandId", commandId)
        .putIfPresent("sequence", sequence)
        .putIfPresent("expectedSequence", expectedSequence)
        .putIfPresent("serverId", serverId)
        .putIfPresent("revision", revision)
        .putIfPresent("localRevision", localRevision)
        .putIfPresent("queueDepth", queueDepth)
        .putIfPresent("inFlightSequence", inFlightSequence)
        .putIfPresent("transport", transport)
        .putIfPresent("result", result)
        .putIfPresent("errorCode", errorCode)
        .putIfPresent("durationMs", durationMs)

    companion object {
        const val SCHEMA_VERSION = 1

        private val TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** Reads one stored line back. A line that does not satisfy the envelope is dropped. */
        fun fromJson(row: JSONObject): DiagnosticsEvent? = runCatching {
            DiagnosticsEvent(
                event = row.getString("event"),
                component = row.getString("component"),
                level = row.optString("level", DiagnosticsLevel.INFO),
                result = row.optString("result", DiagnosticsResult.SUCCEEDED),
                span = TraceContext(
                    traceId = row.getString("traceId"),
                    spanId = row.getString("spanId"),
                    parentSpanId = row.textOrNull("parentSpanId"),
                ),
                time = Instant.from(TIME_FORMAT.parse(row.getString("time"))).toEpochMilli(),
                syncOperationId = row.textOrNull("syncOperationId"),
                attempt = row.intOrNull("attempt"),
                deviceId = row.textOrNull("deviceId"),
                requestId = row.textOrNull("requestId"),
                commandId = row.textOrNull("commandId"),
                sequence = row.longOrNull("sequence"),
                expectedSequence = row.longOrNull("expectedSequence"),
                serverId = row.textOrNull("serverId"),
                revision = row.longOrNull("revision"),
                localRevision = row.longOrNull("localRevision"),
                queueDepth = row.intOrNull("queueDepth"),
                inFlightSequence = row.longOrNull("inFlightSequence"),
                transport = row.textOrNull("transport"),
                errorCode = row.textOrNull("errorCode"),
                durationMs = row.longOrNull("durationMs"),
            )
        }.getOrNull()
    }
}

private fun JSONObject.putIfPresent(key: String, value: Any?): JSONObject =
    if (value == null) this else put(key, value)

private fun JSONObject.textOrNull(key: String): String? =
    optString(key).takeIf { it.isNotBlank() && it != "null" }

private fun JSONObject.longOrNull(key: String): Long? = if (has(key)) optLong(key) else null

private fun JSONObject.intOrNull(key: String): Int? = if (has(key)) optInt(key) else null
