package com.biasnil.audioforge.audio

import android.content.Context
import android.net.Uri
import android.util.Log
import com.biasnil.audioforge.data.DetectedGenre
import com.biasnil.audioforge.data.GenreTaxonomy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Detects a song's genre on the phone with YAMNet (Google's AudioSet sound
 * classifier, bundled in assets): a 30 s window of the song at 16 kHz ->
 * 521 label scores per 0.48 s frame -> averaged -> [GenreTaxonomy]. No
 * network. Runs on the CPU (XNNPACK, 2 threads); one instance per run,
 * closed when the run ends.
 */
class GenreClassifier(private val context: Context) : Closeable {

    private val interpreterLazy = lazy { Interpreter(loadModel(), Interpreter.Options().setNumThreads(2)) }
    private val interpreter: Interpreter by interpreterLazy

    /** YAMNet's class names (display_name), in output order. */
    private val labels: List<String> by lazy {
        context.assets.open(CLASS_MAP).bufferedReader().useLines { lines -> lines.drop(1).map(::displayName).toList() }
    }

    /** The song at [uri], [durationMs] long (0 = unknown). Never throws, except to cancel. */
    suspend fun detect(uri: Uri, durationMs: Long): DetectedGenre {
        val window = GenreWindow.forDuration(durationMs)
            ?: return DetectedGenre(durationMs, DetectedGenre.Status.TOO_SHORT, null, null, 0.0, emptyList())
        return try {
            val decoded = AudioDecoder.decodeMono(
                context, uri,
                sampleRate = SAMPLE_RATE,
                startMs = window.startMs,
                maxMs = window.lengthMs,
                lowPass = true,
            ) ?: return failed(durationMs)
            val length = if (durationMs > 0) durationMs else decoded.durationMs
            if (decoded.samples.size < GenreWindow.MIN_SONG_MS / 1000 * SAMPLE_RATE) {
                return DetectedGenre(length, DetectedGenre.Status.TOO_SHORT, null, null, 0.0, emptyList())
            }
            currentCoroutineContext().ensureActive()
            GenreTaxonomy.decide(scores(decoded.samples), length)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't detect the genre of $uri", e)
            failed(durationMs)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory detecting the genre of $uri", e)
            failed(durationMs)
        } catch (e: LinkageError) {
            // LiteRT's native library missing or not loadable on this device.
            Log.w(TAG, "Couldn't load the genre model's runtime", e)
            failed(durationMs)
        }
    }

    /** YAMNet's label -> score, averaged over every frame of [waveform] (16 kHz mono, -1..1). */
    private fun scores(waveform: FloatArray): Map<String, Float> {
        interpreter.resizeInput(0, intArrayOf(waveform.size))
        interpreter.allocateTensors()
        // Find the scores among the outputs by shape ([frames, 521]), not by position.
        val scoresIndex = (0 until interpreter.outputTensorCount).first { i ->
            val shape = interpreter.getOutputTensor(i).shapeSignature()
            shape.size == 2 && shape[1] == labels.size
        }
        // The frame count is only known after running; one frame per 0.48 s, plus padding.
        val maxFrames = waveform.size / FRAME_HOP + 4
        val output = ByteBuffer.allocateDirect(maxFrames * labels.size * 4).order(ByteOrder.nativeOrder())
        val input = ByteBuffer.allocateDirect(waveform.size * 4).order(ByteOrder.nativeOrder())
        input.asFloatBuffer().put(waveform)
        interpreter.runForMultipleInputsOutputs(arrayOf(input), mapOf(scoresIndex to output))

        // How many frames came out: from the bytes copied, not getOutputTensor().shape(),
        // which isn't refreshed after a run with a new input size.
        val frames = output.position() / (labels.size * 4)
        check(frames > 0) { "The model returned no frames" }
        output.rewind()
        val floats = output.asFloatBuffer()
        val sums = FloatArray(labels.size)
        for (frame in 0 until frames) for (label in labels.indices) sums[label] += floats.get()
        return labels.indices.associate { labels[it] to sums[it] / frames.coerceAtLeast(1) }
    }

    private fun failed(durationMs: Long) = DetectedGenre(durationMs, DetectedGenre.Status.FAILED, null, null, 0.0, emptyList())

    /** Memory-mapped straight from the APK (stored uncompressed, see build.gradle.kts). */
    private fun loadModel(): MappedByteBuffer =
        context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }

    override fun close() {
        // Only if it was created: touching it would load the model just to close it.
        if (interpreterLazy.isInitialized()) interpreter.close()
    }

    companion object {
        private const val TAG = "GenreClassifier"
        private const val MODEL = "yamnet.tflite"
        private const val CLASS_MAP = "yamnet_class_map.csv"
        const val SAMPLE_RATE = 16_000
        /** YAMNet moves 0.48 s (7680 samples) per frame. */
        private const val FRAME_HOP = 7_680

        /** The display_name column of a class map line: index,mid,display_name (the name may be quoted). */
        internal fun displayName(line: String): String {
            val rest = line.substringAfter(',').substringAfter(',')
            return if (rest.startsWith('"')) rest.removePrefix("\"").removeSuffix("\"").replace("\"\"", "\"") else rest
        }
    }
}
