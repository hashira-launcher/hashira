package com.hashira.launcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Minimalist: white, black and grays.
private val Scheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2B2B2B),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFF9E9E9E),
    background = Color.Black,
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF0F0F0F),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1C1C1C),
    onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF616161)
)

@Composable
fun HashiraTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
