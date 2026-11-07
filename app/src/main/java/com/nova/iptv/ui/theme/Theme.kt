package com.nova.iptv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val NovaBlue = Color(0xFF6C7BFF)
val NovaViolet = Color(0xFF9B6CFF)
val NovaCyan = Color(0xFF38BDF8)
val NovaBlack = Color(0xFF07070B)
val NovaCharcoal = Color(0xFF111118)
val NovaCharcoal2 = Color(0xFF1A1A24)
val NovaLive = Color(0xFFFF4D6D)

private val DarkColors = darkColorScheme(
    primary = NovaBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A2F66),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = NovaViolet,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF3A2A66),
    onSecondaryContainer = Color(0xFFEADDFF),
    tertiary = NovaCyan,
    background = NovaBlack,
    onBackground = Color(0xFFE8E8F0),
    surface = NovaCharcoal,
    onSurface = Color(0xFFE8E8F0),
    surfaceVariant = NovaCharcoal2,
    onSurfaceVariant = Color(0xFFA9A9BC),
    surfaceContainer = NovaCharcoal,
    surfaceContainerHigh = NovaCharcoal2,
    surfaceContainerHighest = Color(0xFF23232F),
    surfaceContainerLow = Color(0xFF0D0D13),
    outline = Color(0xFF3A3A4A),
    outlineVariant = Color(0xFF26263A),
    error = Color(0xFFFF6B81),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4A57E0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFF7A4FE0),
    secondaryContainer = Color(0xFFEADDFF),
    tertiary = Color(0xFF0284C7),
    background = Color(0xFFF7F7FB),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEDF5),
    onSurfaceVariant = Color(0xFF55556A),
    outline = Color(0xFFC4C4D4),
)

private val NovaTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

/** UI flags provided app-wide. */
data class NovaUiFlags(val lowEnd: Boolean = false, val isTv: Boolean = false)

val LocalNovaFlags = staticCompositionLocalOf { NovaUiFlags() }

val NovaGradient: Brush get() = Brush.linearGradient(listOf(NovaBlue, NovaViolet))

@Composable
fun NovaTheme(themeMode: String, flags: NovaUiFlags, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    CompositionLocalProvider(LocalNovaFlags provides flags) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = NovaTypography,
            content = content,
        )
    }
}
