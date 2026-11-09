package com.thermling.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class ThermlingPalette(
    val dark: Boolean,
    val window: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val border: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val canvas: Color,
    val previewBg: Color,
    val pageEdge: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
)

val LightPalette = ThermlingPalette(
    dark = false,
    window = Color(0xFFF4F6F9),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF7F8FA),
    border = Color(0xFFD7DCE4),
    text = Color(0xFF1F2937),
    muted = Color(0xFF6B7280),
    accent = Color(0xFF1A73E8),
    onAccent = Color(0xFFFFFFFF),
    canvas = Color(0xFFE9ECF1),
    previewBg = Color(0xFFDFE3EA),
    pageEdge = Color(0xFFAEB6C2),
    success = Color(0xFF2E7D32),
    warning = Color(0xFF9A5B00),
    danger = Color(0xFFB3261E),
)

val DarkPalette = ThermlingPalette(
    dark = true,
    window = Color(0xFF111318),
    surface = Color(0xFF1C1F26),
    surfaceAlt = Color(0xFF232730),
    border = Color(0xFF3A404C),
    text = Color(0xFFE7E9EE),
    muted = Color(0xFF9AA3B2),
    accent = Color(0xFF7CB0FF),
    onAccent = Color(0xFF0D1524),
    canvas = Color(0xFF0D0F13),
    previewBg = Color(0xFF171A20),
    pageEdge = Color(0xFF555D6C),
    success = Color(0xFF74D187),
    warning = Color(0xFFF3B75F),
    danger = Color(0xFFFF8D84),
)

val LabelPaper = Color.White

val LocalThermlingPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun ThermlingTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.accent,
            background = palette.window,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            error = palette.danger,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            secondary = palette.accent,
            background = palette.window,
            onBackground = palette.text,
            surface = palette.surface,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.muted,
            outline = palette.border,
            error = palette.danger,
        )
    }
    CompositionLocalProvider(LocalThermlingPalette provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
