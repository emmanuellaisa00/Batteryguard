package com.laisadevstudio.batteryguard

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class NetworkSnapshot(
    val connected: Boolean,
    val label: String,
    val isCellular: Boolean,
    val isWifi: Boolean
)

object DeviceTools {

    fun getBatteryLevel(context: Context): Int {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (capacity >= 0) return capacity

            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            if (level >= 0 && scale > 0) level * 100 / scale else 0
        } catch (_: Exception) {
            0
        }
    }

    fun getNetworkSnapshot(context: Context): NetworkSnapshot {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return NetworkSnapshot(false, "Offline", false, false)
            val caps = cm.getNetworkCapabilities(network)
                ?: return NetworkSnapshot(false, "Offline", false, false)

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                    NetworkSnapshot(true, "Wi‑Fi", false, true)
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    NetworkSnapshot(true, "Mobile Data", true, false)
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    NetworkSnapshot(true, "Ethernet", false, false)
                else -> NetworkSnapshot(true, "Connected", false, false)
            }
        } catch (_: Exception) {
            NetworkSnapshot(false, "Offline", false, false)
        }
    }

    fun isAirplaneModeOn(context: Context): Boolean {
        return try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: Exception) {
            false
        }
    }

    fun firstTorchCameraId(context: Context): String? {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val flash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
                flash && lensFacing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (_: Exception) {
            null
        }
    }

    fun formatDurationCompact(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return if (minutes > 0) {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        } else {
            String.format(Locale.US, "0:%02d", seconds)
        }
    }

    fun formatClock(nowMs: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("h:mm", Locale.US).format(Date(nowMs))

    fun formatDate(nowMs: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("EEEE, MMM d", Locale.US).format(Date(nowMs))
}
