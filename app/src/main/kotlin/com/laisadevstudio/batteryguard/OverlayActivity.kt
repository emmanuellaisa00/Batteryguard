package com.laisadevstudio.batteryguard

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.ActivityInfo
import android.os.*
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*


import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.laisadevstudio.batteryguard.ui.theme.BatteryGuardTheme

class OverlayActivity : ComponentActivity() {

    companion object {
        const val TAG = "OverlayActivity"
        private const val RECLAIM_DELAY_MS = 250L   // single delay used everywhere
    }

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val handler = Handler(Looper.getMainLooper())

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level < 0 || scale <= 0) return
            val pct = level * 100 / scale
            BatteryGuardService.currentBatteryLevel = pct
            BatteryGuardService.isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            BatteryGuardService.isGuardActive = pct < AppPrefs.getThreshold(context)
            if (!BatteryGuardService.isGuardActive) releaseKiosk()
        }
    }

    private val reclaimRunnable = Runnable {
        if (!BatteryGuardService.isGuardActive) return@Runnable
        Log.d(TAG, "Reclaiming focus")
        startActivity(Intent(this, OverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { setShowWhenLocked(true); setTurnScreenOn(true) }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        startForegroundService(Intent(this, OverlayWindowService::class.java))
        startKioskLockTask()
        disableStatusBar(true)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        setContent { BatteryGuardTheme { KioskOverlayScreen() } }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && BatteryGuardService.isGuardActive) scheduleReclaim()
        else handler.removeCallbacks(reclaimRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Activity was brought to front by reclaimRunnable — cancel any pending re-post
        handler.removeCallbacks(reclaimRunnable)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(reclaimRunnable)
        if (BatteryGuardService.isGuardActive && !OverlayWindowService.isRunning)
            startForegroundService(Intent(this, OverlayWindowService::class.java))
    }

    override fun onPause() {
        super.onPause()
        if (BatteryGuardService.isGuardActive) scheduleReclaim()
    }

    private fun scheduleReclaim() {
        handler.removeCallbacks(reclaimRunnable)   // never double-post
        handler.postDelayed(reclaimRunnable, RECLAIM_DELAY_MS)
    }

    private fun startKioskLockTask() {
        try {
            if (dpm.isDeviceOwnerApp(packageName)) dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
        } catch (e: Exception) { Log.e(TAG, "startLockTask: ${e.message}") }
    }

    private fun disableStatusBar(disabled: Boolean) {
        if (!dpm.isAdminActive(adminComponent)) return
        try { @Suppress("DEPRECATION") dpm.setStatusBarDisabled(adminComponent, disabled) }
        catch (e: Exception) { Log.w(TAG, "setStatusBarDisabled: ${e.message}") }
    }

    private fun releaseKiosk() {
        disableStatusBar(false)
        stopService(Intent(this, OverlayWindowService::class.java))
        try { stopLockTask() } catch (_: Exception) {}
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(reclaimRunnable)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        if (!BatteryGuardService.isGuardActive) disableStatusBar(false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?) = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_CAMERA -> true
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = when (keyCode) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
        KeyEvent.KEYCODE_MENU -> true
        else -> super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {}

    // ═══════════════════════════════════════════════════════════════
    //  COMPOSE UI
    // ═══════════════════════════════════════════════════════════════

    @Composable
    fun KioskOverlayScreen() {
        val battery   = remember { mutableIntStateOf(BatteryGuardService.currentBatteryLevel) }
        val charging  = remember { mutableStateOf(BatteryGuardService.isCharging) }
        val threshold = AppPrefs.getThreshold(this)

        LaunchedEffect(Unit) {
            while (true) {
                battery.intValue = BatteryGuardService.currentBatteryLevel
                charging.value   = BatteryGuardService.isCharging
                kotlinx.coroutines.delay(500)
            }
        }

        val batteryColor = when {
            battery.intValue < 15           -> Color(0xFFFF1744)
            battery.intValue < threshold    -> Color(0xFFFF6B35)
            battery.intValue < threshold + 10 -> Color(0xFFFFAB00)
            else                            -> Color(0xFF00E676)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF04040F), Color(0xFF080820), Color(0xFF04040F)))),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 48.dp)
            ) {
                // ── Header ──────────────────────────────────────────────
                Icon(
                    painter = painterResource(R.drawable.ic_shield_lock),
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    "DEVICE LOCKED",
                    fontSize = 24.sp, fontWeight = FontWeight.ExtraBold,
                    color = Color.White, letterSpacing = 3.sp
                )

                // ── Animated Battery ─────────────────────────────────────
                AnimatedBattery(
                    level = battery.intValue,
                    threshold = threshold,
                    isCharging = charging.value,
                    color = batteryColor
                )

                // ── Charging status ──────────────────────────────────────
                if (charging.value) {
                    ChargingStatusCard(battery.intValue, threshold, batteryColor)
                } else {
                    NotChargingCard(battery.intValue, threshold, batteryColor)
                }

                // ── Progress toward threshold ────────────────────────────
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${battery.intValue}%", color = batteryColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Target: $threshold%", color = Color(0xFF00E5FF), fontSize = 12.sp)
                    }
                    LinearProgressBar(
                        progress = (battery.intValue.toFloat() / threshold.toFloat()).coerceIn(0f, 1f),
                        color = batteryColor
                    )
                }

                // ── Pre-lock warning ─────────────────────────────────────
                if (!charging.value && battery.intValue in threshold..(threshold + BatteryGuardService.WARN_MARGIN)) {
                    WarningCard(battery.intValue, threshold)
                }
            }
        }
    }

    // ── Animated Battery Widget ──────────────────────────────────────────

    @Composable
    fun AnimatedBattery(level: Int, threshold: Int, isCharging: Boolean, color: Color) {
        val infiniteTransition = rememberInfiniteTransition(label = "battery")

        // Charging fill animation — rises from current level upward
        val chargeFill by infiniteTransition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
            label = "charge_fill"
        )

        // Bolt pulse
        val boltAlpha by infiniteTransition.animateFloat(
            initialValue = 0.4f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "bolt_alpha"
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(180.dp, 220.dp)) {
            Canvas(modifier = Modifier.size(140.dp, 200.dp)) {
                val w = size.width
                val h = size.height
                val strokeW = 6.dp.toPx()
                val cornerR = 16.dp.toPx()
                val tipW = w * 0.3f
                val tipH = 12.dp.toPx()
                val bodyTop = tipH

                // Battery tip (positive terminal)
                drawRoundRect(
                    color = color.copy(alpha = 0.6f),
                    topLeft = Offset((w - tipW) / 2f, 0f),
                    size = Size(tipW, tipH + cornerR),
                    cornerRadius = CornerRadius(cornerR / 2f)
                )

                // Battery body outline
                drawRoundRect(
                    color = color.copy(alpha = 0.3f),
                    topLeft = Offset(strokeW / 2, bodyTop),
                    size = Size(w - strokeW, h - bodyTop - strokeW / 2),
                    cornerRadius = CornerRadius(cornerR),
                    style = Stroke(width = strokeW)
                )

                // Fill level
                val fillFraction = level / 100f
                val bodyInnerH = h - bodyTop - strokeW
                val fillH = bodyInnerH * fillFraction
                val fillTop = bodyTop + bodyInnerH - fillH

                // If charging: animated shimmer on top of fill
                if (isCharging) {
                    val shimmerTop = fillTop - (bodyInnerH * (1f - fillFraction) * chargeFill).coerceAtLeast(0f)
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(Color.Transparent, color.copy(alpha = 0.25f), color),
                            startY = shimmerTop,
                            endY = h - strokeW
                        ),
                        topLeft = Offset(strokeW, shimmerTop),
                        size = Size(w - strokeW * 2, h - strokeW - shimmerTop),
                        cornerRadius = CornerRadius(cornerR - strokeW / 2)
                    )
                }

                // Solid fill
                if (fillH > 0) {
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            listOf(color.copy(alpha = 0.6f), color),
                            startY = fillTop, endY = h - strokeW
                        ),
                        topLeft = Offset(strokeW, fillTop),
                        size = Size(w - strokeW * 2, fillH),
                        cornerRadius = CornerRadius(cornerR - strokeW / 2)
                    )
                }
            }

            // Center: percentage
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = 16.dp)) {
                if (isCharging) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bolt),
                        contentDescription = null,
                        tint = color.copy(alpha = boltAlpha),
                        modifier = Modifier.size(28.dp)
                    )
                }
                Text(
                    "$level%",
                    fontSize = 38.sp, fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                Text(
                    if (isCharging) "charging" else "battery",
                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }

    @Composable
    fun ChargingStatusCard(battery: Int, threshold: Int, color: Color) {
        val infiniteTransition = rememberInfiniteTransition(label = "card")
        val glowAlpha by infiniteTransition.animateFloat(
            initialValue = 0.3f, targetValue = 0.8f,
            animationSpec = infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "glow"
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, color.copy(alpha = glowAlpha), RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0A1A0A))
        ) {
            Row(
                modifier = Modifier.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(painter = painterResource(R.drawable.ic_plug), null,
                    tint = color, modifier = Modifier.size(28.dp))
                Column {
                    Text("Charging", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        if (battery >= threshold) "Unlocking soon…"
                        else "Need ${threshold - battery}% more to unlock",
                        fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }

    @Composable
    fun NotChargingCard(battery: Int, threshold: Int, color: Color) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A0808))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(painter = painterResource(R.drawable.ic_lock), null,
                    tint = color, modifier = Modifier.size(24.dp))
                Text("Charge to $threshold% to unlock",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    color = Color.White, textAlign = TextAlign.Center)
                Text("${threshold - battery}% needed — plug in your charger",
                    fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center)
            }
        }
    }

    @Composable
    fun WarningCard(battery: Int, threshold: Int) {
        val infiniteTransition = rememberInfiniteTransition(label = "warn")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.6f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
            label = "warn_alpha"
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFFFFAB00).copy(alpha = alpha), RoundedCornerShape(12.dp)),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1200))
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(painter = painterResource(R.drawable.ic_warning), null,
                    tint = Color(0xFFFFAB00), modifier = Modifier.size(22.dp))
                Text(
                    "Battery at $battery% — device will lock below $threshold%. Charge now.",
                    fontSize = 13.sp, color = Color(0xFFFFAB00),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    @Composable
    fun LinearProgressBar(progress: Float, color: Color) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.08f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(
                        Brush.horizontalGradient(listOf(color.copy(alpha = 0.6f), color))
                    )
            )
        }
    }
}
