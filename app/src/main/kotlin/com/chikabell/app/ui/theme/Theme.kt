package com.chikabell.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private val ChikaBellLightColors = lightColorScheme(
    primary = Color(0xFF1B6353),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDF4EF),
    onPrimaryContainer = Color(0xFF0A392F),
    secondary = Color(0xFF3F665B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEDE7),
    onSecondaryContainer = Color(0xFF183B32),
    tertiary = Color(0xFF7C3D48),
    onTertiary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1D3029),
    surface = Color.White,
    onSurface = Color(0xFF1D3029),
    surfaceVariant = Color(0xFFE2E8E4),
    onSurfaceVariant = Color(0xFF4B5E54),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEDF4EF),
    surfaceContainer = Color(0xFFEDF4EF),
    surfaceContainerHigh = Color(0xFFE6ECE8),
    outline = Color(0xFF82958B),
    outlineVariant = Color(0xFFC4CBC7),
)

@Composable
fun ChikaBellTheme(content: @Composable () -> Unit) {
    val type = Typography()
    MaterialTheme(
        colorScheme = ChikaBellLightColors,
        typography = type.copy(bodySmall = type.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp)),
        content = content,
    )
}
