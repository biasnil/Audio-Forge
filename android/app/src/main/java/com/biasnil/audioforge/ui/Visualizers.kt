package com.biasnil.audioforge.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import com.biasnil.audioforge.audio.VisualizerTap
import com.biasnil.audioforge.data.CustomMediaEntry
import com.biasnil.audioforge.data.VisualizerStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * One frame of the visualizer: the spectrum ([levels], 0..1, low to high),
 * the time, and how far a record has turned (only turns while music plays).
 */
class VisualizerFrame(val levels: FloatArray, val timeMs: Long, val spinDegrees: Float)

/**
 * The visualizer's frames, ~30 a second, while [active]. Measuring the
 * spectrum costs a little, so the tap is only on while something shows it.
 * [idleMotion]: when nothing plays, gentle made-up levels so the Settings
 * previews still show what each style looks like.
 */
@Composable
fun rememberVisualizerFrame(active: Boolean, idleMotion: Boolean = false): State<VisualizerFrame> {
    val playback = LocalAppContainer.current.playback
    DisposableEffect(active) {
        if (active) playback.acquireVisualizer()
        onDispose { if (active) playback.releaseVisualizer() }
    }
    val frame = remember { mutableStateOf(VisualizerFrame(FloatArray(VisualizerTap.BANDS), 0L, 0f)) }
    LaunchedEffect(active, idleMotion) {
        if (!active) return@LaunchedEffect
        var spin = frame.value.spinDegrees
        var last = 0L
        var lastEmit = 0L
        while (true) {
            val now = withFrameMillis { it }
            val dt = if (last == 0L) 0L else now - last
            last = now
            if (now - lastEmit < FRAME_MS) continue
            lastEmit = now
            val playing = playback.state.value.isPlaying
            if (playing) spin = (spin + dt * DISC_DEGREES_PER_MS) % 360f
            var levels = playback.visualizer.levels()
            if (idleMotion && levels.all { it < 0.02f }) levels = idleLevels(now)
            frame.value = VisualizerFrame(levels, now, spin)
        }
    }
    return frame
}

private const val FRAME_MS = 30L
/** 33⅓ rpm. */
private const val DISC_DEGREES_PER_MS = 360f * 33.333f / 60_000f

/** A slow made-up spectrum (louder lows), for previews when nothing plays. */
private fun idleLevels(timeMs: Long): FloatArray {
    val t = timeMs / 1000.0
    return FloatArray(VisualizerTap.BANDS) { band ->
        val x = band.toDouble() / VisualizerTap.BANDS
        (0.25 + 0.35 * (1 - x) + 0.2 * sin(t * 2.1 + band * 0.7) + 0.1 * sin(t * 3.7 + band * 1.9)).toFloat().coerceIn(0.05f, 1f)
    }
}

/**
 * Draws [style] with [color] (the song's cover colour) and its own darker
 * and shifted shades. [cover] is the record label for [VisualizerStyle.Disc].
 */
@Composable
fun VisualizerView(style: VisualizerStyle, frame: VisualizerFrame, color: Color, cover: ImageBitmap?, modifier: Modifier = Modifier) {
    val particles = remember(style) { if (style == VisualizerStyle.Particles) ParticleField() else null }
    Canvas(modifier) {
        val levels = frame.levels
        when (style) {
            VisualizerStyle.ClassicBars -> classicBars(levels, color)
            VisualizerStyle.Spectrum -> spectrum(levels, color)
            VisualizerStyle.NeonBars -> neonBars(levels, color)
            VisualizerStyle.LineWave -> lineWave(levels, frame.timeMs, color)
            VisualizerStyle.RetroWave -> retroWave(levels, frame.timeMs, color)
            VisualizerStyle.FlowingWave -> flowingWave(levels, frame.timeMs, color)
            VisualizerStyle.Radial -> radial(levels, frame.timeMs, color)
            VisualizerStyle.Particles -> particles?.draw(this, levels, frame.timeMs, color)
            VisualizerStyle.Geometric -> geometric(levels, frame.timeMs, color)
            VisualizerStyle.Disc -> disc(levels, frame.spinDegrees, color, cover)
        }
    }
}

// --- Colour helpers ------------------------------------------------------------

