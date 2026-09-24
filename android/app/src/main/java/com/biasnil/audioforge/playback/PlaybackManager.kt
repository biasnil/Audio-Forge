package com.biasnil.audioforge.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.biasnil.audioforge.audio.AudioEffectsProcessor
import com.biasnil.audioforge.audio.EqualizerSettings
import com.biasnil.audioforge.audio.dbToLinear
import com.biasnil.audioforge.audio.parseReplayGainDb
import com.biasnil.audioforge.data.AppSettings
import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the UI shows about playback. The position isn't in here -- it's polled, see [PlaybackManager.positionMs]. */
data class PlaybackState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffleMode: ShuffleMode = ShuffleMode.Off,
    val repeatMode: RepeatMode = RepeatMode.Off,
    /** 0-200; above 100 boosts. */
    val volumePercent: Int = 100,
)

/**
 * The desktop's AudioEngine + queue handling. Two ExoPlayers stand in for
 * the desktop's two sound slots: one plays the current track, the other
 * fades the next one in during a crossfade and then becomes the current
 * one. Each player runs an [AudioEffectsProcessor] (EQ + volume/ReplayGain
 * gain); player.volume is only used for the fade itself.
 *
 * Lives for the whole app process; [PlaybackService] exposes the active
 * player to the system as a media session. Main thread only.
 */
