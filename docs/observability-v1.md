# Observability V1 — trace context, attributes, event vocabulary

Status: implementation contract, 2026-09-17. Applies to `tplanner-android` (phone + watch),
`tplanner-server`, and `tplanner-desktop`.

Canonical copy: `tplanner-android/docs/observability-v1.md`. Verbatim mirrors are committed as
`docs/observability-v1.md` in the server and desktop repositories. Edit the canonical copy first,
then re-copy; never edit a mirror.

This document freezes four things and nothing else: the trace context that crosses process and
transport boundaries, TPlanner's semantic attributes, the event vocabulary, and the rules that keep
diagnostics out of the synchronization transaction. Collection, storage, query, topology and UI
belong to the observability backend (OpenTelemetry → SkyWalking OAP), not to this contract.

## 1. Two layers, both required

| Layer | Question it answers | Identity |
| --- | --- | --- |
| trace / span | who called whom, how long it took, where it failed | W3C Trace Context: `traceId` = 32 hex, `spanId` = 16 hex |
| event | which business state transition happened, with which V5 numbers | `event` + TPlanner attributes, attached to the span that caused it |

A span alone renders `POST /tplanner/v5/batch 409 2.3 ms` and stops. An event alone has no causal
parent. Neither replaces the other, and every diagnostic line carries both.

One trace is one causal tree. `traceId` is created once, at the origin of an attempt, and is never
changed along the chain. **Every hop that does work creates its own `spanId` and injects its own
context downstream.** A hop that forwards a received `traceparent` unchanged declares the next
service to be a child of a span it never spoke to, and erases itself from the causal tree — which is
exactly the relationship this layer exists to record.

TPlanner therefore does not invent id formats, does not run a collector, and does not ship a query
UI. It propagates standard context and emits standard-shaped events.

## 2. `syncOperationId` vs `traceId`

Two different lifetimes. They are never merged.

| | `syncOperationId` | `traceId` |
| --- | --- | --- |
| Means | one durable piece of synchronization work | one actual execution attempt of that work |
| Generated | when the work item is persisted (outbox row, queued command, pending watch request) | when an attempt starts, at its origin |
| Survives | process death, reboot, network loss, hours of backoff | nothing; it ends with the attempt |
| On retry | unchanged | new value for every attempt |
| Used for | "is this still the same pending job", cross-attempt correlation | causal tree, latency, span parenting |
| Must never | affect idempotency, receipts or sequence decisions | be persisted as business state |

`attempt` (1-based) travels with each event, so a retried job reads as: same `syncOperationId`,
`attempt=2`, new `traceId`.

`deviceId`, `commandId` and `sequence` stay the V5 protocol's own business identity. Trace context
is diagnostic only: **the server returns an identical response with or without trace headers, and no
client changes retry, queue or idempotency behaviour because of a trace value.**

## 3. Propagation

### 3.1 The rule at every hop: extract, start a span, inject

No hop forwards a `traceparent` it received. Each hop does the same three standard W3C steps:

```
receive traceparent            extract  → (traceId, parentSpanId, flags)
create its own span            start    → new spanId, parentSpanId = extracted spanId
send downstream                inject   → traceparent carrying its own spanId
```

The watch-to-server chain therefore reads:

```
watch      injects traceparent A   (spanId A, no parent)
  ↓ envelope
phone      extracts A, starts relay span B (parent A), injects traceparent B
  ↓ HTTP
server     extracts B, starts span C (parent B)
```

`traceId` is identical in A, B and C. `spanId` differs at every hop. The phone relay appears in the
causal tree as the parent of the server span, which is what actually happens on the wire: the watch
never talks to the server.

This needs no vendor API. Extract, start-span and inject are the standard Trace Context operations,
and a hop that legitimately passes metadata along (`tracestate`) still replaces `traceparent`.

### 3.2 Phone / Desktop → Server (HTTP)

```http
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
tracestate: <optional, pass through>
X-TPlanner-Sync-Operation: 7c0f5c6e-...
```

- The `traceparent` value is always the **sender's own** current span context, produced by §3.1 — for
  a phone-originated sync that is the sync run span, for a relayed watch exchange it is the relay
  span, never the watch's value.
- `traceparent` uses the W3C format verbatim; the sampled flag is `-01` for synchronization runs.
- `X-TPlanner-Sync-Operation` carries `syncOperationId`, so server events still join client events
  when a retry produced a new trace.
