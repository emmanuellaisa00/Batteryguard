package com.laisadevstudio.batteryguard

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.laisadevstudio.batteryguard.ui.GlassCard
import com.laisadevstudio.batteryguard.ui.GlassPill
import com.laisadevstudio.batteryguard.ui.GlassSectionTitle
import com.laisadevstudio.batteryguard.ui.LiquidProgress
import com.laisadevstudio.batteryguard.ui.MetricBubble
import com.laisadevstudio.batteryguard.ui.theme.AlertOrange
import com.laisadevstudio.batteryguard.ui.theme.AlertRed
import com.laisadevstudio.batteryguard.ui.theme.AuroraBlue
import com.laisadevstudio.batteryguard.ui.theme.BatteryGuardTheme
import com.laisadevstudio.batteryguard.ui.theme.DeepNight
import com.laisadevstudio.batteryguard.ui.theme.IceBlue
import com.laisadevstudio.batteryguard.ui.theme.SuccessMint
import kotlinx.coroutines.delay

class OverlayActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PREVIEW = "preview"
        private const val RECLAIM_DELAY_MS = 250L
        private const val LOCK_TASK_DELAY_MS = 350L
        private const val SLEEP_DELAY_MS = 5_000L
    }

    private val previewMode by lazy { intent.getBooleanExtra(EXTRA_PREVIEW, false) }

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val handler = Handler(Looper.getMainLooper())
    private var suppressNextReclaim = false

    private var torchEnabled by mutableStateOf(false)
    private var torchCameraId: String? = null

    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private val emergencyAuthenticators: Int by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            if (!previewMode && !BatteryGuardService.isGuardActive) {
                releaseKiosk()
                return
            }
            if (BatteryGuardService.isCharging) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private val reclaimRunnable = Runnable {
        if (previewMode || !BatteryGuardService.isGuardActive) return@Runnable
        startActivity(Intent(this, OverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        })
    }

    private val sleepRunnable = Runnable {
        if (previewMode || !BatteryGuardService.isGuardActive) return@Runnable
        killBackgroundApps()
        if (dpm.isAdminActive(adminComponent)) {
            try {
                suppressNextReclaim = true
                dpm.lockNow()
            } catch (_: Exception) {
                suppressNextReclaim = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        torchCameraId = DeviceTools.firstTorchCameraId(this)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SECURE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(false)
        }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        if (!previewMode) {
            try {
                startForegroundService(Intent(this, OverlayWindowService::class.java))
            } catch (_: Exception) {}
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        setupBiometricPrompt()

        setContent {
            BatteryGuardTheme {
                OverlayScreen()
            }
        }

        if (!previewMode) {
            handler.postDelayed({
                startKioskLockTask()
                disableStatusBar(true)
            }, LOCK_TASK_DELAY_MS)
            scheduleSleep()
        }
    }

    override fun onResume() {
        super.onResume()
        suppressNextReclaim = false
        handler.removeCallbacks(reclaimRunnable)
        if (!previewMode && BatteryGuardService.isGuardActive && !OverlayWindowService.isRunning) {
            try {
                startForegroundService(Intent(this, OverlayWindowService::class.java))
            } catch (_: Exception) {}
        }
        if (!previewMode) scheduleSleep()
    }

    override fun onPause() {
        super.onPause()
        val wasSuppressed = suppressNextReclaim
        suppressNextReclaim = false
        if (!previewMode && BatteryGuardService.isGuardActive && !wasSuppressed) {
            scheduleReclaim()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!previewMode && !hasFocus && BatteryGuardService.isGuardActive) scheduleReclaim()
        else handler.removeCallbacks(reclaimRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handler.removeCallbacks(reclaimRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        setTorch(false)
        if (!previewMode && !BatteryGuardService.isGuardActive) disableStatusBar(false)
    }

    private fun setupBiometricPrompt() {
        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    AppPrefs.startEmergencyBypass(this@OverlayActivity)
                    BatteryGuardService.requestRefresh(this@OverlayActivity)
                    showToast("Emergency pass granted for 2 minutes")
                    releaseKiosk()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                        showToast(errString.toString())
                    }
                }
            }
        )

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Emergency bypass")
            .setSubtitle("Unlock for 2 minutes, then BatteryGuard checks all rules again")
            .setConfirmationRequired(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(emergencyAuthenticators)
                } else {
                    setNegativeButtonText("Cancel")
                }
            }
            .build()
    }

    private fun startEmergencyBypass() {
        if (previewMode) {
            finish()
            return
        }
        val status = BiometricManager.from(this)
            .canAuthenticate(emergencyAuthenticators)
        if (status == BiometricManager.BIOMETRIC_SUCCESS) {
            biometricPrompt.authenticate(promptInfo)
        } else {
            showToast("Biometric auth unavailable on this device")
        }
    }

    private fun toggleTorch() {
        if (torchCameraId == null) {
            showToast("Torch unavailable")
            return
        }
        try {
            setTorch(!torchEnabled)
        } catch (e: Exception) {
            showToast("Torch unavailable: ${e.message?.take(32) ?: "error"}")
        }
    }

    private fun setTorch(enabled: Boolean) {
        val cameraId = torchCameraId ?: return
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            cameraManager.setTorchMode(cameraId, enabled)
            torchEnabled = enabled
        } catch (_: Exception) {}
    }

    private fun showRestrictedToggleMessage(label: String) {
        showToast("$label is status-only on modern Android unless system/OEM privileges are available")
    }

    private fun scheduleSleep() {
        if (previewMode || !BatteryGuardService.isGuardActive) return
        handler.removeCallbacks(sleepRunnable)
        handler.postDelayed(sleepRunnable, SLEEP_DELAY_MS)
    }

    private fun scheduleReclaim() {
        handler.removeCallbacks(reclaimRunnable)
        handler.postDelayed(reclaimRunnable, RECLAIM_DELAY_MS)
    }

    private fun startKioskLockTask() {
        try {
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            }
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
        } catch (_: Exception) {
            showToast("Kiosk mode unavailable on this setup")
        }
    }

    private fun disableStatusBar(disabled: Boolean) {
        if (!dpm.isAdminActive(adminComponent)) return
        try {
            @Suppress("DEPRECATION")
            dpm.setStatusBarDisabled(adminComponent, disabled)
        } catch (_: Exception) {}
    }

    private fun releaseKiosk() {
        disableStatusBar(false)
        stopService(Intent(this, OverlayWindowService::class.java))
        try { stopLockTask() } catch (_: Exception) {}
        finish()
    }

    private fun killBackgroundApps() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val runningApps = am.runningAppProcesses ?: return
            for (proc in runningApps) {
                if (proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) continue
                for (pkg in proc.pkgList) {
                    if (pkg == packageName || pkg.startsWith("com.android.") || pkg.startsWith("android")) continue
                    try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private fun showToast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?) = when (keyCode) {
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_VOLUME_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_CAMERA -> true
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = when (keyCode) {
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_MENU -> true
        else -> super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    @Composable
    private fun OverlayScreen() {
        val previewReasons = remember { listOf(GuardReason.LOW_BATTERY, GuardReason.BEDTIME, GuardReason.DAILY_LIMIT) }
        val battery = remember { mutableIntStateOf(if (previewMode) 12 else BatteryGuardService.currentBatteryLevel) }
        val charging = remember { mutableStateOf(if (previewMode) false else BatteryGuardService.isCharging) }
        val reasons = remember { mutableStateOf(if (previewMode) previewReasons else BatteryGuardService.activeReasons.toList()) }
        val bypassRemaining = remember { mutableLongStateOf(AppPrefs.getEmergencyBypassRemainingMs(this)) }
        val network = remember { mutableStateOf(DeviceTools.getNetworkSnapshot(this)) }
        val airplane = remember { mutableStateOf(DeviceTools.isAirplaneModeOn(this)) }
        val nowMs = remember { mutableLongStateOf(System.currentTimeMillis()) }

        LaunchedEffect(Unit) {
            while (true) {
                nowMs.longValue = System.currentTimeMillis()
                if (!previewMode) {
                    battery.intValue = BatteryGuardService.currentBatteryLevel
                    charging.value = BatteryGuardService.isCharging
                    reasons.value = BatteryGuardService.activeReasons.toList()
                    if (!BatteryGuardService.isGuardActive && !AppPrefs.isEmergencyBypassActive(this@OverlayActivity)) {
                        releaseKiosk()
                    }
                }
                bypassRemaining.longValue = AppPrefs.getEmergencyBypassRemainingMs(this@OverlayActivity)
                network.value = DeviceTools.getNetworkSnapshot(this@OverlayActivity)
                airplane.value = DeviceTools.isAirplaneModeOn(this@OverlayActivity)
                delay(1_000L)
            }
        }

        val activeReasons = if (previewMode) previewReasons else reasons.value
        val unlockItems = GuardRules.unlockRequirements(this, activeReasons.toSet())
        val batteryTarget = GuardRules.batteryUnlockTarget(this)
        val batteryColor = when {
            battery.intValue < 10 -> AlertRed
            battery.intValue < batteryTarget -> AlertOrange
            else -> SuccessMint
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF020912),
                            DeepNight,
                            Color(0xFF061120),
                            Color(0xFF02070D)
                        )
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .size(280.dp)
                    .align(Alignment.TopCenter)
                    .offset(y = 60.dp)
                    .graphicsLayer(alpha = 0.85f)
                    .blur(42.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                IceBlue.copy(alpha = 0.24f),
                                AuroraBlue.copy(alpha = 0.10f),
                                Color.Transparent
                            )
                        ),
                        CircleShape
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 22.dp, vertical = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                TopIsland(
                    time = DeviceTools.formatClock(nowMs.longValue),
                    date = DeviceTools.formatDate(nowMs.longValue),
                    battery = battery.intValue,
                    charging = charging.value,
                    reasonCount = activeReasons.size,
                    preview = previewMode
                )

                Spacer(Modifier.height(4.dp))
                Text(
                    if (previewMode) "LIQUID LOCK PREVIEW" else "DEVICE LOCKED",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                Text(
                    if (previewMode) "Preview mode won't enforce kiosk restrictions." else "BatteryGuard is enforcing your active safety rules.",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                BatteryOrb(
                    battery = battery.intValue,
                    charging = charging.value,
                    target = batteryTarget,
                    color = batteryColor
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricBubble("Unlock at", "$batteryTarget%+", Modifier.weight(1f), SuccessMint)
                    MetricBubble("Network", network.value.label, Modifier.weight(1f), AuroraBlue)
                }

                if (activeReasons.isNotEmpty()) {
                    GlassCard(accent = AlertOrange, stronger = true, modifier = Modifier.fillMaxWidth()) {
                        GlassSectionTitle(
                            title = "Why you're locked",
                            subtitle = "BatteryGuard can stack multiple lock reasons. The phone only unlocks when every active reason clears — unless you use the 2-minute emergency pass."
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            activeReasons.forEach { GlassPill(GuardRules.reasonShort(it), accent = AlertOrange) }
                        }
                    }
                }

                GlassCard(accent = IceBlue, stronger = true, modifier = Modifier.fillMaxWidth()) {
                    GlassSectionTitle(
                        title = "What unlocks this?",
                        subtitle = "Battery rule is separate from KeepSafe schedule rules. Emergency bypass unlocks for 2 minutes only, then BatteryGuard checks all rules again."
                    )
                    Spacer(Modifier.height(12.dp))
                    unlockItems.forEach {
                        Text("• $it", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (bypassRemaining.longValue > 0L) {
                        Spacer(Modifier.height(8.dp))
                        GlassPill(
                            text = "Emergency pass active • ${DeviceTools.formatDurationCompact(bypassRemaining.longValue)} left",
                            accent = SuccessMint
                        )
                    }
                }

                GlassCard(accent = AuroraBlue, stronger = true, modifier = Modifier.fillMaxWidth()) {
                    GlassSectionTitle(
                        title = "Safe quick actions",
                        subtitle = "Useful but limited. Torch is real. Data and airplane are shown in the glass tray, but Android usually restricts direct control unless system/OEM privileges exist."
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionTile(
                            label = "Torch",
                            state = if (torchEnabled) "On" else "Off",
                            accent = if (torchEnabled) SuccessMint else IceBlue,
                            icon = if (torchEnabled) R.drawable.ic_bolt else R.drawable.ic_plug,
                            modifier = Modifier.weight(1f),
                            onClick = { toggleTorch() }
                        )
                        ActionTile(
                            label = "Data",
                            state = if (network.value.isCellular) "On" else network.value.label,
                            accent = AuroraBlue,
                            icon = R.drawable.ic_battery_shield,
                            modifier = Modifier.weight(1f),
                            onClick = { showRestrictedToggleMessage("Mobile data") }
                        )
                        ActionTile(
                            label = "Airplane",
                            state = if (airplane.value) "On" else "Off",
                            accent = if (airplane.value) AlertOrange else Color.White,
                            icon = R.drawable.ic_lock,
                            modifier = Modifier.weight(1f),
                            onClick = { showRestrictedToggleMessage("Airplane mode") }
                        )
                    }
                }

                FilledTonalButton(
                    onClick = { startEmergencyBypass() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (previewMode) Color.White.copy(alpha = 0.12f) else SuccessMint.copy(alpha = 0.18f),
                        contentColor = Color.White
                    )
                ) {
                    Text(if (previewMode) "Exit preview" else "Emergency bypass • 2 minutes")
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    @Composable
    private fun TopIsland(
        time: String,
        date: String,
        battery: Int,
        charging: Boolean,
        reasonCount: Int,
        preview: Boolean
    ) {
        GlassCard(accent = IceBlue, stronger = true, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(time, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(date, color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    GlassPill(text = if (preview) "Preview" else "$reasonCount rules", accent = IceBlue)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$battery%${if (charging) " • charging" else ""}",
                        color = Color.White.copy(alpha = 0.80f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    @Composable
    private fun BatteryOrb(
        battery: Int,
        charging: Boolean,
        target: Int,
        color: Color
    ) {
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(
                            color.copy(alpha = 0.40f),
                            color.copy(alpha = 0.18f),
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(if (charging) R.drawable.ic_bolt else R.drawable.ic_shield_lock),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(10.dp))
                Text("$battery%", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (charging) "charging toward unlock" else "charge needed to release",
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(14.dp))
                Box(modifier = Modifier.fillMaxWidth(0.8f)) {
                    LiquidProgress(progress = battery / target.toFloat(), accent = color)
                }
            }
        }
    }

    @Composable
    private fun ActionTile(
        label: String,
        state: String,
        accent: Color,
        icon: Int,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Color.White.copy(alpha = 0.10f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(14.dp)
                    .clickable(onClick = onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
                Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(state, color = Color.White.copy(alpha = 0.60f), fontSize = 11.sp)
            }
        }
    }
}
