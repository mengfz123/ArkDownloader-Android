package com.ark.chunkdownloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = ArkPrimary,
    onPrimary = Color.White,
    primaryContainer = ArkPrimarySoft,
    onPrimaryContainer = ArkPrimary,
    secondary = ArkSub,
    onSecondary = ArkBg,
    background = ArkBg,
    onBackground = ArkText,
    surface = ArkCard,
    onSurface = ArkText,
    surfaceVariant = ArkCard2,
    onSurfaceVariant = ArkSub,
    outline = ArkBorder,
    error = ArkError,
    onError = Color.White,
    errorContainer = ArkErrorSoft
)

@Composable
fun ArkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = ArkTypography,
        content = content
    )
}
