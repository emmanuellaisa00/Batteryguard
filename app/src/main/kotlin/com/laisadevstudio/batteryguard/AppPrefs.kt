package com.laisadevstudio.batteryguard

import android.content.Context
import java.time.LocalDate

data class TimeWindow(
    val enabled: Boolean,
    val startMinutes: Int,
    val endMinutes: Int
)

object AppPrefs {
    private const val PREF_NAME = "battery_guard_prefs"

    private const val KEY_PROTECTION_ENABLED = "protection_enabled"
    private const val KEY_BATTERY_PROTECTION_ENABLED = "battery_protection_enabled"
    private const val KEY_SCHEDULE_PROTECTION_ENABLED = "schedule_protection_enabled"
    private const val KEY_DAILY_LIMIT_ENABLED = "daily_limit_enabled"

    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_WARN_MARGIN = "warn_margin"

    private const val KEY_WINDOW1_ENABLED = "window1_enabled"
    private const val KEY_WINDOW1_START = "window1_start"
    private const val KEY_WINDOW1_END = "window1_end"

    private const val KEY_WINDOW2_ENABLED = "window2_enabled"
    private const val KEY_WINDOW2_START = "window2_start"
    private const val KEY_WINDOW2_END = "window2_end"

    private const val KEY_BEDTIME_ENABLED = "bedtime_enabled"
    private const val KEY_BEDTIME_START = "bedtime_start"
    private const val KEY_BEDTIME_END = "bedtime_end"

    private const val KEY_DAILY_LIMIT_MINUTES = "daily_limit_minutes"
    private const val KEY_TODAY_USAGE_MS = "today_usage_ms"
    private const val KEY_TODAY_USAGE_DAY = "today_usage_day"

    private const val KEY_EMERGENCY_BYPASS_UNTIL = "emergency_bypass_until"
    private const val KEY_EMERGENCY_DAY = "emergency_day"
    private const val KEY_EMERGENCY_COUNT = "emergency_count"
    private const val KEY_EMERGENCY_LAST_SESSION = "emergency_last_session"

    private const val KEY_LOCK_COUNT = "lock_count"
    private const val KEY_LAST_LOCK_AT = "last_lock_at"
    private const val KEY_LAST_UNLOCK_AT = "last_unlock_at"
    private const val KEY_LAST_LOCK_REASONS = "last_lock_reasons"

    private const val KEY_CURRENT_LOCK_SESSION_ID = "current_lock_session_id"
    private const val KEY_BATTERY_LOCK_LATCHED = "battery_lock_latched"
    private const val KEY_BATTERY_THRESHOLD_AT_LOCK = "battery_threshold_at_lock"
    private const val KEY_BATTERY_UNLOCK_FLOOR_AT_LOCK = "battery_unlock_floor_at_lock"

    private const val DEFAULT_THRESHOLD = 20
    private const val DEFAULT_WARN_MARGIN = 10
    private const val DEFAULT_DAILY_LIMIT_MINUTES = 120
    private const val MAX_EMERGENCY_PASSES_PER_DAY = 2
    const val EMERGENCY_PASS_DURATION_MS = 2 * 60 * 1000L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun edit(context: Context) = prefs(context).edit()

    private fun todayKey(): String = LocalDate.now().toString()

    private fun ensureUsageBucket(context: Context) {
        val p = prefs(context)
        val today = todayKey()
        if (p.getString(KEY_TODAY_USAGE_DAY, null) != today) {
            edit(context)
                .putString(KEY_TODAY_USAGE_DAY, today)
                .putLong(KEY_TODAY_USAGE_MS, 0L)
                .apply()
        }
    }

    private fun ensureEmergencyBucket(context: Context) {
        val p = prefs(context)
        val today = todayKey()
        if (p.getString(KEY_EMERGENCY_DAY, null) != today) {
            edit(context)
                .putString(KEY_EMERGENCY_DAY, today)
                .putInt(KEY_EMERGENCY_COUNT, 0)
                .apply()
        }
    }

