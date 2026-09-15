package com.hamhuo.tplanner

import android.content.Context
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import com.hamhuo.tplanner.syncv3.SyncV3ProjectionCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Builds the same durable phone snapshot for live GMS and RFCOMM refresh requests. */
internal object WatchScheduleSnapshotProvider {
    private data class InstalledProjection(
        val events: List<ScheduleItem>,
        val version: Long,
        val brokerToSequence: Long,
    )

    fun queueCurrent(context: Context): String? {
        val appContext = context.applicationContext
        val installed = runBlocking(Dispatchers.IO) {
            val database = TPlannerDatabase.get(appContext)
            database.runInTransaction<InstalledProjection?> {
                val meta = database.syncV3Dao().getSyncState() ?: return@runInTransaction null
                if (meta.installedSnapshotVersion <= 0L) return@runInTransaction null
                val mirror = meta.serverMirrorJson?.let(::JSONObject) ?: return@runInTransaction null
                // Read authoritative content and its provenance together, using the same codec
                // as V4 delta installation. Phone optimistic rows cannot become a Watch baseline.
                InstalledProjection(
                    events = SyncV3ProjectionCodec.project(mirror).events,
                    version = meta.installedSnapshotVersion,
                    brokerToSequence = meta.installedBrokerToSequence,
                )
            }
        } ?: return null
        return WatchScheduleSync.push(
            appContext,
            installed.events,
            installed.version,
            installed.brokerToSequence,
        )
    }
}
