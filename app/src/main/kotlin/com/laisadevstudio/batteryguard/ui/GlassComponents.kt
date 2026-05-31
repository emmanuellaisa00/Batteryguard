package com.laisadevstudio.batteryguard.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.laisadevstudio.batteryguard.ui.LocalUiAccessibility

private val GlassBorder = Color.White.copy(alpha = 0.12f)
private val GlassFill = Color.White.copy(alpha = 0.06f)
private val GlassFillStrong = Color.White.copy(alpha = 0.10f)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF9DE1FF),
    stronger: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val accessibility = LocalUiAccessibility.current
    val cardColor = when {
        accessibility.reduceTransparency && stronger -> Color(0xFF132537)
        accessibility.reduceTransparency -> Color(0xFF10202F)
        stronger -> GlassFillStrong
        else -> GlassFill
    }
    val overlayAlpha = if (accessibility.reduceTransparency) 0.03f else if (stronger) 0.06f else 0.045f
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = BorderStroke(1.dp, GlassBorder.copy(alpha = if (accessibility.reduceTransparency) 0.18f else 0.6f))
    ) {
        Box(
            modifier = Modifier
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = overlayAlpha),
                            Color.White.copy(alpha = 0.015f),
                            Color.Transparent
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(content = content)
        }
    }
}

@Composable
fun GlassPill(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF9DE1FF),
    active: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val accessibility = LocalUiAccessibility.current
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ) else Modifier
            ),
        color = if (accessibility.reduceTransparency) {
            if (active) Color(0xFF173047) else Color(0xFF10202F)
        } else {
            if (active) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f)
        },
        border = BorderStroke(1.dp, Color.White.copy(alpha = if (active) 0.22f else 0.10f)),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = if (active) 0.96f else 0.65f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun MetricBubble(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF9DE1FF)
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label, color = Color.White.copy(alpha = 0.58f), fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun GlassSectionTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
fun LiquidProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    accent: Color = Color(0xFF9DE1FF)
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.10f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            accent.copy(alpha = 0.55f),
                            accent,
                            Color.White.copy(alpha = 0.85f)
                        )
                    )
                )
        )
    }
}

@Composable
fun GlassToggleDot(active: Boolean, color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(if (active) color else Color.White.copy(alpha = 0.20f))
    )
}
