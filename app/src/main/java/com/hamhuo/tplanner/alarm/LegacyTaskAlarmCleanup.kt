package com.hamhuo.tplanner

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Cancels alarms left by older installations; this app no longer schedules or delivers alarms. */
internal object LegacyTaskAlarmCleanup {
    private const val PREFS = "tplanner_task_alarms"
    private const val KEY_SCHEDULED_IDS = "scheduled_ids"
    private const val KEY_DELIVERED_PREFIX = "delivered_"
    private const val CHANNEL_ID = "task_alarms_v1"

    fun cancel(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val eventIds = prefs.getStringSet(KEY_SCHEDULED_IDS, emptySet()).orEmpty() +
            prefs.all.keys.filter { it.startsWith(KEY_DELIVERED_PREFIX) }
                .map { it.removePrefix(KEY_DELIVERED_PREFIX) }
        val alarmManager = appContext.getSystemService(AlarmManager::class.java)
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)

        eventIds.forEach { eventId ->
            // Keep the legacy PendingIntent identity without retaining its deleted receiver.
            val intent = Intent().apply {
                setClassName(appContext.packageName, "com.hamhuo.tplanner.TaskAlarmReceiver")
                action = "com.hamhuo.tplanner.action.FIRE_TASK_ALARM"
                data = Uri.Builder().scheme("tplanner").authority("alarm").appendPath(eventId).build()
            }
            PendingIntent.getBroadcast(
                appContext,
                0,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { pending ->
                alarmManager?.cancel(pending)
                pending.cancel()
            }
            notificationManager?.cancel(eventId.hashCode())
        }
        // Removing the retired channel also clears notifications whose delivery marker was lost.
        notificationManager?.deleteNotificationChannel(CHANNEL_ID)
        prefs.edit().clear().apply()
    }
}
