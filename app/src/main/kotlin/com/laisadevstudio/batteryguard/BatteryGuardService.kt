package com.laisadevstudio.batteryguard

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.os.PowerManager
import android.app.KeyguardManager
import android.util.Log
import androidx.core.app.NotificationCompat

class BatteryGuardService : Service() {

    companion object {
        const val CHANNEL_ID   = "battery_guard_channel"
        const val WARN_CHANNEL = "battery_warn_channel"
        const val NOTIFICATION_ID = 1001
        const val WARN_NOTIF_ID   = 1002
        const val TAG = "BatteryGuardService"

        @Volatile var isGuardActive       = false
        @Volatile var isCharging          = false
        @Volatile var currentBatteryLevel = 100

        const val WARN_MARGIN = 10
        private var warnFired = false
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            if (level < 0 || scale <= 0) return

            currentBatteryLevel = level * 100 / scale
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL

            val threshold = AppPrefs.getThreshold(context)
            val wasActive = isGuardActive
            isGuardActive = currentBatteryLevel < threshold

            // Pre-lock warning
            val warnLevel = threshold + WARN_MARGIN
            when {
                currentBatteryLevel in (threshold + 1)..warnLevel && !warnFired && !isCharging -> {
                    warnFired = true
                    fireWarningNotification(currentBatteryLevel, threshold)
                }
                currentBatteryLevel > warnLevel || isCharging -> {
                    warnFired = false
                    clearWarningNotification()
                }
                isGuardActive -> clearWarningNotification()
            }

            if (isGuardActive != wasActive)
                Log.d(TAG, "Guard=$isGuardActive battery=$currentBatteryLevel% threshold=$threshold%")

            // Guard just became inactive — tear down overlay
            if (!isGuardActive && OverlayWindowService.isRunning)
                context.stopService(Intent(context, OverlayWindowService::class.java))

            // Guard just became active — launch overlay immediately if screen is
            // already on and the user is already past the lock screen
            if (isGuardActive && !wasActive) {
                tryLaunchOverlayIfUnlocked(context)
            }

            updateNotification(currentBatteryLevel, threshold)
        }
    }

    // Fires when screen turns on (power button press or charge-connected wake)
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            Log.d(TAG, "Screen on. Guard=$isGuardActive")
            if (isGuardActive) {
                // Small delay so the keyguard state settles before we check it
                Handler(Looper.getMainLooper()).postDelayed({
                    tryLaunchOverlayIfUnlocked(context)
                }, 300)
            }
        }
    }

    // Fires after lock screen is dismissed (PIN/pattern/swipe entered)
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            Log.d(TAG, "Unlocked. Guard=$isGuardActive battery=$currentBatteryLevel%")
            if (isGuardActive) launchOverlay(context)
        }
    }

    /**
     * Launch overlay only if the screen is on AND the keyguard is not showing.
     * This covers: battery drops while screen is already on, or screen wakes
     * without a lock screen (e.g. swipe-only / no security).
     */
    private fun tryLaunchOverlayIfUnlocked(context: Context) {
        val pm = context.getSystemService(POWER_SERVICE) as PowerManager
        val km = context.getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val screenOn   = pm.isInteractive
        val keyguardUp = km.isKeyguardLocked
        Log.d(TAG, "tryLaunchOverlay: screenOn=$screenOn keyguardUp=$keyguardUp")
        if (screenOn && !keyguardUp) launchOverlay(context)
        // If keyguard IS up, ACTION_USER_PRESENT will fire after dismissal
    }

    private fun launchOverlay(context: Context) {
        if (!OverlayWindowService.isRunning)
            context.startForegroundService(Intent(context, OverlayWindowService::class.java))
        context.startActivity(Intent(context, OverlayActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        })
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForegroundCompat()
        registerReceiver(batteryReceiver,  IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(unlockReceiver,   IntentFilter(Intent.ACTION_USER_PRESENT))
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        Log.d(TAG, "BatteryGuardService started")
    }

    private fun startForegroundCompat() {
        val n = buildNotification(100, AppPrefs.getThreshold(this))
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(NOTIFICATION_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(batteryReceiver)  } catch (_: Exception) {}
        try { unregisterReceiver(unlockReceiver)   } catch (_: Exception) {}
        try { unregisterReceiver(screenOnReceiver) } catch (_: Exception) {}
        try {
            startForegroundService(Intent(applicationContext, BatteryGuardService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Could not restart service: ${e.message}")
        }
    }

    private fun updateNotification(battery: Int, threshold: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(battery, threshold))
    }

    private fun buildNotification(battery: Int, threshold: Int): Notification {
        val text = if (battery < threshold)
            "Device locked — charge to $threshold% to unlock"
        else
            "Monitoring battery — $battery% (locks below $threshold%)"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BatteryGuard Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun fireWarningNotification(battery: Int, threshold: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val n = NotificationCompat.Builder(this, WARN_CHANNEL)
            .setContentTitle("Low Battery Warning")
            .setContentText("Battery at $battery% — device will lock below $threshold%. Charge now.")
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
