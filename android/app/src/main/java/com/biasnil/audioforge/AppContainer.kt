package com.biasnil.audioforge

import android.content.Context
import com.biasnil.audioforge.audio.GenreAnalyzer
import com.biasnil.audioforge.audio.LibraryAnalyzer
import com.biasnil.audioforge.data.AnalysisStore
import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.CoverArtLoader
import com.biasnil.audioforge.data.CustomMediaRepository
import com.biasnil.audioforge.data.DuplicateDismissals
import com.biasnil.audioforge.data.GenreStore
import com.biasnil.audioforge.data.LibraryRepository
import com.biasnil.audioforge.data.SongDataStore
import com.biasnil.audioforge.data.UpdateChecker
import com.biasnil.audioforge.data.WallpaperRepository
import com.biasnil.audioforge.lyrics.LyricLineTracker
import com.biasnil.audioforge.lyrics.LyricsRepository
import com.biasnil.audioforge.playback.PlaybackManager
import com.biasnil.audioforge.playback.QueueStore
import com.biasnil.audioforge.tags.TagEditor
import com.biasnil.audioforge.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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
    /** Key/BPM analysis and genre detection take turns: both decode audio and work the CPU. */
    private val analysisLock = Mutex()
    val analyzer = LibraryAnalyzer(context, analysis, store, appScope, appVisible, analysisLock)
    /** Detected genres per song (Settings -> Detect Genres); separate from the GENRE tag. */
    val genres = GenreStore(context, appScope)
    val genreAnalyzer = GenreAnalyzer(context, genres, appScope, appVisible, analysisLock)
    val playback = PlaybackManager(context, store, queueStore, songData, analysis, appScope, library::isPodcast)
    val lyrics = LyricsRepository(context, store, playback, appScope)
    val tagEditor = TagEditor(context)
    val wallpapers = WallpaperRepository(context, store)
    /** The visualizer's custom GIFs / videos (Settings -> Visualizer). */
    val customMedia = CustomMediaRepository(context, store)
    /** Settings -> About -> Version: is there a newer Android release on GitHub? */
    val updates = UpdateChecker(store)
    /** Songs marked "Not duplicates" on the Duplicates page. */
    val duplicateDismissals = DuplicateDismissals(context, appScope).apply { pruneWhenLoaded(library.tracks) }

    /** The synced lyric line being sung (index into the current lyrics), for the lock screen and Android Auto. */
    val currentLyricLine: StateFlow<Int> = LyricLineTracker(playback, lyrics, songData, store, appScope).index

    /** The home-screen widgets (Settings -> Widgets), redrawn as playback changes. */
    val widgets = WidgetUpdater(context, playback, songData, lyrics, currentLyricLine, coverArt, appScope)

    init {
        // Last session's queue, paused where it was. Saved songs are looked up
        // by URI, so this waits for the first library scan.
        appScope.launch {
            val saved = queueStore.load() ?: return@launch
            library.tracks.first { it.isNotEmpty() }
            playback.restoreQueue(saved, queueStore.loadPosition(), library::trackFor)
        }
    }
}
