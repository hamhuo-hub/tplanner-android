package com.hamhuo.tplanner

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.hamhuo.tplanner.ai.AiSkillAssets
import com.hamhuo.tplanner.ai.DeepSeekChatTransport
import com.hamhuo.tplanner.ai.PlanExtractor
import com.hamhuo.tplanner.designsystem.TPlannerLightTokens
import com.hamhuo.tplanner.syncv5.V5Store
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var permissionLauncherInFlight = false

    // Nearby-device Bluetooth and notifications share one runtime request.
    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Log.d(TAG, "permissions result: $results")
        permissionLauncherInFlight = false
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
        applyImmersiveMode()
        advancePermissionSetup()
        lifecycleScope.launch { initializeContent() }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台时系统会把状态栏放回来，这里重新收起。
        applyImmersiveMode()
        advancePermissionSetup()
        // Local state is already durable; this only converges it with the server.
        V5Sync.request(this)
    }

    /**
     * 主界面全天沉浸：状态栏不占位，内容从屏幕顶端开始；用户从边缘下滑仍可临时唤出。
     *
     * 导航栏保留——底部导航岛与手势条需要它自己的安全区。
     */
    private fun applyImmersiveMode() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private suspend fun initializeContent() {
        val eventStore = ScheduleItemStore(this)
        val manager = SyncManager(this)
        val deepseekKey = BuildConfig.DEEPSEEK_API_KEY
        val amapKey = BuildConfig.AMAP_API_KEY
        AmapGeocoder.setApiKey(amapKey)
        // 契约资产在 APK 内的 assets/ai-skill/（由 scripts/generate-ai-skill.py --android 同步）。
        // 读不到资产或版本不匹配时不再抛错中止：AI 路径自动退化为本地规则兜底。
        val skillAssets = runCatching { AiSkillAssets.load { path -> assets.open(path).bufferedReader().readText() } }
            .onFailure { Log.w(LLM_LOG_TAG, "phase=init_assets result=failed reason=${it.javaClass.simpleName}") }
            .getOrNull()
        val planExtractor = PlanExtractor(
            assets = skillAssets,
            transport = deepseekKey.takeIf { it.isNotBlank() }?.let { DeepSeekChatTransport(it) },
            // 设备 UUID（不是个人信息）：只用于缓存隔离与限流分组。
            userId = runCatching { "tplanner-${V5Store(this@MainActivity).deviceId}" }.getOrNull(),
        )
        Log.i(
            LLM_LOG_TAG,
            "phase=init skill=${skillAssets?.skillVersion ?: "unavailable"} " +
                "keyConfigured=${deepseekKey.isNotBlank()} " +
                "modelAvailable=${planExtractor.available} " +
                "locationApiConfigured=${amapKey.isNotBlank()} syncProtocol=v5",
        )

        val initialEventRecovery = eventStore.latestEventDraftRecovery()
        val initialEvents = eventStore.getAll()
        val initialServerUrl = manager.getServerUrl()
        V5Sync.request(this)
        setContent {
            TPlannerPhoneTheme {
                MainScreen(
                    eventStore = eventStore,
                    manager = manager,
                    planExtractor = planExtractor,
                    amapApiKey = amapKey,
                    initialEvents = initialEvents,
                    initialServerUrl = initialServerUrl,
                    initialEventRecovery = initialEventRecovery,
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // A bounded periodic refresh keeps long-lived sessions convergent without a
                // notification channel or a long poll.
                while (true) {
                    kotlinx.coroutines.delay(PERIODIC_REFRESH_MS)
                    runCatching { V5Sync.synchronize(this@MainActivity) }
                }
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
        private const val PERIODIC_REFRESH_MS = 15 * 60 * 1000L

        // Survives Activity recreation without retaining launcher state.
        private var runtimePermissionsAttempted = false
    }
}
