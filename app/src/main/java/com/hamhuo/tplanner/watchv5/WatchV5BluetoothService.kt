package com.hamhuo.tplanner.watchv5

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.hamhuo.tplanner.WatchV5Protocol
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Secure paired-device RFCOMM fallback. This service never converts canonical task content. */
class WatchV5BluetoothService : Service() {
    @Volatile private var running = false
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var active: BluetoothSocket? = null
    private val timer = Executors.newSingleThreadScheduledExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!hasPermission(this)) { stopSelf(); return }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Watch sync", NotificationManager.IMPORTANCE_LOW))
        startForeground(5015, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("TPlanner").setContentText("Watch synchronization")
            .setOngoing(true).build())
        running = true
        Thread({ acceptLoop() }, "tplanner-v5-relay").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        while (running) {
            try {
                val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                if (adapter == null || !adapter.isEnabled) { Thread.sleep(3000); continue }
                val listener = adapter.listenUsingRfcommWithServiceRecord("TPlanner V5", WatchV5Protocol.RFCOMM_UUID)
                server = listener
                listener.use {
                    while (running) {
                        val socket = listener.accept()
                        active = socket
                        val timeout = timer.schedule({ runCatching { socket.close() } }, 75, TimeUnit.SECONDS)
                        try {
                            socket.use {
                                val request = WatchV5Protocol.read(socket.inputStream)
                                val response = WatchV5Relay.exchange(applicationContext, request)
                                WatchV5Protocol.write(socket.outputStream, response)
                            }
                        } catch (error: Exception) {
                            Log.w("WatchV5Bluetooth", "Exchange failed", error)
                        } finally { timeout.cancel(false); active = null }
                    }
                }
            } catch (error: Exception) {
                if (running) {
                    Log.w("WatchV5Bluetooth", "Listener interrupted", error)
                    runCatching { Thread.sleep(3000) }
                }
            } finally { server = null }
        }
    }

    override fun onDestroy() {
        running = false
        runCatching { server?.close() }
        runCatching { active?.close() }
        timer.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "tplanner_v5_watch_sync"
        fun hasPermission(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        fun startIfAllowed(context: Context) {
            if (!hasPermission(context)) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, WatchV5BluetoothService::class.java)) }
                .onFailure { Log.w("WatchV5Bluetooth", "Start deferred until app foreground", it) }
        }
    }
}

class WatchV5BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { WatchV5BluetoothService.startIfAllowed(context) }
}
