# Sync V5 — jCal canonical packet

Status: implementation contract, 2026-09-16. Applies to `mobile_andorid`, `master`, and
`sync_server`. V3/V4 were prototypes: V5 uses new empty stores and endpoints, without
migration, legacy readers, fallback protocols, or dual writes. No tests or builds are run
as part of this refactor, per the user's instruction.

## One source of task facts

The payload is RFC 7265 jCal with RFC 5545 semantics. A live record contains exactly one
`calendar` array. Task facts are never repeated in transport metadata, SQL columns, Watch
messages, or a second mutable Task model. UI accessors and calendar-provider values are
read-only interpretations of that array. Local draft/queued revisions are versions of the
same document, not another domain representation.

A calendar is `["vcalendar", properties, components]`. A property is
`[lowercaseName, parameters, lowercaseValueType, value, ...]`; parameter names are
lowercase, values retain their case. No JavaScript object pretending to be jCal is accepted.
Each record contains one master `vtodo` or `vjournal`, optional recurrence exceptions of
the same kind and UID, and any required `vtimezone` components. UID belongs only to the
standard component. `dtstamp` is UTC, `version` is `2.0`, and `prodid` identifies TPlanner.

Tasks use VTODO: `uid`, `summary`, `description`, optional `dtstart` and `due`, `status`,
`completed`, `rrule`, `rdate`, `exdate`, `recurrence-id`, `location` and `geo`. Do not use
VEVENT's `dtend` in VTODO. Unscheduled tasks have no invented date. Date-only values stay
date-only. Timed values are UTC with `Z`, or local values with explicit TZID and matching
VTIMEZONE. Floating times and numeric UTC offsets are not emitted. A TZID must be a resolvable
IANA zone name; validators resolve it rather than requiring the VTIMEZONE component to be
present, because TPlanner's own writers emit UTC. Recurrence is a
standard rule plus exceptions, not a set of separately synchronized generated Tasks.
Editors may offer a subset of recurrence rules; unsupported rules must remain intact.

Recurring tasks keep one master component per rule. A single instance's state (for example
"this occurrence is done") is a second component with the same UID and kind carrying
`recurrence-id`, never a separately generated record; a cancelled instance is an `exdate`
on the master. Readers expand occurrences locally and must not persist the expansion.

Notes use VJOURNAL with `dtstart` of type `date` and `description`; the daily note UID is
`journal:YYYY-MM-DD`. They travel through the same record/command pipeline.

Non-independent checklist items are one `x-tplanner-checklist` TEXT property containing
JSON `[{"id":"uuid","text":"...","completed":false}]`; array order is display order.
This is explicitly TEXT, not a made-up jCal JSON value type. A checklist is not a second
independent task. `x-tplanner-list-id` TEXT retains the requested list identifier field,
without restoring custom-list objects or UI. `x-tplanner-color` INTEGER is the optional
TPlanner category index. Standard fields must never be repeated in these extensions.
Unknown standard/extension properties and parameters survive unrelated edits.

## Transport and central authority

V5 is a single central SQLite writer behind the HTTP API. SQLite transactions atomically
commit documents, monotonically increasing global revision, device sequence and permanent
command receipts. A separate broker/materializer is not required for this single-server
product. Use a new `sync-v5.sqlite` database; no reads of V3 data. All endpoints use
`Authorization: Bearer <token>`; the server must require a configured token. HTTPS is
required outside local development. `serverId` is generated and persisted with this store.

`GET /tplanner/v5/snapshot` returns:

```json
{"protocolVersion":5,"serverId":"uuid","revision":12,"records":[
  {"revision":12,"deleted":false,"calendar":["vcalendar",[
    ["version",{},"text","2.0"],["prodid",{},"text","-//TPlanner//Sync V5//EN"]
  ],[["vtodo",[
    ["uid",{},"text","uuid"],["dtstamp",{},"date-time","2026-09-16T12:00:00Z"],
    ["summary",{},"text","完成申论"],["status",{},"text","NEEDS-ACTION"],
    ["dtstart",{},"date-time","2026-09-17T12:00:00Z"],
    ["due",{},"date-time","2026-09-17T13:00:00Z"],
    ["x-tplanner-checklist",{},"text","[{\"id\":\"uuid-child\",\"text\":\"发给老师\",\"completed\":false}]"]
  ],[]]]]},
  {"revision":9,"deleted":true,"uid":"deleted-uuid"}
]}
```

Live record UID is derived from jCal. Tombstones carry only UID because they have no
calendar content. Revision is a nonnegative JSON safe integer; each applied mutation
increments it, including deletion. Records are sorted by UID for stable snapshots.

`POST /tplanner/v5/batch` accepts at most 100 commands:

```json
{"protocolVersion":5,"deviceId":"uuid","commands":[
  {"commandId":"uuid","sequence":1,"operation":"put","baseRevision":0,"calendar":["vcalendar",[],[]]},
  {"commandId":"uuid2","sequence":2,"operation":"delete","baseRevision":7,"uid":"other-uuid"}
]}
```

