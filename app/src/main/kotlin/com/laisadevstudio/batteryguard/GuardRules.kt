package com.laisadevstudio.batteryguard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

enum class GuardReason {
    LOW_BATTERY,
    OUTSIDE_ALLOWED_WINDOW,
    BEDTIME,
    DAILY_LIMIT
}

object GuardRules {

    fun evaluate(context: Context, batteryLevel: Int): Set<GuardReason> {
        if (!AppPrefs.isProtectionEnabled(context)) return emptySet()
        if (AppPrefs.isEmergencyBypassActive(context)) return emptySet()

        val reasons = linkedSetOf<GuardReason>()

        if (AppPrefs.isBatteryProtectionEnabled(context)) {
            val threshold = AppPrefs.getThreshold(context)
            val unlockTarget = batteryUnlockTarget(context)
            val lowBatteryActive = if (AppPrefs.isBatteryLockLatched(context)) {
                batteryLevel < unlockTarget
            } else {
                batteryLevel < threshold
            }
            if (lowBatteryActive) reasons += GuardReason.LOW_BATTERY
        }

        if (AppPrefs.isScheduleProtectionEnabled(context)) {
            val now = nowMinutesOfDay()
            val windows = AppPrefs.getAllowedWindows(context).filter { it.enabled }
            if (windows.isNotEmpty() && windows.none { isWithinWindow(now, it.startMinutes, it.endMinutes) }) {
                reasons += GuardReason.OUTSIDE_ALLOWED_WINDOW
            }

            val bedtime = AppPrefs.getBedtimeWindow(context)
            if (bedtime.enabled && isWithinWindow(now, bedtime.startMinutes, bedtime.endMinutes)) {
                reasons += GuardReason.BEDTIME
            }
        }

        if (AppPrefs.isDailyUsageLimitEnabled(context) &&
            AppPrefs.getTodayUsageMinutes(context) >= AppPrefs.getDailyUsageLimitMinutes(context)) {
            reasons += GuardReason.DAILY_LIMIT
        }

        return reasons
    }

    fun batteryUnlockTarget(context: Context): Int =
        max(20, AppPrefs.getThreshold(context))

    fun syncBatteryLatch(context: Context, batteryLevel: Int) {
        if (!AppPrefs.isProtectionEnabled(context) || !AppPrefs.isBatteryProtectionEnabled(context)) {
            AppPrefs.setBatteryLockLatched(context, false)
            return
        }
        val threshold = AppPrefs.getThreshold(context)
        val unlockTarget = batteryUnlockTarget(context)
        when {
            batteryLevel < threshold -> AppPrefs.setBatteryLockLatched(context, true)
            AppPrefs.isBatteryLockLatched(context) && batteryLevel >= unlockTarget -> AppPrefs.setBatteryLockLatched(context, false)
        }
    }

    fun reasonLabel(reason: GuardReason): String = when (reason) {
        GuardReason.LOW_BATTERY -> "Low Battery"
        GuardReason.OUTSIDE_ALLOWED_WINDOW -> "Outside Allowed Time"
        GuardReason.BEDTIME -> "Sleep / Focus Lock"
        GuardReason.DAILY_LIMIT -> "Daily Limit Reached"
    }

    fun reasonShort(reason: GuardReason): String = when (reason) {
        GuardReason.LOW_BATTERY -> "Battery"
        GuardReason.OUTSIDE_ALLOWED_WINDOW -> "Schedule"
        GuardReason.BEDTIME -> "Bedtime"
        GuardReason.DAILY_LIMIT -> "Daily Limit"
    }

    fun reasonsCsv(reasons: Set<GuardReason>): String =
        reasons.joinToString(", ") { reasonLabel(it) }

    fun unlockRequirements(context: Context, reasons: Set<GuardReason>): List<String> {
        val items = mutableListOf<String>()
        if (GuardReason.LOW_BATTERY in reasons) {
            items += "Charge to ${batteryUnlockTarget(context)}% or higher"
        }
        if (GuardReason.OUTSIDE_ALLOWED_WINDOW in reasons) {
            items += nextAllowedWindowText(context)?.let { "Wait until $it" } ?: "Wait for the next allowed time window"
        }
        if (GuardReason.BEDTIME in reasons) {
            val bedtime = AppPrefs.getBedtimeWindow(context)
            items += "Bedtime lock ends at ${formatMinutes(bedtime.endMinutes)}"
        }
        if (GuardReason.DAILY_LIMIT in reasons) {
            items += "Daily usage resets at midnight"
        }
        if (items.isEmpty()) items += "All protection rules are clear"
        return items
    }

    fun nextAllowedWindowText(context: Context): String? {
        val windows = AppPrefs.getAllowedWindows(context).filter { it.enabled }
        if (windows.isEmpty()) return null
        val now = nowMinutesOfDay()
        val futureStarts = windows
            .map { it.startMinutes }
            .sorted()

        val todayNext = futureStarts.firstOrNull { it > now }
        return if (todayNext != null) {
            "${formatMinutes(todayNext)} today"
        } else {
            "${formatMinutes(futureStarts.first())} tomorrow"
        }
    }

    fun usageRemainingMinutes(context: Context): Int =
        (AppPrefs.getDailyUsageLimitMinutes(context) - AppPrefs.getTodayUsageMinutes(context)).coerceAtLeast(0)

    fun formatMinutes(minutes: Int): String {
        val hour = (minutes / 60) % 24
        val minute = minutes % 60
        val amPm = if (hour >= 12) "PM" else "AM"
        val displayHour = when (val h = hour % 12) {
            0 -> 12
            else -> h
        }
        return String.format(Locale.US, "%d:%02d %s", displayHour, minute, amPm)
    }

    fun formatTimestamp(timestamp: Long): String {
        if (timestamp <= 0L) return "Never"
        return SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(timestamp))
    }

    fun protectionStrength(
        hasAdmin: Boolean,
        hasOverlay: Boolean,
        hasAccessibility: Boolean,
        isDeviceOwner: Boolean
    ): String = when {
        isDeviceOwner && hasAdmin && hasOverlay && hasAccessibility -> "Full lockdown"
        hasAdmin && hasOverlay && hasAccessibility -> "Strong"
        hasAdmin && hasOverlay -> "Balanced"
        else -> "Basic"
    }

    fun isWithinWindow(nowMinutes: Int, startMinutes: Int, endMinutes: Int): Boolean {
        return if (startMinutes == endMinutes) {
            true
        } else if (startMinutes < endMinutes) {
            nowMinutes in startMinutes until endMinutes
        } else {
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
    }

    fun nowMinutesOfDay(): Int {
        val now = java.time.LocalTime.now()
        return now.hour * 60 + now.minute
    }
}
