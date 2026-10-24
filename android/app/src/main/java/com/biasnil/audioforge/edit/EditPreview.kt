package com.biasnil.audioforge.edit

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Plays an edit straight from the decoded PCM, so changes are heard before
 * anything is saved. Positions are frames on the edit's timeline.
 */
class EditPreview(private val source: PcmSource, private val scope: CoroutineScope) {
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private var job: Job? = null
    /** Bumped by every play/stop, so a stopped run finishing late doesn't touch the new one's state. */
    @Volatile private var run = 0

    /** Plays [edit] from [from] (edited timeline) until the end or [stop]. */
    fun play(edit: AudioEdit, from: Long) {
        stop()
        val start = from.coerceIn(0, (edit.length - 1).coerceAtLeast(0))
        _position.value = start
        _playing.value = true
        val myRun = ++run
        job = scope.launch(Dispatchers.IO) {
            val channelMask = if (source.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBuffer = AudioTrack.getMinBufferSize(source.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(source.sampleRate).setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val gain = gainFactor(edit.gainDb)
            val buffer = ShortArray(CHUNK_FRAMES * source.channels)
            var written = 0L
            try {
                track.play()
                source.reader().use { reader ->
                    for (span in edit.spansFrom(start)) {
                        var at = span.start
                        while (at < span.end && isActive) {
                            val frames = reader.read(at, minOf(CHUNK_FRAMES.toLong(), span.end - at).toInt(), buffer)
                            if (frames <= 0) break
                            val samples = frames * source.channels
                            if (gain != 1f) {
                                for (i in 0 until samples) buffer[i] = (buffer[i] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
                            }
                            track.write(buffer, 0, samples)
                            at += frames
                            written += frames
                            // What's being heard now: written, less what's still waiting in the buffer.
                            if (run == myRun) _position.value = start + track.playbackHeadPosition.toLong().coerceAtMost(written)
                        }
                    }
                }
                // Let the last buffer play out.
                while (isActive && track.playbackHeadPosition < written) {
                    if (run == myRun) _position.value = start + track.playbackHeadPosition
                    Thread.sleep(20)
                }
            } finally {
                runCatching { track.stop() }
                track.release()
                if (run == myRun) _playing.value = false
            }
        }
    }

    fun stop() {
        run++
        job?.cancel()
        job = null
        _playing.value = false
    }

    /** Moves the playhead without playing. */
    fun seek(position: Long) {
        stop()
        _position.value = position.coerceAtLeast(0)
    }

    private companion object {
        const val CHUNK_FRAMES = 2048
    }
}