The empty calendar above is a shape illustration, not a valid application record.
`commandId` and device `sequence` are stable across retries. Device sequences start at 1
and must be contiguous. A new command sits exactly at the device's expected sequence; a
command below that value is a replay and is answered from its stored receipt when
`commandId`, `deviceId`, `sequence` and content fingerprint all match, so retrying an
already-committed batch returns the original receipts without writing anything. Reusing an
identity with different bytes/content is an error. Gaps return HTTP 409 with
`{"code":"SEQUENCE_GAP","expectedSequence":n}` without accepting later commands. Validate
the whole batch's envelope/identity/sequence before any writes. Semantic document
errors consume that command's sequence and return a permanent rejected receipt so the
queue cannot remain blocked behind poison data.

`baseRevision` is an exact per-record compare-and-set guard, 0 only for a never-seen UID.
A stale put/delete returns a conflict, never silently overwrites a newer document.
Recreating a deleted UID requires that tombstone's current revision. A delete of a
nonexistent record with base 0 creates a tombstone, preventing delayed creates reviving it.
Receipts are committed in the same transaction as all accepted mutations:

```json
{"protocolVersion":5,"serverId":"uuid","revision":13,"receipts":[
  {"commandId":"uuid","sequence":1,"status":"applied","revision":13},
  {"commandId":"uuid2","sequence":2,"status":"conflict","revision":11,"code":"REVISION_CONFLICT"}
]}
```

Statuses: `applied`, `conflict`, `rejected`. Rejections also include `code`; their revision
is the existing record revision or 0. HTTP 200 means durable receipts, not that every
command applied. GET snapshot and installed-revision confirmation complete synchronization.
`GET /tplanner/v5/health` exposes protocolVersion/serverId/revision only (no private data).
No V3/V4 endpoints, notification long polling, compressed hash contracts, or delta codecs.
Foreground/manual and bounded periodic refresh fetch the latest full logical snapshot.

## Client durability and conflicts

Use new V5 stores. Persist the unchanged jCal document and outgoing operation before
reporting a local save. Keep one immutable in-flight command and retry it before issuing
a new sequence; unsent edits to one UID may coalesce. A successor local edit after an
in-flight save may advance its base only from that save's own applied receipt. Never
blindly rebase an edit after another device wins. Keep conflicts/rejections and their
local calendar document visible with explicit discard/reapply actions.

Install snapshots atomically. Do not clear an applied pending command until a snapshot
with revision at least its applied receipt revision has been installed. Older or different
serverId snapshots must not overwrite current state silently; a changed server requires
an explicit reset/reconnect choice. Local edits must survive process restart and transport
failure. UI state reads the mirror overlaid with still-pending local documents.

## Watch and platform adapters

Phone/Watch exchange the same V5 batch/receipt/snapshot JSON, without mapping to separate
schedule/task wire models. The Watch has its own durable device identity and sequences.
The phone is a relay: it must not rewrite Watch identities or claim central acceptance
before the server commits. Data Layer and RFCOMM carry the same bytes and use the same
idempotency rules. Watchface positions and list entries are transient reads of jCal.

Every snapshot is the complete live set, so it declares its own scope: absence from a
snapshot means the record is not there, and an actual deletion always arrives as a
tombstone. A client must never accept a partial set as if it were complete — nothing may
filter, cap, truncate or pre-project the records a peer receives. Each device keeps its own
cursor, pending queue and local display state; sharing the same canonical content does not
mean sharing runtime state.

System Calendar is a one-way, retryable side effect after canonical local state is saved.
Android uses a dedicated app-owned local calendar and CalendarContract. Only scheduled
incomplete VTODO records are projected as provider Events; unscheduled Tasks and VJOURNAL
remain in TPlanner. Map `dtstart`/`due` and standard recurrence/reminder semantics at this
boundary; never invent a date to force an export. Completion/deletion removes this app's
provider event. Provider changes are not read back as task edits. Permission denial or
provider failure must not break normal saving, sync, or Watch acknowledgement.

Local provider calendar/event IDs, UID-to-event mapping, projection cursor, retries and
errors stay device-local. Delete/update only rows owned by this adapter. Maintain a
stable app ownership key to repair interrupted inserts without producing duplicates.
Apple/Windows/Linux adapters follow this contract when those native targets exist;
Web/Electron must expose working RFC 5545 ICS export and honest platform capability,
without pretending browser JavaScript can write a system calendar.

## AI and UI

AI extracts a list of independent goal/theme Tasks, their checklists and optional explicit
times. It does not classify event/status/task or make alarm decisions. A missing time
stays missing. Client assigns stable UIDs to a persisted proposal and accepts all its
selected records in one local transaction. Repeated confirmation cannot create duplicates.
Task UI keeps Notes, Inbox, Today (including earlier overdue unfinished tasks) and a date
view. No Custom Lists, type chooser, or custom alarm scheduler is reintroduced.

## References

- RFC 7265: https://www.rfc-editor.org/rfc/rfc7265.html (jCal structure and value encoding)
- RFC 5545: https://www.rfc-editor.org/rfc/rfc5545.html (VTODO, VJOURNAL, time and recurrence)
