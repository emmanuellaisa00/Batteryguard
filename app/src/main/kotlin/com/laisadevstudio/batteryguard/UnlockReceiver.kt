package com.laisadevstudio.batteryguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Stub — actual unlock logic is registered dynamically in BatteryGuardService
// to handle ACTION_USER_PRESENT which cannot be declared in manifest
class UnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {}
}
