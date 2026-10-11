package com.biasnil.audioforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.biasnil.audioforge.data.Appearance

// Fixed colors rather than Android's wallpaper-based dynamic color, so the
// app looks the same as the iOS version on every phone.
private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color.White,
    secondary = BlueDark,
    onSecondary = Color.White,
    background = DarkWindow,
    onBackground = Color.White,
    surface = DarkWindow,
    onSurface = Color.White,
    surfaceVariant = DarkRaised,
    onSurfaceVariant = DarkMutedText,
    surfaceContainerLowest = DarkBase,
    surfaceContainerLow = DarkRaised,
    surfaceContainer = DarkRaised,
    surfaceContainerHigh = DarkRaisedHigh,
    surfaceContainerHighest = DarkRaisedHigh,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
)

private val LightColors = lightColorScheme(
    primary = BlueLight,
    onPrimary = Color.White,
    secondary = BlueLight,
    onSecondary = Color.White,
    background = LightWindow,
    onBackground = Color.Black,
    surface = LightWindow,
    onSurface = Color.Black,
    surfaceVariant = LightRaised,
    onSurfaceVariant = LightMutedText,
    surfaceContainerLowest = LightBase,
    surfaceContainerLow = LightRaised,
    surfaceContainer = LightRaised,
    surfaceContainerHigh = LightRaisedHigh,
    surfaceContainerHighest = LightRaisedHigh,
    outline = LightOutline,
    outlineVariant = LightOutline,
)

/** Whether [appearance] means dark right now (System follows the phone). */
@Composable
fun isDark(appearance: Appearance): Boolean = when (appearance) {
    Appearance.System -> isSystemInDarkTheme()
    Appearance.Light -> false
    Appearance.Dark -> true
}

@Composable
fun AudioForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /** Replaces the accent (Now Playing tints its controls with the cover's colour). */
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val base = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = if (accent != null) base.copy(primary = accent, secondary = accent) else base,
        content = content,
    )
}
