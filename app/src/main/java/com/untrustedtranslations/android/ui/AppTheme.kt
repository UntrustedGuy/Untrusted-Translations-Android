package com.untrustedtranslations.android.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object AppColors {
    val Void = Color(0xFF08080D)
    val Surface = Color(0xFF12121A)
    val SurfaceRaised = Color(0xFF1B1A25)
    val Violet = Color(0xFFA58AFF)
    val Cyan = Color(0xFF67E8D7)
    val Coral = Color(0xFFFF7378)
    val Text = Color(0xFFF7F3FF)
    val Muted = Color(0xFFAAA5B8)
}

private val AppScheme = darkColorScheme(
    primary = AppColors.Violet,
    onPrimary = Color(0xFF160E2C),
    primaryContainer = Color(0xFF342662),
    onPrimaryContainer = Color(0xFFE9E0FF),
    secondary = AppColors.Cyan,
    onSecondary = Color(0xFF071F1C),
    secondaryContainer = Color(0xFF163F3A),
    tertiary = AppColors.Coral,
    background = AppColors.Void,
    surface = AppColors.Surface,
    surfaceVariant = AppColors.SurfaceRaised,
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.SurfaceRaised,
    onBackground = AppColors.Text,
    onSurface = AppColors.Text,
    onSurfaceVariant = AppColors.Muted,
    outline = Color(0xFF4B4759),
    outlineVariant = Color(0xFF302E3A),
    error = Color(0xFFFF8A8F),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 38.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.25.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun UntrustedTranslationsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