- Both headers are ignored by authorization, batch validation and sequence decisions. A server that
  never reads them must keep working unchanged.
- Android has exactly one place to change: `V5Http.request()` in
  `shared/src/main/kotlin/com/hamhuo/tplanner/syncv5/V5Transport.kt`. Desktop: `src/syncV5/transport.js`.

### 3.3 Watch → Phone (relay envelope)

The relay envelope from `WatchV5Protocol.request` gains one optional field:

```json
{
  "protocolVersion": 5,
  "deviceId": "...",
  "requestId": "...",
  "kind": "batch",
  "trace": { "traceparent": "00-<32hex>-<16hex>-01", "syncOperationId": "..." },
  "body": {}
}
```

- `trace` is optional and is not asserted by `validateRequest`. A relay that does not understand it
  ignores it; a watch that gets no `trace` starts a fresh trace at the relay.
- `trace.traceparent` is the watch's own span context. The relay **extracts** it, starts its own relay
  span as a child (§3.1), and injects a **new** `traceparent` into the HTTP request. Raw copying is
  forbidden: it would make the server span a child of the watch span and drop the relay out of the
  trace. The `traceId` — and therefore `syncOperationId` correlation — is preserved end to end.
- One trace covers watch → Data Layer → relay → HTTPS → server → receipt → watch, with one span per
  hop.
- RFCOMM carries the same JSON envelope, so `trace` needs no separate RFCOMM work.
- `requestId` stays the per-exchange transport identity. It is an attribute, never the trace id and
  never the operation id.

## 4. Event envelope

One JSON object per line, one line per state transition, identical field names in Kotlin and
JavaScript.

```json
{
  "schemaVersion": 1,
  "time": "2026-09-17T22:43:29.103Z",
  "level": "WARN",
  "event": "server.sequence.gap",
  "component": "server",

  "syncOperationId": "7c0f5c6e-...",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "spanId": "00f067aa0ba902b7",
  "parentSpanId": "b7ad6b7169203331",
  "attempt": 1,

  "deviceId": "8fd6...",
  "requestId": "3d1c...",
  "commandId": "50ce...",
  "sequence": 17,
  "expectedSequence": 18,

  "serverId": "9e13...",
  "revision": 53,
  "queueDepth": 1,
  "inFlightSequence": 17,

  "transport": "https",
  "result": "failed",
  "errorCode": "SEQUENCE_GAP",
  "durationMs": 2
}
```

`time` is RFC 3339 in UTC with milliseconds and a literal `Z`. Field names are camelCase, matching
the existing V5 protocol JSON.

| Field | Type | Presence | Meaning |
| --- | --- | --- | --- |
| `schemaVersion` | int | required | `1` for this document |
| `time` | string | required | RFC 3339 UTC, millisecond precision |
| `level` | enum | required | `DEBUG` `INFO` `WARN` `ERROR` |
| `event` | string | required | one name from §5, never free text |
| `component` | enum | required | `watch` `phone` `relay` `server` `desktop` |
| `syncOperationId` | string | required when work is durable | business correlation across attempts |
| `traceId` | string (32 hex) | required | W3C trace id |
| `spanId` | string (16 hex) | required | the span that emitted this event; unique per hop (§3.1) |
| `parentSpanId` | string (16 hex) | optional | absent only on a root span |
| `attempt` | int ≥ 1 | required with `syncOperationId` | retry counter of that work item |
| `deviceId` | string (UUID) | see below | V5 device identity |
| `requestId` | string (UUID) | optional | watch ↔ phone exchange identity |
| `commandId` | string | required for command events | V5 command identity |
| `sequence` | int | required for command events | client sequence of that command |
| `expectedSequence` | int | required on a gap | server-side expectation |
| `serverId` | string (UUID) | required for server events | which authority answered |
| `revision` | int | required for receipt/snapshot events | server revision involved |
| `localRevision` | int | optional | client revision before install |
| `queueDepth` | int | optional | unsent commands at that moment |
| `inFlightSequence` | int | optional | the command that may not be dropped |
| `transport` | enum | required for transport events | `datalayer` `rfcomm` `https` |
| `result` | enum | required | see §4.1 |
| `errorCode` | string | required when failed | a V5 code (`SEQUENCE_GAP`, `COMMAND_ID_REUSE`, …) or a transport code |
| `durationMs` | int | required on `*.completed` / `*.failed` | elapsed time of that unit |

`deviceId` presence:

