package com.laisadevstudio.batteryguard

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laisadevstudio.batteryguard.ui.theme.BatteryGuardTheme

class MainActivity : ComponentActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, DeviceAdminReceiver::class.java)
        startForegroundService(Intent(this, BatteryGuardService::class.java))
        setContent { BatteryGuardTheme { SetupScreen() } }
    }


    @Composable
    fun SetupScreen() {
        val isAdmin   = dpm.isAdminActive(adminComponent)
        val hasOverlay = Settings.canDrawOverlays(this)
        val threshold = remember { mutableIntStateOf(AppPrefs.getThreshold(this@MainActivity)) }
        val battery   = remember { mutableIntStateOf(getBatteryLevel()) }
        val charging  = remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            while (true) {
                battery.intValue = getBatteryLevel()
                charging.value = BatteryGuardService.isCharging
                kotlinx.coroutines.delay(1000)
            }
        }

        val batteryColor = when {
            battery.intValue < 15 -> Color(0xFFFF1744)
            battery.intValue < threshold.intValue -> Color(0xFFFF6B35)
            else -> Color(0xFF00E676)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF0A0A1A), Color(0xFF0D1B2A), Color(0xFF1A0A2E))))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(28.dp))

                Icon(
                    painter = painterResource(R.drawable.ic_shield_lock),
                    contentDescription = null,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(64.dp)
                )
                Text("BatteryGuard", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("by laisadevstudio", fontSize = 12.sp, color = Color(0xFF00E5FF).copy(alpha = 0.7f))

                Spacer(Modifier.height(4.dp))

                // Battery card
                Card(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2A3A))
                ) {
                    Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = if (charging.value) painterResource(R.drawable.ic_bolt) else painterResource(R.drawable.ic_shield_lock),
                            contentDescription = null,
                            tint = batteryColor,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text("Current Battery", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                            Text(
                                "${battery.intValue}%${if (charging.value) "  — Charging" else ""}",
                                fontSize = 26.sp, fontWeight = FontWeight.Bold, color = batteryColor
                            )
                        }
                    }
                }

                // Threshold slider
                Card(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E2A3A))
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Lock Threshold", color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Medium)
                            Text("${threshold.intValue}%", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        Slider(
                            value = threshold.intValue.toFloat(),
                            onValueChange = {
                                threshold.intValue = it.toInt()
                                AppPrefs.setThreshold(this@MainActivity, it.toInt())
                            },
                            valueRange = 5f..80f, steps = 14,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF00E5FF),
                                activeTrackColor = Color(0xFF00E5FF),
                                inactiveTrackColor = Color.White.copy(alpha = 0.15f)
                            )
                        )
                        Text(
                            "Device locks when battery drops below ${threshold.intValue}%",
                            fontSize = 12.sp, color = Color.White.copy(alpha = 0.45f)
                        )
                    }
                }

                // Warning zone info
                Card(
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1200))
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painter = painterResource(R.drawable.ic_warning), null,
                            tint = Color(0xFFFFAB00), modifier = Modifier.size(20.dp))
                        Text(
                            "Warning fires at ${threshold.intValue + BatteryGuardService.WARN_MARGIN}% — ${BatteryGuardService.WARN_MARGIN}% before lock",
                            fontSize = 12.sp, color = Color(0xFFFFAB00)
                        )
                    }
                }

                // Permissions
                PermCard(
                    title = "Device Administrator",
                    subtitle = "Required for kiosk lock mode",
                    iconRes = R.drawable.ic_shield_lock,
                    granted = isAdmin,
                    onGrant = {
                        startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                "BatteryGuard needs admin access to lock your device when battery is critically low.")
                        })
                    }
                )

                PermCard(
                    title = "Display Over Apps",
                    subtitle = "Required to overlay all screens",
                    iconRes = R.drawable.ic_shield_lock,
                    granted = hasOverlay,
                    onGrant = {
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")))
                    }
                )

                if (isAdmin && hasOverlay) {
                    Card(
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF003320))
                    ) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(painter = painterResource(R.drawable.ic_check), null,
                                tint = Color(0xFF00E676), modifier = Modifier.size(20.dp))
                            Text(
                                "BatteryGuard active. Locks below ${threshold.intValue}%, warns at ${threshold.intValue + BatteryGuardService.WARN_MARGIN}%.",
                                fontSize = 13.sp, color = Color(0xFF00E676), textAlign = TextAlign.Start
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }

    @Composable
    fun PermCard(title: String, subtitle: String, iconRes: Int, granted: Boolean, onGrant: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (granted) Color(0xFF003320) else Color(0xFF2A1A0A)
            )
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (granted) Color(0xFF00E676) else Color(0xFFFF6B35),
                    modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(subtitle, color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
                if (!granted) {
                    Button(
                        onClick = onGrant,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) { Text("Grant", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                } else {
                    Icon(painter = painterResource(R.drawable.ic_check), null,
                        tint = Color(0xFF00E676), modifier = Modifier.size(22.dp))
                }
            }
        }
    }

    private fun getBatteryLevel(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (cap >= 0) return cap
        // Fallback: read from sticky broadcast (works on emulators too)
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (level >= 0 && scale > 0) level * 100 / scale else 0
    }
}
