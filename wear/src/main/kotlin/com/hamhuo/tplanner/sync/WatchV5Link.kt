package com.hamhuo.tplanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Watch half of the V5 relay.
 *
 * Data Layer and RFCOMM both carry [WatchV5Protocol] framing around the shared V5 JSON, so this
 * object never sees a schedule/task wire model. The watch's own device identity travels in the
 * request envelope; the phone relays it unchanged and only the server produces business receipts.
 * A successful send proves nothing on its own: callers must still apply the returned receipt and
 * install the snapshot that confirms it.
 */
internal object WatchV5Link {
    private const val TAG = "TplannerV5Link"
    private const val IO_TIMEOUT_SECONDS = 10L
    private const val RESPONSE_TIMEOUT_SECONDS = 25L
    private const val RFCOMM_CONNECT_TIMEOUT_MS = 10_000L
    private const val RFCOMM_WRITE_TIMEOUT_MS = 5_000L
    private const val RFCOMM_RESPONSE_TIMEOUT_MS = 20_000L

    private val watchdog = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "tplanner-v5-link-watchdog").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun snapshot(context: Context, deviceId: String): JSONObject =
        exchange(context, deviceId, "snapshot", null)

    fun batch(context: Context, deviceId: String, batch: JSONObject): JSONObject =
        exchange(context, deviceId, "batch", batch)

    /** The third fact: the watch durably installed a snapshot at this revision. */
    fun reportInstalled(context: Context, deviceId: String, serverId: String, revision: Long): JSONObject =
        exchange(context, deviceId, "installed", JSONObject()
            .put("protocolVersion", 5).put("serverId", serverId).put("revision", revision))

    private fun exchange(context: Context, deviceId: String, kind: String, body: JSONObject?): JSONObject {
        val request = WatchV5Protocol.request(deviceId, kind, body)
        val response = viaDataLayer(context, request) ?: viaBluetooth(context, request)
            ?: error("无法连接手机，修改保留在本机")
        return WatchV5Protocol.responseBody(request, response)
    }

    /**
     * The relay answers on its own Data Layer path. The response is an asset so a complete
     * snapshot is never truncated by a message size limit.
     */
    private fun viaDataLayer(context: Context, request: JSONObject): JSONObject? {
        val client = runCatching { Wearable.getDataClient(context) }.getOrNull() ?: return null
        val responsePath = WatchV5Protocol.responsePath(request)
        val received = AtomicReference<JSONObject?>()
        val latch = CountDownLatch(1)
        val listener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                if (event.dataItem.uri.path != responsePath) continue
                val body = runCatching { readResponse(client, event) }
                    .onFailure { Log.w(TAG, "Unreadable relay response", it) }
                    .getOrNull() ?: continue
                if (received.compareAndSet(null, body)) latch.countDown()
            }
        }
        return try {
            Tasks.await(
                client.addListener(
                    listener,
                    Uri.parse("wear://*$responsePath"),
                    DataClient.FILTER_LITERAL,
                ),
                IO_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            val put = PutDataMapRequest.create(WatchV5Protocol.requestPath(request)).apply {
                dataMap.putAsset(WatchV5Protocol.ASSET_KEY, Asset.createFromBytes(request.toString().toByteArray(Charsets.UTF_8)))
            }.asPutDataRequest().setUrgent()
            Tasks.await(client.putDataItem(put), IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (latch.await(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) received.get() else null
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Data Layer relay unavailable", error)
            null
        } finally {
            runCatching { Tasks.await(client.removeListener(listener), IO_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            removeRelayItems(client, request)
        }
    }

    /** Both ends of one exchange are per-request scratch space on the Data Layer. */
    private fun removeRelayItems(client: DataClient, request: JSONObject) {
        listOf(WatchV5Protocol.requestPath(request), WatchV5Protocol.responsePath(request)).forEach { path ->
            runCatching {
                Tasks.await(client.deleteDataItems(Uri.parse("wear://*$path")), IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    private fun readResponse(client: DataClient, event: com.google.android.gms.wearable.DataEvent): JSONObject? {
        val asset = DataMapItem.fromDataItem(event.dataItem).dataMap.getAsset(WatchV5Protocol.ASSET_KEY) ?: return null
        val stream = Tasks.await(client.getFdForAsset(asset), IO_TIMEOUT_SECONDS, TimeUnit.SECONDS).inputStream
        val bytes = stream.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                require(buffer.size() + read <= WatchV5Protocol.MAX_BYTES) { "V5_PACKET_TOO_LARGE" }
                buffer.write(chunk, 0, read)
            }
            buffer.toByteArray()
        }
        require(bytes.isNotEmpty()) { "V5_PACKET_EMPTY" }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }

    /** Paired-phone RFCOMM fallback for watches without a working Data Layer. */
    @SuppressLint("MissingPermission")
    private fun viaBluetooth(context: Context, request: JSONObject): JSONObject? {
        val phone = pairedPhone(context) ?: return null
        var socket: BluetoothSocket? = null
        return try {
            val connected = phone.createRfcommSocketToServiceRecord(WatchV5Protocol.RFCOMM_UUID)
            socket = connected
            withSocketWatchdog(connected, RFCOMM_CONNECT_TIMEOUT_MS, "connect") { connected.connect() }
            withSocketWatchdog(connected, RFCOMM_WRITE_TIMEOUT_MS, "request write") {
                WatchV5Protocol.write(connected.outputStream, request.toString())
            }
            JSONObject(withSocketWatchdog(connected, RFCOMM_RESPONSE_TIMEOUT_MS, "response") {
                WatchV5Protocol.read(connected.inputStream)
            })
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Bluetooth relay failed", error)
            null
        } finally {
            runCatching { socket?.close() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun pairedPhone(context: Context): android.bluetooth.BluetoothDevice? {
        if (!hasBluetoothPermission(context)) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return runCatching {
            if (!adapter.isEnabled) return@runCatching null
            adapter.bondedDevices?.firstOrNull { device ->
                device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE
            }
        }.getOrNull()
    }

    internal fun hasBluetoothPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun <T> withSocketWatchdog(
        socket: BluetoothSocket,
        timeoutMs: Long,
        operation: String,
        block: () -> T,
    ): T {
        val expired = AtomicBoolean(false)
        val timeout = watchdog.schedule(
            {
                expired.set(true)
                runCatching { socket.close() }
            },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )
        return try {
            block()
        } catch (error: Exception) {
            if (expired.get()) {
                throw SocketTimeoutException("RFCOMM $operation timed out").apply { initCause(error) }
            }
            throw error
        } finally {
            timeout.cancel(false)
        }
    }
}
