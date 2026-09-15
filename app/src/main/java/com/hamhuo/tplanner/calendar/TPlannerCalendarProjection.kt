package com.hamhuo.tplanner.calendar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import com.hamhuo.tplanner.syncv5.JcalDocument
import com.hamhuo.tplanner.syncv5.V5Settings

/**
 * One-way projection of canonical V5 jCal documents into a dedicated app-owned system calendar:
 * TPlanner only ever writes provider rows that carry its own ownership key, never reads provider
 * state back as a task fact, never turns it into a server command, watch message or task edit, and
 * never lets a projection failure block saving, server sync or watch acknowledgement.
 *
 * The system calendar is a retryable side effect of canonical local state, not a participant in
 * synchronization. Each [reconcile] derives the whole desired provider state from the documents it
 * is given, compares it with the rows this app owns, and repairs the difference; a pass that cannot
 * run (missing permission, absent or broken provider) leaves its work queued in device-local
 * storage and is replayed by a background worker.
 *
 * Typical wiring from the data core and the UI:
 * ```
 * // after local state is durably saved
 * TPlannerCalendarProjection.onDocumentsChanged(context)
 * // when the user turns the consumer on, then request the runtime permission from an Activity
 * TPlannerCalendarProjection.setEnabled(context, true)
 * TPlannerCalendarProjection.requestPermissions(activity)
 * // whenever a full reconciliation is wanted, e.g. after installing a server snapshot
 * TPlannerCalendarProjection.reconcile(context, store.documents())
 * ```
 */
object TPlannerCalendarProjection {

    /** Request code for [requestPermissions]; callers only have to pass it to their own callback. */
    const val PERMISSION_REQUEST_CODE = 0x7CA1

    private val PERMISSIONS = arrayOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    )

    /** True while the user wants the system calendar kept in step with the canonical documents. */
    fun isEnabled(context: Context): Boolean = V5Settings(context.applicationContext).calendarEnabled

    /**
     * Turns the consumer on or off. Enabling records the intent and queues work; the matching
     * runtime permission is requested separately by [requestPermissions] because only an Activity
     * can show that prompt. Disabling queues removal of every row this app owns and leaves the rest
     * of the app untouched.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        V5Settings(app).calendarEnabled = enabled
        CalendarProjectionScheduler.enqueue(app)
        CalendarProjectionPump.request(app)
    }

    /**
     * Called by the canonical store after local state is durably saved. It only hands work to the
     * durable queue and an off-thread pass, so it never blocks the caller and never throws.
     */
    fun onDocumentsChanged(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        CalendarProjectionScheduler.enqueue(app)
        CalendarProjectionPump.request(app)
    }

    /**
     * Full reconciliation from the current canonical documents. Callers pass the same
     * [JcalDocument] list they persist, so no second task model ever crosses this boundary.
     */
    fun reconcile(context: Context, documents: List<JcalDocument>) {
        runCatching { CalendarProjectionEngine.reconcile(context.applicationContext, documents) }
    }

    /** Reading the provider for the idempotency lookup and writing the projection are both required. */
    fun permissionsGranted(context: Context): Boolean {
        val app = context.applicationContext
        return PERMISSIONS.all { app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    /** The runtime permissions to request when the user turns the consumer on. */
    fun requiredPermissions(): Array<String> = PERMISSIONS.copyOf()

    /** Requests [requiredPermissions] from an Activity the user is currently looking at. */
    fun requestPermissions(activity: Activity) {
        activity.requestPermissions(PERMISSIONS, PERMISSION_REQUEST_CODE)
    }

    /** True while projection is paused because the calendar permission is denied or revoked. */
    fun isPaused(context: Context): Boolean = CalendarProjectionStore(context.applicationContext).paused()

    /** Last device-local projection problem, or null when the last pass was clean. */
    fun lastError(context: Context): String? = CalendarProjectionStore(context.applicationContext).lastError()
}
