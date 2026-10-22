package com.odomap.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** paper, ink, one accent */
object Odo {
    val Paper = Color(0xFFF4EFE5)
    val PaperDeep = Color(0xFFEAE3D5)
    val Card = Color(0xFFFBF8F2)
    val Ink = Color(0xFF22303C)
    val InkSoft = Color(0xFF5E6873)
    val InkFaint = Color(0xFF9AA0A6)
    val Accent = Color(0xFFE2552D)
    val Line = Color(0xFFDDD3C1)
}

private val scheme =
    lightColorScheme(
        primary = Odo.Ink,
        onPrimary = Odo.Paper,
        secondary = Odo.Accent,
        onSecondary = Color.White,
        background = Odo.Paper,
        onBackground = Odo.Ink,
        surface = Odo.Card,
        onSurface = Odo.Ink,
        surfaceVariant = Odo.PaperDeep,
        onSurfaceVariant = Odo.InkSoft,
        outline = Odo.Line,
    )

private val type =
    Typography(
        displayLarge = TextStyle(fontSize = 72.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
        titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 18.sp),
        bodyMedium = TextStyle(fontSize = 16.sp),
        labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    )

@Composable
fun OdoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}

/**
 * Extra UI scale for big in-car windows. Pleos head units report mdpi (160 dpi) on a 2560×1440 screen that is read from arm's length, so
 * plain dp sizes come out tiny. The window's shorter side is mapped to ~[UI_BASE_DP] dp (scale 1…2). Read by [UiScaled] and the map.
 */
val LocalUiScale = compositionLocalOf { 1f }

private const val UI_BASE_DP = 800f

/** Window-size based scale: sets [LocalUiScale] and a scaled [LocalDensity] for [content]. Use once at the root. */
@Composable
fun UiScaleRoot(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val scale = (minOf(maxWidth.value, maxHeight.value) / UI_BASE_DP).coerceIn(1f, 2f)
        CompositionLocalProvider(LocalUiScale provides scale) { UiScaled(content) }
    }
}

/** Applies [LocalUiScale] to the density — also needed inside dialogs, whose window resets LocalDensity. */
@Composable
fun UiScaled(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val scale = LocalUiScale.current
    CompositionLocalProvider(LocalDensity provides Density(base.density * scale, base.fontScale), content = content)
}
