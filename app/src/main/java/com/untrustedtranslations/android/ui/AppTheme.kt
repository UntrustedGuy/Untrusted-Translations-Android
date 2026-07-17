package com.untrustedtranslations.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AppColors {
    val Void = Color(0xFF09090D)
    val Surface = Color(0xFF14141B)
    val SurfaceRaised = Color(0xFF1D1D27)
    val Violet = Color(0xFF9B7BFF)
    val Cyan = Color(0xFF63E6D6)
    val Coral = Color(0xFFFF6F73)
    val Text = Color(0xFFF5F2FF)
    val Muted = Color(0xFFA8A3B5)
}

private val AppScheme = darkColorScheme(
    primary = AppColors.Violet,
    onPrimary = Color(0xFF130D28),
    primaryContainer = Color(0xFF30235A),
    secondary = AppColors.Cyan,
    tertiary = AppColors.Coral,
    background = AppColors.Void,
    surface = AppColors.Surface,
    surfaceVariant = AppColors.SurfaceRaised,
    onBackground = AppColors.Text,
    onSurface = AppColors.Text,
    onSurfaceVariant = AppColors.Muted,
    outline = Color(0xFF454252),
)

@Composable fun UntrustedTranslationsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppScheme, content = content)
}
