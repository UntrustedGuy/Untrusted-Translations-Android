package com.untrustedtranslations.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object AppColors {
    val Ink = Color(0xFF171520)
    val Paper = Color(0xFFFFF8ED)
    val Violet = Color(0xFF6E51E8)
    val VioletSoft = Color(0xFFEDE8FF)
    val Coral = Color(0xFFFF6B5E)
    val Mist = Color(0xFFF5F2FA)
    val Muted = Color(0xFF706C78)
}

private val AppScheme = lightColorScheme(
    primary = AppColors.Violet, onPrimary = Color.White,
    primaryContainer = AppColors.VioletSoft, secondary = AppColors.Coral,
    background = AppColors.Mist, surface = Color.White,
    onBackground = AppColors.Ink, onSurface = AppColors.Ink,
)

@Composable fun UntrustedTranslationsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppScheme, content = content)
}
