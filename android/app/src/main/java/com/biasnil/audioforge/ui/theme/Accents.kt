package com.biasnil.audioforge.ui.theme

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The accent colours offered in Settings -> Appearance (ARGB): blue first, today's look. */
val ACCENT_SWATCHES: List<Int> = listOf(
    0xFF0A84FF, // blue
    0xFF3949AB, // indigo
    0xFF7E57C2, // purple
    0xFFE91E63, // pink
    0xFFE53935, // red
    0xFFFB8C00, // orange
    0xFFFFB300, // amber
    0xFF43A047, // green
    0xFF00897B, // teal
    0xFF00ACC1, // cyan
).map { it.toInt() }

/** A custom accent's colour roles (ARGB), for one of light / dark. */
data class AccentTones(val primary: Int, val onPrimary: Int, val container: Int, val onContainer: Int)

/**
 * The accent roles for [seed]: same hue and saturation, lightness moved
 * until text on them is readable (at least 4.5:1, WCAG AA). Light: a
 * darker primary under white text. Dark: a lighter primary under
 * near-black text. No Android types, so it's unit testable.
 */
fun accentTones(seed: Int, dark: Boolean): AccentTones {
    val (h, s, _) = toHsl(seed)
    return if (dark) {
        val onPrimary = fromHsl(h, s, 0.12)
        var l = 0.70
        while (contrastRatio(fromHsl(h, s, l), onPrimary) < MIN_CONTRAST && l < 0.98) l += 0.01
        val onContainer = fromHsl(h, s, 0.90)
        var c = 0.25
        while (contrastRatio(fromHsl(h, s, c), onContainer) < MIN_CONTRAST && c > 0.02) c -= 0.01
        AccentTones(fromHsl(h, s, l), onPrimary, fromHsl(h, s, c), onContainer)
    } else {
        val onPrimary = WHITE
        var l = 0.45
        while (contrastRatio(fromHsl(h, s, l), onPrimary) < MIN_CONTRAST && l > 0.02) l -= 0.01
        val onContainer = fromHsl(h, s, 0.12)
        var c = 0.90
        while (contrastRatio(fromHsl(h, min(s, 0.9), c), onContainer) < MIN_CONTRAST && c < 0.98) c += 0.01
        AccentTones(fromHsl(h, s, l), onPrimary, fromHsl(h, min(s, 0.9), c), onContainer)
    }
}

/** Dark mode's window colour: true black (OLED), or a soft dark grey. */
fun darkBackground(pureBlack: Boolean): Int = if (pureBlack) BLACK else SOFT_DARK

/** WCAG contrast ratio of two opaque colours, 1..21. */
fun contrastRatio(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

private fun luminance(color: Int): Double {
    fun channel(shift: Int): Double {
        val c = ((color shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

/** ARGB -> hue (0..360), saturation and lightness (0..1). */
private fun toHsl(color: Int): Triple<Double, Double, Double> {
    val r = ((color shr 16) and 0xFF) / 255.0
    val g = ((color shr 8) and 0xFF) / 255.0
    val b = (color and 0xFF) / 255.0
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val l = (maxC + minC) / 2
    if (maxC == minC) return Triple(0.0, 0.0, l)
    val d = maxC - minC
    val s = if (l > 0.5) d / (2 - maxC - minC) else d / (maxC + minC)
    val h = when (maxC) {
        r -> ((g - b) / d + (if (g < b) 6 else 0))
        g -> ((b - r) / d + 2)
        else -> ((r - g) / d + 4)
    } * 60
    return Triple(h, s, l)
}

private fun fromHsl(h: Double, s: Double, l: Double): Int {
    val c = (1 - abs(2 * l - 1)) * s
    val x = c * (1 - abs((h / 60) % 2 - 1))
    val m = l - c / 2
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0.0)
        h < 120 -> Triple(x, c, 0.0)
        h < 180 -> Triple(0.0, c, x)
        h < 240 -> Triple(0.0, x, c)
        h < 300 -> Triple(x, 0.0, c)
        else -> Triple(c, 0.0, x)
    }
    fun byte(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
}

private const val MIN_CONTRAST = 4.5
private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()
private const val SOFT_DARK = 0xFF121212.toInt()