- **required** for every client `sync.*`, `store.*` and `transport.*` event, and for the server's
  `server.batch.*`, `server.sequence.*`, `server.command.*` and `server.receipt.*` events, where the
  batch body already carries it;
- **optional** where the protocol genuinely does not carry a device identity — `server.request.received`
  and `server.snapshot.served`. `GET /tplanner/v5/snapshot` has no `deviceId`, and observability must
  not invent one or widen the HTTP contract to obtain it;
- never guessed, never defaulted, never derived from the bearer token. An unknown field stays absent
  rather than being filled with a placeholder.

### 4.1 `result`

`result` stays required, and it must be exactly one of these eight values:

| `result` | Meaning | Events that use it |
| --- | --- | --- |
| `started` | a unit of work opened | every `*.started` |
| `succeeded` | the transition completed as intended | `*.completed`, `*.accepted`, `*.applied`, `*.installed`, `*.released`, `*.queued`, `*.prepared`, `*.received`, `*.returned`, `*.forwarded`, `*.served`, `*.validated`, `*.committed` |
| `failed` | the transition did not complete | `*.failed`, `server.sequence.gap` |
| `replayed` | an existing result was reused instead of computing a new one | `server.sequence.replay` |
| `conflicted` | the command lost a race and was preserved for the user | `server.command.conflict` |
| `rejected` | the command is permanently unacceptable | `server.command.rejected` |
| `deferred` | the attempt stopped on purpose and stays queued; not a failure | `sync.run.deferred` |
| `retained` | state was deliberately kept unresolved for the next attempt | `store.receipt.retained`, `store.inflight.retained` |

`result` repeats what `event` already encodes; it exists so one query can select every failure, or
every deferral, across scopes without matching event names. A transition that has no accurate value
here means the vocabulary is missing an event, not that a value may be stretched: `deferred` is not
`failed`, and `retained` is not `failed`.

## 5. Event vocabulary

Naming rule: `<scope>.<subject>.<transition>`, lowercase, dot-separated, past tense for a completed
transition and `started` for the opening of a unit of work. Scopes are `sync`, `store`, `transport`,
`relay`, `server`. A new event name is a change to this document, not a local decision.

### 5.1 Sync run — `phone`, `watch`, `desktop`

| Event | Emitted when |
| --- | --- |
| `sync.run.started` | an attempt begins, with `attempt`, `queueDepth`, `inFlightSequence` |
| `sync.run.completed` | §7's full chain finished; the only success signal |
| `sync.run.failed` | the attempt gave up or ended without convergence |
| `sync.run.deferred` | the attempt stopped deliberately (no network, no relay) and stays queued |

### 5.2 Durable store — `phone`, `watch`, `desktop`

| Event | Emitted when |
| --- | --- |
| `store.command.queued` | a local edit became an unsent canonical command |
| `store.batch.prepared` | a batch was built from queued commands (one event per command) |
| `store.receipt.accepted` | a receipt with status `applied` matched the in-flight command |
| `store.receipt.retained` | a receipt came back `conflict`/`rejected`, so the command stays unresolved |
| `store.snapshot.installed` | a snapshot and its revision were durably installed |
| `store.inflight.released` | the in-flight command is finally resolved and may be dropped |
| `store.inflight.retained` | the in-flight command survives this attempt |

### 5.3 Transport — `watch`, `phone`, `desktop`

One exchange emits `transport.request.started`, optional intermediate events, and **exactly one
terminal event**. The boundary between the response events is fixed here, so a broken read can never
be reported as a completed request:

| Event | Boundary |
| --- | --- |
| `transport.request.started` | an exchange begins on one medium (`transport` attribute) |
| `transport.request.completed` | **terminal, success**: the transport API finished one round trip, the response carrier was obtained, and its envelope was read, parsed, validated and matched to this request. `durationMs` is the whole exchange. |
| `transport.response.received` | intermediate: a response carrier for this request has arrived, before it is read or parsed. Distinguishes "bytes are here" from "bytes are understood"; emitted only where arrival is separately observable (Data Layer item, HTTP response head, RFCOMM frame). |
| `transport.response.failed` | **terminal, failure**: a carrier appeared, but reading, decoding or envelope validation/matching failed. The carrier's existence is never upgraded into a completed request. |
| `transport.request.failed` | **terminal, failure**: no carrier was ever obtained — connect, write, read or timeout failed at the transport API. |
| `transport.fallback.started` | Data Layer gave up and RFCOMM begins (a new exchange, with its own started/terminal pair) |

