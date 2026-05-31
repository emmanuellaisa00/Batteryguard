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
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.CompositionLocalProvider
import androidx.fragment.app.FragmentActivity
import com.laisadevstudio.batteryguard.ui.LocalUiAccessibility
import com.laisadevstudio.batteryguard.ui.UiAccessibilityState
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

private enum class HomeTab(val title: String) {
    DASHBOARD("Dashboard"),
    BATTERY("Battery"),
    KEEPSAFE("KeepSafe"),
    SECURITY("Emergency"),
    DIAGNOSTICS("Diagnostics")
}

class MainActivity : FragmentActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private var permRefreshTick by mutableIntStateOf(0)
    private lateinit var settingsPrompt: BiometricPrompt
    private lateinit var settingsPromptInfo: BiometricPrompt.PromptInfo
    private val settingsAuthenticators: Int by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
    }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permRefreshTick++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        window.statusBarColor = android.graphics.Color.parseColor("#0B1A29")
        window.navigationBarColor = android.graphics.Color.parseColor("#0B1A29")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        try {
            startForegroundService(Intent(this, BatteryGuardService::class.java))
        } catch (_: Exception) {
            showToast("Could not start BatteryGuard service")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setupSettingsPrompt()

        setContent {
            val reducedTransparency = remember { mutableStateOf(AppPrefs.isReducedTransparencyEnabled(this@MainActivity)) }
            BatteryGuardTheme {
                CompositionLocalProvider(
                    LocalUiAccessibility provides UiAccessibilityState(
                        reduceTransparency = reducedTransparency.value
                    )
                ) {
                    MainScreen(
                        reducedTransparency = reducedTransparency.value,
                        onReducedTransparencyChanged = {
                            reducedTransparency.value = it
                            AppPrefs.setReducedTransparencyEnabled(this@MainActivity, it)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permRefreshTick++
    }

    private fun setupSettingsPrompt() {
        settingsPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    AppPrefs.startSettingsUnlock(this@MainActivity)
                    permRefreshTick++
                    showToast("Settings unlocked for 5 minutes")
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
        settingsPromptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock BatteryGuard settings")
            .setSubtitle("Authenticate to change protection rules and schedules")
            .setConfirmationRequired(false)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(settingsAuthenticators)
                } else {
                    setNegativeButtonText("Cancel")
                }
            }
            .build()
    }

    private fun requestSettingsUnlock() {
        val status = BiometricManager.from(this).canAuthenticate(settingsAuthenticators)
        if (status == BiometricManager.BIOMETRIC_SUCCESS) {
            settingsPrompt.authenticate(settingsPromptInfo)
        } else {
            showToast("Biometric or device credential unavailable")
        }
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
        } catch (_: Exception) {
        }
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
    private fun MainScreen(
        reducedTransparency: Boolean,
        onReducedTransparencyChanged: (Boolean) -> Unit
    ) {
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
        val settingsUnlockRemaining = remember { mutableLongStateOf(AppPrefs.getSettingsUnlockRemainingMs(this)) }
        val underlyingReasons = remember { mutableStateOf(BatteryGuardService.underlyingReasonsLive.toList()) }

        LaunchedEffect(Unit) {
            while (true) {
                battery.intValue = getBatteryLevel()
                charging.value = BatteryGuardService.isCharging
                usageToday.intValue = AppPrefs.getTodayUsageMinutes(this@MainActivity)
                activeReasons.value = BatteryGuardService.activeReasons.toList()
                underlyingReasons.value = BatteryGuardService.underlyingReasonsLive.toList()
                bypassRemaining.longValue = AppPrefs.getEmergencyBypassRemainingMs(this@MainActivity)
                settingsUnlockRemaining.longValue = AppPrefs.getSettingsUnlockRemainingMs(this@MainActivity)
                network.value = DeviceTools.getNetworkSnapshot(this@MainActivity)
                delay(1_000L)
            }
        }

        val batterySettingsLocked = remember(battery.intValue, activeReasons.value) {
            AppPrefs.isBatterySettingsLocked(this@MainActivity) || GuardReason.LOW_BATTERY in activeReasons.value
        }
        val dangerousSettingsLocked = batterySettingsLocked
        val batteryTarget = GuardRules.batteryUnlockTarget(this)
        val protectionStrength = GuardRules.protectionStrength(isAdmin, hasOverlay, hasA11y, isDeviceOwner)
        val usageProgress = if (dailyLimit.intValue > 0) {
            usageToday.intValue.toFloat() / dailyLimit.intValue.toFloat()
        } else 0f
        val emergencyState = remember(battery.intValue, underlyingReasons.value, bypassRemaining.longValue) {
            GuardRules.emergencyPassState(this@MainActivity, battery.intValue, underlyingReasons.value.toSet())
        }
        val currentSessionId = AppPrefs.getCurrentLockSessionId(this)
        val appLockActive = remember(currentSessionId, underlyingReasons.value, bypassRemaining.longValue) {
            currentSessionId != 0L && (underlyingReasons.value.isNotEmpty() || bypassRemaining.longValue > 0L)
        }
        val settingsUnlocked = remember(settingsUnlockRemaining.longValue) {
            settingsUnlockRemaining.longValue > 0L
        }
        var showOnboarding by remember { mutableStateOf(!AppPrefs.isOnboardingDone(this@MainActivity)) }
        var selectedScreen by remember { mutableStateOf(HomeTab.DASHBOARD) }

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
            Column(modifier = Modifier.fillMaxSize()) {
                AppMenuBar(
                    selectedScreen = selectedScreen,
                    charging = charging.value,
                    onSelect = { selectedScreen = it }
                )
                Crossfade(
                    targetState = when {
                        appLockActive -> "lock"
                        showOnboarding -> "onboarding"
                        else -> "content"
                    },
                    label = "main_state"
                ) { state ->
                    when (state) {
                        "lock" -> AppSelfProtectionScreen(
                            reasons = underlyingReasons.value,
                            bypassRemainingMs = bypassRemaining.longValue,
                            battery = battery.intValue,
                            unlockFloor = GuardRules.batteryUnlockTarget(this@MainActivity)
                        )
                        "onboarding" -> OnboardingScreen(
                            reduceTransparency = reducedTransparency,
                            onReducedTransparencyChanged = onReducedTransparencyChanged,
                            onContinue = {
                                AppPrefs.setOnboardingDone(this@MainActivity, true)
                                showOnboarding = false
                            }
                        )
                        else -> MainPage(
                            tab = selectedScreen,
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
                            isAdmin = isAdmin,
                            hasOverlay = hasOverlay,
                            hasA11y = hasA11y,
                            hasNotifPerm = hasNotifPerm,
                            ignoresBatteryOpt = ignoresBatteryOpt,
                            isDeviceOwner = isDeviceOwner,
                            isDefaultHome = isDefaultHome,
                            batteryEnabled = batteryEnabled.value,
                            scheduleEnabled = scheduleEnabled.value,
                            threshold = threshold.intValue,
                            warnMargin = warnMargin.intValue,
                            window1 = window1.value,
                            window2 = window2.value,
                            bedtime = bedtime.value,
                            dangerousSettingsLocked = dangerousSettingsLocked || !settingsUnlocked,
                            batterySettingsLocked = batterySettingsLocked,
                            emergencyState = emergencyState,
                            currentSessionId = currentSessionId,
                            onPreview = { launchPreviewOverlay() },
                            onResetUsage = {
                                AppPrefs.resetUsageToday(this@MainActivity)
                                usageToday.intValue = 0
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            },
                            onAdmin = { openDeviceAdminSettings() },
                            onOverlay = { openOverlaySettings() },
                            onA11y = { openAccessibilitySettings() },
                            onNotif = { openNotificationPermission() },
                            onBatteryOpt = { openBatteryOptimizationSettings() },
                            onProtectionEnabled = {
                                protectionEnabled.value = it
                                AppPrefs.setProtectionEnabled(this@MainActivity, it)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            },
                            onBatteryEnabled = {
                                batteryEnabled.value = it
                                AppPrefs.setBatteryProtectionEnabled(this@MainActivity, it)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            },
                            onScheduleEnabled = {
                                scheduleEnabled.value = it
                                AppPrefs.setScheduleProtectionEnabled(this@MainActivity, it)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            },
                            onDailyLimitEnabled = {
                                dailyLimitEnabled.value = it
                                AppPrefs.setDailyUsageLimitEnabled(this@MainActivity, it)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            },
                            onThresholdChanged = {
                                threshold.intValue = it
                                AppPrefs.setThreshold(this@MainActivity, it)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            },
                            onWarnMarginChanged = {
                                warnMargin.intValue = it
                                AppPrefs.setWarnMargin(this@MainActivity, it)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            },
                            onDailyLimitChanged = {
                                dailyLimit.intValue = it
                                AppPrefs.setDailyUsageLimitMinutes(this@MainActivity, it)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            },
                            onWindow1Changed = {
                                window1.value = it
                                AppPrefs.setWindow1(this@MainActivity, it)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            },
                            onWindow2Changed = {
                                window2.value = it
                                AppPrefs.setWindow2(this@MainActivity, it)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            },
                            onBedtimeChanged = {
                                bedtime.value = it
                                AppPrefs.setBedtimeWindow(this@MainActivity, it)
                                BatteryGuardService.requestRefresh(this@MainActivity)
                            },
                            onPickTime = { initial, callback -> pickTime(initial, callback) },
                            settingsUnlocked = settingsUnlocked,
                            settingsUnlockRemainingMs = settingsUnlockRemaining.longValue,
                            onRequestSettingsUnlock = { requestSettingsUnlock() },
                            reducedTransparency = reducedTransparency,
                            onReducedTransparencyChanged = onReducedTransparencyChanged
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun MainPage(
        tab: HomeTab,
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
        isAdmin: Boolean,
        hasOverlay: Boolean,
        hasA11y: Boolean,
        hasNotifPerm: Boolean,
        ignoresBatteryOpt: Boolean,
        isDeviceOwner: Boolean,
        isDefaultHome: Boolean,
        batteryEnabled: Boolean,
        scheduleEnabled: Boolean,
        threshold: Int,
        warnMargin: Int,
        window1: TimeWindow,
        window2: TimeWindow,
        bedtime: TimeWindow,
        dangerousSettingsLocked: Boolean,
        batterySettingsLocked: Boolean,
        emergencyState: EmergencyPassState,
        currentSessionId: Long,
        onPreview: () -> Unit,
        onResetUsage: () -> Unit,
        onAdmin: () -> Unit,
        onOverlay: () -> Unit,
        onA11y: () -> Unit,
        onNotif: () -> Unit,
        onBatteryOpt: () -> Unit,
        onProtectionEnabled: (Boolean) -> Unit,
        onBatteryEnabled: (Boolean) -> Unit,
        onScheduleEnabled: (Boolean) -> Unit,
        onDailyLimitEnabled: (Boolean) -> Unit,
        onThresholdChanged: (Int) -> Unit,
        onWarnMarginChanged: (Int) -> Unit,
        onDailyLimitChanged: (Int) -> Unit,
        onWindow1Changed: (TimeWindow) -> Unit,
        onWindow2Changed: (TimeWindow) -> Unit,
        onBedtimeChanged: (TimeWindow) -> Unit,
        onPickTime: (Int, (Int) -> Unit) -> Unit,
        settingsUnlocked: Boolean,
        settingsUnlockRemainingMs: Long,
        onRequestSettingsUnlock: () -> Unit,
        reducedTransparency: Boolean,
        onReducedTransparencyChanged: (Boolean) -> Unit
    ) {
        PageContainer {
            when (tab) {
                HomeTab.DASHBOARD -> {
                    HeroDashboard(
                        battery = battery,
                        charging = charging,
                        protectionEnabled = protectionEnabled,
                        protectionStrength = protectionStrength,
                        usageToday = usageToday,
                        usageLimit = usageLimit,
                        dailyLimitEnabled = dailyLimitEnabled,
                        activeReasons = activeReasons,
                        bypassRemainingMs = bypassRemainingMs,
                        networkLabel = networkLabel,
                        onPreview = onPreview,
                        onResetUsage = onResetUsage
                    )
                    LockSnapshotCard(
                        activeReasons = activeReasons,
                        battery = battery,
                        sessionId = currentSessionId,
                        batterySettingsLocked = batterySettingsLocked
                    )
                    PermissionSection(
                        isAdmin = isAdmin,
                        hasOverlay = hasOverlay,
                        hasA11y = hasA11y,
                        hasNotifPerm = hasNotifPerm,
                        ignoresBatteryOpt = ignoresBatteryOpt,
                        onAdmin = onAdmin,
                        onOverlay = onOverlay,
                        onA11y = onA11y,
                        onNotif = onNotif,
                        onBatteryOpt = onBatteryOpt
                    )
                }
                HomeTab.BATTERY -> {
                    if (!settingsUnlocked) {
                        SettingsUnlockCard(
                            remainingMs = settingsUnlockRemainingMs,
                            onUnlock = onRequestSettingsUnlock
                        )
                    }
                    if (batterySettingsLocked) {
                        BatteryLockShieldCard(
                            battery = battery,
                            thresholdAtLock = AppPrefs.getBatteryThresholdAtLock(this@MainActivity),
                            unlockFloor = AppPrefs.getBatteryUnlockFloorAtLock(this@MainActivity)
                        )
                    }
                    BatteryControlCard(
                        protectionEnabled = protectionEnabled,
                        batteryEnabled = batteryEnabled,
                        threshold = threshold,
                        warnMargin = warnMargin,
                        battery = battery,
                        unlockFloor = GuardRules.batteryUnlockTarget(this@MainActivity),
                        enabled = !dangerousSettingsLocked,
                        onProtectionEnabled = onProtectionEnabled,
                        onBatteryEnabled = onBatteryEnabled,
                        onThresholdChanged = onThresholdChanged,
                        onWarnMarginChanged = onWarnMarginChanged
                    )
                    LockRuleCard()
                }
                HomeTab.KEEPSAFE -> {
                    if (!settingsUnlocked) {
                        SettingsUnlockCard(
                            remainingMs = settingsUnlockRemainingMs,
                            onUnlock = onRequestSettingsUnlock
                        )
                    }
                    if (dangerousSettingsLocked) {
                        SessionLockedBanner(
                            title = "KeepSafe settings paused",
                            subtitle = "BatteryGuard is in a battery-lock session. Schedule and limit controls become editable again after the battery rule clears."
                        )
                    }
                    KeepSafeCard(
                        scheduleEnabled = scheduleEnabled,
                        dailyLimitEnabled = dailyLimitEnabled,
                        dailyLimit = usageLimit,
                        usageToday = usageToday,
                        window1 = window1,
                        window2 = window2,
                        bedtime = bedtime,
                        enabled = !dangerousSettingsLocked,
                        onScheduleEnabled = onScheduleEnabled,
                        onDailyLimitEnabled = onDailyLimitEnabled,
                        onDailyLimitChanged = onDailyLimitChanged,
                        onWindow1Changed = onWindow1Changed,
                        onWindow2Changed = onWindow2Changed,
                        onBedtimeChanged = onBedtimeChanged,
                        onPickTime = onPickTime
                    )
                }
                HomeTab.SECURITY -> {
                    EmergencyPolicyCard(
                        battery = battery,
                        activeReasons = activeReasons,
                        emergencyState = emergencyState,
                        bypassRemainingMs = bypassRemainingMs,
                        onPreview = onPreview
                    )
                    LockRuleCard()
                }
                HomeTab.DIAGNOSTICS -> {
                    DiagnosticsSection(
                        isAdmin = isAdmin,
                        hasOverlay = hasOverlay,
                        hasA11y = hasA11y,
                        hasNotif = hasNotifPerm,
                        deviceOwner = isDeviceOwner,
                        isDefaultHome = isDefaultHome,
                        ignoresBatteryOpt = ignoresBatteryOpt,
                        guardActive = BatteryGuardService.isGuardActive,
                        activeReasons = activeReasons,
                        lockCount = AppPrefs.getLockCount(this@MainActivity),
                        lastLock = AppPrefs.getLastLockAt(this@MainActivity),
                        lastUnlock = AppPrefs.getLastUnlockAt(this@MainActivity),
                        lastReason = AppPrefs.getLastLockReasons(this@MainActivity)
                    )
                    AppearanceCard(
                        reducedTransparency = reducedTransparency,
                        onReducedTransparencyChanged = onReducedTransparencyChanged
                    )
                }
            }
        }
    }

    @Composable
    private fun AppSelfProtectionScreen(
        reasons: List<GuardReason>,
        bypassRemainingMs: Long,
        battery: Int,
        unlockFloor: Int
    ) {
        PageContainer {
            GlassCard(accent = AlertRed, stronger = true) {
                GlassSectionTitle(
                    title = "BatteryGuard is protecting its own settings",
                    subtitle = "When BatteryGuard has an active lock session, the app itself becomes read-only. This prevents emergency pass or temporary access from weakening protection while the lock is still in force."
                )
                Spacer(Modifier.height(12.dp))
                Text("Device locked. Please clear the active lock conditions before changing settings.", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricBubble("Battery", "$battery%", Modifier.weight(1f), if (battery < 10) AlertRed else IceBlue)
                    MetricBubble("Unlock floor", "$unlockFloor%", Modifier.weight(1f), SuccessMint)
                }
                if (reasons.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    reasons.forEach {
                        Text("• ${GuardRules.reasonLabel(it)} — ${GuardRules.reasonDetail(this@MainActivity, it)}", color = Color.White.copy(alpha = 0.74f), fontSize = 13.sp, lineHeight = 20.sp)
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (bypassRemainingMs > 0L) {
                    Spacer(Modifier.height(12.dp))
                    GlassPill("Emergency pass active • ${DeviceTools.formatDurationCompact(bypassRemainingMs)} left", accent = SuccessMint)
                }
            }
        }
    }

    @Composable
    private fun OnboardingScreen(
        reduceTransparency: Boolean,
        onReducedTransparencyChanged: (Boolean) -> Unit,
        onContinue: () -> Unit
    ) {
        PageContainer {
            GlassCard(accent = IceBlue, stronger = true) {
                GlassSectionTitle(
                    title = "Welcome to BatteryGuard",
                    subtitle = "A simpler interface, stricter rules, and protected settings."
                )
                Spacer(Modifier.height(12.dp))
                Text("• Liquid glass is restrained to key surfaces so content stays primary.", color = Color.White.copy(alpha = 0.74f), fontSize = 13.sp)
                Text("• Emergency pass never overrides battery lock and never works below 10%.", color = Color.White.copy(alpha = 0.74f), fontSize = 13.sp)
                Text("• When BatteryGuard is actively locking the phone, the app becomes read-only.", color = Color.White.copy(alpha = 0.74f), fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = "Reduced transparency",
                    subtitle = "Use a more solid appearance for easier readability.",
                    checked = reduceTransparency,
                    onChecked = onReducedTransparencyChanged
                )
                Spacer(Modifier.height(14.dp))
                FilledTonalButton(
                    onClick = onContinue,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = IceBlue.copy(alpha = 0.22f),
                        contentColor = Color.White
                    )
                ) { Text("Continue") }
            }
        }
    }

    @Composable
    private fun SettingsUnlockCard(
        remainingMs: Long,
        onUnlock: () -> Unit
    ) {
        GlassCard(accent = SuccessMint) {
            GlassSectionTitle(
                title = if (remainingMs > 0L) "Settings unlocked" else "Unlock critical settings",
                subtitle = if (remainingMs > 0L) "Critical settings stay open briefly, then lock again automatically."
                else "Authenticate before changing battery protection or schedule rules."
            )
            Spacer(Modifier.height(12.dp))
            if (remainingMs > 0L) {
                GlassPill("Unlocked for ${DeviceTools.formatDurationCompact(remainingMs)}", accent = SuccessMint)
            } else {
                FilledTonalButton(
                    onClick = onUnlock,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = SuccessMint.copy(alpha = 0.20f),
                        contentColor = Color.White
                    )
                ) { Text("Unlock settings") }
            }
        }
    }

    @Composable
    private fun AppearanceCard(
        reducedTransparency: Boolean,
        onReducedTransparencyChanged: (Boolean) -> Unit
    ) {
        GlassCard(accent = IceBlue) {
            GlassSectionTitle(
                title = "Accessibility & appearance",
                subtitle = "Liquid glass should stay readable. Turn on reduced transparency for a calmer, more solid surface treatment."
            )
            Spacer(Modifier.height(12.dp))
            ToggleRow(
                title = "Reduced transparency",
                subtitle = "Swap the translucent glass look for a stronger solid background.",
                checked = reducedTransparency,
                onChecked = onReducedTransparencyChanged
            )
        }
    }

    @Composable
    private fun AppMenuBar(
        selectedScreen: HomeTab,
        charging: Boolean,
        onSelect: (HomeTab) -> Unit
    ) {
        var expanded by remember { mutableStateOf(false) }
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            accent = IceBlue,
            stronger = true
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("BatteryGuard", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${selectedScreen.title} • ${if (charging) "charging" else "ready"}",
                        color = Color.White.copy(alpha = 0.56f),
                        fontSize = 12.sp
                    )
                }
                Box {
                    GlassPill(
                        text = "Menu",
                        accent = IceBlue,
                        onClick = { expanded = true }
                    )
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        HomeTab.entries.forEach { screen ->
                            DropdownMenuItem(
                                text = { Text(screen.title) },
                                onClick = {
                                    expanded = false
                                    onSelect(screen)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PageContainer(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            content()
            Spacer(Modifier.height(24.dp))
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
            Text("Command center", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Battery status, protection state, and today’s limits.",
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
            if (activeReasons.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Active lock reasons", color = Color.White.copy(alpha = 0.86f), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeReasons.forEach { reason ->
                        GlassPill(GuardRules.reasonShort(reason), accent = AlertOrange)
                    }
                }
            }
            if (bypassRemainingMs > 0L) {
                Spacer(Modifier.height(12.dp))
                GlassPill(
                    text = "Emergency pass live • ${DeviceTools.formatDurationCompact(bypassRemainingMs)} left",
                    accent = SuccessMint
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onPreview,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.14f),
                        contentColor = Color.White
                    )
                ) { Text("Preview lock") }
                FilledTonalButton(
                    onClick = onResetUsage,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White.copy(alpha = 0.10f),
                        contentColor = Color.White
                    )
                ) { Text("Reset usage") }
            }
        }
    }

    @Composable
    private fun LockSnapshotCard(
        activeReasons: List<GuardReason>,
        battery: Int,
        sessionId: Long,
        batterySettingsLocked: Boolean
    ) {
        GlassCard(accent = AlertOrange) {
            GlassSectionTitle(
                title = "Current lock logic",
                subtitle = "Low battery always has priority. Emergency pass never overrides it."
            )
            Spacer(Modifier.height(12.dp))
            if (activeReasons.isEmpty()) {
                Text("No active lock reasons right now.", color = Color.White.copy(alpha = 0.64f), fontSize = 13.sp)
            } else {
                activeReasons.forEach { reason ->
                    Text("• ${GuardRules.reasonLabel(reason)} — ${GuardRules.reasonDetail(this@MainActivity, reason)}", color = Color.White.copy(alpha = 0.74f), fontSize = 13.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricBubble("Battery now", "$battery%", Modifier.weight(1f), if (battery < 10) AlertRed else IceBlue)
                MetricBubble("Lock session", if (sessionId == 0L) "None" else sessionId.toString().takeLast(6), Modifier.weight(1f), AuroraBlue)
            }
            if (batterySettingsLocked) {
                Spacer(Modifier.height(12.dp))
                GlassPill("Battery session active • battery settings locked", accent = AlertOrange)
            }
        }
    }

    @Composable
    private fun BatteryControlCard(
        protectionEnabled: Boolean,
        batteryEnabled: Boolean,
        threshold: Int,
        warnMargin: Int,
        battery: Int,
        unlockFloor: Int,
        enabled: Boolean,
        onProtectionEnabled: (Boolean) -> Unit,
        onBatteryEnabled: (Boolean) -> Unit,
        onThresholdChanged: (Int) -> Unit,
        onWarnMarginChanged: (Int) -> Unit
    ) {
        GlassCard(accent = IceBlue, stronger = true) {
            GlassSectionTitle(
                title = "Battery protection",
                subtitle = "Once battery lock starts, the unlock floor stays fixed for that session."
            )
            Spacer(Modifier.height(14.dp))
            if (!enabled) {
                SessionLockedBanner(
                    title = "Battery settings are read-only",
                    subtitle = "An active battery-lock session is in progress. Thresholds and protection toggles unlock only after the battery rule clears."
                )
                Spacer(Modifier.height(12.dp))
            }
            ToggleRow(
                title = "Master protection",
                subtitle = "Turn all protection rules on or off",
                checked = protectionEnabled,
                enabled = enabled,
                onChecked = onProtectionEnabled
            )
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                title = "Battery lock engine",
                subtitle = "Monitor battery and enforce low-battery lock",
                checked = batteryEnabled,
                enabled = enabled,
                onChecked = onBatteryEnabled
            )
            Spacer(Modifier.height(14.dp))
            DisabledableContent(enabled = enabled) {
                Text("Lock threshold • $threshold%", color = Color.White, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = threshold.toFloat(),
                    onValueChange = { onThresholdChanged(it.toInt()) },
                    enabled = enabled,
                    valueRange = 5f..80f,
                    steps = 14,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = IceBlue,
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                    )
                )
                Text(
                    "Current battery: $battery%. Battery lock triggers below this threshold, but release requires $unlockFloor%+.",
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(12.dp))
                Text("Warning margin • $warnMargin%", color = Color.White, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = warnMargin.toFloat(),
                    onValueChange = { onWarnMarginChanged(it.toInt()) },
                    enabled = enabled,
                    valueRange = 3f..20f,
                    steps = 16,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = AuroraBlue,
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                    )
                )
                Spacer(Modifier.height(12.dp))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricBubble("Unlock floor", "$unlockFloor%", Modifier.weight(1f), SuccessMint)
                MetricBubble("Warns at", "${threshold + warnMargin}%", Modifier.weight(1f), AlertOrange)
            }
        }
    }

    @Composable
    private fun BatteryLockShieldCard(
        battery: Int,
        thresholdAtLock: Int,
        unlockFloor: Int
    ) {
        GlassCard(accent = AlertRed, stronger = true) {
            GlassSectionTitle(
                title = "Battery lock session latched",
                subtitle = "This protects against using emergency pass or temporary access to reduce the threshold and escape the current battery lock."
            )
            Spacer(Modifier.height(12.dp))
            Text("• Battery now: $battery%", color = Color.White.copy(alpha = 0.76f), fontSize = 13.sp)
            Text("• Threshold at lock: ${thresholdAtLock.coerceAtLeast(5)}%", color = Color.White.copy(alpha = 0.76f), fontSize = 13.sp)
            Text("• Unlock floor for this session: ${unlockFloor.coerceAtLeast(20)}%", color = Color.White.copy(alpha = 0.76f), fontSize = 13.sp)
        }
    }

    @Composable
    private fun KeepSafeCard(
        scheduleEnabled: Boolean,
        dailyLimitEnabled: Boolean,
        dailyLimit: Int,
        usageToday: Int,
        window1: TimeWindow,
        window2: TimeWindow,
        bedtime: TimeWindow,
        enabled: Boolean,
        onScheduleEnabled: (Boolean) -> Unit,
        onDailyLimitEnabled: (Boolean) -> Unit,
        onDailyLimitChanged: (Int) -> Unit,
        onWindow1Changed: (TimeWindow) -> Unit,
        onWindow2Changed: (TimeWindow) -> Unit,
        onBedtimeChanged: (TimeWindow) -> Unit,
        onPickTime: (Int, (Int) -> Unit) -> Unit
    ) {
        GlassCard(accent = AuroraBlue, stronger = true) {
            GlassSectionTitle(
                title = "KeepSafe time control",
                subtitle = "Set allowed windows, bedtime, and a daily phone-time budget."
            )
            Spacer(Modifier.height(14.dp))
            ToggleRow(
                title = "Schedule lock",
                subtitle = "Only allow use during your safe windows",
                checked = scheduleEnabled,
                enabled = enabled,
                onChecked = onScheduleEnabled
            )
            Spacer(Modifier.height(12.dp))
            WindowEditor(
                title = "Allowed window 1",
                window = window1,
                accent = IceBlue,
                enabled = enabled,
                onToggle = { onWindow1Changed(window1.copy(enabled = it)) },
                onStart = {
                    onPickTime(window1.startMinutes) { picked -> onWindow1Changed(window1.copy(startMinutes = picked)) }
                },
                onEnd = {
                    onPickTime(window1.endMinutes) { picked -> onWindow1Changed(window1.copy(endMinutes = picked)) }
                }
            )
            Spacer(Modifier.height(10.dp))
            WindowEditor(
                title = "Allowed window 2",
                window = window2,
                accent = AuroraBlue,
                enabled = enabled,
                onToggle = { onWindow2Changed(window2.copy(enabled = it)) },
                onStart = {
                    onPickTime(window2.startMinutes) { picked -> onWindow2Changed(window2.copy(startMinutes = picked)) }
                },
                onEnd = {
                    onPickTime(window2.endMinutes) { picked -> onWindow2Changed(window2.copy(endMinutes = picked)) }
                }
            )
            Spacer(Modifier.height(10.dp))
            WindowEditor(
                title = "Bedtime / focus lock",
                window = bedtime,
                accent = AlertOrange,
                enabled = enabled,
                onToggle = { onBedtimeChanged(bedtime.copy(enabled = it)) },
                onStart = {
                    onPickTime(bedtime.startMinutes) { picked -> onBedtimeChanged(bedtime.copy(startMinutes = picked)) }
                },
                onEnd = {
                    onPickTime(bedtime.endMinutes) { picked -> onBedtimeChanged(bedtime.copy(endMinutes = picked)) }
                }
            )
            Spacer(Modifier.height(14.dp))
            ToggleRow(
                title = "Daily screen-time limit",
                subtitle = "Lock after your allowed phone time is used up",
                checked = dailyLimitEnabled,
                enabled = enabled,
                onChecked = onDailyLimitEnabled
            )
            Spacer(Modifier.height(10.dp))
            DisabledableContent(enabled = enabled) {
                Text("Daily limit • $dailyLimit min", color = Color.White, fontWeight = FontWeight.SemiBold)
                Slider(
                    value = dailyLimit.toFloat(),
                    onValueChange = { onDailyLimitChanged(it.toInt()) },
                    enabled = enabled,
                    valueRange = 15f..720f,
                    steps = 46,
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = SuccessMint,
                        inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                    )
                )
                Text(
                    "Today used $usageToday of $dailyLimit minutes",
                    color = Color.White.copy(alpha = 0.60f),
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                LiquidProgress(progress = usageToday.toFloat() / dailyLimit.toFloat(), accent = SuccessMint)
            }
        }
    }

    @Composable
    private fun EmergencyPolicyCard(
        battery: Int,
        activeReasons: List<GuardReason>,
        emergencyState: EmergencyPassState,
        bypassRemainingMs: Long,
        onPreview: () -> Unit
    ) {
        GlassCard(accent = SuccessMint, stronger = true) {
            GlassSectionTitle(
                title = "Emergency policy",
                subtitle = "Emergency pass is for non-battery locks only. One per session, two per day."
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricBubble("Battery", "$battery%", Modifier.weight(1f), if (battery < 10) AlertRed else IceBlue)
                MetricBubble(
                    "Passes left",
                    emergencyState.remainingToday.toString(),
                    Modifier.weight(1f),
                    if (emergencyState.allowed) SuccessMint else AlertOrange
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricBubble("Used today", emergencyState.usedToday.toString(), Modifier.weight(1f), AuroraBlue)
                MetricBubble(
                    "Mode",
                    if (bypassRemainingMs > 0L) "Pass active" else if (emergencyState.allowed) "Allowed" else "Tools only",
                    Modifier.weight(1f),
                    if (emergencyState.allowed) SuccessMint else AlertOrange
                )
            }
            Spacer(Modifier.height(12.dp))
            GlassPill(text = emergencyState.title, accent = if (emergencyState.allowed) SuccessMint else AlertOrange)
            Spacer(Modifier.height(10.dp))
            Text(emergencyState.detail, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(12.dp))
            if (activeReasons.isNotEmpty()) {
                Text("Current lock reasons", color = Color.White.copy(alpha = 0.82f), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeReasons.forEach { GlassPill(GuardRules.reasonShort(it), accent = AlertOrange) }
                }
                Spacer(Modifier.height(10.dp))
            }
            Text("• Emergency pass is blocked below 10% battery.", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
            Text("• If battery lock is active, other locks cannot use emergency pass either.", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
            Text("• During battery lock, settings become read-only for that session.", color = Color.White.copy(alpha = 0.70f), fontSize = 12.sp)
            Spacer(Modifier.height(14.dp))
            FilledTonalButton(
                onClick = onPreview,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White
                )
            ) { Text("Preview lock screen") }
        }
    }

    @Composable
    private fun LockRuleCard() {
        GlassCard(accent = AlertOrange) {
            GlassSectionTitle(
                title = "Final lock rules",
                subtitle = "This is the production policy BatteryGuard now follows."
            )
            Spacer(Modifier.height(12.dp))
            Text("• Low battery is the highest-priority lock.", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
            Text("• Emergency pass never overrides battery lock.", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
            Text("• Below 10% battery, BatteryGuard switches to emergency-tools-only mode.", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
            Text("• One emergency pass is allowed per lock session.", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
            Text("• Only two emergency passes are allowed per day.", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
            Text("• Battery threshold changes do not weaken a battery-lock session once it starts.", color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp)
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
                subtitle = "Service state, permissions, launcher role, and lock history."
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
        enabled: Boolean = true,
        onChecked: (Boolean) -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.55f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp, lineHeight = 18.sp)
            }
            Switch(
                checked = checked,
                enabled = enabled,
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
    private fun WindowEditor(
        title: String,
        window: TimeWindow,
        accent: Color,
        enabled: Boolean,
        onToggle: (Boolean) -> Unit,
        onStart: () -> Unit,
        onEnd: () -> Unit
    ) {
        GlassCard(accent = accent) {
            ToggleRow(
                title = title,
                subtitle = if (window.enabled) "${GuardRules.formatMinutes(window.startMinutes)} → ${GuardRules.formatMinutes(window.endMinutes)}" else "Disabled",
                checked = window.enabled,
                enabled = enabled,
                onChecked = onToggle
            )
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = onStart,
                    enabled = enabled,
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
                    enabled = enabled,
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

    @Composable
    private fun SessionLockedBanner(title: String, subtitle: String) {
        GlassCard(accent = AlertOrange) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color.White.copy(alpha = 0.68f), fontSize = 12.sp, lineHeight = 18.sp)
        }
    }

    @Composable
    private fun DisabledableContent(enabled: Boolean, content: @Composable ColumnScope.() -> Unit) {
        Column(modifier = Modifier.alpha(if (enabled) 1f else 0.55f), content = content)
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
