package com.hamhuo.tplanner.diagnostics

/**
 * One attempt at converging this device with the server: the run span of §5.1 plus the store and
 * transport transitions of §5.2 and §5.3.
 *
 * Every method is safe from a worker thread and never throws. The class enforces two rules of the
 * contract rather than trusting callers: a run has exactly one terminal event, and one exchange has
 * exactly one terminal event (§5.3), so a failure can never be reported as a completion afterwards.
 *
 * @param syncOperationId durable correlation of the pending work, kept across attempts
 * @param attempt 1-based retry counter for that work item
 */
class DiagnosticsRun(
    val syncOperationId: String,
    val attempt: Int,
    val deviceId: String? = null,
    val component: String = DiagnosticsComponent.PHONE,
    val context: TraceContext = TraceContext.root(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val startedAt = clock()
    private var opened = false
    private var terminal = false

    /** True only after [runCompleted]: this attempt actually converged, so §7's chain was complete. */
    var converged: Boolean = false
        private set

    fun runStarted(queueDepth: Int?, inFlightSequence: Long?) {
        if (opened) return
        opened = true
        record(
            event = DiagnosticsEvents.SYNC_RUN_STARTED,
            level = DiagnosticsLevel.INFO,
            result = DiagnosticsResult.STARTED,
            span = context,
            queueDepth = queueDepth,
            inFlightSequence = inFlightSequence,
        )
    }

    fun runCompleted() {
        if (terminal) return
        terminal = true
        converged = true
        record(DiagnosticsEvents.SYNC_RUN_COMPLETED, DiagnosticsLevel.INFO, DiagnosticsResult.SUCCEEDED, context,
            durationMs = elapsed())
    }

    fun runFailed(errorCode: String) {
        if (terminal) return
        terminal = true
        record(DiagnosticsEvents.SYNC_RUN_FAILED, DiagnosticsLevel.ERROR, DiagnosticsResult.FAILED, context,
            errorCode = errorCode, durationMs = elapsed())
    }

    /**
     * The attempt stopped deliberately and the work stays queued. `errorCode` may be absent: §4 only
     * requires it when the run failed.
     */
    fun runDeferred(errorCode: String?) {
        if (terminal) return
        terminal = true
        record(DiagnosticsEvents.SYNC_RUN_DEFERRED, DiagnosticsLevel.WARN, DiagnosticsResult.DEFERRED, context,
            errorCode = errorCode, durationMs = elapsed())
    }

    /** A command left the queue, or an immutable in-flight command is being sent again. */
    fun commandPrepared(commandId: String, sequence: Long) = record(
        DiagnosticsEvents.STORE_BATCH_PREPARED, DiagnosticsLevel.INFO, DiagnosticsResult.SUCCEEDED, context,
        commandId = commandId, sequence = sequence,
    )

    fun receiptAccepted(commandId: String, sequence: Long, revision: Long) = record(
        DiagnosticsEvents.STORE_RECEIPT_ACCEPTED, DiagnosticsLevel.INFO, DiagnosticsResult.SUCCEEDED, context,
        commandId = commandId, sequence = sequence, revision = revision,
    )

    /** The server answered `conflict` or `rejected`: the command stays unresolved on purpose. */
    fun receiptRetained(commandId: String, sequence: Long, errorCode: String?) = record(
        DiagnosticsEvents.STORE_RECEIPT_RETAINED, DiagnosticsLevel.WARN, DiagnosticsResult.RETAINED, context,
        commandId = commandId, sequence = sequence, errorCode = errorCode,
    )

    fun snapshotInstalled(revision: Long, localRevision: Long) = record(
        DiagnosticsEvents.STORE_SNAPSHOT_INSTALLED, DiagnosticsLevel.INFO, DiagnosticsResult.SUCCEEDED, context,
        revision = revision, localRevision = localRevision,
    )

    fun inflightReleased(commandId: String, sequence: Long, revision: Long) = record(
        DiagnosticsEvents.STORE_INFLIGHT_RELEASED, DiagnosticsLevel.INFO, DiagnosticsResult.SUCCEEDED, context,
        commandId = commandId, sequence = sequence, revision = revision,
    )

    fun inflightRetained(commandId: String, sequence: Long) = record(
        DiagnosticsEvents.STORE_INFLIGHT_RETAINED, DiagnosticsLevel.WARN, DiagnosticsResult.RETAINED, context,
        commandId = commandId, sequence = sequence,
    )

    /** A network exchange on one medium, with the three-part boundary of §5.3. */
    fun exchange(transport: String): Exchange = Exchange(transport)

    /**
     * The first medium gave up and the next one begins (§5.3). It belongs to the run, not to either
     * exchange; `transport` names the medium that is about to start.
     */
    fun fallbackStarted(transport: String) = record(
        DiagnosticsEvents.TRANSPORT_FALLBACK_STARTED, DiagnosticsLevel.WARN, DiagnosticsResult.STARTED,
        context, transport = transport,
    )

    inner class Exchange(val transport: String) {
        private val span = context.child()

        /**
         * This exchange's own clock, never the run's.
         *
         * An inner class that falls through to the outer `elapsed()` measures from the run's start,
         * which made a later exchange report the whole run's age: a second snapshot round trip of
         * ~0.5 s was recorded as 4201 ms. Name and receiver are kept distinct so that cannot return.
         */
        private val spanStartedAt = clock()
        private var carrierArrived = false
        private var terminal = false

        private fun elapsed(): Long = clock() - spanStartedAt

        fun started() = record(
            DiagnosticsEvents.TRANSPORT_REQUEST_STARTED, DiagnosticsLevel.INFO, DiagnosticsResult.STARTED, span,
            transport = transport,
        )

        /** The carrier is here; it has not been read, parsed or matched yet. */
        fun responseReceived() {
            if (terminal || carrierArrived) return
            carrierArrived = true
            record(DiagnosticsEvents.TRANSPORT_RESPONSE_RECEIVED, DiagnosticsLevel.INFO, DiagnosticsResult.SUCCEEDED,
                span, transport = transport, durationMs = elapsed())
        }

        /** The envelope was read, parsed, validated and matched to this request. */
        fun completed() {
            if (terminal) return
            terminal = true
            record(DiagnosticsEvents.TRANSPORT_REQUEST_COMPLETED, DiagnosticsLevel.INFO, DiagnosticsResult.SUCCEEDED,
                span, transport = transport, durationMs = elapsed())
        }

        /** No carrier was ever obtained. */
        fun failed(errorCode: String) {
            if (terminal) return
            terminal = true
            record(DiagnosticsEvents.TRANSPORT_REQUEST_FAILED, DiagnosticsLevel.ERROR, DiagnosticsResult.FAILED,
                span, transport = transport, errorCode = errorCode, durationMs = elapsed())
        }

        /** A carrier arrived but could not be turned into a usable response. */
        fun responseFailed(errorCode: String) {
            if (terminal) return
            terminal = true
            record(DiagnosticsEvents.TRANSPORT_RESPONSE_FAILED, DiagnosticsLevel.ERROR, DiagnosticsResult.FAILED,
                span, transport = transport, errorCode = errorCode, durationMs = elapsed())
        }
    }

    private fun elapsed(): Long = clock() - startedAt

    private fun record(
        event: String,
        level: String,
        result: String,
        span: TraceContext,
        commandId: String? = null,
        sequence: Long? = null,
        revision: Long? = null,
        localRevision: Long? = null,
        queueDepth: Int? = null,
        inFlightSequence: Long? = null,
        transport: String? = null,
        errorCode: String? = null,
        durationMs: Long? = null,
    ) {
        Diagnostics.emit(
            DiagnosticsEvent(
                event = event,
                component = component,
                level = level,
                result = result,
                span = span,
                syncOperationId = syncOperationId,
                attempt = attempt,
                deviceId = deviceId,
                commandId = commandId,
                sequence = sequence,
                revision = revision,
                localRevision = localRevision,
                queueDepth = queueDepth,
                inFlightSequence = inFlightSequence,
                transport = transport,
                errorCode = errorCode,
                durationMs = durationMs,
            ),
        )
    }
}
