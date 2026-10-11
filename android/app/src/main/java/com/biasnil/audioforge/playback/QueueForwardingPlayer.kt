package com.biasnil.audioforge.playback

import androidx.annotation.OptIn
import androidx.media3.common.FlagSet
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Wraps whichever of PlaybackManager's two players is active. ExoPlayer
 * only ever holds the current track (the queue lives in [PlaybackQueue]),
 * so on its own it would tell the media session there's no next/previous
 * track and the notification would hide those buttons.
 * This wrapper advertises them and routes them -- from the notification,
 * lock screen, headphones, Bluetooth, Android Auto -- through [PlaybackManager].
 *
 * It also puts the lyric line being sung in place of the artist (the
 * "Lyrics on Lock Screen & Android Auto" setting), and ignores the media
 * session's own attempts to replace the playlist: songs picked in Android
 * Auto are started by PlaybackService through the manager instead.
 */
@OptIn(UnstableApi::class)
class QueueForwardingPlayer(
    player: Player,
    private val manager: PlaybackManager,
) : ForwardingPlayer(player) {

    private val listeners = CopyOnWriteArraySet<Player.Listener>()

    /** Shown instead of the artist; null = the song's own metadata. */
    var lyricLine: String? = null
        set(value) {
            if (field == value) return
            field = value
            val metadata = mediaMetadata
            val events = Player.Events(FlagSet.Builder().add(Player.EVENT_MEDIA_METADATA_CHANGED).build())
            for (listener in listeners) {
                listener.onMediaMetadataChanged(metadata)
                listener.onEvents(this, events)
            }
        }

    override fun addListener(listener: Player.Listener) {
        super.addListener(listener)
        listeners += listener
    }

    override fun removeListener(listener: Player.Listener) {
        super.removeListener(listener)
        listeners -= listener
    }

    override fun getMediaMetadata(): MediaMetadata {
        val base = super.getMediaMetadata()
        val line = lyricLine ?: return base
        return base.buildUpon().setArtist(line).setSubtitle(line).build()
    }

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(*QUEUE_COMMANDS).build()

    override fun isCommandAvailable(command: Int): Boolean =
        command in QUEUE_COMMANDS || super.isCommandAvailable(command)

    override fun seekToNext() = manager.next()

    override fun seekToNextMediaItem() = manager.next()

    override fun seekToPrevious() = manager.previous()

    override fun seekToPreviousMediaItem() = manager.previous()

    // Lock-screen / notification scrubbing: through the manager, so a seek
    // during a crossfade drops the fade instead of moving one track only.
    override fun seekTo(positionMs: Long) = manager.seekTo(positionMs)

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = manager.seekTo(positionMs)

    // The session sets what Android Auto picked; PlaybackService has already started it.
    override fun setMediaItems(mediaItems: MutableList<MediaItem>) = Unit
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) = Unit
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) = Unit
    override fun setMediaItem(mediaItem: MediaItem) = Unit
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = Unit
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = Unit

    // Resuming from the system (Bluetooth play button with nothing loaded...): through the manager.
    override fun play() = manager.play()

    override fun prepare() = Unit // the manager prepares its players itself

    private companion object {
        val QUEUE_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}