    // ── Protection switches ───────────────────────────────────────────────────
    fun isProtectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PROTECTION_ENABLED, true)

    fun setProtectionEnabled(context: Context, enabled: Boolean) {
        edit(context).putBoolean(KEY_PROTECTION_ENABLED, enabled).apply()
    }

    fun isBatteryProtectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BATTERY_PROTECTION_ENABLED, true)

    fun setBatteryProtectionEnabled(context: Context, enabled: Boolean) {
        edit(context).putBoolean(KEY_BATTERY_PROTECTION_ENABLED, enabled).apply()
    }

    fun isScheduleProtectionEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SCHEDULE_PROTECTION_ENABLED, false)

    fun setScheduleProtectionEnabled(context: Context, enabled: Boolean) {
        edit(context).putBoolean(KEY_SCHEDULE_PROTECTION_ENABLED, enabled).apply()
    }

    fun isDailyUsageLimitEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DAILY_LIMIT_ENABLED, false)

    fun setDailyUsageLimitEnabled(context: Context, enabled: Boolean) {
        edit(context).putBoolean(KEY_DAILY_LIMIT_ENABLED, enabled).apply()
    }

    // ── Battery rules ─────────────────────────────────────────────────────────
    fun getThreshold(context: Context): Int =
        prefs(context).getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    fun setThreshold(context: Context, value: Int) {
        edit(context).putInt(KEY_THRESHOLD, value.coerceIn(5, 80)).apply()
    }

    fun getWarnMargin(context: Context): Int =
        prefs(context).getInt(KEY_WARN_MARGIN, DEFAULT_WARN_MARGIN)

    fun setWarnMargin(context: Context, value: Int) {
        edit(context).putInt(KEY_WARN_MARGIN, value.coerceIn(3, 20)).apply()
    }

    fun isBatteryLockLatched(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BATTERY_LOCK_LATCHED, false)

    fun beginBatteryLockSession(context: Context, thresholdAtLock: Int, unlockFloor: Int) {
        edit(context)
            .putBoolean(KEY_BATTERY_LOCK_LATCHED, true)
            .putInt(KEY_BATTERY_THRESHOLD_AT_LOCK, thresholdAtLock)
            .putInt(KEY_BATTERY_UNLOCK_FLOOR_AT_LOCK, unlockFloor)
            .apply()
    }

    fun clearBatteryLockSession(context: Context) {
        edit(context)
            .putBoolean(KEY_BATTERY_LOCK_LATCHED, false)
            .remove(KEY_BATTERY_THRESHOLD_AT_LOCK)
            .remove(KEY_BATTERY_UNLOCK_FLOOR_AT_LOCK)
            .apply()
    }

    fun getBatteryThresholdAtLock(context: Context): Int =
        prefs(context).getInt(KEY_BATTERY_THRESHOLD_AT_LOCK, 0)

    fun getBatteryUnlockFloorAtLock(context: Context): Int =
        prefs(context).getInt(KEY_BATTERY_UNLOCK_FLOOR_AT_LOCK, 0)

    fun isBatterySettingsLocked(context: Context): Boolean =
        isBatteryLockLatched(context)

    // ── Allowed windows ───────────────────────────────────────────────────────
    fun getWindow1(context: Context): TimeWindow = TimeWindow(
        enabled = prefs(context).getBoolean(KEY_WINDOW1_ENABLED, false),
        startMinutes = prefs(context).getInt(KEY_WINDOW1_START, 6 * 60),
        endMinutes = prefs(context).getInt(KEY_WINDOW1_END, 8 * 60)
    )

    fun setWindow1(context: Context, window: TimeWindow) {
        edit(context)
            .putBoolean(KEY_WINDOW1_ENABLED, window.enabled)
            .putInt(KEY_WINDOW1_START, window.startMinutes.coerceIn(0, 1439))
            .putInt(KEY_WINDOW1_END, window.endMinutes.coerceIn(0, 1439))
            .apply()
    }

    fun getWindow2(context: Context): TimeWindow = TimeWindow(
        enabled = prefs(context).getBoolean(KEY_WINDOW2_ENABLED, false),
        startMinutes = prefs(context).getInt(KEY_WINDOW2_START, 17 * 60),
        endMinutes = prefs(context).getInt(KEY_WINDOW2_END, 20 * 60)
    )

    fun setWindow2(context: Context, window: TimeWindow) {
        edit(context)
            .putBoolean(KEY_WINDOW2_ENABLED, window.enabled)
            .putInt(KEY_WINDOW2_START, window.startMinutes.coerceIn(0, 1439))
            .putInt(KEY_WINDOW2_END, window.endMinutes.coerceIn(0, 1439))
            .apply()
    }

    fun getAllowedWindows(context: Context): List<TimeWindow> = listOf(
        getWindow1(context),
        getWindow2(context)
    )

    // ── Bedtime / focus lock ──────────────────────────────────────────────────
    fun getBedtimeWindow(context: Context): TimeWindow = TimeWindow(
        enabled = prefs(context).getBoolean(KEY_BEDTIME_ENABLED, false),
        startMinutes = prefs(context).getInt(KEY_BEDTIME_START, 22 * 60),
        endMinutes = prefs(context).getInt(KEY_BEDTIME_END, 6 * 60)
    )

    fun setBedtimeWindow(context: Context, window: TimeWindow) {
        edit(context)
            .putBoolean(KEY_BEDTIME_ENABLED, window.enabled)
            .putInt(KEY_BEDTIME_START, window.startMinutes.coerceIn(0, 1439))
            .putInt(KEY_BEDTIME_END, window.endMinutes.coerceIn(0, 1439))
            .apply()
    }

    // ── Daily usage limit ─────────────────────────────────────────────────────
    fun getDailyUsageLimitMinutes(context: Context): Int =
        prefs(context).getInt(KEY_DAILY_LIMIT_MINUTES, DEFAULT_DAILY_LIMIT_MINUTES)

    fun setDailyUsageLimitMinutes(context: Context, minutes: Int) {
        edit(context).putInt(KEY_DAILY_LIMIT_MINUTES, minutes.coerceIn(15, 720)).apply()
    }

    fun addUsageMs(context: Context, deltaMs: Long) {
        if (deltaMs <= 0L) return
        ensureUsageBucket(context)
        val current = prefs(context).getLong(KEY_TODAY_USAGE_MS, 0L)
        edit(context).putLong(KEY_TODAY_USAGE_MS, current + deltaMs).apply()
    }

    fun getTodayUsageMs(context: Context): Long {
        ensureUsageBucket(context)
        return prefs(context).getLong(KEY_TODAY_USAGE_MS, 0L)
    }

    fun getTodayUsageMinutes(context: Context): Int =
        (getTodayUsageMs(context) / 60_000L).toInt()

    fun resetUsageToday(context: Context) {
        ensureUsageBucket(context)
        edit(context).putLong(KEY_TODAY_USAGE_MS, 0L).apply()
    }

    // ── Lock sessions ──────────────────────────────────────────────────────────
    fun getCurrentLockSessionId(context: Context): Long =
        prefs(context).getLong(KEY_CURRENT_LOCK_SESSION_ID, 0L)

    fun beginLockSession(context: Context): Long {
        val id = System.currentTimeMillis()
        edit(context).putLong(KEY_CURRENT_LOCK_SESSION_ID, id).apply()
        return id
    }

    fun clearLockSession(context: Context) {
        edit(context).putLong(KEY_CURRENT_LOCK_SESSION_ID, 0L).apply()
    }

    // ── Emergency bypass ──────────────────────────────────────────────────────
    fun getEmergencyPassDailyLimit(): Int = MAX_EMERGENCY_PASSES_PER_DAY

    fun getEmergencyPassesUsedToday(context: Context): Int {
        ensureEmergencyBucket(context)
        return prefs(context).getInt(KEY_EMERGENCY_COUNT, 0)
    }

    fun getEmergencyPassesRemainingToday(context: Context): Int =
        (MAX_EMERGENCY_PASSES_PER_DAY - getEmergencyPassesUsedToday(context)).coerceAtLeast(0)

    fun getLastEmergencySessionId(context: Context): Long =
        prefs(context).getLong(KEY_EMERGENCY_LAST_SESSION, 0L)

    fun hasEmergencyPassBeenUsedForCurrentSession(context: Context): Boolean {
        val currentSession = getCurrentLockSessionId(context)
        return currentSession != 0L && getLastEmergencySessionId(context) == currentSession
    }

    fun startEmergencyBypass(
        context: Context,
        sessionId: Long,
        durationMs: Long = EMERGENCY_PASS_DURATION_MS
    ) {
        ensureEmergencyBucket(context)
        val used = getEmergencyPassesUsedToday(context)
        edit(context)
            .putLong(KEY_EMERGENCY_BYPASS_UNTIL, System.currentTimeMillis() + durationMs)
            .putInt(KEY_EMERGENCY_COUNT, used + 1)
            .putLong(KEY_EMERGENCY_LAST_SESSION, sessionId)
            .apply()
    }

    fun clearEmergencyBypass(context: Context) {
        edit(context).putLong(KEY_EMERGENCY_BYPASS_UNTIL, 0L).apply()
    }

    fun isEmergencyBypassActive(context: Context): Boolean =
        getEmergencyBypassRemainingMs(context) > 0L

    fun getEmergencyBypassRemainingMs(context: Context): Long {
        val until = prefs(context).getLong(KEY_EMERGENCY_BYPASS_UNTIL, 0L)
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    // ── Lock history ──────────────────────────────────────────────────────────
    fun recordLockEvent(context: Context, reasonsCsv: String) {
        val p = prefs(context)
        edit(context)
            .putInt(KEY_LOCK_COUNT, p.getInt(KEY_LOCK_COUNT, 0) + 1)
            .putLong(KEY_LAST_LOCK_AT, System.currentTimeMillis())
            .putString(KEY_LAST_LOCK_REASONS, reasonsCsv)
            .apply()
    }

    fun recordUnlockEvent(context: Context) {
        edit(context).putLong(KEY_LAST_UNLOCK_AT, System.currentTimeMillis()).apply()
    }

    fun getLockCount(context: Context): Int = prefs(context).getInt(KEY_LOCK_COUNT, 0)
    fun getLastLockAt(context: Context): Long = prefs(context).getLong(KEY_LAST_LOCK_AT, 0L)
    fun getLastUnlockAt(context: Context): Long = prefs(context).getLong(KEY_LAST_UNLOCK_AT, 0L)
    fun getLastLockReasons(context: Context): String =
        prefs(context).getString(KEY_LAST_LOCK_REASONS, "") ?: ""
}
