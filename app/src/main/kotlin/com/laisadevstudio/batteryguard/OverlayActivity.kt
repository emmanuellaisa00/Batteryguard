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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlin.math.sin

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
        val unlockItems = GuardRules.unlockRequirements(this, activeReasons.toSet())
        val batteryTarget = GuardRules.batteryUnlockTarget(this)
        val batteryColor = when {
            battery.intValue < 10 -> AlertRed
            battery.intValue < batteryTarget -> AlertOrange
            else -> SuccessMint
        }
        val emergencyState = GuardRules.emergencyPassState(this, battery.intValue, activeReasons.toSet())

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
                    .size(300.dp)
                    .align(Alignment.TopCenter)
                    .offset(y = 40.dp)
                    .graphicsLayer(alpha = 0.9f)
                    .blur(54.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                IceBlue.copy(alpha = 0.22f),
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

                Text(
                    if (previewMode) "LIQUID LOCK PREVIEW" else "DEVICE LOCKED",
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp
                )
                Text(
                    if (previewMode) "Preview mode shows the full lock design without enforcing kiosk restrictions."
                    else "BatteryGuard is enforcing the active rules below. Battery reasons are always the highest priority.",
                    color = Color.White.copy(alpha = 0.62f),
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
                    MetricBubble("Unlock at", "$batteryTarget%+", Modifier.weight(1f), SuccessMint)
                    MetricBubble("Network", network.value.label, Modifier.weight(1f), AuroraBlue)
                }

                GlassCard(accent = AlertOrange, stronger = true, modifier = Modifier.fillMaxWidth()) {
                    GlassSectionTitle(
                        title = "Why this device is locked",
                        subtitle = "BatteryGuard stacks reasons, but low battery always wins. Emergency pass does not override battery lock or anything below 10%."
                    )
                    Spacer(Modifier.height(12.dp))
                    if (activeReasons.isEmpty()) {
                        Text("No active reasons — this is preview mode or the device is between states.", color = Color.White.copy(alpha = 0.64f), fontSize = 13.sp)
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            activeReasons.forEach { GlassPill(GuardRules.reasonShort(it), accent = AlertOrange) }
                        }
                        Spacer(Modifier.height(10.dp))
                        activeReasons.forEach { reason ->
                            Text(
                                "• ${GuardRules.reasonLabel(reason)} — ${GuardRules.reasonDetail(this@OverlayActivity, reason)}",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 13.sp,
                                lineHeight = 20.sp
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }

                GlassCard(accent = IceBlue, stronger = true, modifier = Modifier.fillMaxWidth()) {
                    GlassSectionTitle(
                        title = "How to unlock",
                        subtitle = "BatteryGuard shows exact next steps instead of only saying 'locked'."
                    )
                    Spacer(Modifier.height(12.dp))
                    unlockItems.forEach {
                        Text("• $it", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                }

                GlassCard(accent = SuccessMint, stronger = true, modifier = Modifier.fillMaxWidth()) {
                    GlassSectionTitle(
                        title = "Emergency tools & pass",
                        subtitle = "Torch is always available. Emergency pass is tightly limited: 2 per day, 1 per session, never below 10%, never during battery lock."
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricBubble("Passes left", emergencyState.remainingToday.toString(), Modifier.weight(1f), if (emergencyState.allowed) SuccessMint else AlertOrange)
                        MetricBubble("Used today", emergencyState.usedToday.toString(), Modifier.weight(1f), AuroraBlue)
                    }
                    Spacer(Modifier.height(10.dp))
                    GlassPill(
                        text = emergencyState.title,
                        accent = if (emergencyState.allowed) SuccessMint else AlertOrange
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(emergencyState.detail, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 20.sp)
                    if (bypassRemaining.longValue > 0L) {
                        Spacer(Modifier.height(10.dp))
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
                        else "Emergency tools only"
                    )
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
    private fun ChargingBatteryOrb(
        battery: Int,
        charging: Boolean,
        target: Int,
        color: Color
    ) {
        val transition = rememberInfiniteTransition(label = "battery_orb")
        val pulse by transition.animateFloat(
            initialValue = 0.88f,
            targetValue = 1.12f,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse"
        )
        val ringSweep by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "sweep"
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

        Box(
            modifier = Modifier.size(252.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerX = size.width / 2f
                val centerY = size.height / 2f
                val baseRadius = size.minDimension * 0.34f
                drawCircle(color = color.copy(alpha = 0.08f), radius = baseRadius * 1.85f * pulse)
                drawCircle(color = color.copy(alpha = 0.14f), radius = baseRadius * 1.45f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = 0.34f), color.copy(alpha = 0.08f), Color.Transparent)
                    ),
                    radius = baseRadius * 1.2f
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(Color.Transparent, color.copy(alpha = 0.2f), Color.White.copy(alpha = 0.8f), color.copy(alpha = 0.2f), Color.Transparent)
                    ),
                    startAngle = ringSweep * 360f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(centerX - baseRadius * 1.42f, centerY - baseRadius * 1.42f),
                    size = androidx.compose.ui.geometry.Size(baseRadius * 2.84f, baseRadius * 2.84f),
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                )

                if (charging) {
                    repeat(4) { index ->
                        val phase = (shimmer + index * 0.23f) % 1f
                        val angle = (phase * 2f * PI).toFloat()
                        val x = centerX + cos(angle + index) * baseRadius * 0.55f
                        val y = centerY + baseRadius * 0.7f - phase * baseRadius * 1.25f
                        drawCircle(
                            color = Color.White.copy(alpha = 0.18f + (1f - phase) * 0.35f),
                            radius = (4f + index * 1.2f),
                            center = androidx.compose.ui.geometry.Offset(x, y)
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    painter = painterResource(if (charging) R.drawable.ic_bolt else R.drawable.ic_shield_lock),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text("$battery%", color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (charging) "charging animation active" else "charge needed to release",
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(14.dp))
                Box(modifier = Modifier.fillMaxWidth(0.78f)) {
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
