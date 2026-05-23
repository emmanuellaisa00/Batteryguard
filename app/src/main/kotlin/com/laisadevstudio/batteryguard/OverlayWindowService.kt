package com.laisadevstudio.batteryguard

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.os.*
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat

/**
 * Dual-layer WindowManager overlay:
 *  1. Full-screen opaque overlay — covers recents, settings, all apps
 *  2. Thin status-bar-height touchable strip at top — intercepts QS swipe-down gestures
 *
 * FLAG_KEEP_SCREEN_ON is intentionally NOT set here — only OverlayActivity holds it
 * so the power button can still sleep the screen normally.
 * State (isGuardActive, currentBatteryLevel, isCharging) is read-only here;
 * BatteryGuardService is the single writer.
 */
class OverlayWindowService : Service() {

    companion object {
        const val TAG        = "OverlayWindowService"
        const val CHANNEL_ID = "overlay_channel"
        const val NOTIF_ID   = 2001
        @Volatile var isRunning = false
    }

    private var windowManager: WindowManager? = null
    private var fullOverlay: View? = null
    private var qsBlocker: View? = null

    // Read-only listener — only reads state, never writes it
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return
            // BatteryGuardService is the single source of truth — just check its flag
            if (!BatteryGuardService.isGuardActive) {
                Log.d(TAG, "Guard inactive — removing overlay")
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        showOverlays()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun showOverlays() {
        // ── 1. Full-screen opaque overlay ──────────────────────────
        // FLAG_KEEP_SCREEN_ON intentionally removed — power button must work
        val fullParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val fullView = View(this).apply { setBackgroundColor(Color.parseColor("#050510")) }
        fullOverlay = fullView
        try { windowManager?.addView(fullView, fullParams) }
        catch (e: Exception) { Log.e(TAG, "fullOverlay: ${e.message}") }

        // ── 2. Status-bar-height QS blocker ─────────────────────────
        val statusBarHeight = resources.getDimensionPixelSize(
            resources.getIdentifier("status_bar_height", "dimen", "android")
        ).let { if (it > 0) it * 3 else 120 }

        val qsParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            statusBarHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }

        val qsView = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, _ -> true }
        }
        qsBlocker = qsView
        try { windowManager?.addView(qsView, qsParams) }
        catch (e: Exception) { Log.e(TAG, "qsBlocker: ${e.message}") }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        fullOverlay?.let { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        qsBlocker?.let  { try { windowManager?.removeView(it) } catch (_: Exception) {} }
        fullOverlay = null; qsBlocker = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("BatteryGuard — Device Locked")
        .setContentText("Charge to ${AppPrefs.getThreshold(this)}% to regain access")
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "BatteryGuard Overlay", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }
}
