package com.theoriacodex.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val TheoriaNightColorScheme = darkColorScheme(
    primary = Color(0xFF8BC4FF),
    onPrimary = Color(0xFF002E4F),
    secondary = Color(0xFFB0C9FF),
    onSecondary = Color(0xFF1A2E57),
    tertiary = Color(0xFFB8D0C2),
    onTertiary = Color(0xFF23372D),
    background = Color(0xFF0A0F18),
    onBackground = Color(0xFFE3E8F5),
    surface = Color(0xFF111826),
    onSurface = Color(0xFFE3E8F5),
    surfaceVariant = Color(0xFF273041),
    onSurfaceVariant = Color(0xFFC6CDD9),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
fun TheoriaNightTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = TheoriaNightColorScheme,
        content = content,
    )
}