`transport.request.completed` and `transport.response.received` prove transport only. They are never
a synchronization result.

The watch `getFdForAsset` / main-thread incident lands exactly here: the carrier arrived
(`transport.response.received`), the read failed, so the exchange ends in
`transport.response.failed` with `errorCode=RESPONSE_UNREADABLE` — and emits no
`transport.request.completed`, because nothing was understood.

### 5.4 Relay — `relay` (the phone, on behalf of a watch)

| Event | Emitted when |
| --- | --- |
| `relay.request.received` | `WatchV5RelayService` accepted a request envelope |
| `relay.request.forwarded` | the relay extracted the watch context, started its relay span, and handed the body to `V5Http` with its own trace headers |
| `relay.response.returned` | the response asset was written back to the Data Layer |
| `relay.request.failed` | reading the request, the upstream call, or the response write failed |

### 5.5 Server — `server`

| Event | Emitted when |
| --- | --- |
| `server.request.received` | Fastify accepted an authorized V5 request (`deviceId` may be absent) |
| `server.batch.validated` | the batch envelope passed `validateBatchRequest` |
| `server.sequence.accepted` | the batch is contiguous with `nextSequence` |
| `server.sequence.replay` | a `commandId` was recognized and answered from its stored receipt |
| `server.sequence.gap` | `sequenceGap()` rejected the batch (`expectedSequence` present) |
| `server.command.applied` | one command produced a receipt with status `applied` |
| `server.command.conflict` | one command produced status `conflict` |
| `server.command.rejected` | one command produced status `rejected` |
| `server.receipt.committed` | the transaction committed and receipts are permanent |
| `server.snapshot.served` | a snapshot was returned, with `revision` (`deviceId` absent) |

### 5.6 Minimum set

Any implementation that emits only these is already useful, and they are the first milestone:
`sync.run.started`, `sync.run.completed`, `sync.run.failed`, `store.batch.prepared`,
`store.receipt.accepted`, `store.snapshot.installed`, `transport.request.failed`,
`transport.response.failed`, `server.sequence.gap`, `server.receipt.committed`.

## 6. Level and error codes

| Level | Meaning |
| --- | --- |
| `DEBUG` | per-step detail, off in production |
| `INFO` | expected progress; the normal spine of a trace |
| `WARN` | degraded, retried, or deliberately deferred, still converging (`transport.fallback.started`, `server.sequence.replay`, `sync.run.deferred`) |
| `ERROR` | convergence is in doubt or user data is at risk (`server.sequence.gap`, `sync.run.failed`) |

`errorCode` reuses V5's existing codes — `UNAUTHORIZED`, `NOT_FOUND`, `BODY_TOO_LARGE`,
`INVALID_JSON`, `UNSUPPORTED_MEDIA_TYPE`, `INVALID_REQUEST`, `SEQUENCE_GAP`, `COMMAND_ID_REUSE`,
`INTERNAL_ERROR` — and adds transport-local codes in the same style for
`transport.request.failed` and `transport.response.failed` (for example `RELAY_UNAVAILABLE`,
`RESPONSE_UNREADABLE`). A transport code never replaces a protocol code.

## 7. What "synchronized" is allowed to mean

A successful run always converges the local store downwards; it uploads only when this run actually
prepares or resumes an outbound command. The condition is therefore causal — what the run did — and
not a snapshot of the queue taken at its start:

```
any successful run:
  sync.run.started
    transport.request.started
    transport.response.received           → carrier arrived (§5.3)
    transport.request.completed           → envelope read, validated, matched
    store.snapshot.installed              → snapshot durably installed at revision R
  sync.run.completed

if this run emits store.batch.prepared, or began with an unresolved in-flight command:
    store.batch.prepared
    store.receipt.accepted                → receipt status applied, commandId matched
    store.snapshot.installed              → the same install that confirms the receipt
    store.inflight.released               → in-flight resolved, may be dropped
```

"The run started with no in-flight command" is **not** the criterion. A command can be promoted from
the queue to in-flight inside the run (`prepareBatch()` takes the queue head), and an immutable
command can already be in-flight when the run begins (a retry). Both upload, so both require the
receipt and the release. A download-only run has neither, and by definition emits none of those three
events — absent, not missing, not failed. `store.snapshot.installed` alone satisfies store convergence
there.

The invariant, stated once:

> **`sync.run.completed` requires `store.snapshot.installed`. If this run prepares or resumes an
> outbound command, the corresponding `store.receipt.accepted` and `store.inflight.released` must also
> occur before completion.**

