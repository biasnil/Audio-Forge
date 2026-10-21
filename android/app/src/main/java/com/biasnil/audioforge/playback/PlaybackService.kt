package com.biasnil.audioforge.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.biasnil.audioforge.AppContainer
import com.biasnil.audioforge.AppLanguage
import com.biasnil.audioforge.AudioForgeApplication
import com.biasnil.audioforge.MainActivity
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.SmartPlaylist
import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.data.TrackSort
import com.biasnil.audioforge.data.albumGroups
import com.biasnil.audioforge.data.artistGroups
import com.biasnil.audioforge.data.sortTracks
import com.biasnil.audioforge.data.tracksByArtist
import com.biasnil.audioforge.data.tracksInAlbum
import com.biasnil.audioforge.lyrics.LyricsState
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Publishes [PlaybackManager]'s player as a media session. Media3 then
 * handles the playback notification, lock-screen controls, headphone and
 * Bluetooth buttons, and keeps this service in the foreground while music
 * plays so it continues with the app closed.
 *
 * It's a media *library* session, so Android Auto can browse the music:
 * Songs, Albums, Artists, Playlists, and Lyrics -- the current song's
 * lyrics as a list (Android Auto has no lyrics view of its own), the line
 * being sung marked, tap a line to jump to it. Like the iOS app's CarPlay screens.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {

    private var session: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val container: AppContainer get() = (application as AudioForgeApplication).container

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.localized(base))
    }

    /**
     * Text for the Android Auto browse tree, in the app language. Looked up
     * each time, so on Android 12 a language change made while this service
     * runs shows up without restarting it.
     */
    private fun text(@StringRes id: Int): String = AppLanguage.localized(this).getString(id)

    override fun onCreate() {
        super.onCreate()
        val playback = container.playback
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val librarySession = MediaLibrarySession.Builder(this, playback.sessionPlayer.value, LibraryCallback())
            .setSessionActivity(openApp) // tapping the notification opens the app
            .build()
        session = librarySession

        // A crossfade hands playback to the other player; point the session at it.
        serviceScope.launch {
            playback.sessionPlayer.collect { player ->
                if (librarySession.player !== player) librarySession.player = player
            }
        }
        // The Lyrics list follows the song: refresh it when the lyrics or the line being sung change.
        serviceScope.launch {
            combine(container.lyrics.state, container.currentLyricLine) { lyrics, line -> lyrics to line }
                .distinctUntilChanged()
                .collect { (lyrics, _) -> librarySession.notifyChildrenChanged(LYRICS_ID, lyricsItems(lyrics).size, null) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

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
        serviceScope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    // --- Browse tree ---------------------------------------------------------------

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT_ID, text(R.string.app_name)), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = children(parentId)
            val from = (page * pageSize).coerceAtMost(children.size)
            val paged = children.subList(from, (from + pageSize).coerceAtMost(children.size))
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(paged), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val uri = mediaId.substringAfter('|', "")
            val track = container.library.trackFor(uri)
            val item = if (track != null) song(mediaId, track) else folder(mediaId, mediaId)
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        /**
         * A song picked in Android Auto: plays its list (the album, playlist...)
         * from it, through the manager. A lyric line: jumps there. The session
         * then hands the items to the player, which ignores them (see
         * QueueForwardingPlayer).
         */
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val picked = mediaItems.getOrNull(startIndex.coerceAtLeast(0)) ?: mediaItems.firstOrNull()
            picked?.let { play(it.mediaId) }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            mediaItems.firstOrNull()?.let { play(it.mediaId) }
            return Futures.immediateFuture(mediaItems)
        }
    }

    private fun play(mediaId: String) {
        val playback = container.playback
        val parent = mediaId.substringBefore('|')
        val key = mediaId.substringAfter('|', "")
        if (parent == LYRICS_ID) {
            val lyrics = container.lyrics.state.value as? LyricsState.Synced ?: return
            val line = lyrics.lines.getOrNull(key.toIntOrNull() ?: return) ?: return
            val track = playback.state.value.current ?: return
            val offset = container.songData.statsFor(track.uri).lyricsOffsetMs
            playback.seekTo((line.timeMs + offset).coerceAtLeast(0))
            return
        }
        val tracks = tracksIn(parent)
        val index = tracks.indexOfFirst { it.uri == key }
        if (index >= 0) playback.playQueue(tracks, index, from = titleOf(parent))
    }

    private fun children(parentId: String): List<MediaItem> {
        val library = container.library.tracks.value
        val settings = container.store.settings.value
        return when {
            parentId == ROOT_ID -> listOf(
                folder(SONGS_ID, text(R.string.tab_songs)),
                folder(ALBUMS_ID, text(R.string.tab_albums)),
                folder(ARTISTS_ID, text(R.string.tab_artists)),
                folder(PLAYLISTS_ID, text(R.string.tab_playlists)),
                folder(LYRICS_ID, text(R.string.show_lyrics)),
            )
            parentId == ALBUMS_ID -> albumGroups(library).map {
                folder("$ALBUM_PREFIX${it.name}", it.name.ifEmpty { text(R.string.unknown_album) }, MediaMetadata.MEDIA_TYPE_ALBUM)
            }
            parentId == ARTISTS_ID -> artistGroups(library).map {
                folder("$ARTIST_PREFIX${it.name}", it.name.ifEmpty { text(R.string.unknown_artist) }, MediaMetadata.MEDIA_TYPE_ARTIST)
            }
            parentId == PLAYLISTS_ID ->
                (SmartPlaylist.BUILT_IN + settings.smartPlaylists).map {
                    folder("$SMART_PREFIX${it.id}", smartTitle(it), MediaMetadata.MEDIA_TYPE_PLAYLIST)
                } + settings.playlists.map { folder("$PLAYLIST_PREFIX${it.id}", it.name, MediaMetadata.MEDIA_TYPE_PLAYLIST) }
            parentId == LYRICS_ID -> lyricsItems(container.lyrics.state.value)
            else -> tracksIn(parentId).map { song("$parentId|${it.uri}", it) }
        }
    }

    /** The songs of a list in the browse tree, in the order they play. */
    private fun tracksIn(parentId: String): List<Track> {
        val library = container.library.tracks.value
        val settings = container.store.settings.value
        return when {
            parentId == SONGS_ID -> sortTracks(library, TrackSort.Title)
            parentId.startsWith(ALBUM_PREFIX) -> tracksInAlbum(library, parentId.removePrefix(ALBUM_PREFIX))
            parentId.startsWith(ARTIST_PREFIX) -> tracksByArtist(library, parentId.removePrefix(ARTIST_PREFIX))
            parentId.startsWith(PLAYLIST_PREFIX) -> settings.playlists
                .firstOrNull { it.id == parentId.removePrefix(PLAYLIST_PREFIX) }
                ?.trackUris?.mapNotNull(container.library::trackFor).orEmpty()
            parentId.startsWith(SMART_PREFIX) -> {
                val id = parentId.removePrefix(SMART_PREFIX)
                val smart = SmartPlaylist.BUILT_IN.firstOrNull { it.id == id } ?: settings.smartPlaylists.firstOrNull { it.id == id }
                smart?.tracks(library, container.songData.stats.value, container.analysis::analysisFor).orEmpty()
            }
            else -> emptyList()
        }
    }

    private fun titleOf(parentId: String): String? {
        val settings = container.store.settings.value
        return when {
            parentId == SONGS_ID -> text(R.string.tab_songs)
            parentId.startsWith(ALBUM_PREFIX) -> parentId.removePrefix(ALBUM_PREFIX)
            parentId.startsWith(ARTIST_PREFIX) -> parentId.removePrefix(ARTIST_PREFIX)
            parentId.startsWith(PLAYLIST_PREFIX) -> settings.playlists.firstOrNull { it.id == parentId.removePrefix(PLAYLIST_PREFIX) }?.name
            parentId.startsWith(SMART_PREFIX) -> {
                val id = parentId.removePrefix(SMART_PREFIX)
                (SmartPlaylist.BUILT_IN + settings.smartPlaylists).firstOrNull { it.id == id }?.let(::smartTitle)
            }
            else -> null
        }
    }

    private fun smartTitle(smart: SmartPlaylist): String = when (smart.id) {
        SmartPlaylist.LOVED_ID -> text(R.string.smart_loved)
        SmartPlaylist.MOST_PLAYED_ID -> text(R.string.smart_most_played)
        SmartPlaylist.RECENTLY_PLAYED_ID -> text(R.string.smart_recently_played)
        SmartPlaylist.RECENTLY_ADDED_ID -> text(R.string.smart_recently_added)
        SmartPlaylist.NEVER_PLAYED_ID -> text(R.string.smart_never_played)
        else -> smart.name
    }

    /**
     * The Lyrics list: synced lyrics from the line before the one being
     * sung (marked ♪), plain lyrics from the top, two lines per row.
     */
    private fun lyricsItems(lyrics: LyricsState): List<MediaItem> = when (lyrics) {
        is LyricsState.Synced -> {
            val current = container.currentLyricLine.value
            val start = (current - 1).coerceAtLeast(0)
            lyrics.lines.withIndex().drop(start).take(MAX_LYRIC_ROWS).map { (index, line) ->
                val text = line.text.ifEmpty { "♪" }
                lyricRow("$LYRICS_ID|$index", if (index == current) "♪  $text" else text, null)
            }
        }
        is LyricsState.Plain -> lyrics.lines.chunked(2).take(MAX_LYRIC_ROWS).mapIndexed { index, pair ->
            lyricRow("$LYRICS_ID|plain$index", pair[0], pair.getOrNull(1))
        }
        LyricsState.Loading -> listOf(lyricRow("$LYRICS_ID|none", text(R.string.lyrics_loading), null))
        else -> listOf(
            lyricRow(
                "$LYRICS_ID|none",
                text(if (container.playback.state.value.current == null) R.string.auto_nothing_playing else R.string.lyrics_not_found),
                null,
            )
        )
    }

    private fun folder(id: String, title: String, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .build()
            )
            .build()

    private fun song(id: String, track: Track): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist.ifEmpty { null })
                    .setAlbumTitle(track.album.ifEmpty { null })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private fun lyricRow(id: String, text: String, detail: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(text)
                    .setSubtitle(detail)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .build()
            )
            .build()

    private companion object {
        const val ROOT_ID = "root"
        const val SONGS_ID = "songs"
        const val ALBUMS_ID = "albums"
        const val ARTISTS_ID = "artists"
        const val PLAYLISTS_ID = "playlists"
        const val LYRICS_ID = "lyrics"
        const val ALBUM_PREFIX = "album:"
        const val ARTIST_PREFIX = "artist:"
        const val PLAYLIST_PREFIX = "playlist:"
        const val SMART_PREFIX = "smart:"
        /** Cars show a limited number of rows. */
        const val MAX_LYRIC_ROWS = 30
    }
}
