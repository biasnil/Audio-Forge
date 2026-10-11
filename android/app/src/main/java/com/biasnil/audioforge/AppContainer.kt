package com.biasnil.audioforge

import android.content.Context
import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.CoverArtLoader
import com.biasnil.audioforge.data.LibraryRepository
import com.biasnil.audioforge.data.SongDataStore
import com.biasnil.audioforge.data.AnalysisStore
import com.biasnil.audioforge.audio.LibraryAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import com.biasnil.audioforge.lyrics.LyricsState
import com.biasnil.audioforge.lyrics.currentLyricIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.biasnil.audioforge.data.WallpaperRepository
import com.biasnil.audioforge.lyrics.LyricsRepository
import com.biasnil.audioforge.tags.TagEditor
import com.biasnil.audioforge.playback.PlaybackManager
import com.biasnil.audioforge.playback.QueueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** App-wide singletons, created once in [AudioForgeApplication]. */
class AppContainer(context: Context) {
    /** Main-thread scope that lives as long as the app process. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val store = AppStore(context, appScope)
    val library = LibraryRepository(context, store, appScope)
    val coverArt = CoverArtLoader(context)
    private val queueStore = QueueStore(context)
    /** Play counts, Loved songs, long-track positions. */
    val songData = SongDataStore(context, appScope)
    /** BPM, key, silence and outro per song ("Analyze Your Songs' Key and BPM"). */
    val analysis = AnalysisStore(context, appScope)
    /** Whether the app is on screen (set by MainActivity); analysis only runs in the background while charging. */
    val appVisible = MutableStateFlow(false)
    val analyzer = LibraryAnalyzer(context, analysis, store, appScope, appVisible)
    val playback = PlaybackManager(context, store, queueStore, songData, analysis, appScope)
    val lyrics = LyricsRepository(context, store, playback, appScope)
    val tagEditor = TagEditor(context)
    val wallpapers = WallpaperRepository(context, store)

    /** The synced lyric line being sung (index into the current lyrics), for the lock screen and Android Auto. */
    val currentLyricLine = MutableStateFlow(-1)

    init {
        // Follows the line being sung: the lock screen shows it (if that's on), Android Auto's Lyrics list marks it.
        appScope.launch {
            while (isActive) {
                val track = playback.state.value.current
                val lyricsState = lyrics.state.value
                var index = -1
                if (track != null && lyricsState is LyricsState.Synced) {
                    val offset = songData.statsFor(track.uri).lyricsOffsetMs
                    index = currentLyricIndex(lyricsState.lines, playback.positionMs() - offset)
                }
                currentLyricLine.value = index
                val line = if (store.settings.value.lyricsOnNowPlaying && index >= 0 && lyricsState is LyricsState.Synced) {
                    lyricsState.lines[index].text.ifEmpty { "♪" }
                } else null
                playback.setNowPlayingLine(line)
                delay(if (playback.state.value.isPlaying) 250 else 1_000)
            }
        }

        // Last session's queue, paused where it was. Saved songs are looked up
        // by URI, so this waits for the first library scan.
        appScope.launch {
            val saved = queueStore.load() ?: return@launch
            library.tracks.first { it.isNotEmpty() }
            playback.restoreQueue(saved, queueStore.loadPosition(), library::trackFor)
        }
    }
}
