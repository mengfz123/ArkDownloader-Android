package com.ark.chunkdownloader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = ArkPrimary,
    onPrimary = Color.White,
    background = ArkBg,
    onBackground = ArkText,
    surface = ArkCard,
    onSurface = ArkText,
    secondary = ArkSub,
    error = ArkError
)

@Composable
fun ArkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