/** [this] with its hue turned by [degrees] (for two-colour styles from one cover colour). */
private fun Color.shiftHue(degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[0] = (hsv[0] + degrees + 360f) % 360f
    hsv[1] = hsv[1].coerceAtLeast(0.45f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun Color.lighter(amount: Float) = lerp(this, Color.White, amount)

/** The spectrum at [x] (0 = lowest band, 1 = highest), interpolated. */
private fun FloatArray.at(x: Float): Float {
    val position = x.coerceIn(0f, 1f) * (size - 1)
    val i = position.toInt().coerceAtMost(size - 2)
    val f = position - i
    return this[i] * (1 - f) + this[i + 1] * f
}

private fun FloatArray.bass(): Float = (0 until 5).map { this[it] }.average().toFloat()
private fun FloatArray.energy(): Float = average().toFloat()

// --- Bars --------------------------------------------------------------------------

private fun DrawScope.classicBars(levels: FloatArray, color: Color) {
    val count = 18
    val slot = size.width / count
    val width = slot * 0.7f
    val brush = Brush.verticalGradient(listOf(color.lighter(0.25f), color.copy(alpha = 0.7f)))
    for (i in 0 until count) {
        val height = (size.height * 0.92f * levels.at(i / (count - 1f))).coerceAtLeast(size.height * 0.03f)
        val left = i * slot + (slot - width) / 2
        // A soft halo behind each bar, then the bar.
        drawRect(color.copy(alpha = 0.18f), Offset(left - width * 0.15f, size.height - height - width * 0.15f), Size(width * 1.3f, height + width * 0.15f))
        drawRect(brush, Offset(left, size.height - height), Size(width, height))
    }
}

/** Many thin columns of LED-like segments, brightest at the bottom. */
private fun DrawScope.spectrum(levels: FloatArray, color: Color) {
    val columns = 40
    val rows = 32
    val slot = size.width / columns
    val segment = size.height / rows
    for (c in 0 until columns) {
        val lit = (levels.at(c / (columns - 1f)) * rows).toInt().coerceAtLeast(1)
        for (r in 0 until lit) {
            val alpha = 0.95f - 0.6f * r / rows
            drawRect(
                color.copy(alpha = alpha),
                Offset(c * slot + slot * 0.12f, size.height - (r + 1) * segment + segment * 0.15f),
                Size(slot * 0.76f, segment * 0.7f),
            )
        }
    }
}

/** A few wide, rounded, glowing bars. */
private fun DrawScope.neonBars(levels: FloatArray, color: Color) {
    val count = 7
    val slot = size.width / count
    val width = slot * 0.55f
    val core = color.lighter(0.45f)
    for (i in 0 until count) {
        val level = (0 until 3).map { levels.at((i * 3 + it) / (count * 3 - 1f)) }.average().toFloat()
        val height = (size.height * 0.85f * level).coerceAtLeast(width)
        val left = i * slot + (slot - width) / 2
        val top = size.height * 0.95f - height
        for ((grow, alpha) in listOf(0.6f to 0.08f, 0.35f to 0.16f, 0.15f to 0.3f)) {
            val g = width * grow
            drawRoundRect(color.copy(alpha = alpha), Offset(left - g, top - g), Size(width + 2 * g, height + 2 * g), CornerRadius((width + 2 * g) / 2))
        }
        drawRoundRect(core, Offset(left, top), Size(width, height), CornerRadius(width / 2))
    }
}

// --- Waves -----------------------------------------------------------------------------

private fun DrawScope.wavePath(points: Int, y: (Float) -> Float): Path = Path().apply {
    for (i in 0..points) {
        val x = i / points.toFloat()
        if (i == 0) moveTo(0f, y(x)) else lineTo(x * size.width, y(x))
    }
}

/** A thin, jittery line following the whole spectrum. */
private fun DrawScope.lineWave(levels: FloatArray, timeMs: Long, color: Color) {
    val t = timeMs / 1000f
    val mid = size.height / 2
    val path = wavePath(120) { x ->
        val amplitude = size.height * (0.06f + 0.38f * levels.at(x))
        mid + amplitude * sin(x * 2 * PI.toFloat() * 7 - t * 4f) * (0.7f + 0.3f * sin(x * 23f + t * 2f))
    }
    val line = color.lighter(0.7f)
    drawPath(path, line.copy(alpha = 0.18f), style = Stroke(width = size.minDimension * 0.03f, cap = StrokeCap.Round))
    drawPath(path, line, style = Stroke(width = size.minDimension * 0.008f, cap = StrokeCap.Round))
}

/** A slow, smooth, thick wave driven by the bass, like an old oscilloscope. */
private fun DrawScope.retroWave(levels: FloatArray, timeMs: Long, color: Color) {
    val t = timeMs / 1000f
    val mid = size.height / 2
    val amplitude = size.height * (0.08f + 0.32f * levels.bass())
    val path = wavePath(120) { x -> mid + amplitude * sin(x * 2 * PI.toFloat() * 2.5f - t * 1.6f) * (0.8f + 0.2f * sin(x * 6f + t)) }
    val warm = color.lighter(0.3f)
    drawPath(path, warm.copy(alpha = 0.15f), style = Stroke(width = size.minDimension * 0.07f, cap = StrokeCap.Round))
    drawPath(path, warm, style = Stroke(width = size.minDimension * 0.022f, cap = StrokeCap.Round))
}

/** Layers of soft, flowing colour that swell with the music. */
private fun DrawScope.flowingWave(levels: FloatArray, timeMs: Long, color: Color) {
    val t = timeMs / 1000f
    val second = color.shiftHue(-50f)
    drawRect(Brush.linearGradient(listOf(color.copy(alpha = 0.55f), second.copy(alpha = 0.55f)), Offset.Zero, Offset(size.width, size.height)))
    val energy = levels.energy()
    for (layer in 0 until 5) {
        val base = size.height * (0.2f + layer * 0.17f)
        val amplitude = size.height * (0.05f + 0.12f * energy + 0.02f * layer)
        val speed = 0.4f + layer * 0.15f
        val path = wavePath(60) { x -> base + amplitude * sin(x * 2 * PI.toFloat() * (0.8f + layer * 0.25f) + t * speed + layer) }
        path.lineTo(size.width, size.height)
        path.lineTo(0f, size.height)
        path.close()
        val shade = lerp(color, second, layer / 4f)
        drawPath(path, Brush.verticalGradient(listOf(shade.lighter(0.25f).copy(alpha = 0.45f), shade.copy(alpha = 0.15f)), base - amplitude, size.height))
    }
}

// --- Circular, particles, geometric ------------------------------------------------

/** Concentric rings that breathe, ringed by a dial of ticks following the spectrum. */
private fun DrawScope.radial(levels: FloatArray, timeMs: Long, color: Color) {
    val t = timeMs / 1000f
    val c = center
    val r = size.minDimension / 2
    val bass = levels.bass()
    for (ring in 0 until 4) {
        val radius = r * (0.22f + ring * 0.13f) * (1 + 0.08f * bass)
        drawCircle(color.copy(alpha = 0.12f), radius, c, style = Stroke(width = r * 0.06f))
        drawCircle(color.lighter(0.3f).copy(alpha = 0.85f - ring * 0.15f), radius, c, style = Stroke(width = r * 0.012f))
    }
    // Rotating dashed arcs.
    rotate(t * 20f, c) {
        for (arc in 0 until 6) {
            drawArc(color.copy(alpha = 0.6f), arc * 60f, 35f, false, Offset(c.x - r * 0.75f, c.y - r * 0.75f), Size(r * 1.5f, r * 1.5f), style = Stroke(width = r * 0.02f))
        }
    }
    val ticks = 72
    for (i in 0 until ticks) {
        val angle = i * 2 * PI / ticks - PI / 2
        val level = levels.at(abs(i - ticks / 2f) / (ticks / 2f))
        val inner = r * 0.8f
        val outer = inner + r * (0.03f + 0.17f * level)
        drawLine(
            color.lighter(0.2f).copy(alpha = 0.5f + 0.5f * level),
            Offset(c.x + inner * cos(angle).toFloat(), c.y + inner * sin(angle).toFloat()),
            Offset(c.x + outer * cos(angle).toFloat(), c.y + outer * sin(angle).toFloat()),
            strokeWidth = r * 0.018f,
            cap = StrokeCap.Round,
        )
    }
}

/** Sparks bursting from the middle, more and faster on loud beats. */
private class ParticleField {
    private class Particle(var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Float, val size: Float)

    private val particles = ArrayList<Particle>()
    private var lastMs = 0L
    private val random = Random(7)

    fun draw(scope: DrawScope, levels: FloatArray, timeMs: Long, color: Color) = with(scope) {
        val dt = if (lastMs == 0L) 0f else ((timeMs - lastMs) / 1000f).coerceIn(0f, 0.1f)
        lastMs = timeMs
        val bass = levels.bass()
        val energy = levels.energy()
        val spawn = (2 + bass * 14).toInt()
        repeat(spawn) {
            if (particles.size >= MAX) return@repeat
            val angle = random.nextFloat() * 2 * PI.toFloat()
            val speed = size.minDimension * (0.15f + random.nextFloat() * 0.6f) * (0.6f + energy)
            particles += Particle(center.x, center.y, cos(angle) * speed, sin(angle) * speed, 1f, size.minDimension * (0.004f + random.nextFloat() * 0.01f))
        }
        val iterator = particles.iterator()
        val spark = color.lighter(0.3f)
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt * 0.7f
            if (p.life <= 0f || p.x < 0 || p.y < 0 || p.x > size.width || p.y > size.height) {
                iterator.remove()
                continue
            }
            // A short streak behind fast sparks.
            drawLine(spark.copy(alpha = p.life * 0.35f), Offset(p.x - p.vx * 0.05f, p.y - p.vy * 0.05f), Offset(p.x, p.y), strokeWidth = p.size)
            drawCircle(spark.copy(alpha = p.life), p.size, Offset(p.x, p.y))
        }
        drawCircle(color.copy(alpha = 0.25f + 0.4f * bass), size.minDimension * (0.03f + 0.05f * bass), center)
    }

    private companion object {
        const val MAX = 600
    }
}

/** A band of sharp triangles whose points jump with the spectrum. */
private fun DrawScope.geometric(levels: FloatArray, timeMs: Long, color: Color) {
    val t = timeMs / 1000f
    val count = 7
    val step = size.width / count
    val mid = size.height / 2
    val edge = color.lighter(0.35f)
    for (i in 0 until count) {
        val level = levels.at(i / (count - 1f))
        val up = if (i % 2 == 0) -1 else 1
        val height = size.height * (0.12f + 0.33f * level) * up
        val x0 = i * step + step * 0.08f * sin(t + i)
        val path = Path().apply {
            moveTo(x0, mid - height * 0.3f)
            lineTo(x0 + step * 1.15f, mid + height * 0.25f)
            lineTo(x0 + step * 0.55f, mid + height)
            close()
        }
        drawPath(path, Brush.linearGradient(listOf(color.copy(alpha = 0.75f), color.copy(alpha = 0.2f)), Offset(x0, mid), Offset(x0 + step, mid + height)))
        drawPath(path, edge, style = Stroke(width = size.minDimension * 0.006f))
    }
}

// --- Record ----------------------------------------------------------------------------

/** A record turning while music plays, the song's cover on its label. */
private fun DrawScope.disc(levels: FloatArray, spinDegrees: Float, color: Color, cover: ImageBitmap?) {
    val c = center
    val r = size.minDimension * 0.47f
    // A glow on the beat, behind the record.
    drawCircle(color.copy(alpha = 0.12f + 0.25f * levels.bass()), r * 1.04f, c)
    drawCircle(Color(0xFF111114), r, c)
    for (groove in 0 until 18) {
        drawCircle(Color.White.copy(alpha = if (groove % 3 == 0) 0.07f else 0.035f), r * (0.42f + groove * 0.031f), c, style = Stroke(width = r * 0.006f))
    }
    rotate(spinDegrees, c) {
        // The light catching the grooves turns with the record.
        drawArc(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent), c), -20f, 40f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
        drawArc(Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.08f), Color.Transparent), c), 160f, 40f, true, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r))
        val label = r * 0.36f
        val circle = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, label)) }
        clipPath(circle) {
            if (cover != null) {
                val side = (label * 2).toInt()
                drawImage(cover, dstOffset = IntOffset((c.x - label).toInt(), (c.y - label).toInt()), dstSize = IntSize(side, side))
            } else {
                drawCircle(color, label, c)
            }
        }
    }
    drawCircle(Color.Black.copy(alpha = 0.6f), r * 0.03f, c)
}

