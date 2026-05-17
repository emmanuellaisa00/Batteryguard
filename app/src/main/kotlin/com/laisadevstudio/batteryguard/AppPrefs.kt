package com.laisadevstudio.batteryguard

import android.content.Context

object AppPrefs {
    private const val PREF_NAME = "battery_guard_prefs"
    private const val KEY_THRESHOLD = "threshold"
    private const val DEFAULT_THRESHOLD = 20

    fun getThreshold(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)
    }

    fun setThreshold(context: Context, value: Int) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_THRESHOLD, value).apply()
    }
}