| Evidence | Permitted conclusion |
| --- | --- |
| Bluetooth/RFCOMM or Data Layer connected | `transport.request.started`, at most `transport.request.completed` |
| a response carrier arrived, before parsing | `transport.response.received` |
| a receipt came back `applied` | `store.receipt.accepted` |
| a snapshot is durably installed | `store.snapshot.installed` |
| §7's branch for this run is complete | `sync.run.completed` |

Consequences, both mandatory:

- The watch UI may show "synchronized" only after `sync.run.completed`. When the run uploaded or
  resumed a command, both the receipt and the release must be present; for a download-only run the
  installed snapshot is the whole criterion. "Bluetooth connected" and "Data Layer response received"
  are transport facts and must never render as a sync result.
- The phone may report `SyncPhase.SUCCESS` only after the snapshot install and revision confirm, not
  after a successful HTTP call.

## 8. Diagnostics never participate in the transaction

The rule that already governs `SyncLog` becomes the rule for all three repositories:

```
canonical store          diagnostics store
   │                          │
   │ write fails → sync fails │ write fails → event is dropped and counted
```

- A diagnostics write must never fail, block, roll back or delay a command, a receipt, or a
  snapshot install.
- Each client keeps a durable local ring buffer: latest 5000 events or 5 MB, whichever comes first,
  JSON lines, in its own storage namespace — never inside the canonical V5 store.
- Android `SyncLog` becomes the UI projection over that buffer: `source` → `component`,
  `message` → the human sentence, `detail` → a readable rendering of the attributes, `errorCode`
  unchanged, plus expandable raw JSON. `SyncLogPanel` keeps showing sentences; engineers expand them.
- Server: Pino already redacts `req.headers.authorization` and already emits one JSON line per
  record; route events carry the same field names. V1 aggregates through stdout/journald/JSONL. No
  Elasticsearch, Loki or Grafana is required to land this contract.

## 9. Never logged

Task `summary`/`title`, `description`, note text, checklist text, location/geo, `Authorization`, the
sync password, jCal/ICS payloads, AI prompts and AI responses.

Allowed: opaque ids (`deviceId`, `requestId`, `commandId`, `serverId`, `syncOperationId`), counters
(`sequence`, `expectedSequence`, `revision`, `queueDepth`), durations, enum values and error codes.

## 10. Responsibilities and order

```
tplanner-android/   phone diagnostics, watch diagnostics, local trace generator,
                    trace context in V5Http.request() + WatchV5RelayService,
                    `trace` in WatchV5Protocol
tplanner-desktop/   the same event schema and generator, trace context in src/syncV5/transport.js
tplanner-server/    traceparent extraction, Pino bindings, sequence/receipt events
```

Land in this order; each step is independently useful and independently releasable:

1. this document
2. Android phone: **local W3C trace context generator** (a fresh `traceId`/root `spanId` per
   `sync.run.started`, generated locally and not yet propagated) + structured diagnostics buffer +
   the §5.6 minimum set. This step is what makes steps 2–3 schema-valid: every emitted event already
   has `traceId` and `spanId` from §4, even though nothing crosses a process boundary yet.
3. Watch: the same local generator + structured diagnostics buffer + `transport.response.failed`
4. Propagation into the relay envelope: watch injects `trace` into `WatchV5Protocol`, the phone relay
   extracts it, starts its relay span and injects its own `traceparent` per §3.1
5. `traceparent` through HTTP (Android, then Desktop)
6. Server: `traceparent` extraction, then Pino events for sequence, command, receipt and snapshot
7. Desktop: adopt the same schema and generator
8. Reproduce the `SEQUENCE_GAP` and watch-false-success incidents from logs alone

Steps 4–6 add propagation and parenting only; they must not change field names or event names.

Done means: given one `traceId` or one `syncOperationId`, the causal chain of the incident is
readable without screenshots and without device Logcat.

## 11. Non-goals

- No TPlanner-specific trace id or span id format; W3C Trace Context only.
- No collector, storage engine, query language or UI in TPlanner.
- No trace context inside V5 batch or command semantics, and no change to sequence, receipt or
  idempotency rules.
- No metrics or topology contract yet; that arrives with the OTLP/SkyWalking wiring.
- No hard dependency on a specific backend: `traceparent` is the stable interface, so
  SkyWalking can be replaced by any OTLP-compatible platform without touching business code.
