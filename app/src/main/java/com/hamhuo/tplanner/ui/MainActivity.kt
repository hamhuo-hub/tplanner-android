package com.hamhuo.tplanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.core.graphics.drawable.toDrawable
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.hamhuo.tplanner.persistence.LegacyImportResult
import com.hamhuo.tplanner.persistence.LegacyPreferencesImporter
import com.hamhuo.tplanner.persistence.TPlannerDatabase
import com.hamhuo.tplanner.persistence.DurableWriteQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private var permissionLauncherInFlight = false

    // Nearby-device Bluetooth and notifications share one runtime request.
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d(TAG, "permissions result: $results")
        permissionLauncherInFlight = false
        WatchTaskImportService.startIfAllowed(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { LegacyTaskAlarmCleanup.cancel(this) }
            .onFailure { Log.w(TAG, "Unable to remove legacy task alarms", it) }
        val canvas = TPlannerLightTokens.Semantic.Color.Canvas
        window.setBackgroundDrawable(canvas.toDrawable())
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(canvas, canvas),
            navigationBarStyle = SystemBarStyle.light(canvas, canvas),
        )
        advancePermissionSetup()
        WatchTaskImportService.startIfAllowed(this)
        lifecycleScope.launch { initializeStorageAndContent() }
    }

    override fun onResume() {
        super.onResume()
        advancePermissionSetup()
        WatchTaskImportService.startIfAllowed(this)
    }

    override fun onStop() {
        val flushed = DurableWriteQueue.flushAllOnStop()
        if (!flushed) Log.w(TAG, "onStop: recovery writes did not flush successfully")
        super.onStop()
    }

    private suspend fun initializeStorageAndContent() {
        val database = TPlannerDatabase.get(this)
        // This is a local installation upgrade only: SharedPreferences facts/drafts become Room
        // rows. It never contacts or reconstructs the retired V1 network protocol.
        val migration = withContext(Dispatchers.IO) {
            LegacyPreferencesImporter(this@MainActivity, database).importIfNeeded()
        }
        if (migration is LegacyImportResult.Blocked) {
            val details = migration.issues.joinToString("; ") { issue ->
                "${issue.source}${issue.key?.let { "/$it" }.orEmpty()}: ${issue.message}"
            }
            Log.e(TAG, "Storage migration blocked: $details")
            Toast.makeText(this, "本地数据迁移失败，原数据未改动：$details", Toast.LENGTH_LONG).show()
            return
        }
        val store = JournalStore(this, database)
        val eventStore = ScheduleItemStore(this, database)
        val manager = SyncManager(this)
        val deepseekKey = BuildConfig.DEEPSEEK_API_KEY
        val amapKey = BuildConfig.AMAP_API_KEY
        AmapGeocoder.setApiKey(amapKey)
        val deepseekService = deepseekKey.takeIf { it.isNotBlank() }?.let(::DeepSeekAnalysisService)
        Log.i(
            LLM_LOG_TAG,
                "phase=init provider=deepseek keyConfigured=${deepseekKey.isNotBlank()} " +
                "serviceCreated=${deepseekService != null} " +
                "locationApiConfigured=${amapKey.isNotBlank()} " +
                "localMigration=${migration::class.simpleName} syncProtocol=v3",
        )

        val initialJournalSession = store.latestDraftRecovery()
        val initialJournalDate = initialJournalSession?.date ?: appToday().toString()
        val initialJournalRecovery = initialJournalSession?.recovery
            ?: store.getDraftRecovery(initialJournalDate)
        val initialContent = when (initialJournalRecovery) {
            JournalDraftRecovery.None -> store.get(initialJournalDate)
            is JournalDraftRecovery.Recovered -> initialJournalRecovery.text
            is JournalDraftRecovery.Conflict -> initialJournalRecovery.text
        }
        val initialEventRecovery = eventStore.latestEventDraftRecovery()
        val initialEvents = eventStore.getAll()
        val initialServerUrl = manager.getServerUrl()
        runCatching { SyncV3Scheduler.enqueue(this) }
            .onFailure { Log.w(TAG, "Unable to start sync outbox worker", it) }
        setContent {
            TPlannerPhoneTheme {
                MainScreen(
                    store = store,
                    eventStore = eventStore,
                    manager = manager,
                    deepseekService = deepseekService,
                    amapApiKey = amapKey,
                    initialContent = initialContent,
                    initialEvents = initialEvents,
                    initialJournalDate = initialJournalDate,
                    initialJournalRecovery = initialJournalRecovery,
                    initialServerUrl = initialServerUrl,
                    initialEventRecovery = initialEventRecovery,
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RemoteChangeMonitor(manager).run()
            }
        }
    }

    // ── Permissions ─────────────────────────────────────────────

    /** Requests Bluetooth and notification access at most once per process. */
    private fun advancePermissionSetup() {
        if (runtimePermissionsAttempted || permissionLauncherInFlight || isFinishing || isDestroyed) return
        runtimePermissionsAttempted = true
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                missing += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                missing += Manifest.permission.BLUETOOTH_SCAN
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (missing.isEmpty()) return
        permissionLauncherInFlight = true
        try {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        } catch (e: Exception) {
            permissionLauncherInFlight = false
            Log.e(TAG, "runtime permission request failed", e)
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TplannerMain"
        private const val LLM_LOG_TAG = "TplannerLLM"

        // Survives Activity recreation without retaining launcher state.
        private var runtimePermissionsAttempted = false
    }
}
