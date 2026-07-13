package com.chikabell.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val ChikaBellLightColors = lightColorScheme(
    primary = Color(0xFF1E6B5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EDE4),
    onPrimaryContainer = Color(0xFF0A392F),
    secondary = Color(0xFF3F665B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEDE7),
    onSecondaryContainer = Color(0xFF183B32),
    tertiary = Color(0xFF7C3D48),
    onTertiary = Color.White,
    background = Color(0xFFF8FAF8),
    onBackground = Color(0xFF1A1C1B),
    surface = Color(0xFFF8FAF8),
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFE2E8E4),
    onSurfaceVariant = Color(0xFF414844),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F6F3),
    surfaceContainer = Color(0xFFECF1ED),
    surfaceContainerHigh = Color(0xFFE6ECE8),
    outline = Color(0xFF747B77),
    outlineVariant = Color(0xFFC4CBC7),
)

@Composable
fun ChikaBellTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ChikaBellLightColors,
        content = content,
    )
}
