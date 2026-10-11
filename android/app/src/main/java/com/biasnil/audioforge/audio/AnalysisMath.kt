package com.biasnil.audioforge.audio

import com.biasnil.audioforge.data.SongAnalysis
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The analysis itself, on mono samples at [SAMPLE_RATE] -- a port of the
 * iOS app's AudioAnalyzer (plain Kotlin, so it runs in JVM tests):
 * - Tempo: spectral-flux onset envelope -> autocorrelation (with a
 *   preference around 120 BPM) -> refined by a comb search that also gives
 *   the beat phase.
 * - Key: chromagram (energy per note C...B) correlated with the Krumhansl
 *   major/minor key profiles in all 24 keys.
 * - Silence / outro: loudness envelope against the song's typical loudness.
 */
object AnalysisMath {
    /** Enough for beats and notes up to ~5 kHz. */
    const val SAMPLE_RATE = 11_025
    /** Long files (mixes, audiobooks) are only analyzed for their first 10 minutes. */
    const val MAX_ANALYZED_SECONDS = 600

    fun analyze(samples: FloatArray, fullDurationMs: Long, analyzedWholeFile: Boolean): SongAnalysis? {
        if (samples.size <= SAMPLE_RATE * 5) return null // under 5 s: nothing useful
        val loudness = loudness(samples, fullDurationMs / 1000.0, analyzedWholeFile)
        val tempo = tempo(samples)
        val key = key(samples)
        return SongAnalysis(
            durationMs = fullDurationMs,
            bpm = tempo.bpm,
            bpmConfidence = tempo.confidence,
            firstBeatMs = tempo.firstBeat * 1000,
            keyIndex = key.first,
            keyConfidence = key.second,
            audioStartMs = (loudness.start * 1000).toLong(),
            audioEndMs = (loudness.end * 1000).toLong(),
            outroStartMs = (loudness.outroStart * 1000).toLong(),
        )
    }

    // --- Spectra -----------------------------------------------------------------

