package com.laisadevstudio.batteryguard.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

@Immutable
data class UiAccessibilityState(
    val reduceTransparency: Boolean = false
)

val LocalUiAccessibility = staticCompositionLocalOf { UiAccessibilityState() }
