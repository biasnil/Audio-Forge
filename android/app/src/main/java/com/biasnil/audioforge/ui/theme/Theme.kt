package com.biasnil.audioforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fixed brand colors rather than Android's wallpaper-based dynamic color,
// so the app looks the same as the desktop version on every phone.
private val DarkColors = darkColorScheme(
    primary = LogoPurple,
    onPrimary = Color.White,
    secondary = LogoPurple,
    onSecondary = Color.White,
    background = DarkWindow,
    onBackground = Color.White,
    surface = DarkWindow,
    onSurface = Color.White,
    surfaceVariant = DarkRaised,
    onSurfaceVariant = DarkMutedText,
    surfaceContainerLowest = DarkBase,
    surfaceContainerLow = DarkBase,
    surfaceContainer = DarkRaised,
    surfaceContainerHigh = DarkRaised,
    surfaceContainerHighest = DarkRaisedHigh,
    outline = DarkOutline,
)

private val LightColors = lightColorScheme(
    primary = DeepPurple,
    onPrimary = Color.White,
    secondary = DeepPurple,
    onSecondary = Color.White,
    background = LightWindow,
    onBackground = Color.Black,
    surface = LightWindow,
    onSurface = Color.Black,
    surfaceVariant = LightRaised,
    onSurfaceVariant = LightMutedText,
    surfaceContainerLowest = LightBase,
    surfaceContainerLow = LightBase,
    surfaceContainer = LightRaised,
    surfaceContainerHigh = LightRaised,
    surfaceContainerHighest = LightRaisedHigh,
    outline = LightOutline,
)

@Composable
fun AudioForgeTheme(
    darkTheme: Boolean = true, // dark by default, same as the desktop app
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
