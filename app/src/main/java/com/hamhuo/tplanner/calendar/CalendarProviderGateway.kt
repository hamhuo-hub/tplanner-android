package com.hamhuo.tplanner.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import java.io.IOException
import java.util.TimeZone

/** One provider row this app owns, read back only for idempotency and drift repair. */
internal class OwnedEvent(val eventId: Long, val calendarId: Long, val row: ProjectionRow)

/**
 * The only place this package touches `CalendarContract`.
 *
 * Every write goes through the sync-adapter URI so the provider accepts the ownership columns, and
 * every update/delete repeats the ownership predicate, so no statement here can reach a row that
 * does not carry this app's key inside this app's own calendar.
 */
internal class CalendarProviderGateway(private val context: Context) {

    companion object {
        private const val ACCOUNT_NAME = "TPlanner"
        private const val CALENDAR_NAME = "TPlanner"
        private val CALENDAR_COLOR = 0xFF00897B.toInt()

        private val CALENDAR_SELECTION =
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.CALENDAR_DISPLAY_NAME}=?"

        private val CALENDAR_ARGS =
            arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL, CALENDAR_NAME)

        private val EVENT_PROJECTION = arrayOf(
            Events._ID,
            Events._SYNC_ID,
            Events.SYNC_DATA1,
            Events.TITLE,
            Events.DESCRIPTION,
            Events.DTSTART,
            Events.DTEND,
            Events.DURATION,
            Events.RRULE,
            Events.ALL_DAY,
            Events.STATUS,
        )
    }

    private val resolver get() = context.contentResolver

    /**
     * Idempotent calendar lookup: find the app-owned local calendar by name and account, keep one,
     * repair duplicates an interrupted run may have created, and only create one when none exists.
     */
    fun ensureCalendar(storedCalendarId: Long): Long {
        val ids = calendarIds()
        val canonical = ids.firstOrNull { it == storedCalendarId } ?: ids.firstOrNull()
        if (canonical != null) {
            ids.filter { it != canonical }.forEach(::deleteCalendar)
            return canonical
        }
        return insertCalendar()
    }

    /** App-owned calendars that currently exist; never creates one. */
    fun existingCalendars(): List<Long> = calendarIds()

    /** Every row of [calendarId] that carries this app's ownership key, with its current content. */
    fun ownedEvents(calendarId: Long): List<OwnedEvent> {
        if (calendarId <= 0L) return emptyList()
        val owned = mutableListOf<OwnedEvent>()
        val selection = "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0"
        resolver.query(
            syncAdapter(Events.CONTENT_URI),
            EVENT_PROJECTION,
            selection,
            arrayOf(calendarId.toString()),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val uid = ownershipUid(
                    syncId = cursor.getString(1),
                    syncData1 = cursor.getString(2),
                ) ?: continue
                val eventId = cursor.getLong(0)
                if (eventId <= 0L) continue
                owned += OwnedEvent(
                    eventId = eventId,
                    calendarId = calendarId,
                    row = providerRow(
                        uid = uid,
                        title = cursor.getString(3),
                        description = cursor.getString(4),
                        allDay = cursor.getInt(9) != 0,
                        startMillis = cursor.getLong(5),
                        endMillis = if (cursor.isNull(6)) null else cursor.getLong(6),
                        durationText = if (cursor.isNull(7)) null else cursor.getString(7),
                        rrule = if (cursor.isNull(8)) null else cursor.getString(8),
                        status = if (cursor.isNull(10)) Events.STATUS_CONFIRMED else cursor.getInt(10),
                    ),
                )
            }
        }
        return owned
    }

    fun insertEvent(row: ProjectionRow, calendarId: Long): Long {
        val uri = resolver.insert(syncAdapter(Events.CONTENT_URI), row.values(calendarId))
            ?: throw IOException("系统日历拒绝了写入")
        return ContentUris.parseId(uri)
    }

    /** True when exactly the owned row was updated; false means the row is gone and must be inserted. */
    fun updateEvent(eventId: Long, row: ProjectionRow, calendarId: Long): Boolean {
        if (eventId <= 0L) return false
        val updated = resolver.update(
            syncAdapter(Events.CONTENT_URI),
            row.values(calendarId),
            ownershipSelection(),
            ownershipArgs(eventId, calendarId, row.uid),
        )
        return updated > 0
    }

    /** Deletes only the owned row; a stale id or a foreign row deletes nothing. */
    fun deleteEvent(eventId: Long, calendarId: Long, uid: String) {
        if (eventId <= 0L || calendarId <= 0L) return
        resolver.delete(
            syncAdapter(Events.CONTENT_URI),
            ownershipSelection(),
            ownershipArgs(eventId, calendarId, uid),
        )
    }

    private fun ownershipSelection(): String =
        "${Events._ID}=? AND ${Events.CALENDAR_ID}=? AND ${Events._SYNC_ID}=?"

    private fun ownershipArgs(eventId: Long, calendarId: Long, uid: String): Array<String> =
        arrayOf(eventId.toString(), calendarId.toString(), uid)

    private fun calendarIds(): List<Long> {
        val ids = mutableListOf<Long>()
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID),
            CALENDAR_SELECTION,
            CALENDAR_ARGS,
            "${Calendars._ID} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getLong(0)
        }
        return ids
    }

    private fun insertCalendar(): Long {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, CALENDAR_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_NAME)
            put(Calendars.CALENDAR_COLOR, CALENDAR_COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        val uri = resolver.insert(syncAdapter(Calendars.CONTENT_URI), values)
            ?: throw IOException("系统日历不可用")
        return ContentUris.parseId(uri)
    }

    /** Deleting a duplicate calendar also removes the rows it holds; they are re-projected after. */
    private fun deleteCalendar(calendarId: Long) {
        resolver.delete(
            syncAdapter(Calendars.CONTENT_URI),
            "${Calendars._ID}=?",
            arrayOf(calendarId.toString()),
        )
    }

    /**
     * A local account type has no account service, so the provider only accepts this app's calendar
     * writes when the caller identifies itself as the sync adapter for that account.
     */
    private fun syncAdapter(base: Uri): Uri = base.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()
}