// --- Custom GIF / video --------------------------------------------------------------

/**
 * A user's GIF or looping video: cropped to a circle if [entry] is round,
 * turning by [spinDegrees] if it spins. Videos play muted with their own
 * small player (like the video wallpaper). [onFailed] if it can't be shown.
 */
@Composable
fun CustomMediaView(entry: CustomMediaEntry, spinDegrees: Float, onFailed: () -> Unit, modifier: Modifier = Modifier) {
    val shape = if (entry.round) CircleShape else RoundedCornerShape(10)
    val turned = modifier
        .graphicsLayer { rotationZ = if (entry.spin) spinDegrees else 0f }
        .clip(shape)
    if (entry.isVideo) {
        VideoWallpaper(videoUri = entry.uri, opacity = 1f, onFailed = onFailed, modifier = turned)
    } else {
        AnimatedImage(entry.uri, onFailed, turned)
    }
}

/** A GIF (or animated WebP / still image), cropped to fill, looping. */
@Composable
private fun AnimatedImage(uri: String, onFailed: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri)))
            } catch (e: Exception) {
                null
            }
        }
        if (value == null) onFailed()
    }
    val image = drawable ?: return Box(modifier)
    DisposableEffect(image) {
        (image as? AnimatedImageDrawable)?.apply {
            repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            start()
        }
        onDispose { (image as? AnimatedImageDrawable)?.stop() }
    }
    AndroidView(
        factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } },
        update = { it.setImageDrawable(image) },
        modifier = modifier,
    )
}
