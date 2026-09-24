package com.biasnil.audioforge.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.biasnil.audioforge.AudioForgeApplication
import com.biasnil.audioforge.MainActivity

/**
 * Publishes [PlaybackManager]'s player as a media session. Media3 then
 * handles the playback notification, lock-screen controls, headphone and
 * Bluetooth buttons, and keeps this service in the foreground while music
 * plays so it continues with the app closed.
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val playback = (application as AudioForgeApplication).container.playback
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, playback.sessionPlayer)
            .setSessionActivity(openApp) // tapping the notification opens the app
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiping the app away from recents stops the service unless music is actually playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Releases the session only -- the player belongs to PlaybackManager,
        // which outlives this service.
        session?.release()
        session = null
        super.onDestroy()
    }
}
