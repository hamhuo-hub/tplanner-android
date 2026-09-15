package com.hamhuo.tplanner.calendar

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The durable, device-local record of what this app put into the system calendar.
 *
 * Keyed by canonical UID, it holds the provider calendar/event identity, the exact content revision
 * that was written, and the action that was in flight when the process stopped. It never holds task
 * facts, and it never leaves the phone.
 */
internal data class ProjectionMapping(
    val uid: String,
    val calendarId: Long,
    val eventId: Long,
    val appliedRevision: String,
    val pendingAction: String?,
)

/** One pass reads this, queues its intents into it, and writes back the result. */
internal data class CalendarProjectionState(
    val calendarId: Long = 0L,
    val mappings: Map<String, ProjectionMapping> = emptyMap(),
    val lastError: String? = null,
    val paused: Boolean = false,
    val attempts: Long = 0L,
    val lastSuccessAt: Long = 0L,
)

/** Small, synchronous and durable: the projection queue must survive the process that queued it. */
internal class CalendarProjectionStore(context: Context) {

    companion object {
        const val ACTION_UPSERT = "upsert"
        const val ACTION_DELETE = "delete"
        private const val NAMESPACE = "tplanner_calendar_projection"
        private const val KEY_STATE = "state"
        private val locks = ConcurrentHashMap<String, Any>()
    }

    private val prefs = context.applicationContext.getSharedPreferences(NAMESPACE, Context.MODE_PRIVATE)
    private val lock = locks.getOrPut(NAMESPACE) { Any() }

    fun read(): CalendarProjectionState = synchronized(lock) { decode(prefs.getString(KEY_STATE, null)) }

    fun write(state: CalendarProjectionState) = synchronized(lock) {
        check(prefs.edit().putString(KEY_STATE, encode(state).toString()).commit()) {
            "无法保存系统日历投影状态"
        }
    }

    fun lastError(): String? = read().lastError

    fun paused(): Boolean = read().paused

    private fun encode(state: CalendarProjectionState): JSONObject = JSONObject().apply {
        put("version", 1)
        put("calendarId", state.calendarId)
        put("attempts", state.attempts)
        put("lastSuccessAt", state.lastSuccessAt)
        put("paused", state.paused)
        state.lastError?.let { put("lastError", it) }
        put("mappings", JSONObject().apply {
            state.mappings.forEach { (uid, mapping) ->
                put(uid, JSONObject().apply {
                    put("calendarId", mapping.calendarId)
                    put("eventId", mapping.eventId)
                    put("appliedRevision", mapping.appliedRevision)
                    mapping.pendingAction?.let { put("pendingAction", it) }
                })
            }
        })
    }

    private fun decode(raw: String?): CalendarProjectionState {
        val state = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return CalendarProjectionState()
        val mappings = LinkedHashMap<String, ProjectionMapping>()
        state.optJSONObject("mappings")?.let { stored ->
            stored.keys().forEach { uid ->
                val row = stored.optJSONObject(uid) ?: return@forEach
                mappings[uid] = ProjectionMapping(
                    uid = uid,
                    calendarId = row.optLong("calendarId", 0L),
                    eventId = row.optLong("eventId", 0L),
                    appliedRevision = row.optString("appliedRevision"),
                    pendingAction = row.optString("pendingAction").takeIf { it.isNotBlank() },
                )
            }
        }
        return CalendarProjectionState(
            calendarId = state.optLong("calendarId", 0L),
            mappings = mappings,
            lastError = state.optString("lastError").takeIf { it.isNotBlank() },
            paused = state.optBoolean("paused", false),
            attempts = state.optLong("attempts", 0L),
            lastSuccessAt = state.optLong("lastSuccessAt", 0L),
        )
    }
}
