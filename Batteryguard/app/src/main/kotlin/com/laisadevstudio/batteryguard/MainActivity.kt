package com.laisadevstudio.batteryguard

import android.Manifest
import android.app.TimePickerDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.laisadevstudio.batteryguard.ui.GlassCard
import com.laisadevstudio.batteryguard.ui.GlassPill
import com.laisadevstudio.batteryguard.ui.GlassSectionTitle
import com.laisadevstudio.batteryguard.ui.GlassToggleDot
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

class MainActivity : ComponentActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private var permRefreshTick by mutableIntStateOf(0)

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permRefreshTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)

        try {
            startForegroundService(Intent(this, BatteryGuardService::class.java))
        } catch (_: Exception) {
            showToast("Could not start BatteryGuard service")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            BatteryGuardTheme {
                MainScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permRefreshTick++
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val expected = ComponentName(this, KioskAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            enabled.split(":").any { ComponentName.unflattenFromString(it) == expected }
        } catch (_: Exception) {
            false
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            false
        }
    }

    private fun isDefaultHomeLauncher(): Boolean {
        return try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            resolved?.activityInfo?.packageName == packageName
        } catch (_: Exception) {
            false
        }
    }

    private fun showToast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openBatteryOptimizationSettings() {
        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun launchPreviewOverlay() {
        startActivity(Intent(this, OverlayActivity::class.java).apply {
            putExtra(OverlayActivity.EXTRA_PREVIEW, true)
        })
    }

    private fun openDeviceAdminSettings() {
        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "BatteryGuard needs admin access to lock the device and keep BatteryGuard active."
            )
        })
    }

    private fun pickTime(initialMinutes: Int, onPicked: (Int) -> Unit) {
        val hour = initialMinutes / 60
        val minute = initialMinutes % 60
        TimePickerDialog(this, { _, h, m -> onPicked(h * 60 + m) }, hour, minute, false).show()
    }

    @Composable
    private fun MainScreen() {
        val tick = permRefreshTick
        val isAdmin = remember(tick) { dpm.isAdminActive(adminComponent) }
        val hasOverlay = remember(tick) { Settings.canDrawOverlays(this) }
        val hasA11y = remember(tick) { isAccessibilityServiceEnabled() }
        val hasNotifPerm = remember(tick) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            } else true
        }
        val isDeviceOwner = remember(tick) { dpm.isDeviceOwnerApp(packageName) }
        val ignoresBatteryOpt = remember(tick) { isIgnoringBatteryOptimizations() }
        val isDefaultHome = remember(tick) { isDefaultHomeLauncher() }

        val protectionEnabled = remember { mutableStateOf(AppPrefs.isProtectionEnabled(this)) }
        val batteryEnabled = remember { mutableStateOf(AppPrefs.isBatteryProtectionEnabled(this)) }
        val scheduleEnabled = remember { mutableStateOf(AppPrefs.isScheduleProtectionEnabled(this)) }
        val dailyLimitEnabled = remember { mutableStateOf(AppPrefs.isDailyUsageLimitEnabled(this)) }

        val threshold = remember { mutableIntStateOf(AppPrefs.getThreshold(this)) }
        val warnMargin = remember { mutableIntStateOf(AppPrefs.getWarnMargin(this)) }
        val dailyLimit = remember { mutableIntStateOf(AppPrefs.getDailyUsageLimitMinutes(this)) }

        val window1 = remember { mutableStateOf(AppPrefs.getWindow1(this)) }
        val window2 = remember { mutableStateOf(AppPrefs.getWindow2(this)) }
        val bedtime = remember { mutableStateOf(AppPrefs.getBedtimeWindow(this)) }

        val battery = remember { mutableIntStateOf(DeviceTools.getBatteryLevel(this)) }
        val usageToday = remember { mutableIntStateOf(AppPrefs.getTodayUsageMinutes(this)) }
        val charging = remember { mutableStateOf(BatteryGuardService.isCharging) }
        val activeReasons = remember { mutableStateOf(BatteryGuardService.activeReasons.toList()) }
        val bypassRemaining = remember { mutableLongStateOf(AppPrefs.getEmergencyBypassRemainingMs(this)) }
        val network = remember { mutableStateOf(DeviceTools.getNetworkSnapshot(this)) }

        LaunchedEffect(Unit) {
            while (true) {
                battery.intValue = getBatteryLevel()
                charging.value = BatteryGuardService.isCharging
                usageToday.intValue = AppPrefs.getTodayUsageMinutes(this@MainActivity)
                activeReasons.value = BatteryGuardService.activeReasons.toList()
                bypassRemaining.longValue = AppPrefs.getEmergencyBypassRemainingMs(this@MainActivity)
                network.value = DeviceTools.getNetworkSnapshot(this@MainActivity)
                delay(1_000L)
            }
        }

        val batteryTarget = GuardRules.batteryUnlockTarget(this)
        val protectionStrength = GuardRules.protectionStrength(isAdmin, hasOverlay, hasA11y, isDeviceOwner)
        val usageProgress = if (dailyLimitEnabled.value) {
            usageToday.intValue.toFloat() / dailyLimit.intValue.toFloat()
        } else 0f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF02101A),
                            DeepNight,
                            Color(0xFF071726),
                            Color(0xFF020B12)
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(10.dp))
                HeroDashboard(
                    battery = battery.intValue,
                    charging = charging.value,
                    protectionEnabled = protectionEnabled.value,
                    protectionStrength = protectionStrength,
                    usageToday = usageToday.intValue,
                    usageLimit = dailyLimit.intValue,
                    dailyLimitEnabled = dailyLimitEnabled.value,
                    activeReasons = activeReasons.value,
                    bypassRemainingMs = bypassRemaining.longValue,
                    networkLabel = network.value.label,
                    onPreview = { launchPreviewOverlay() },
                    onResetUsage = {
                        AppPrefs.resetUsageToday(this@MainActivity)
                        usageToday.intValue = 0
                        BatteryGuardService.requestRefresh(this@MainActivity)
                    }
                )

                PermissionSection(
                    isAdmin = isAdmin,
                    hasOverlay = hasOverlay,
                    hasA11y = hasA11y,
                    hasNotifPerm = hasNotifPerm,
                    ignoresBatteryOpt = ignoresBatteryOpt,
                    onAdmin = { openDeviceAdminSettings() },
                    onOverlay = { openOverlaySettings() },
                    onA11y = { openAccessibilitySettings() },
                    onNotif = { openNotificationPermission() },
                    onBatteryOpt = { openBatteryOptimizationSettings() }
                )

                GlassCard(accent = IceBlue, stronger = true) {
                    GlassSectionTitle(
                        title = "BatteryGuard",
                        subtitle = "Liquid-lock battery protection. Locks below your threshold, but once locked it only releases at 20% or higher — or your threshold if it is above 20%."
                    )
                    Spacer(Modifier.height(14.dp))
                    ToggleRow(
                        title = "Master protection",
                        subtitle = "Turn all BatteryGuard rules on or off",
                        checked = protectionEnabled.value,
                        onChecked = {
                            protectionEnabled.value = it
                            AppPrefs.setProtectionEnabled(this@MainActivity, it)
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    ToggleRow(
                        title = "Battery lock engine",
                        subtitle = "Monitor battery and enforce low-battery lock",
                        checked = batteryEnabled.value,
                        onChecked = {
                            batteryEnabled.value = it
                            AppPrefs.setBatteryProtectionEnabled(this@MainActivity, it)
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        }
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Lock threshold • ${threshold.intValue}%", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = threshold.intValue.toFloat(),
                        onValueChange = {
                            threshold.intValue = it.toInt()
                            AppPrefs.setThreshold(this@MainActivity, threshold.intValue)
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        },
                        valueRange = 5f..80f,
                        steps = 14,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = IceBlue,
                            inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                        )
                    )
                    Text(
                        "Locks when battery falls below ${threshold.intValue}%. Current battery: ${battery.intValue}%.",
                        color = Color.White.copy(alpha = 0.60f),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Warning margin • ${warnMargin.intValue}%", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = warnMargin.intValue.toFloat(),
                        onValueChange = {
                            warnMargin.intValue = it.toInt()
                            AppPrefs.setWarnMargin(this@MainActivity, warnMargin.intValue)
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        },
                        valueRange = 3f..20f,
                        steps = 16,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = AuroraBlue,
                            inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricBubble("Unlock floor", "$batteryTarget%", modifier = Modifier.weight(1f), accent = SuccessMint)
                        MetricBubble("Warns at", "${threshold.intValue + warnMargin.intValue}%", modifier = Modifier.weight(1f), accent = AlertOrange)
                    }
                }

                GlassCard(accent = AuroraBlue, stronger = true) {
                    GlassSectionTitle(
                        title = "KeepSafe",
                        subtitle = "Reduce phone time, protect sleep and focus, and define exactly when the phone can be used."
                    )
                    Spacer(Modifier.height(14.dp))
                    ToggleRow(
                        title = "Schedule lock",
                        subtitle = "Only allow use during your safe windows",
                        checked = scheduleEnabled.value,
                        onChecked = {
                            scheduleEnabled.value = it
                            AppPrefs.setScheduleProtectionEnabled(this@MainActivity, it)
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    PresetRow(
                        onStudent = {
                            val w1 = TimeWindow(true, 6 * 60, 8 * 60)
                            val w2 = TimeWindow(true, 17 * 60, 20 * 60)
                            val bed = TimeWindow(true, 22 * 60, 6 * 60)
                            window1.value = w1; window2.value = w2; bedtime.value = bed
                            AppPrefs.setWindow1(this@MainActivity, w1)
                            AppPrefs.setWindow2(this@MainActivity, w2)
                            AppPrefs.setBedtimeWindow(this@MainActivity, bed)
                            AppPrefs.setScheduleProtectionEnabled(this@MainActivity, true)
                            scheduleEnabled.value = true
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        },
                        onNight = {
                            val bed = TimeWindow(true, 21 * 60 + 30, 6 * 60)
                            bedtime.value = bed
                            AppPrefs.setBedtimeWindow(this@MainActivity, bed)
                            AppPrefs.setScheduleProtectionEnabled(this@MainActivity, true)
                            scheduleEnabled.value = true
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        },
                        onFocus = {
                            val w1 = TimeWindow(true, 7 * 60, 8 * 60)
                            val w2 = TimeWindow(true, 19 * 60, 21 * 60)
                            window1.value = w1; window2.value = w2
                            AppPrefs.setWindow1(this@MainActivity, w1)
                            AppPrefs.setWindow2(this@MainActivity, w2)
                            AppPrefs.setScheduleProtectionEnabled(this@MainActivity, true)
                            scheduleEnabled.value = true
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    WindowEditor(
                        title = "Allowed window 1",
                        window = window1.value,
                        accent = IceBlue,
                        onToggle = {
                            window1.value = window1.value.copy(enabled = it)
                            AppPrefs.setWindow1(this@MainActivity, window1.value)
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        },
                        onStart = {
                            pickTime(window1.value.startMinutes) { picked ->
                                window1.value = window1.value.copy(startMinutes = picked)
                                AppPrefs.setWindow1(this@MainActivity, window1.value)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            }
                        },
                        onEnd = {
                            pickTime(window1.value.endMinutes) { picked ->
                                window1.value = window1.value.copy(endMinutes = picked)
                                AppPrefs.setWindow1(this@MainActivity, window1.value)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    WindowEditor(
                        title = "Allowed window 2",
                        window = window2.value,
                        accent = AuroraBlue,
                        onToggle = {
                            window2.value = window2.value.copy(enabled = it)
                            AppPrefs.setWindow2(this@MainActivity, window2.value)
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        },
                        onStart = {
                            pickTime(window2.value.startMinutes) { picked ->
                                window2.value = window2.value.copy(startMinutes = picked)
                                AppPrefs.setWindow2(this@MainActivity, window2.value)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            }
                        },
                        onEnd = {
                            pickTime(window2.value.endMinutes) { picked ->
                                window2.value = window2.value.copy(endMinutes = picked)
                                AppPrefs.setWindow2(this@MainActivity, window2.value)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    WindowEditor(
                        title = "Bedtime / focus lock",
                        window = bedtime.value,
                        accent = AlertOrange,
                        onToggle = {
                            bedtime.value = bedtime.value.copy(enabled = it)
                            AppPrefs.setBedtimeWindow(this@MainActivity, bedtime.value)
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        },
                        onStart = {
                            pickTime(bedtime.value.startMinutes) { picked ->
                                bedtime.value = bedtime.value.copy(startMinutes = picked)
                                AppPrefs.setBedtimeWindow(this@MainActivity, bedtime.value)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            }
                        },
                        onEnd = {
                            pickTime(bedtime.value.endMinutes) { picked ->
                                bedtime.value = bedtime.value.copy(endMinutes = picked)
                                AppPrefs.setBedtimeWindow(this@MainActivity, bedtime.value)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            }
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    ToggleRow(
                        title = "Daily screen-time limit",
                        subtitle = "Lock after your allowed phone time is used up",
                        checked = dailyLimitEnabled.value,
                        onChecked = {
                            dailyLimitEnabled.value = it
                            AppPrefs.setDailyUsageLimitEnabled(this@MainActivity, it)
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Daily limit • ${dailyLimit.intValue} min", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = dailyLimit.intValue.toFloat(),
                        onValueChange = {
                            dailyLimit.intValue = it.toInt()
                            AppPrefs.setDailyUsageLimitMinutes(this@MainActivity, dailyLimit.intValue)
                            BatteryGuardService.requestRefresh(this@MainActivity)
                        },
                        valueRange = 15f..720f,
                        steps = 46,
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = SuccessMint,
                            inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                        )
                    )
                    Text(
                        "Today used ${usageToday.intValue} of ${dailyLimit.intValue} minutes",
                        color = Color.White.copy(alpha = 0.60f),
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LiquidProgress(progress = usageProgress, accent = SuccessMint)
                }

                GlassCard(accent = SuccessMint, stronger = true) {
                    GlassSectionTitle(
                        title = "Emergency + Test Lab",
                        subtitle = "Emergency pass unlocks the device for 2 minutes using biometric auth, with device credential fallback where Android allows it. Preview mode lets you test the full lock UI safely before you trust it."
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricBubble(
                            label = "Emergency pass",
                            value = if (bypassRemaining.longValue > 0L) DeviceTools.formatDurationCompact(bypassRemaining.longValue) else "Ready",
                            modifier = Modifier.weight(1f),
                            accent = SuccessMint
                        )
                        MetricBubble(
                            label = "Next schedule",
                            value = GuardRules.nextAllowedWindowText(this@MainActivity) ?: "Open",
                            modifier = Modifier.weight(1f),
                            accent = AuroraBlue
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(
                            onClick = { launchPreviewOverlay() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = IceBlue.copy(alpha = 0.18f),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Preview lock")
                        }
                        FilledTonalButton(
                            onClick = {
                                BatteryGuardService.requestRefresh(this@MainActivity)
                                showToast("BatteryGuard rules refreshed")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = SuccessMint.copy(alpha = 0.18f),
                                contentColor = Color.White
                            )
                        ) {
                            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Refresh rules")
                        }
                    }
                }

                DiagnosticsSection(
                    isAdmin = isAdmin,
                    hasOverlay = hasOverlay,
                    hasA11y = hasA11y,
                    hasNotif = hasNotifPerm,
                    deviceOwner = isDeviceOwner,
                    isDefaultHome = isDefaultHome,
                    ignoresBatteryOpt = ignoresBatteryOpt,
                    guardActive = BatteryGuardService.isGuardActive,
                    activeReasons = activeReasons.value,
                    lockCount = AppPrefs.getLockCount(this),
                    lastLock = AppPrefs.getLastLockAt(this),
                    lastUnlock = AppPrefs.getLastUnlockAt(this),
                    lastReason = AppPrefs.getLastLockReasons(this)
                )

                Spacer(Modifier.height(28.dp))
            }
        }
    }

    @Composable
    private fun HeroDashboard(
        battery: Int,
        charging: Boolean,
        protectionEnabled: Boolean,
        protectionStrength: String,
        usageToday: Int,
        usageLimit: Int,
        dailyLimitEnabled: Boolean,
        activeReasons: List<GuardReason>,
        bypassRemainingMs: Long,
        networkLabel: String,
        onPreview: () -> Unit,
        onResetUsage: () -> Unit
    ) {
        val accent = when {
            activeReasons.isNotEmpty() -> AlertOrange
            charging -> SuccessMint
            else -> IceBlue
        }

        GlassCard(accent = accent, stronger = true) {
            Text("BatteryGuard", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Liquid glass battery discipline, KeepSafe schedules, and a 2-minute emergency pass.",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricBubble("Battery", "$battery%${if (charging) " • charging" else ""}", Modifier.weight(1f), accent)
                MetricBubble(
                    "Protection",
                    if (protectionEnabled) protectionStrength else "Off",
                    Modifier.weight(1f),
                    if (protectionEnabled) IceBlue else AlertRed
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricBubble("Network", networkLabel, Modifier.weight(1f), AuroraBlue)
                MetricBubble(
                    "Today",
                    if (dailyLimitEnabled) "$usageToday / $usageLimit min" else "$usageToday min",
                    Modifier.weight(1f),
                    SuccessMint
                )
            }
            Spacer(Modifier.height(16.dp))
            if (bypassRemainingMs > 0L) {
                GlassPill(text = "Emergency pass live • ${DeviceTools.formatDurationCompact(bypassRemainingMs)} left", accent = SuccessMint)
                Spacer(Modifier.height(10.dp))
            }
            if (activeReasons.isNotEmpty()) {
                Text("Active lock reasons", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeReasons.forEach { reason ->
                        GlassPill(text = GuardRules.reasonShort(reason), accent = AlertOrange)
                    }
                }
                Spacer(Modifier.height(12.dp))
                GuardRules.unlockRequirements(this@MainActivity, activeReasons.toSet()).forEach {
                    Text("• $it", color = Color.White.copy(alpha = 0.68f), fontSize = 12.sp)
                }
                Spacer(Modifier.height(12.dp))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onPreview,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White
                    )
                ) {
                    Text("Preview overlay")
                }
                FilledTonalButton(
                    onClick = onResetUsage,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.09f),
                        contentColor = Color.White
                    )
                ) {
                    Text("Reset usage")
                }
            }
        }
    }

    @Composable
    private fun PermissionSection(
        isAdmin: Boolean,
        hasOverlay: Boolean,
        hasA11y: Boolean,
        hasNotifPerm: Boolean,
        ignoresBatteryOpt: Boolean,
        onAdmin: () -> Unit,
        onOverlay: () -> Unit,
        onA11y: () -> Unit,
        onNotif: () -> Unit,
        onBatteryOpt: () -> Unit
    ) {
        GlassCard(accent = AuroraBlue) {
            GlassSectionTitle(
                title = "Readiness",
                subtitle = "For maximum enforcement, BatteryGuard needs admin, overlay and accessibility. Battery optimization exemption helps it survive OEM killers."
            )
            Spacer(Modifier.height(14.dp))
            PermissionTile("Device admin", "Needed for lockNow and stronger kiosk behavior", isAdmin, false, onAdmin)
            Spacer(Modifier.height(10.dp))
            PermissionTile("Display over apps", "Needed for fullscreen overlay lock", hasOverlay, false, onOverlay)
            Spacer(Modifier.height(10.dp))
            PermissionTile("Accessibility", "Blocks notification shade / quick settings", hasA11y, true, onA11y)
            Spacer(Modifier.height(10.dp))
            PermissionTile("Notifications", "Low-battery warnings and persistent status", hasNotifPerm, true, onNotif)
            Spacer(Modifier.height(10.dp))
            PermissionTile("Battery optimization", "Recommended so OEM power managers don't kill the service", ignoresBatteryOpt, true, onBatteryOpt)
        }
    }

    @Composable
    private fun DiagnosticsSection(
        isAdmin: Boolean,
        hasOverlay: Boolean,
        hasA11y: Boolean,
        hasNotif: Boolean,
        deviceOwner: Boolean,
        isDefaultHome: Boolean,
        ignoresBatteryOpt: Boolean,
        guardActive: Boolean,
        activeReasons: List<GuardReason>,
        lockCount: Int,
        lastLock: Long,
        lastUnlock: Long,
        lastReason: String
    ) {
        GlassCard(accent = AlertOrange) {
            GlassSectionTitle(
                title = "Diagnostics dashboard",
                subtitle = "Everything that matters at a glance — service state, permissions, launcher role, lockdown strength, and lock history."
            )
            Spacer(Modifier.height(14.dp))
            DiagnosticRow("Service running", true)
            DiagnosticRow("Guard active", guardActive)
            DiagnosticRow("Device admin", isAdmin)
            DiagnosticRow("Overlay permission", hasOverlay)
            DiagnosticRow("Accessibility", hasA11y)
            DiagnosticRow("Notifications", hasNotif)
            DiagnosticRow("Device owner", deviceOwner)
            DiagnosticRow("Default launcher", isDefaultHome)
            DiagnosticRow("Battery optimization ignored", ignoresBatteryOpt)
            Spacer(Modifier.height(14.dp))
            Text("Current reasons", color = Color.White.copy(alpha = 0.80f), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            if (activeReasons.isEmpty()) {
                Text("No active lock reasons", color = Color.White.copy(alpha = 0.56f), fontSize = 12.sp)
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeReasons.forEach { GlassPill(GuardRules.reasonShort(it), accent = AlertOrange) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricBubble("Lock count", lockCount.toString(), Modifier.weight(1f), AlertOrange)
                MetricBubble("Last lock", GuardRules.formatTimestamp(lastLock), Modifier.weight(1f), AlertRed)
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricBubble("Last unlock", GuardRules.formatTimestamp(lastUnlock), Modifier.weight(1f), SuccessMint)
                MetricBubble("Last reasons", if (lastReason.isBlank()) "—" else lastReason, Modifier.weight(1f), IceBlue)
            }
        }
    }

    @Composable
    private fun ToggleRow(
        title: String,
        subtitle: String,
        checked: Boolean,
        onChecked: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp, lineHeight = 18.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = IceBlue,
                    uncheckedThumbColor = Color.White.copy(alpha = 0.9f),
                    uncheckedTrackColor = Color.White.copy(alpha = 0.16f)
                )
            )
        }
    }

    @Composable
    private fun PresetRow(
        onStudent: () -> Unit,
        onNight: () -> Unit,
        onFocus: () -> Unit
    ) {
        Column {
            Text("Presets", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill("Student", accent = AuroraBlue, onClick = onStudent)
                GlassPill("Night discipline", accent = AlertOrange, onClick = onNight)
                GlassPill("Focus", accent = SuccessMint, onClick = onFocus)
            }
        }
    }

    @Composable
    private fun WindowEditor(
        title: String,
        window: TimeWindow,
        accent: Color,
        onToggle: (Boolean) -> Unit,
        onStart: () -> Unit,
        onEnd: () -> Unit
    ) {
        GlassCard(accent = accent) {
            ToggleRow(
                title = title,
                subtitle = if (window.enabled) "${GuardRules.formatMinutes(window.startMinutes)} → ${GuardRules.formatMinutes(window.endMinutes)}" else "Disabled",
                checked = window.enabled,
                onChecked = onToggle
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onStart,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.10f),
                        contentColor = Color.White
                    )
                ) {
                    Text("Start • ${GuardRules.formatMinutes(window.startMinutes)}")
                }
                FilledTonalButton(
                    onClick = onEnd,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.08f),
                        contentColor = Color.White
                    )
                ) {
                    Text("End • ${GuardRules.formatMinutes(window.endMinutes)}")
                }
            }
        }
    }

    @Composable
    private fun PermissionTile(
        title: String,
        subtitle: String,
        granted: Boolean,
        optional: Boolean,
        onGrant: () -> Unit
    ) {
        GlassCard(accent = if (granted) SuccessMint else if (optional) IceBlue else AlertOrange) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                        if (optional) GlassPill("optional", accent = Color.White, active = false)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(subtitle, color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp, lineHeight = 18.sp)
                }
                if (granted) {
                    GlassPill("Ready", accent = SuccessMint)
                } else {
                    FilledTonalButton(
                        onClick = onGrant,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Grant")
                    }
                }
            }
        }
    }

    @Composable
    private fun DiagnosticRow(label: String, active: Boolean) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassToggleDot(active = active, color = if (active) SuccessMint else AlertRed)
                Text(if (active) "Ready" else "Missing", color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    private fun getBatteryLevel(): Int {
        return try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (cap >= 0) cap else 0
        } catch (_: Exception) {
            DeviceTools.getBatteryLevel(this)
        }
    }
}
