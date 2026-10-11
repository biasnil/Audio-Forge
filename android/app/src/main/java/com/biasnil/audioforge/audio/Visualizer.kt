package com.biasnil.audioforge.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Spectrum levels for the Now Playing visualizer (the iOS VisualizerTap):
 * the active player's effects processor writes its mono output here while
 * [enabled]; [levels] turns the latest 1024 samples into [BANDS] bars, 0..1,
 * low to high. One writer (the audio thread), one reader (the UI); a torn
 * read only means one slightly odd frame of bars.
 */
class VisualizerTap {
    @Volatile
    var enabled = false

    @Volatile
    var sampleRate = 44_100

    private val ring = FloatArray(RING)
    @Volatile
    private var written = 0L

    /** Audio thread: one mono sample. */
    fun push(sample: Float) {
        val index = written
        ring[(index and (RING - 1).toLong()).toInt()] = sample
        written = index + 1
    }

    private val fft = AnalysisMath.Fft(SIZE)
    private val window = DoubleArray(SIZE) { 0.5 - 0.5 * cos(2 * PI * it / SIZE) }
    private val re = DoubleArray(SIZE)
    private val im = DoubleArray(SIZE)
    private val smoothed = FloatArray(BANDS)
    private var lastWritten = -1L

    /** UI thread, ~30 times a second: the current bars. Falls back to zero when nothing new has played. */
    fun levels(): FloatArray {
        val end = written
        if (end == lastWritten || end < SIZE) {
            // Paused: let the bars fall.
            for (band in 0 until BANDS) smoothed[band] *= 0.85f
            return smoothed.copyOf()
        }
        lastWritten = end
        val start = end - SIZE
        for (i in 0 until SIZE) {
            re[i] = ring[((start + i) and (RING - 1).toLong()).toInt()] * window[i]
            im[i] = 0.0
        }
        fft.transform(re, im)
        // Logarithmically spaced bands from ~40 Hz to ~16 kHz, in dB, mapped to 0..1.
        val binWidth = sampleRate / 2.0 / (SIZE / 2)
        for (band in 0 until BANDS) {
            val low = 40 * 400.0.pow(band.toDouble() / BANDS)
            val high = 40 * 400.0.pow((band + 1).toDouble() / BANDS)
            val first = max(1, (low / binWidth).toInt())
            val last = min(SIZE / 2 - 1, max(first, (high / binWidth).toInt()))
            var peak = 0.0
            for (bin in first..last) peak = max(peak, sqrt(re[bin] * re[bin] + im[bin] * im[bin]))
            val db = 20 * log10(max(peak / SIZE * 2, 1e-6)) // ×2: the iOS FFT's scale
            val level = ((db + 70) / 60).toFloat().coerceIn(0f, 1f)
            // Rise quickly, fall slowly.
            smoothed[band] = if (level > smoothed[band]) level else smoothed[band] * 0.85f + level * 0.15f
        }
        return smoothed.copyOf()
    }

    companion object {
        const val BANDS = 24
        private const val SIZE = 1024
        private const val RING = 4096 // power of two
    }
}
