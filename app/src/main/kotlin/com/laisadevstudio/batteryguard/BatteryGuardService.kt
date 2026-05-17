package com.laisadevstudio.batteryguard

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat

class BatteryGuardService : Service() {

    companion object {
        const val CHANNEL_ID   = "battery_guard_channel"
        const val WARN_CHANNEL = "battery_warn_channel"
        const val NOTIFICATION_ID = 1001
        const val WARN_NOTIF_ID   = 1002
        const val TAG = "BatteryGuardService"

        var isGuardActive      = false
        var isCharging         = false
        var currentBatteryLevel = 100

        // Pre-warn when battery drops to threshold + WARN_MARGIN
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
            if (currentBatteryLevel in threshold..warnLevel && !warnFired && !isCharging) {
                warnFired = true
                fireWarningNotification(currentBatteryLevel, threshold)
            } else if (currentBatteryLevel > warnLevel || isCharging) {
                warnFired = false
                clearWarningNotification()
            }

            if (isGuardActive != wasActive)
                Log.d(TAG, "Guard=$isGuardActive battery=$currentBatteryLevel% threshold=$threshold%")

            if (!isGuardActive && OverlayWindowService.isRunning)
                context.stopService(Intent(context, OverlayWindowService::class.java))

            updateNotification(currentBatteryLevel, threshold)
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            Log.d(TAG, "Unlocked. Guard=$isGuardActive battery=$currentBatteryLevel%")
            if (isGuardActive) {
                context.startForegroundService(Intent(context, OverlayWindowService::class.java))
                context.startActivity(Intent(context, OverlayActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                })
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForegroundCompat()
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(unlockReceiver,  IntentFilter(Intent.ACTION_USER_PRESENT))
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
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(unlockReceiver)  } catch (_: Exception) {}
        startForegroundService(Intent(applicationContext, BatteryGuardService::class.java))
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
