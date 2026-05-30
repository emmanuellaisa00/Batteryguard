package com.laisadevstudio.batteryguard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility service that detects when the Quick Settings panel or
 * notification shade opens while guard is active and immediately closes it.
 */
class KioskAccessibilityService : AccessibilityService() {

    companion object {
        const val TAG = "KioskA11y"
    }

    private val qsPanelPackages = setOf(
        "com.android.systemui",
        "com.android.settings"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!BatteryGuardService.isGuardActive) return
        val pkg = event?.packageName?.toString() ?: return

        val isWindowChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        if (isWindowChange && pkg in qsPanelPackages) {
            Log.d(TAG, "Blocked system UI window: $pkg")
            // Collapse notification / QS panel
            performGlobalAction(GLOBAL_ACTION_BACK)
            // Re-launch our overlay if guard is active
            if (BatteryGuardService.isGuardActive) {
                val i = Intent(this, OverlayActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                }
                startActivity(i)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }
}
