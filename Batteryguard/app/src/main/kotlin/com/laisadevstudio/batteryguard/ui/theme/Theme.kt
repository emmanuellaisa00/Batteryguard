package com.laisadevstudio.batteryguard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val IceBlue = Color(0xFF9DE1FF)
val SilverGlass = Color(0xFFE5F2FF)
val AuroraBlue = Color(0xFF72CCFF)
val DeepOcean = Color(0xFF04121D)
val DeepNight = Color(0xFF091A28)
val AlertOrange = Color(0xFFFFB56B)
val AlertRed = Color(0xFFFF6B7A)
val SuccessMint = Color(0xFF7CF4D6)

private val LiquidGlassScheme = darkColorScheme(
    primary = IceBlue,
    onPrimary = Color.Black,
    secondary = AuroraBlue,
    onSecondary = Color.Black,
    tertiary = SilverGlass,
    background = DeepOcean,
    onBackground = Color.White,
    surface = Color(0xFF0A1D2D),
    onSurface = Color.White,
    surfaceVariant = Color(0x552B4E67),
    error = AlertRed,
    onError = Color.Black
)

@Composable
fun BatteryGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LiquidGlassScheme,
        content = content
    )
}
