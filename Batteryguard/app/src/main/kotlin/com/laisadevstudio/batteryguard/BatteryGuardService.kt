package com.laisadevstudio.batteryguard

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class BatteryGuardService : Service() {

    companion object {
        const val CHANNEL_ID = "battery_guard_channel"
        const val WARN_CHANNEL = "battery_warn_channel"
        const val NOTIFICATION_ID = 1001
        const val WARN_NOTIF_ID = 1002
        const val TAG = "BatteryGuardService"
        const val ACTION_REFRESH = "com.laisadevstudio.batteryguard.REFRESH"

        @Volatile var isGuardActive = false
        @Volatile var isCharging = false
        @Volatile var currentBatteryLevel = 100
        @Volatile var activeReasons: Set<GuardReason> = emptySet()

        fun requestRefresh(context: Context) {
            try {
                val intent = Intent(context, BatteryGuardService::class.java).setAction(ACTION_REFRESH)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "requestRefresh failed: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastUsageTickMs = 0L
    private var warningFired = false
    private var underlyingReasons: Set<GuardReason> = emptySet()
    private val bypassExpiryRunnable = Runnable {
        evaluateGuardState("bypassExpiry")
    }

    private val pulseRunnable = object : Runnable {
        override fun run() {
            updateUsageClock()
            evaluateGuardState("pulse")
            handler.postDelayed(this, 5_000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level < 0 || scale <= 0) return

            currentBatteryLevel = level * 100 / scale
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            evaluateGuardState("battery")
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            evaluateGuardState("userPresent")
            if (isGuardActive) launchOverlay(context)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    lastUsageTickMs = System.currentTimeMillis()
                    handler.postDelayed({
                        evaluateGuardState("screenOn")
                        if (isGuardActive) tryLaunchOverlayIfUnlocked(context)
                    }, 350L)
                }
                Intent.ACTION_SCREEN_OFF -> lastUsageTickMs = System.currentTimeMillis()
            }
        }
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            evaluateGuardState(intent.action ?: "time")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForegroundCompat()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_PRESENT))
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        })
        lastUsageTickMs = System.currentTimeMillis()
        handler.post(pulseRunnable)
        Log.d(TAG, "BatteryGuardService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH) {
            evaluateGuardState("manualRefresh")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(unlockReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(timeReceiver) } catch (_: Exception) {}
        try {
            startForegroundService(Intent(applicationContext, BatteryGuardService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Could not restart service: ${e.message}")
        }
    }

    private fun updateUsageClock() {
        val now = System.currentTimeMillis()
        if (lastUsageTickMs == 0L) {
            lastUsageTickMs = now
            return
        }
        val elapsed = (now - lastUsageTickMs).coerceAtLeast(0L)
        lastUsageTickMs = now
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isInteractive && !isGuardActive) {
            AppPrefs.addUsageMs(this, elapsed)
        }
    }

    private fun evaluateGuardState(source: String) {
        GuardRules.syncBatteryLatch(this, currentBatteryLevel)

        val oldVisibleReasons = activeReasons
        val oldUnderlyingReasons = underlyingReasons
        val hadUnderlyingLock = oldUnderlyingReasons.isNotEmpty()

        underlyingReasons = GuardRules.evaluateRaw(this, currentBatteryLevel)
        val bypassActive = AppPrefs.isEmergencyBypassActive(this)
        activeReasons = if (bypassActive) emptySet() else underlyingReasons
        isGuardActive = activeReasons.isNotEmpty()

        handleWarningNotification(underlyingReasons)

        if (underlyingReasons.isNotEmpty() && !hadUnderlyingLock) {
            AppPrefs.beginLockSession(this)
            AppPrefs.recordLockEvent(this, GuardRules.reasonsCsv(underlyingReasons))
        } else if (underlyingReasons.isEmpty() && hadUnderlyingLock) {
            AppPrefs.recordUnlockEvent(this)
            AppPrefs.clearLockSession(this)
            AppPrefs.clearEmergencyBypass(this)
        }

        if (bypassActive) scheduleBypassExpiryCheck()
        else handler.removeCallbacks(bypassExpiryRunnable)

        if (!isGuardActive) {
            if (OverlayWindowService.isRunning) stopService(Intent(this, OverlayWindowService::class.java))
        } else if (oldVisibleReasons != activeReasons) {
            tryLaunchOverlayIfUnlocked(this)
        }

        updateNotification()
        if (oldVisibleReasons != activeReasons || oldUnderlyingReasons != underlyingReasons) {
            Log.d(
                TAG,
                "[$source] visible=$isGuardActive battery=$currentBatteryLevel underlying=${GuardRules.reasonsCsv(underlyingReasons)}"
            )
        }
    }

    private fun handleWarningNotification(rawReasons: Set<GuardReason>) {
        val threshold = AppPrefs.getThreshold(this)
        val warnLevel = threshold + AppPrefs.getWarnMargin(this)
        val batteryGuardEnabled = AppPrefs.isProtectionEnabled(this) && AppPrefs.isBatteryProtectionEnabled(this)
        val shouldWarn = batteryGuardEnabled &&
            !isCharging &&
            currentBatteryLevel in (threshold + 1)..warnLevel &&
            GuardReason.LOW_BATTERY !in rawReasons

        when {
            shouldWarn && !warningFired -> {
                warningFired = true
                fireWarningNotification(currentBatteryLevel, threshold)
            }
            !shouldWarn -> {
                warningFired = false
                clearWarningNotification()
            }
        }
        if (GuardReason.LOW_BATTERY in rawReasons) clearWarningNotification()
    }

    private fun scheduleBypassExpiryCheck() {
        handler.removeCallbacks(bypassExpiryRunnable)
        val remaining = AppPrefs.getEmergencyBypassRemainingMs(this)
        if (remaining > 0L) {
            handler.postDelayed(bypassExpiryRunnable, remaining + 250L)
        }
    }

    private fun tryLaunchOverlayIfUnlocked(context: Context) {
        val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager
        val keyguardManager = context.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val screenOn = powerManager.isInteractive
        val keyguardLocked = keyguardManager.isKeyguardLocked
        if (screenOn && !keyguardLocked) launchOverlay(context)
    }

    private fun launchOverlay(context: Context) {
        if (AppPrefs.isEmergencyBypassActive(context)) return
        if (!OverlayWindowService.isRunning) {
            context.startForegroundService(Intent(context, OverlayWindowService::class.java))
        }
        context.startActivity(Intent(context, OverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        })
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            AppPrefs.isEmergencyBypassActive(this) -> "BatteryGuard — Emergency Pass Active"
            !AppPrefs.isProtectionEnabled(this) -> "BatteryGuard — Protection Off"
            isGuardActive -> "BatteryGuard — Device Locked"
            else -> "BatteryGuard — Monitoring"
        }

        val text = when {
            AppPrefs.isEmergencyBypassActive(this) -> {
                "Unlocked for ${DeviceTools.formatDurationCompact(AppPrefs.getEmergencyBypassRemainingMs(this))}"
            }
            !AppPrefs.isProtectionEnabled(this) -> "Protection disabled in dashboard"
            underlyingReasons.isNotEmpty() -> {
                val reasons = GuardRules.reasonsCsv(underlyingReasons)
                val target = GuardRules.batteryUnlockTarget(this)
                if (GuardReason.LOW_BATTERY in underlyingReasons) "$reasons • charge to $target%+ to release"
                else reasons
            }
            else -> {
                val usage = AppPrefs.getTodayUsageMinutes(this)
                "Battery ${currentBatteryLevel}% • today ${usage}m • ready"
            }
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun fireWarningNotification(battery: Int, threshold: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, WARN_CHANNEL)
            .setContentTitle("BatteryGuard warning")
            .setContentText("Battery at $battery% — device locks below $threshold%. Charge now.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Battery at $battery% — device locks below $threshold%. Charge now or BatteryGuard will lock the device."
                )
            )
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(false)
            .build()
        nm.notify(WARN_NOTIF_ID, n)
    }

    private fun clearWarningNotification() {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(WARN_NOTIF_ID)
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "BatteryGuard Monitor", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(WARN_CHANNEL, "Battery Warnings", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) }
        )
    }
}
