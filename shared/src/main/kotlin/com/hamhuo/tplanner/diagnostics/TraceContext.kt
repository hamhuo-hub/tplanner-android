package com.hamhuo.tplanner.diagnostics

import java.util.UUID

/**
 * W3C Trace Context identity of one span: `traceId` is 32 hex, `spanId` is 16 hex.
 *
 * A trace belongs to one attempt and never changes. A hop that does work derives a child, so the
 * `traceId` survives and the `spanId` does not. Forwarding a received context unchanged is not
 * something this type can express, which is deliberate: see `docs/observability-v1.md` §3.1.
 */
data class TraceContext(
    val traceId: String,
    val spanId: String,
    val parentSpanId: String? = null,
    val sampled: Boolean = true,
) {
    /** A new span in the same trace, with this span as its parent. */
    fun child(): TraceContext = TraceContext(traceId, randomSpanId(), spanId, sampled)

    companion object {
        /** The root span of a new attempt: a fresh trace with no parent. */
        fun root(): TraceContext = TraceContext(randomTraceId(), randomSpanId(), null)
    }
}

private fun randomTraceId(): String = UUID.randomUUID().toString().replace("-", "")

private fun randomSpanId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
