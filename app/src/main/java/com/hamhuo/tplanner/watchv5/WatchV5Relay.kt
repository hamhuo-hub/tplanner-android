package com.hamhuo.tplanner.watchv5

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.hamhuo.tplanner.WatchV5Protocol
import com.hamhuo.tplanner.syncv5.V5Http
import com.hamhuo.tplanner.syncv5.V5Settings
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The phone relays the watch's identity unchanged; only the server produces business receipts. */
object WatchV5Relay {
    private val lock = Any()

    fun exchange(context: Context, raw: String): String = synchronized(lock) {
        require(raw.toByteArray(Charsets.UTF_8).size <= WatchV5Protocol.MAX_BYTES)
        val request = JSONObject(raw)
        WatchV5Protocol.validateRequest(request)
        try {
            val settings = V5Settings(context.applicationContext)
            val http = V5Http(settings.url, settings.token)
            val body = when (request.getString("kind")) {
                "snapshot" -> http.snapshot()
                "batch" -> {
                    val batch = request.getJSONObject("body")
                    require(batch.getString("deviceId") == request.getString("deviceId")) { "DEVICE_ID_MISMATCH" }
                    http.send(batch)
                }
                "installed" -> {
                    val installed = request.getJSONObject("body")
                    require(installed.getInt("protocolVersion") == 5)
                    val serverId = installed.getString("serverId")
                    val revision = installed.getLong("revision")
                    require(revision >= 0L)
                    val prefs = context.getSharedPreferences("tplanner_watch_v5_delivery", Context.MODE_PRIVATE)
                    val device = request.getString("deviceId")
                    val previous = prefs.getString(device, null)?.let(::JSONObject)
                    require(previous == null || previous.getString("serverId") == serverId) { "SERVER_CHANGED" }
                    require(prefs.edit().putString(device, JSONObject().put("serverId", serverId)
                        .put("revision", maxOf(revision, previous?.optLong("revision", 0L) ?: 0L)).toString()).commit())
                    JSONObject().put("protocolVersion", 5).put("serverId", serverId).put("revision", revision)
                }
                else -> error("UNKNOWN_RELAY_OPERATION")
            }
            WatchV5Protocol.response(request, body).toString()
        } catch (error: Exception) {
            Log.w("WatchV5Relay", "Relay failed", error)
            JSONObject().put("protocolVersion", 5).put("deviceId", request.getString("deviceId"))
                .put("requestId", request.getString("requestId"))
                .put("error", error.message ?: "RELAY_FAILED").toString()
        }
    }
}

/** Assets keep the complete JSON packet intact even when a snapshot exceeds message limits. */
class WatchV5RelayService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED ||
                !event.dataItem.uri.path.orEmpty().startsWith(WatchV5Protocol.REQUEST_PATH)) continue
            val asset = DataMapItem.fromDataItem(event.dataItem).dataMap.getAsset(WatchV5Protocol.ASSET_KEY) ?: continue
            worker.execute {
                runCatching {
                    val stream = Tasks.await(Wearable.getDataClient(applicationContext).getFdForAsset(asset), 30, TimeUnit.SECONDS).inputStream
                    val raw = stream.use { input ->
                        val bytes = input.readBytes(WatchV5Protocol.MAX_BYTES + 1)
                        require(bytes.size <= WatchV5Protocol.MAX_BYTES)
                        bytes.toString(Charsets.UTF_8)
                    }
                    val request = JSONObject(raw)
                    WatchV5Protocol.validateRequest(request)
                    val response = WatchV5Relay.exchange(applicationContext, raw)
                    val outgoing = PutDataMapRequest.create(WatchV5Protocol.responsePath(request)).apply {
                        dataMap.putAsset(WatchV5Protocol.ASSET_KEY, Asset.createFromBytes(response.toByteArray(Charsets.UTF_8)))
                    }.asPutDataRequest().setUrgent()
                    Tasks.await(Wearable.getDataClient(applicationContext).putDataItem(outgoing), 30, TimeUnit.SECONDS)
                }.onFailure { Log.w("WatchV5Relay", "Data Layer relay failed", it) }
            }
        }
    }

    companion object { private val worker = Executors.newSingleThreadExecutor() }
}
