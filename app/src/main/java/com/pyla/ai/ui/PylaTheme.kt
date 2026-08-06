package com.pyla.ai.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Single source of truth for the PylaAndroid design language. Raw ARGB ints live in
 * [PylaPalette] so both Compose and the platform-View floating overlay share the exact same
 * palette without pulling Compose into the overlay service.
 *
 * The palette is intentionally restrained: a near-black surface system with one warm accent,
 * high-contrast text, and hairline outlines — close to stock Material 3 dark with a unique
 * identity, not a futuristic glass concept.
 */
object PylaPalette {
    const val BACKGROUND = 0xFF0E1218.toInt()
    const val SURFACE = 0xFF161B24.toInt()
    const val SURFACE_VARIANT = 0xFF1E2430.toInt()
    const val OUTLINE = 0xFF2A313D.toInt()

    const val YELLOW = 0xFFFFC400.toInt()
    const val GREEN = 0xFF4CD964.toInt()
    const val RED = 0xFFFF5252.toInt()
    const val BLUE = 0xFF54A9FF.toInt()
    const val PURPLE = 0xFF9C8CFF.toInt()
    const val TEAL = 0xFF5AC8FA.toInt()
    const val ORANGE = 0xFFFF9500.toInt()

    const val TEXT_HIGH = 0xFFEDEFF4.toInt()
    const val TEXT_MED = 0xFFB6BCC8.toInt()
    const val TEXT_LOW = 0xFF848B99.toInt()
}

object PylaColors {
    val Background = Color(PylaPalette.BACKGROUND)
    val Surface = Color(PylaPalette.SURFACE)
    val SurfaceVariant = Color(PylaPalette.SURFACE_VARIANT)
    val Outline = Color(PylaPalette.OUTLINE)

    val Yellow = Color(PylaPalette.YELLOW)
    val Green = Color(PylaPalette.GREEN)
    val Red = Color(PylaPalette.RED)
    val Blue = Color(PylaPalette.BLUE)
    val Purple = Color(PylaPalette.PURPLE)
    val Teal = Color(PylaPalette.TEAL)
    val Orange = Color(PylaPalette.ORANGE)

    val TextHigh = Color(PylaPalette.TEXT_HIGH)
    val TextMed = Color(PylaPalette.TEXT_MED)
    val TextLow = Color(PylaPalette.TEXT_LOW)
}

private val CardShape = RoundedCornerShape(16.dp)
private val PillShape = RoundedCornerShape(20.dp)

/** Subtle elevated card: solid surface, thin outline, no blur, no glow. */
@Composable
fun PylaCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        colors = CardDefaults.cardColors(containerColor = PylaColors.Surface),
        border = BorderStroke(1.dp, PylaColors.Outline),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) { content() }
    }
}

@Composable
fun SectionHeader(icon: ImageVector, title: String, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Text(
            title,
            style = titleMedium(),
            color = PylaColors.TextHigh,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun titleLarge(): TextStyle = TextStyle(
    fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PylaColors.TextHigh,
)
@Composable
fun titleMedium(): TextStyle = TextStyle(
    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = PylaColors.TextHigh,
)
@Composable
fun titleSmall(): TextStyle = TextStyle(
    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = PylaColors.TextHigh,
)
@Composable
fun bodyMono(): TextStyle = TextStyle(
    fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = PylaColors.TextMed,
)

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(PillShape)
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.30f), PillShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
    }
}

/** Primary action button: solid accent, flat, press scale feedback. */
@Composable
fun PrimaryButton(
    label: String,
    icon: ImageVector?,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pressed = remember { mutableStateOf(false) }
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed.value && enabled) 0.97f else 1f,
        animationSpec = tween(120), label = "btn",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .alpha(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) accent else accent.copy(alpha = 0.22f))
            .clickable(enabled = enabled) {
                pressed.value = true
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
            Text(label, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
    LaunchedEffect(pressed.value) {
        if (pressed.value) { delay(110); pressed.value = false }
    }
}

@Composable
fun SecondaryButton(
    label: String,
    icon: ImageVector?,
    accent: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(PylaColors.SurfaceVariant)
            .border(1.dp, if (enabled) accent.copy(alpha = 0.40f) else PylaColors.Outline, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, tint = if (enabled) accent else accent.copy(alpha = 0.35f), modifier = Modifier.size(16.dp))
            Text(label, color = if (enabled) accent else accent.copy(alpha = 0.45f), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        }
    }
}

@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color = PylaColors.TextMed) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = PylaColors.TextLow, fontSize = 13.sp)
        Text(value, color = valueColor, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}