@OptIn(UnstableApi::class)
class PlaybackManager(
    context: Context,
    private val store: AppStore,
    private val scope: CoroutineScope,
) {
    private val appContext: Context = context.applicationContext
    private val equalizer = EqualizerSettings()
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    /** One player + its effects chain + what it's playing. */
    private inner class Slot {
        val effects = AudioEffectsProcessor(equalizer)
        val player: ExoPlayer = buildPlayer(appContext, effects)
        var track: Track? = null
        var replayGainDb = 0f

        init {
            player.addListener(SlotListener(this))
        }
    }

    private val slots = arrayOf(Slot(), Slot())
    private var activeIndex = 0
    private val active: Slot get() = slots[activeIndex]
    private val incoming: Slot get() = slots[1 - activeIndex]
    private var crossfading = false

    private val queue = PlaybackQueue<Track>()
    private var volumePercent = store.settings.value.volumePercent

    private val _state = MutableStateFlow(PlaybackState(volumePercent = volumePercent))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** File name of a track that couldn't be played. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    /**
     * What the media session (notification, lock screen, headphone buttons)
     * controls: the active player, re-wrapped whenever a crossfade swaps them.
     */
    private val _sessionPlayer = MutableStateFlow<Player>(QueueForwardingPlayer(active.player, this))
    val sessionPlayer: StateFlow<Player> = _sessionPlayer.asStateFlow()

    init {
        active.player.setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)

        scope.launch {
            store.settings.collect { settings -> applyAudioSettings(settings) }
        }
        // The desktop's 200 ms update tick, at 100 ms for smoother fades.
        scope.launch {
            while (isActive) {
                tick()
                delay(100)
            }
        }
    }

    // --- Transport ---------------------------------------------------------

    /** Replaces the queue and starts [startIndex] -- the desktop's setQueueAndPlay(). */
    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        abortCrossfade()
        queue.setQueue(tracks, startIndex)
        queue.currentItem?.let(::load)
    }

    fun togglePlayPause() {
        if (active.player.isPlaying) pause() else play()
    }

    fun play() {
        if (queue.isEmpty) return
        val player = active.player
        when (player.playbackState) {
            Player.STATE_ENDED -> player.seekTo(0) // end of the queue: play the last track again
            Player.STATE_IDLE -> player.prepare() // after an error
        }
        player.play() // a fading-in track follows along, see SlotListener.onPlayWhenReadyChanged
    }

    fun pause() = active.player.pause()

    /** A manual skip always hard-cuts, discarding any fade in progress (same as the desktop). */
    fun next() {
        abortCrossfade()
        queue.cancelPendingNext()
        advance(fromAutoAdvance = false)
    }

    fun previous() {
        abortCrossfade()
        queue.cancelPendingNext()
        if (queue.movePrevious()) queue.currentItem?.let(::load)
    }

    /**
     * A seek during a crossfade drops the fade instead of moving only the
     * outgoing track. The queue keeps its pending pick, so if the new
     * position is still inside the crossfade window the fade restarts into
     * the same track, from its beginning.
     */
    fun seekTo(positionMs: Long) {
        abortCrossfade()
        active.player.seekTo(positionMs.coerceAtLeast(0))
    }

    fun positionMs(): Long = active.player.currentPosition

    fun cycleShuffleMode() {
        queue.cycleShuffleMode()
        publish()
    }

    fun cycleRepeatMode() {
        queue.cycleRepeatMode()
        publish()
    }

    /** 0-200. Applies immediately; call [saveVolume] when the user lets go of the slider. */
    fun setVolume(percent: Int) {
        volumePercent = percent.coerceIn(0, AppSettings.MAX_VOLUME_PERCENT)
        slots.forEach(::applyGain)
        publish()
    }

    fun saveVolume() {
        val percent = volumePercent
        store.update { it.copy(volumePercent = percent) }
    }

    // --- Internals ---------------------------------------------------------

    private fun advance(fromAutoAdvance: Boolean) {
        if (queue.isEmpty) return
        if (fromAutoAdvance && queue.repeatMode == RepeatMode.One) {
            active.player.seekTo(0)
            active.player.play()
            return
        }
        if (queue.moveNext()) {
            queue.currentItem?.let(::load)
        }
        // else: end of the queue -- stays stopped on the last track
    }

    /** Hard cut to [track] on the active player. */
    private fun load(track: Track) {
        val slot = active
        slot.track = track
        slot.replayGainDb = 0f // until this file's tags are read, see onTracksChanged
        applyGain(slot)
        slot.player.volume = 1f
        slot.player.setMediaItem(track.toMediaItem())
        slot.player.prepare()
        slot.player.play()
        publish()
    }

    private fun tick() {
        val player = active.player
        if (active.track == null) return
        val length = player.duration
        if (length == C.TIME_UNSET || length <= 0) return
        val cursor = player.currentPosition

        if (crossfading) {
            updateCrossfade(cursor, length)
            return
        }
        val settings = store.settings.value
        if (settings.crossfadeEnabled && player.isPlaying && length - cursor <= settings.crossfadeSeconds * 1000L) {
            tryStartCrossfade()
        }
    }

    private fun tryStartCrossfade() {
        if (queue.repeatMode == RepeatMode.One) return // restarting the same track doesn't need a fade
        if (!queue.peekNext()) return // end of the queue: nothing to fade into
        val next = queue.pendingNextItem ?: return

        val slot = incoming
        slot.track = next
        slot.replayGainDb = 0f
        applyGain(slot)
        slot.player.volume = 0f
        slot.player.setMediaItem(next.toMediaItem())
        slot.player.prepare()
        slot.player.play()
        crossfading = true
    }

    private fun updateCrossfade(cursor: Long, length: Long) {
        val remaining = length - cursor
        val fadeMs = store.settings.value.crossfadeSeconds * 1000f
        val progress = 1f - (remaining / fadeMs).coerceIn(0f, 1f)
        active.player.volume = 1f - progress
        incoming.player.volume = progress
        if (remaining <= 50 || active.player.playbackState == Player.STATE_ENDED) {
            finalizeCrossfade()
        }
    }

    /** The faded-in player becomes the active one (the desktop's finalizeCrossfade()). */
    private fun finalizeCrossfade() {
        if (!crossfading) return
        crossfading = false
        val old = active
        activeIndex = 1 - activeIndex

        // Hand audio focus over: only the active player holds it, or the two would take it from each other.
        active.player.setAudioAttributes(audioAttributes, true)
        active.player.volume = 1f
        old.player.setAudioAttributes(audioAttributes, false)
        old.player.stop()
        old.player.clearMediaItems()
        old.track = null

        queue.commitPendingNext()
        _sessionPlayer.value = QueueForwardingPlayer(active.player, this)
        publish()
    }

    /** Drops a fade in progress, leaving the current track playing at full volume. Doesn't touch the queue. */
    private fun abortCrossfade() {
        if (!crossfading) return
        crossfading = false
        incoming.player.stop()
        incoming.player.clearMediaItems()
        incoming.track = null
        active.player.volume = 1f
    }

    private fun applyAudioSettings(settings: AppSettings) {
        equalizer.update(settings.eqEnabled, settings.eqBandGainsDb)
        slots.forEach(::applyGain)
    }

    /** The desktop's baseVolumeFor(): volume x ReplayGain (if on) x EQ post-gain (if the EQ is on). */
    private fun applyGain(slot: Slot) {
        val settings = store.settings.value
        var gain = volumePercent / 100f
        if (settings.replayGainEnabled) gain *= dbToLinear(slot.replayGainDb)
        if (settings.eqEnabled) gain *= dbToLinear(settings.eqPostGainDb)
        slot.effects.gain = gain
    }

    private fun publish() {
        val player = active.player
        val playerDuration = player.duration
        _state.value = PlaybackState(
            current = queue.currentItem,
            isPlaying = player.isPlaying,
            durationMs = if (playerDuration != C.TIME_UNSET && playerDuration > 0) playerDuration
            else queue.currentItem?.durationMs ?: 0L,
            shuffleMode = queue.shuffleMode,
            repeatMode = queue.repeatMode,
            volumePercent = volumePercent,
        )
    }

    private inner class SlotListener(private val slot: Slot) : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (slot === active) publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (slot !== active) return
            if (playbackState == Player.STATE_ENDED) {
                // Crossfade-off path, or a fade that couldn't start (end of queue).
                if (crossfading) finalizeCrossfade() else advance(fromAutoAdvance = true)
            }
            publish()
        }

        /**
         * Pause/play from anywhere (the app, the notification, a phone call
         * taking audio focus, headphones unplugged) lands on the active
         * player; the track fading in follows it, so it never keeps playing
         * on its own.
         */
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (slot === active && crossfading) {
                incoming.player.playWhenReady = playWhenReady
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            slot.replayGainDb = readReplayGainDb(tracks) ?: 0f
            applyGain(slot)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (slot === active) {
                val track = slot.track
                _errors.tryEmit(track?.fileName?.ifEmpty { track.title }.orEmpty())
                publish()
            } else {
                // The next track failed to load for a crossfade: drop the fade;
                // it gets another go (and reports the error) on the normal advance.
                Log.w(TAG, "Crossfade preload failed for ${slot.track?.fileName}", error)
                abortCrossfade()
                queue.cancelPendingNext()
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackManager"
    }
}

