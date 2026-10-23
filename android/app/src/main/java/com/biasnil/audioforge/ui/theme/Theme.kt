package com.biasnil.audioforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.biasnil.audioforge.data.AppSettings
import com.biasnil.audioforge.data.Appearance
import com.biasnil.audioforge.data.ColourStyle

// The app's own colours by default (the iOS look, the same on every phone);
// Settings -> Appearance can switch to the wallpaper's (Material You) or an accent.
private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color.White,
    // Blue containers (Settings' icon circles, selected chips), not Material's default purple.
    primaryContainer = Color(accentTones(AppSettings.DEFAULT_ACCENT, dark = true).container),
    onPrimaryContainer = Color(accentTones(AppSettings.DEFAULT_ACCENT, dark = true).onContainer),
    secondaryContainer = Color(accentTones(AppSettings.DEFAULT_ACCENT, dark = true).container),
    onSecondaryContainer = Color(accentTones(AppSettings.DEFAULT_ACCENT, dark = true).onContainer),
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
    primaryContainer = Color(accentTones(AppSettings.DEFAULT_ACCENT, dark = false).container),
    onPrimaryContainer = Color(accentTones(AppSettings.DEFAULT_ACCENT, dark = false).onContainer),
    secondaryContainer = Color(accentTones(AppSettings.DEFAULT_ACCENT, dark = false).container),
    onSecondaryContainer = Color(accentTones(AppSettings.DEFAULT_ACCENT, dark = false).onContainer),
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

/** Settings -> Appearance's colour choices, as the theme needs them. */
data class ThemeStyle(
    val colours: ColourStyle = ColourStyle.AudioForge,
    val accent: Int = AppSettings.DEFAULT_ACCENT,
    val pureBlack: Boolean = true,
)

val AppSettings.themeStyle: ThemeStyle
    get() = ThemeStyle(colourStyle, accentColor, pureBlack)

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
    style: ThemeStyle = ThemeStyle(),
    /** Replaces the accent (Now Playing tints its controls with the cover's colour). */
    accent: Color? = null,
    content: @Composable () -> Unit,
) {
    val scheme = colorScheme(darkTheme, style)
    MaterialTheme(
        colorScheme = if (accent != null) scheme.copy(primary = accent, secondary = accent) else scheme,
        content = content,
    )
}

@Composable
private fun colorScheme(dark: Boolean, style: ThemeStyle): ColorScheme {
    val base = when (style.colours) {
        ColourStyle.AudioForge -> if (dark) DarkColors else LightColors
        ColourStyle.MaterialYou -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        ColourStyle.Custom -> {
            val tones = accentTones(style.accent, dark)
            val primary = Color(tones.primary)
            val onPrimary = Color(tones.onPrimary)
            (if (dark) DarkColors else LightColors).copy(
                primary = primary,
                onPrimary = onPrimary,
                secondary = primary,
                onSecondary = onPrimary,
                primaryContainer = Color(tones.container),
                onPrimaryContainer = Color(tones.onContainer),
                secondaryContainer = Color(tones.container),
                onSecondaryContainer = Color(tones.onContainer),
            )
        }
    }
    if (!dark) return base
    // Dark: true black (OLED), or a soft grey with slightly lighter raised rows.
    val window = Color(darkBackground(style.pureBlack))
    return if (style.pureBlack) {
        base.copy(background = window, surface = window, surfaceContainerLowest = window)
    } else {
        base.copy(
            background = window,
            surface = window,
            surfaceContainerLowest = window,
            surfaceVariant = SoftDarkRaised,
            surfaceContainerLow = SoftDarkRaised,
            surfaceContainer = SoftDarkRaised,
            surfaceContainerHigh = SoftDarkRaisedHigh,
            surfaceContainerHighest = SoftDarkRaisedHigh,
        )
    }
}
