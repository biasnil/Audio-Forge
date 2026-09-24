package com.biasnil.audioforge.playback

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Wraps whichever of PlaybackManager's two players is active. ExoPlayer
 * only ever holds the current track (the queue lives in [PlaybackQueue]),
 * so on its own it would tell the media session there's no next/previous
 * track and the notification would hide those buttons.
 * This wrapper advertises them and routes them -- from the notification,
 * lock screen, headphones, Bluetooth, etc. -- through [PlaybackManager].
 */
@OptIn(UnstableApi::class)
class QueueForwardingPlayer(
    player: Player,
    private val manager: PlaybackManager,
) : ForwardingPlayer(player) {

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

    private companion object {
        val QUEUE_COMMANDS = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        )
    }
}
