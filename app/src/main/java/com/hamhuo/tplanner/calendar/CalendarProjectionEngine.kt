package com.hamhuo.tplanner.calendar

import android.content.Context
import com.hamhuo.tplanner.R
import com.hamhuo.tplanner.syncv5.JcalDocument
import com.hamhuo.tplanner.syncv5.V5Store
import java.util.concurrent.locks.ReentrantLock

/** A pass either finished the work it could see, or wants to be run again later. */
internal enum class ProjectionOutcome { SUCCESS, RETRY }

/** One provider operation. Its durable intent is written before it runs, never after. */
private sealed class Planned {
    abstract val uid: String

    class Upsert(override val uid: String, val row: ProjectionRow, val eventId: Long?) : Planned()

    /** [calendarId] is the calendar the row actually lives in, so a stale mapping cannot misfire. */
    class Remove(override val uid: String, val eventId: Long, val calendarId: Long) : Planned()
}

/**
 * Reconciles the system calendar with the canonical documents.
 *
 * The provider write and the durable mapping cannot share a transaction, so the order is always:
 * persist the intent, write the provider row carrying the record UID as its ownership key, then
 * persist the result. A process that dies anywhere in between is repaired by the next pass, which
 * finds its own row by that key and updates it instead of inserting a second one.
 */
internal object CalendarProjectionEngine {

    private val lock = ReentrantLock()

    /** Reads the canonical store, then reconciles. Used by the worker and the coalescing pump. */
    fun runFromCanonicalStore(context: Context): ProjectionOutcome {
        val store = CalendarProjectionStore(context)
        if (!TPlannerCalendarProjection.isEnabled(context)) return pass(context, store, emptyList())
        val documents = try {
            V5Store(context).documents()
        } catch (error: Exception) {
            return fail(store, context.getString(R.string.calendar_projection_failed, describe(error)))
        }
        return pass(context, store, documents)
    }

    /** Full reconciliation from the documents the caller already holds. */
    fun reconcile(context: Context, documents: List<JcalDocument>): ProjectionOutcome =
        pass(context, CalendarProjectionStore(context), documents)

    private fun pass(
        context: Context,
        store: CalendarProjectionStore,
        documents: List<JcalDocument>,
    ): ProjectionOutcome {
        if (!lock.tryLock()) return ProjectionOutcome.SUCCESS // another pass already owns the provider
        return try {
            runPass(context, store, documents)
        } catch (error: Exception) {
            fail(store, context.getString(R.string.calendar_projection_failed, describe(error)))
        } finally {
            lock.unlock()
        }
    }

    private fun fail(store: CalendarProjectionStore, message: String): ProjectionOutcome {
        runCatching {
            val state = store.read()
            store.write(state.copy(paused = false, lastError = message, attempts = state.attempts + 1L))
        }
        return ProjectionOutcome.RETRY
    }

