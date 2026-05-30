package com.laisadevstudio.batteryguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("BootReceiver", "System/package event — starting BatteryGuardService")
            val serviceIntent = Intent(context, BatteryGuardService::class.java)
            context.startForegroundService(serviceIntent)
        }
    }
}