/** An ExoPlayer whose audio goes through [effects] before reaching the speaker. */
@OptIn(UnstableApi::class)
private fun buildPlayer(context: Context, effects: AudioEffectsProcessor): ExoPlayer {
    val renderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): AudioSink =
            DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf<AudioProcessor>(effects))
                .build()
    }
    return ExoPlayer.Builder(context, renderersFactory)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ false, // turned on for whichever player is active
        )
        .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
        .setWakeMode(C.WAKE_MODE_LOCAL) // keep playing with the screen off
        .build()
}

/**
 * The track's ReplayGain from its tags, as ExoPlayer read them: ID3 TXXX
 * (MP3), Vorbis comments (FLAC, OGG, Opus) or iTunes freeform atoms (M4A).
 */
@OptIn(UnstableApi::class)
private fun readReplayGainDb(tracks: Tracks): Float? {
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until group.length) {
            val metadata: Metadata = group.getTrackFormat(i).metadata ?: continue
            for (j in 0 until metadata.length()) {
                val value = when (val entry = metadata.get(j)) {
                    is TextInformationFrame ->
                        if (entry.id == "TXXX" && entry.description.equals(REPLAYGAIN_TRACK_GAIN, ignoreCase = true)) {
                            entry.values.firstOrNull()
                        } else null
                    is VorbisComment ->
                        if (entry.key.equals(REPLAYGAIN_TRACK_GAIN, ignoreCase = true)) entry.value else null
                    is InternalFrame ->
                        if (entry.description.equals(REPLAYGAIN_TRACK_GAIN, ignoreCase = true)) entry.text else null
                    else -> null
                }
                parseReplayGainDb(value)?.let { return it }
            }
        }
    }
    return null
}

private const val REPLAYGAIN_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"

/**
 * Title/artist/album from the library; ExoPlayer adds the file's embedded
 * artwork itself, which the notification and lock screen pick up.
 */
private fun Track.toMediaItem(): MediaItem =
    MediaItem.Builder()
        .setMediaId(uri)
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist.ifEmpty { null })
                .setAlbumTitle(album.ifEmpty { null })
                .build()
        )
        .build()
