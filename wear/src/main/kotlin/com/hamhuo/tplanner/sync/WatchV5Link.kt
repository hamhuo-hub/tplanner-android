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
import com.hamhuo.tplanner.diagnostics.DiagnosticsErrorCode
import com.hamhuo.tplanner.diagnostics.DiagnosticsRun
import com.hamhuo.tplanner.diagnostics.DiagnosticsTransport
import com.hamhuo.tplanner.syncv5.SyncRejectedException
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
 *
 * Each medium is one diagnostic exchange (§5.3): the Data Layer attempt and the RFCOMM fallback
 * report separately, so "the phone never answered" and "the phone answered but the watch could not
 * read it" stay distinguishable. Diagnostics never change the fallback order or the result.
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

    /**
     * Data Layer callbacks are delivered on the main thread, so every blocking GMS read has to
     * happen here instead. One thread is enough: an exchange has one outstanding response.
     */
    private val dataLayerIo = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tplanner-v5-datalayer-io").apply { isDaemon = true }
    }

    fun snapshot(context: Context, deviceId: String, run: DiagnosticsRun? = null): JSONObject =
        exchange(context, deviceId, "snapshot", null, run)

    fun batch(context: Context, deviceId: String, batch: JSONObject, run: DiagnosticsRun? = null): JSONObject =
        exchange(context, deviceId, "batch", batch, run)

    /** The third fact: the watch durably installed a snapshot at this revision. */
    fun reportInstalled(
        context: Context,
        deviceId: String,
        serverId: String,
        revision: Long,
        run: DiagnosticsRun? = null,
    ): JSONObject =
        exchange(context, deviceId, "installed", JSONObject()
            .put("protocolVersion", 5).put("serverId", serverId).put("revision", revision), run)

    /** One medium's answer: the parsed frame, plus the exchange that still owes a terminal event. */
    private class Relay(val frame: JSONObject?, val exchange: DiagnosticsRun.Exchange?)

    private fun exchange(
        context: Context,
        deviceId: String,
        kind: String,
        body: JSONObject?,
        run: DiagnosticsRun?,
    ): JSONObject {
        val request = WatchV5Protocol.request(deviceId, kind, body)
        val dataLayer = viaDataLayer(context, request, run)
        val relay = if (dataLayer.frame != null) {
            dataLayer
        } else {
            // The Data Layer gave up and RFCOMM begins. The fallback order itself is unchanged.
            run?.fallbackStarted(DiagnosticsTransport.RFCOMM)
            viaBluetooth(context, request, run)
        }
        val frame = relay.frame ?: error("无法连接手机，修改保留在本机")
        // An exchange completes only once the envelope is read, parsed AND matched to this request.
        // Bytes carrying someone else's answer are an unusable response, not a completed request.
        if (!WatchV5Protocol.matches(request, frame)) {
            relay.exchange?.responseFailed(DiagnosticsErrorCode.RESPONSE_UNREADABLE)
            error("中继响应与请求不匹配")
        }
        relay.exchange?.completed()
        // The relay reports the server's rejection as a structured code; carrying it typed keeps
        // recovery (for example a new device identity after SEQUENCE_GAP) off message parsing.
        frame.optString("errorCode").takeIf(String::isNotBlank)?.let { code ->
            throw SyncRejectedException(code, status = 0)
        }
        return WatchV5Protocol.responseBody(request, frame)
    }

    /**
     * The relay answers on its own Data Layer path. The response is an asset so a complete
     * snapshot is never truncated by a message size limit.
     *
     * The exchange stays open to two different endings on purpose: a carrier that arrived and could
     * not be read (`response.failed`) is a different fault from a phone that never answered
     * (`request.failed`), and only this function can tell them apart.
     */
    private fun viaDataLayer(context: Context, request: JSONObject, run: DiagnosticsRun?): Relay {
        val exchange = run?.exchange(DiagnosticsTransport.DATALAYER)
        exchange?.started()
        val client = runCatching { Wearable.getDataClient(context) }.getOrNull()
        if (client == null) {
            exchange?.failed(DiagnosticsErrorCode.RELAY_UNAVAILABLE)
            return Relay(null, exchange)
        }
        val responsePath = WatchV5Protocol.responsePath(request)
        val received = AtomicReference<JSONObject?>()
        // Set on the callback thread, read after the wait: the response item exists, whatever
        // reading its asset then does.
        val carrierArrived = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val listener = DataClient.OnDataChangedListener { events: DataEventBuffer ->
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                if (event.dataItem.uri.path != responsePath) continue
                // The item on the response path IS the carrier. It is recorded before the asset is
                // even looked at, so a missing or unreadable packet stays "answered, but unusable"
                // instead of degrading into "the phone never answered".
                carrierArrived.set(true)
                val asset = DataMapItem.fromDataItem(event.dataItem)
                    .dataMap
                    .getAsset(WatchV5Protocol.ASSET_KEY) ?: continue
                dataLayerIo.execute {
                    runCatching { readResponse(client, asset) }
                        .onSuccess { body -> if (received.compareAndSet(null, body)) latch.countDown() }
                        .onFailure { error ->
                            if (error is InterruptedException) Thread.currentThread().interrupt()
                            Log.w(TAG, "Unreadable relay response", error)
                            latch.countDown()
                        }
                }
            }
        }
        val body = try {
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
            exchange?.failed(DiagnosticsErrorCode.RELAY_UNAVAILABLE)
            null
        } finally {
            runCatching { Tasks.await(client.removeListener(listener), IO_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            removeRelayItems(client, request)
        }
        return if (body != null) {
            // Bytes arrived and parsed. The terminal event is not sent here: only the caller can
            // tell whether the envelope matches this request (§5.3).
            exchange?.responseReceived()
            Relay(body, exchange)
        } else {
            if (carrierArrived.get()) {
                // The item was there; reading it failed. This is the case the earlier main-thread
                // getFdForAsset incident produced, and it must never look like "no answer".
                exchange?.responseReceived()
                exchange?.responseFailed(DiagnosticsErrorCode.RESPONSE_UNREADABLE)
            } else {
                exchange?.failed(DiagnosticsErrorCode.RELAY_UNAVAILABLE)
            }
            Relay(null, exchange)
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

    /** Blocking GMS read; the asset was already extracted on the callback thread. */
    private fun readResponse(client: DataClient, asset: Asset): JSONObject {
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
    private fun viaBluetooth(context: Context, request: JSONObject, run: DiagnosticsRun?): Relay {
        val exchange = run?.exchange(DiagnosticsTransport.RFCOMM)
        exchange?.started()
        val phone = pairedPhone(context)
        if (phone == null) {
            exchange?.failed(DiagnosticsErrorCode.RELAY_UNAVAILABLE)
            return Relay(null, exchange)
        }
        var socket: BluetoothSocket? = null
        var frameArrived = false
        return try {
            val connected = phone.createRfcommSocketToServiceRecord(WatchV5Protocol.RFCOMM_UUID)
            socket = connected
            withSocketWatchdog(connected, RFCOMM_CONNECT_TIMEOUT_MS, "connect") { connected.connect() }
            withSocketWatchdog(connected, RFCOMM_WRITE_TIMEOUT_MS, "request write") {
                WatchV5Protocol.write(connected.outputStream, request.toString())
            }
            val frame = withSocketWatchdog(connected, RFCOMM_RESPONSE_TIMEOUT_MS, "response") {
                WatchV5Protocol.read(connected.inputStream)
            }
            // A complete frame was read: on this medium the carrier and its bytes arrive together.
            frameArrived = true
            exchange?.responseReceived()
            Relay(JSONObject(frame), exchange)
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            Log.w(TAG, "Bluetooth relay failed", error)
            if (frameArrived) exchange?.responseFailed(DiagnosticsErrorCode.RESPONSE_UNREADABLE)
            else exchange?.failed(DiagnosticsErrorCode.of(error))
            Relay(null, exchange)
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
