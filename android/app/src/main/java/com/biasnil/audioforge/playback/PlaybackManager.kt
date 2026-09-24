package com.biasnil.audioforge.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.Track
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/** What the UI shows about playback. The position isn't in here -- it's polled, see [PlaybackManager.positionMs]. */
data class PlaybackState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val shuffleMode: ShuffleMode = ShuffleMode.Off,
    val repeatMode: RepeatMode = RepeatMode.Off,
    val volumePercent: Int = 100,
)

/**
 * The desktop's AudioEngine + queue handling in one place: owns the ExoPlayer,
 * decides what plays next with [PlaybackQueue], and loads one track at a time
 * (the same model the desktop app uses, which is what stage 3's crossfade
 * builds on). Lives for the whole app process; [PlaybackService] exposes it
 * to the system as a media session. Main thread only, like ExoPlayer itself.
 */
@OptIn(UnstableApi::class)
class PlaybackManager(context: Context, private val store: AppStore) {

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true, // pause for calls and other apps' audio
        )
        .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
        .setWakeMode(C.WAKE_MODE_LOCAL) // keep playing with the screen off
        .build()

    /** What the media session (notification, lock screen, headphone buttons) controls. */
    val sessionPlayer: Player = QueueForwardingPlayer(player, this)

    private val queue = PlaybackQueue<Track>()

    private val _state = MutableStateFlow(PlaybackState(volumePercent = store.settings.value.volumePercent))
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** File name of a track that couldn't be played. */
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        player.volume = _state.value.volumePercent / 100f
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    advance(fromAutoAdvance = true)
                }
                publish()
            }

            override fun onPlayerError(error: PlaybackException) {
                val track = queue.currentItem
                _errors.tryEmit(track?.fileName?.ifEmpty { track.title }.orEmpty())
                publish()
            }
        })
    }

    /** Replaces the queue and starts [startIndex] -- the desktop's setQueueAndPlay(). */
    fun playQueue(tracks: List<Track>, startIndex: Int) {
        if (tracks.isEmpty()) return
        queue.setQueue(tracks, startIndex)
        queue.currentItem?.let(::load)
    }

    fun togglePlayPause() {
        if (player.isPlaying) pause() else play()
    }

    fun play() {
        if (queue.isEmpty) return
        when (player.playbackState) {
            Player.STATE_ENDED -> player.seekTo(0) // end of the queue: play the last track again
            Player.STATE_IDLE -> player.prepare() // after an error
        }
        player.play()
    }

    fun pause() = player.pause()

    fun next() = advance(fromAutoAdvance = false)

    fun previous() {
        if (queue.movePrevious()) queue.currentItem?.let(::load)
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    fun positionMs(): Long = player.currentPosition

    fun cycleShuffleMode() {
        queue.cycleShuffleMode()
        publish()
    }

    fun cycleRepeatMode() {
        queue.cycleRepeatMode()
        publish()
    }

    /** Applies immediately; call [saveVolume] when the user lets go of the slider. */
    fun setVolume(percent: Int) {
        player.volume = percent.coerceIn(0, 100) / 100f
        publish()
    }

    fun saveVolume() {
        val percent = _state.value.volumePercent
        store.update { it.copy(volumePercent = percent) }
    }

    private fun advance(fromAutoAdvance: Boolean) {
        if (queue.isEmpty) return
        if (fromAutoAdvance && queue.repeatMode == RepeatMode.One) {
            player.seekTo(0)
            player.play()
            return
        }
        if (queue.moveNext()) {
            queue.currentItem?.let(::load)
        }
        // else: end of the queue -- stays stopped on the last track
    }

    private fun load(track: Track) {
        player.setMediaItem(track.toMediaItem())
        player.prepare()
        player.play()
        publish()
    }

    private fun publish() {
        val playerDuration = player.duration
        _state.value = PlaybackState(
            current = queue.currentItem,
            isPlaying = player.isPlaying,
            durationMs = if (playerDuration != C.TIME_UNSET && playerDuration > 0) playerDuration
            else queue.currentItem?.durationMs ?: 0L,
            shuffleMode = queue.shuffleMode,
            repeatMode = queue.repeatMode,
            volumePercent = (player.volume * 100).roundToInt(),
        )
    }
}

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
