package com.ringpass.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val RingPassDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FD1C5),
    onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF134E4A),
    onPrimaryContainer = Color(0xFF99F6E4),
    secondary = Color(0xFF818CF8),
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF2E2A5C),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Color(0xFFFBBF24),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF121722),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1B2333),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF2D3748),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val RingPassTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun RingPassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RingPassDarkColorScheme,
        typography = RingPassTypography,
        content = content
    )
}
