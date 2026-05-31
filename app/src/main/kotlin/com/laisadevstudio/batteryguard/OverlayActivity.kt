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
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.laisadevstudio.batteryguard.ui.GlassCard
import com.laisadevstudio.batteryguard.ui.LocalUiAccessibility
import com.laisadevstudio.batteryguard.ui.UiAccessibilityState
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
import kotlin.math.PI
import kotlin.math.cos

private enum class LockPanel { STATUS, REASONS, TOOLS }

class OverlayActivity : FragmentActivity() {

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
        window.statusBarColor = android.graphics.Color.parseColor("#0C1E2B")
        window.navigationBarColor = android.graphics.Color.parseColor("#0C1E2B")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

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
            } catch (_: Exception) {
            }
        }

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        setupBiometricPrompt()

        setContent {
            val reducedTransparency = AppPrefs.isReducedTransparencyEnabled(this@OverlayActivity)
            BatteryGuardTheme {
                CompositionLocalProvider(
                    LocalUiAccessibility provides UiAccessibilityState(
                        reduceTransparency = reducedTransparency
                    )
                ) {
                    OverlayScreen()
                }
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
            } catch (_: Exception) {
            }
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
                    val sessionId = AppPrefs.getCurrentLockSessionId(this@OverlayActivity)
                        .takeIf { it != 0L } ?: System.currentTimeMillis()
                    AppPrefs.startEmergencyBypass(this@OverlayActivity, sessionId)
                    BatteryGuardService.requestRefresh(this@OverlayActivity)
                    showToast("Emergency pass granted for 2 minutes")
                    releaseKiosk()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_CANCELED) {
                        showToast(errString.toString())
                    }
                }
            }
        )

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Emergency pass")
            .setSubtitle("Unlock for 2 minutes. BatteryGuard will re-check all rules when it ends.")
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

    private fun startEmergencyBypass(reasons: Set<GuardReason>, batteryLevel: Int) {
        if (previewMode) {
            finish()
            return
        }
        val state = GuardRules.emergencyPassState(this, batteryLevel, reasons)
        if (!state.allowed) {
            showToast(state.title)
            return
        }
        val status = BiometricManager.from(this).canAuthenticate(emergencyAuthenticators)
        if (status == BiometricManager.BIOMETRIC_SUCCESS) {
            biometricPrompt.authenticate(promptInfo)
        } else {
            showToast("Biometric or device credential unavailable")
        }
    }

    private fun toggleTorch() {
        if (torchCameraId == null) {
            showToast("Torch unavailable")
            return
        }
        setTorch(!torchEnabled)
    }

    private fun setTorch(enabled: Boolean) {
        val cameraId = torchCameraId ?: return
        try {
            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            cameraManager.setTorchMode(cameraId, enabled)
            torchEnabled = enabled
        } catch (_: Exception) {
        }
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
                dpm.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
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
        } catch (_: Exception) {
        }
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
        } catch (_: Exception) {
        }
    }

    private fun showToast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
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
    override fun onBackPressed() {
    }

    @Composable
    private fun OverlayScreen() {
        val previewReasons = remember { listOf(GuardReason.LOW_BATTERY, GuardReason.BEDTIME, GuardReason.DAILY_LIMIT) }
        val selectedPanel = remember { mutableStateOf(LockPanel.STATUS) }
        val battery = remember { mutableIntStateOf(if (previewMode) 12 else BatteryGuardService.currentBatteryLevel) }
        val charging = remember { mutableStateOf(if (previewMode) true else BatteryGuardService.isCharging) }
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
        val batteryTarget = GuardRules.batteryUnlockTarget(this)
        val batteryColor = when {
            charging.value -> Color(0xFF34C759)
            battery.intValue < 10 -> AlertRed
            battery.intValue < batteryTarget -> AlertOrange
            else -> SuccessMint
        }
        val emergencyState = GuardRules.emergencyPassState(this, battery.intValue, activeReasons.toSet())
        val primaryMessage = when {
            GuardReason.LOW_BATTERY in activeReasons -> "Device locked. Please charge device to unlock."
            GuardReason.BEDTIME in activeReasons -> "Device locked. Please wait until the focus lock ends."
            GuardReason.DAILY_LIMIT in activeReasons -> "Device locked. Daily screen time has been exhausted."
            GuardReason.OUTSIDE_ALLOWED_WINDOW in activeReasons -> "Device locked. Please wait for the next allowed time."
            else -> "Device locked. Please clear the active rules to unlock."
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF07121C),
                            Color(0xFF0B1C28),
                            Color(0xFF09202E),
                            Color(0xFF07131D)
                        )
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .size(300.dp)
                    .align(Alignment.TopCenter)
                    .offset(y = 44.dp)
                    .graphicsLayer(alpha = 0.9f)
                    .blur(58.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                if (charging.value) Color(0xFF34C759).copy(alpha = 0.18f) else IceBlue.copy(alpha = 0.18f),
                                AuroraBlue.copy(alpha = 0.08f),
                                Color.Transparent
                            )
                        ),
                        CircleShape
                    )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TopIsland(
                    time = DeviceTools.formatClock(nowMs.longValue),
                    date = DeviceTools.formatDate(nowMs.longValue),
                    battery = battery.intValue,
                    charging = charging.value,
                    reasonCount = activeReasons.size,
                    preview = previewMode
                )

                Text(
                    if (previewMode) "LOCK SCREEN PREVIEW" else "DEVICE LOCKED",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                Text(
                    primaryMessage,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )

                ChargingBatteryOrb(
                    battery = battery.intValue,
                    charging = charging.value,
                    target = batteryTarget,
                    color = batteryColor
                )

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricBubble("Unlock at", "$batteryTarget%+", Modifier.weight(1f), if (charging.value) Color(0xFF34C759) else SuccessMint)
                    MetricBubble("Network", network.value.label, Modifier.weight(1f), AuroraBlue)
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LockPanel.entries.forEach { panel ->
                        GlassPill(
                            text = when (panel) {
                                LockPanel.STATUS -> "Now"
                                LockPanel.REASONS -> "Why"
                                LockPanel.TOOLS -> "Tools"
                            },
                            modifier = Modifier.weight(1f),
                            accent = if (selectedPanel.value == panel) IceBlue else Color.White,
                            active = selectedPanel.value == panel,
                            onClick = { selectedPanel.value = panel }
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    Crossfade(targetState = selectedPanel.value, label = "lock_panel") { panel ->
                        when (panel) {
                            LockPanel.STATUS -> LockStatusPanel(
                                battery = battery.intValue,
                                charging = charging.value,
                                batteryTarget = batteryTarget,
                                batteryColor = batteryColor,
                                primaryMessage = primaryMessage,
                                bypassRemainingMs = bypassRemaining.longValue,
                                emergencyState = emergencyState
                            )
                            LockPanel.REASONS -> LockReasonPanel(activeReasons = activeReasons)
                            LockPanel.TOOLS -> LockToolsPanel(
                                torchEnabled = torchEnabled,
                                networkLabel = network.value.label,
                                airplaneOn = airplane.value,
                                emergencyState = emergencyState,
                                onTorch = { toggleTorch() },
                                onData = { showRestrictedToggleMessage("Mobile data") },
                                onAirplane = { showRestrictedToggleMessage("Airplane mode") }
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = { startEmergencyBypass(activeReasons.toSet(), battery.intValue) },
                    enabled = previewMode || emergencyState.allowed,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (previewMode) Color.White.copy(alpha = 0.12f) else SuccessMint.copy(alpha = 0.18f),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        if (previewMode) "Exit preview"
                        else if (emergencyState.allowed) "Emergency pass • 2 minutes"
                        else emergencyState.title
                    )
                }
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
    private fun ChargingBatteryOrb(
        battery: Int,
        charging: Boolean,
        target: Int,
        color: Color
    ) {
        val transition = rememberInfiniteTransition(label = "battery_orb")
        val pulse by transition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        val shimmer by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer"
        )

        val fillProgress = (battery / target.toFloat()).coerceIn(0f, 1f)
        val activeScale = if (charging) pulse else 1f
        val chargingGreen = Color(0xFF34C759)

        Box(modifier = Modifier.size(224.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val radius = size.minDimension * 0.34f
                drawCircle(color = (if (charging) chargingGreen else color).copy(alpha = 0.10f), radius = radius * 1.7f * activeScale)
                drawCircle(color = Color.White.copy(alpha = 0.06f), radius = radius * 1.3f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(
                            (if (charging) chargingGreen else color).copy(alpha = 0.30f),
                            (if (charging) chargingGreen else color).copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    ),
                    radius = radius * 1.05f
                )
                val orbRadius = radius * 0.98f
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val orbCenter = androidx.compose.ui.geometry.Offset(centerX, centerY)
                drawCircle(color = Color.White.copy(alpha = 0.06f), radius = orbRadius, center = orbCenter)
                val fillTop = centerY + orbRadius - (orbRadius * 2f * fillProgress)
                drawCircle(
                    brush = Brush.verticalGradient(
                        listOf(
                            if (charging) chargingGreen.copy(alpha = 0.55f) else color.copy(alpha = 0.35f),
                            if (charging) chargingGreen else color
                        ),
                        startY = fillTop,
                        endY = centerY + orbRadius
                    ),
                    radius = orbRadius,
                    center = orbCenter
                )
                if (charging) {
                    repeat(5) { index ->
                        val phase = (shimmer + index * 0.17f) % 1f
                        val angle = (phase * 2f * PI).toFloat()
                        val x = centerX + cos((angle + index).toDouble()).toFloat() * orbRadius * 0.55f
                        val y = centerY + orbRadius * 0.72f - phase * orbRadius * 1.35f
                        drawCircle(
                            color = chargingGreen.copy(alpha = 0.15f + (1f - phase) * 0.38f),
                            radius = (3.8f + index * 0.8f),
                            center = androidx.compose.ui.geometry.Offset(x, y)
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(if (charging) R.drawable.ic_bolt else R.drawable.ic_shield_lock),
                    contentDescription = null,
                    tint = if (charging) chargingGreen else color,
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("$battery%", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (charging) "Charging to unlock" else "Not charging",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                Box(modifier = Modifier.fillMaxWidth(0.8f)) {
                    LiquidProgress(progress = fillProgress, accent = if (charging) chargingGreen else color)
                }
            }
        }
    }

    @Composable
    private fun LockStatusPanel(
        battery: Int,
        charging: Boolean,
        batteryTarget: Int,
        batteryColor: Color,
        primaryMessage: String,
        bypassRemainingMs: Long,
        emergencyState: EmergencyPassState
    ) {
        GlassCard(accent = if (charging) Color(0xFF34C759) else batteryColor, stronger = true, modifier = Modifier.fillMaxSize()) {
            Text(primaryMessage, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                if (charging) "Keep the charger connected until the target is reached."
                else "Connect a charger to start unlock progress.",
                color = Color.White.copy(alpha = 0.70f),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricBubble("Battery", "$battery%", Modifier.weight(1f), batteryColor)
                MetricBubble("Target", "$batteryTarget%", Modifier.weight(1f), SuccessMint)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                if (charging) "BatteryGuard releases after the target is reached and all other reasons clear."
                else "No charger detected. Device stays locked until charging begins.",
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                lineHeight = 18.sp
            )
            if (bypassRemainingMs > 0L) {
                Spacer(Modifier.height(10.dp))
                GlassPill("Emergency pass active • ${DeviceTools.formatDurationCompact(bypassRemainingMs)} left", accent = SuccessMint)
            } else {
                Spacer(Modifier.height(10.dp))
                GlassPill(emergencyState.title, accent = if (emergencyState.allowed) SuccessMint else AlertOrange)
            }
        }
    }

    @Composable
    private fun LockReasonPanel(activeReasons: List<GuardReason>) {
        GlassCard(accent = AlertOrange, stronger = true, modifier = Modifier.fillMaxSize()) {
            GlassSectionTitle(
                title = "Lock reasons",
                subtitle = "Lock details are grouped into focused panels for clarity."
            )
            Spacer(Modifier.height(12.dp))
            if (activeReasons.isEmpty()) {
                Text("No active reasons. This usually means preview mode or a transition between states.", color = Color.White.copy(alpha = 0.68f), fontSize = 13.sp)
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeReasons.forEach { GlassPill(GuardRules.reasonShort(it), accent = AlertOrange) }
                }
                Spacer(Modifier.height(10.dp))
                activeReasons.forEach { reason ->
                    Text(
                        "• ${GuardRules.reasonLabel(reason)} — ${GuardRules.reasonDetail(this@OverlayActivity, reason)}",
                        color = Color.White.copy(alpha = 0.74f),
                        fontSize = 13.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    @Composable
    private fun LockToolsPanel(
        torchEnabled: Boolean,
        networkLabel: String,
        airplaneOn: Boolean,
        emergencyState: EmergencyPassState,
        onTorch: () -> Unit,
        onData: () -> Unit,
        onAirplane: () -> Unit
    ) {
        GlassCard(accent = AuroraBlue, stronger = true, modifier = Modifier.fillMaxSize()) {
            GlassSectionTitle(
                title = "Safe tools",
                subtitle = "Only safe tools remain available during lock."
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile(
                    label = "Torch",
                    state = if (torchEnabled) "On" else "Off",
                    accent = if (torchEnabled) SuccessMint else IceBlue,
                    icon = if (torchEnabled) R.drawable.ic_bolt else R.drawable.ic_plug,
                    modifier = Modifier.weight(1f),
                    onClick = onTorch
                )
                ActionTile(
                    label = "Data",
                    state = networkLabel,
                    accent = AuroraBlue,
                    icon = R.drawable.ic_battery_shield,
                    modifier = Modifier.weight(1f),
                    onClick = onData
                )
                ActionTile(
                    label = "Airplane",
                    state = if (airplaneOn) "On" else "Off",
                    accent = if (airplaneOn) AlertOrange else Color.White,
                    icon = R.drawable.ic_lock,
                    modifier = Modifier.weight(1f),
                    onClick = onAirplane
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(emergencyState.detail, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp, lineHeight = 18.sp)
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