    /** Radix-2 FFT of Hann-windowed frames; calls [body] with each frame's magnitude spectrum (size/2 bins). */
    private inline fun forEachSpectrum(samples: FloatArray, size: Int, hop: Int, body: (Int, FloatArray) -> Unit) {
        if (samples.size < size) return
        val fft = Fft(size)
        val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }
        val real = DoubleArray(size)
        val imag = DoubleArray(size)
        val magnitudes = FloatArray(size / 2)
        var index = 0
        var start = 0
        while (start + size <= samples.size) {
            for (i in 0 until size) {
                real[i] = (samples[start + i] * window[i]).toDouble()
                imag[i] = 0.0
            }
            fft.transform(real, imag)
            for (bin in 0 until size / 2) {
                magnitudes[bin] = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin]).toFloat()
            }
            body(index, magnitudes)
            index++
            start += hop
        }
    }

    /** In-place iterative complex FFT with precomputed twiddles and bit reversal (also used by the visualizer). */
    internal class Fft(private val n: Int) {
        private val cosTable = DoubleArray(n / 2) { cos(2 * PI * it / n) }
        private val sinTable = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
        private val reversed = IntArray(n).also { table ->
            val bits = Integer.numberOfTrailingZeros(n)
            for (i in 0 until n) table[i] = Integer.reverse(i) ushr (32 - bits)
        }

        fun transform(re: DoubleArray, im: DoubleArray) {
            for (i in 0 until n) {
                val j = reversed[i]
                if (j > i) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var size = 2
            while (size <= n) {
                val half = size / 2
                val step = n / size
                var start = 0
                while (start < n) {
                    var k = 0
                    for (j in start until start + half) {
                        val wr = cosTable[k]
                        val wi = sinTable[k]
                        val l = j + half
                        val tr = re[l] * wr - im[l] * wi
                        val ti = re[l] * wi + im[l] * wr
                        re[l] = re[j] - tr
                        im[l] = im[j] - ti
                        re[j] += tr
                        im[j] += ti
                        k += step
                    }
                    start += size
                }
                size *= 2
            }
        }
    }

    // --- Tempo ---------------------------------------------------------------------

    private const val ONSET_FRAME = 1024
    private const val ONSET_HOP = 128
    private const val ONSET_HOP_TIME = ONSET_HOP.toDouble() / SAMPLE_RATE

    class Tempo(val bpm: Double?, val confidence: Double, val firstBeat: Double)

    fun tempo(samples: FloatArray): Tempo {
        // 1. Onset strength: how much the (log-compressed) spectrum grows from one frame to the next.
        val bins = ONSET_FRAME / 2
        var previous = FloatArray(bins)
        var current = FloatArray(bins)
        val envelope = FloatArray(maxOf(0, (samples.size - ONSET_FRAME) / ONSET_HOP + 1))
        forEachSpectrum(samples, ONSET_FRAME, ONSET_HOP) { index, magnitudes ->
            var flux = 0f
            for (bin in 0 until bins) {
                val value = ln1p(100 * magnitudes[bin])
                current[bin] = value
                if (index > 0) flux += max(0f, value - previous[bin])
            }
            val swap = previous
            previous = current
            current = swap
            envelope[index] = flux
        }
        if (envelope.size <= 400) return Tempo(null, 0.0, 0.0)

        // 2. Keep only the peaks: subtract a ~0.5 s moving average, clip negatives, normalize.
        val window = (0.5 / ONSET_HOP_TIME).toInt()
        val smoothed = FloatArray(envelope.size)
        var sum = 0f
        for (i in envelope.indices) {
            sum += envelope[i]
            if (i >= window) sum -= envelope[i - window]
            smoothed[i] = max(0f, envelope[i] - sum / min(i + 1, window))
        }
        val mean = smoothed.average().toFloat()
        if (mean <= 0) return Tempo(null, 0.0, 0.0)
        for (i in smoothed.indices) smoothed[i] /= mean

        // 3. Autocorrelation over 60-200 BPM, weighted towards ~120 BPM (one octave either side).
        val minLag = ((60.0 / 200) / ONSET_HOP_TIME).toInt()
        val maxLag = ((60.0 / 60) / ONSET_HOP_TIME).toInt()
        if (smoothed.size <= maxLag * 4) return Tempo(null, 0.0, 0.0)
        val correlation = DoubleArray(maxLag + 2)
        for (lag in max(1, minLag - 1)..(maxLag + 1)) {
            var dot = 0.0
            val count = smoothed.size - lag
            for (i in 0 until count) dot += smoothed[i] * smoothed[i + lag]
            correlation[lag] = dot / count
        }
        var bestLag = minLag
        var bestScore = Double.NEGATIVE_INFINITY
        var scoreSum = 0.0
        for (lag in minLag..maxLag) {
            val bpm = 60 / (lag * ONSET_HOP_TIME)
            val octaves = ln(bpm / 120) / ln(2.0)
            val score = correlation[lag] * exp(-0.5 * octaves * octaves)
            scoreSum += correlation[lag]
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        val average = scoreSum / (maxLag - minLag + 1)
        val peakRatio = if (average > 0) correlation[bestLag] / average else 0.0
        // Parabolic interpolation around the peak for a sub-frame period.
        val a = correlation[bestLag - 1]
        val b = correlation[bestLag]
        val c = correlation[bestLag + 1]
        val denominator = a - 2 * b + c
        val offset = if (denominator != 0.0) (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5) else 0.0
        val roughPeriod = bestLag + offset

        // 4. Comb search: the period (±2%) and phase whose beat positions land on the most onsets.
        var bestPeriod = roughPeriod
        var bestPhase = 0.0
        var bestComb = Double.NEGATIVE_INFINITY
        val last = (smoothed.size - 1).toDouble()
        for (step in -40..40) {
            val period = roughPeriod * (1 + step * 0.0005)
            for (phase in 0 until max(period.toInt(), 1)) {
                var position = phase.toDouble()
                var total = 0.0
                var count = 0
                while (position < last) {
                    val i = position.toInt()
                    val fraction = (position - i).toFloat()
                    total += smoothed[i] * (1 - fraction) + smoothed[i + 1] * fraction
                    count++
                    position += period
                }
                val score = if (count > 0) total / count else 0.0
                if (score > bestComb) {
                    bestComb = score
                    bestPeriod = period
                    bestPhase = phase.toDouble()
                }
            }
        }

        val bpm = 60 / (bestPeriod * ONSET_HOP_TIME)
        // How much the beat stands out: ~1 for no pulse, 2+ for a steady drum beat.
        val confidence = ((peakRatio - 1.15) / 0.85).coerceIn(0.0, 1.0)
        // Onset frame i is centred at (i × hop + frameSize / 2) samples.
        val firstBeat = (bestPhase * ONSET_HOP + ONSET_FRAME / 2) / SAMPLE_RATE
        if (confidence <= 0.1 || !bpm.isFinite()) return Tempo(null, confidence, 0.0)
        return Tempo((bpm * 10).roundToInt() / 10.0, confidence, firstBeat)
    }

    // --- Key -----------------------------------------------------------------------

    /** Krumhansl-Kessler key profiles (how much each scale degree is heard in a key). */
    private val MAJOR = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MINOR = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    /** (key index or null, confidence). */
    fun key(samples: FloatArray): Pair<Int?, Double> {
        val size = 4096
        val binWidth = SAMPLE_RATE.toDouble() / size
        // Which note (pitch class) each FFT bin belongs to, from C2 (65 Hz) to ~C7 (2.1 kHz).
        val pitchOfBin = IntArray(size / 2) { -1 }
        for (bin in 1 until size / 2) {
            val frequency = bin * binWidth
            if (frequency < 65 || frequency > 2100) continue
            val midi = 69 + 12 * ln(frequency / 440) / ln(2.0)
            pitchOfBin[bin] = (midi.roundToInt() % 12 + 12) % 12
        }
        val chroma = DoubleArray(12)
        val frame = DoubleArray(12)
        forEachSpectrum(samples, size, size) { _, magnitudes ->
            frame.fill(0.0)
            var energy = 0.0
            for (bin in 1 until size / 2) {
                val pitch = pitchOfBin[bin]
                if (pitch < 0) continue
                val value = magnitudes[bin].toDouble()
                frame[pitch] += value
                energy += value
            }
            if (energy > 0) for (pitch in 0 until 12) chroma[pitch] += frame[pitch] / energy
        }
        if (chroma.none { it > 0 }) return null to 0.0

        val scores = ArrayList<Pair<Int, Double>>(24)
        for (tonic in 0 until 12) {
            scores += tonic to correlation(chroma, rotated(MAJOR, tonic))
            scores += (12 + tonic) to correlation(chroma, rotated(MINOR, tonic))
        }
        scores.sortByDescending { it.second }
        val best = scores[0]
        val margin = best.second - scores[1].second
        val confidence = (margin * 6 + max(0.0, best.second - 0.5)).coerceIn(0.0, 1.0)
        if (best.second <= 0.3) return null to confidence
        return best.first to confidence
    }

    /** The profile for the key whose tonic is [tonic] (profile[0] is the tonic). */
    private fun rotated(profile: DoubleArray, tonic: Int) = DoubleArray(12) { profile[((it - tonic) % 12 + 12) % 12] }

    private fun correlation(x: DoubleArray, y: DoubleArray): Double {
        val meanX = x.average()
        val meanY = y.average()
        var covariance = 0.0
        var varianceX = 0.0
        var varianceY = 0.0
        for (i in x.indices) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            covariance += dx * dy
            varianceX += dx * dx
            varianceY += dy * dy
        }
        val denominator = sqrt(varianceX * varianceY)
        return if (denominator > 0) covariance / denominator else 0.0
    }

    // --- Silence and outro -----------------------------------------------------------

    class Loudness(val start: Double, val end: Double, val outroStart: Double)

    fun loudness(samples: FloatArray, fullDuration: Double, analyzedWholeFile: Boolean): Loudness {
        val hop = 512
        val hopTime = hop.toDouble() / SAMPLE_RATE
        val frames = samples.size / hop
        if (frames <= 20) return Loudness(0.0, fullDuration, fullDuration)

        val levels = FloatArray(frames) { i ->
            var squares = 0.0
            for (j in i * hop until (i + 1) * hop) squares += samples[j] * samples[j]
            val rms = sqrt(squares / hop).toFloat()
            (20 * log10(max(rms, 1e-7f).toDouble())).toFloat()
        }
        val peak = levels.max()
        val threshold = max(peak - 45, -60f) // quieter than this counts as silence
        val first = levels.indexOfFirst { it > threshold }.takeIf { it >= 0 } ?: 0
        val last = levels.indexOfLast { it > threshold }.takeIf { it >= 0 } ?: (frames - 1)
        val start = first * hopTime

        // Only the analyzed part is known; for long files the end stays the file's end.
        if (!analyzedWholeFile) return Loudness(start, fullDuration, fullDuration)
        val end = min((last + 1) * hopTime, fullDuration)

        // Typical loudness of the song (median of the non-silent part).
        val body = levels.copyOfRange(first, last + 1).also { it.sort() }
        val typical = body[body.size / 2]

        // Outro: walking back from the end, the last moment the 1-second average loudness is
        // still within 6 dB of typical. Limited to the last 20 s.
        val averageWindow = max(1, (1 / hopTime).toInt())
        var outroFrame = last
        var index = last
        val earliest = max(first, last - (20 / hopTime).toInt())
        while (index >= earliest) {
            val lower = max(first, index - averageWindow + 1)
            var total = 0f
            for (k in lower..index) total += levels[k]
            if (total / (index - lower + 1) >= typical - 6) break
            outroFrame = index
            index--
        }
        return Loudness(start, end, min(outroFrame * hopTime, end))
    }
}