    private fun runPass(
        context: Context,
        store: CalendarProjectionStore,
        documents: List<JcalDocument>,
    ): ProjectionOutcome {
        val state = store.read()
        val enabled = TPlannerCalendarProjection.isEnabled(context)
        val untitled = context.getString(R.string.untitled_event)

        val desired = LinkedHashMap<String, ProjectionRow>()
        var unsupported: String? = null
        if (enabled) {
            documents.forEach { document ->
                when (val decision = project(document, untitled)) {
                    is ProjectionDecision.Project -> desired[document.uid] = decision.row
                    is ProjectionDecision.Unsupported ->
                        unsupported =
                            context.getString(R.string.calendar_projection_unsupported_rule, decision.rule)
                    ProjectionDecision.InsideOnly -> Unit
                }
            }
        }

        if (!enabled && state.mappings.isEmpty() && state.calendarId <= 0L) {
            store.write(
                state.copy(
                    paused = false,
                    lastError = null,
                    attempts = 0L,
                    lastSuccessAt = System.currentTimeMillis(),
                ),
            )
            return ProjectionOutcome.SUCCESS
        }

        if (!TPlannerCalendarProjection.permissionsGranted(context)) {
            store.write(
                state.copy(
                    paused = true,
                    attempts = state.attempts + 1L,
                    lastError = context.getString(R.string.calendar_projection_permission_denied),
                ),
            )
            return ProjectionOutcome.RETRY
        }

        val gateway = CalendarProviderGateway(context)
        // Turning the consumer off removes what it wrote; it never creates a calendar to do that.
        val calendarId = if (enabled) gateway.ensureCalendar(state.calendarId) else state.calendarId
        val calendars = if (enabled) listOf(calendarId)
        else gateway.existingCalendars().ifEmpty { listOf(state.calendarId) }

        val mappings = state.mappings.toMutableMap()
        val plan = mutableListOf<Planned>()
        val ownedByUid = LinkedHashMap<String, OwnedEvent>()

        calendars.filter { it > 0L }.forEach { id ->
            gateway.ownedEvents(id).groupBy { it.row.uid }.forEach { (uid, rows) ->
                val keeper = rows.first()
                if (!ownedByUid.containsKey(uid)) ownedByUid[uid] = keeper
                // Duplicate rows are this app's own interrupted inserts: keep one, drop the rest.
                rows.drop(1).forEach { plan += Planned.Remove(uid, it.eventId, it.calendarId) }
                // Orphan rows have no mapping left to describe them; the ownership key is the intent.
                if ((!enabled || uid !in desired) && state.mappings[uid] == null) {
                    plan += Planned.Remove(uid, keeper.eventId, keeper.calendarId)
                }
            }
        }

        if (enabled) {
            desired.forEach { (uid, row) ->
                val event = ownedByUid[uid]
                val mapping = mappings[uid]
                val applied = mapping?.takeIf { it.calendarId == calendarId }?.appliedRevision
                if (event != null && mapping != null && event.row.matches(row) && applied == row.revision) {
                    if (mapping.eventId != event.eventId) {
                        // The provider row is right but was never recorded: adopt it, do not insert.
                        mappings[uid] = ProjectionMapping(uid, calendarId, event.eventId, row.revision, null)
                    }
                } else {
                    plan += Planned.Upsert(uid, row, event?.eventId)
                    mappings[uid] = ProjectionMapping(
                        uid = uid,
                        calendarId = calendarId,
                        eventId = event?.eventId ?: mapping?.eventId ?: 0L,
                        appliedRevision = mapping?.appliedRevision.orEmpty(),
                        pendingAction = CalendarProjectionStore.ACTION_UPSERT,
                    )
                }
            }
        }

        // Records that left the canonical set (completed, deleted, unscheduled, note) lose their row.
        mappings.keys.toList().forEach { uid ->
            if (enabled && uid in desired) return@forEach
            val mapping = mappings.getValue(uid)
            val owned = ownedByUid[uid]
            plan += Planned.Remove(
                uid = uid,
                eventId = owned?.eventId ?: mapping.eventId,
                calendarId = owned?.calendarId ?: mapping.calendarId,
            )
            mappings[uid] = mapping.copy(pendingAction = CalendarProjectionStore.ACTION_DELETE)
        }

        // Phase one: the intent is durable before the provider is touched.
        if (plan.isNotEmpty()) {
            store.write(state.copy(calendarId = calendarId, mappings = mappings.toMap()))
        }

        var failure: String? = null
        plan.forEach { action ->
            try {
                when (action) {
                    is Planned.Upsert -> {
                        val existingId = action.eventId
                        val eventId =
                            if (existingId != null && gateway.updateEvent(existingId, action.row, calendarId)) existingId
                            else gateway.insertEvent(action.row, calendarId)
                        mappings[action.uid] = ProjectionMapping(
                            uid = action.uid,
                            calendarId = calendarId,
                            eventId = eventId,
                            appliedRevision = action.row.revision,
                            pendingAction = null,
                        )
                    }
                    is Planned.Remove -> {
                        gateway.deleteEvent(action.eventId, action.calendarId, action.uid)
                        mappings.remove(action.uid)
                    }
                }
            } catch (error: Exception) {
                // The intent stays queued for the next pass; one bad row never blocks the others.
                failure = describe(error)
            }
        }

        val error = failure ?: unsupported
        store.write(
            state.copy(
                calendarId = calendarId,
                mappings = mappings.toMap(),
                paused = false,
                lastError = error,
                attempts = if (failure == null) 0L else state.attempts + 1L,
                lastSuccessAt = if (failure == null) System.currentTimeMillis() else state.lastSuccessAt,
            ),
        )
        return if (failure == null) ProjectionOutcome.SUCCESS else ProjectionOutcome.RETRY
    }

    private fun describe(error: Exception): String =
        error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
}
