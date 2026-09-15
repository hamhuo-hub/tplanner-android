package com.hamhuo.tplanner

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Resumes the durable V5 outbox after boot, package replacement and Bluetooth power changes. */
class WatchSyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> WatchTaskOutbox.resumePending(context)

            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_ON) WatchTaskOutbox.resumePending(context)
            }

            else -> Log.d(TAG, "Ignoring action=${intent.action}")
        }
    }

    private companion object {
        const val TAG = "TplannerSyncBoot"
    }
}
