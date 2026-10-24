package com.biasnil.audioforge

import android.content.Context
import com.biasnil.audioforge.audio.GenreAnalyzer
import com.biasnil.audioforge.audio.LibraryAnalyzer
import com.biasnil.audioforge.data.AnalysisStore
import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.AudiobookStore
import com.biasnil.audioforge.data.FreeAudiobookRepository
import com.biasnil.audioforge.data.ChapterRepository
import com.biasnil.audioforge.data.bookKey
import com.biasnil.audioforge.data.CoverArtLoader
import com.biasnil.audioforge.data.CustomMediaRepository
import com.biasnil.audioforge.data.DuplicateDismissals
import com.biasnil.audioforge.data.GenreStore
import com.biasnil.audioforge.data.LibraryRepository
import com.biasnil.audioforge.data.PlayLogStore
import com.biasnil.audioforge.data.PodcastRepository
import com.biasnil.audioforge.data.HeadphoneProfileRepository
import com.biasnil.audioforge.data.MetadataRepository
import com.biasnil.audioforge.data.RadioRepository
import com.biasnil.audioforge.data.RadioStation
import com.biasnil.audioforge.data.SongDataStore
import com.biasnil.audioforge.data.UpdateChecker
import com.biasnil.audioforge.data.WallpaperRepository
import com.biasnil.audioforge.lyrics.LyricLineTracker
import com.biasnil.audioforge.lyrics.LyricsRepository
import com.biasnil.audioforge.playback.OutputDeviceMonitor
import com.biasnil.audioforge.playback.PlaybackManager
import com.biasnil.audioforge.playback.QueueStore
import com.biasnil.audioforge.tags.TagEditor
import com.biasnil.audioforge.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** App-wide singletons, created once in [AudioForgeApplication]. */
class AppContainer(context: Context) {
    /** Main-thread scope that lives as long as the app process. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val store = AppStore(context, appScope)
    val library = LibraryRepository(context, store, appScope)
    val coverArt = CoverArtLoader(context)
    /** Missing tags and covers from Apple / Deezer (tag editor, Settings -> Library). */
    val metadata = MetadataRepository(context, coverArt, appScope)
    private val queueStore = QueueStore(context)
    /** Play counts, Loved songs, long-track positions. */
    val songData = SongDataStore(context, appScope)
    /** Every play, for Settings -> Listening stats. */
    val playLog = PlayLogStore(context, appScope)
    /** BPM, key, silence and outro per song ("Analyze Your Songs' Key and BPM"). */
    val analysis = AnalysisStore(context, appScope)
    /** Whether the app is on screen (set by MainActivity); analysis only runs in the background while charging. */
    val appVisible = MutableStateFlow(false)
    /** Key/BPM analysis and genre detection each have their own threads, so both can run at once. */
    val analyzer = LibraryAnalyzer(context, analysis, store, appScope, appVisible)
    /** Detected genres per song (Settings -> Detect Genres); separate from the GENRE tag. */
    val genres = GenreStore(context, appScope)
    val genreAnalyzer = GenreAnalyzer(context, genres, appScope, appVisible)
    // Audiobooks behave like podcast episodes in the player: they always resume and count as listened at the end.
    val playback = PlaybackManager(context, store, queueStore, songData, playLog, analysis, appScope) { uri ->
        library.isPodcast(uri) || library.isAudiobook(uri)
    }
    /** Where music is playing now; swaps in that device's EQ when "A separate EQ for each device" is on. */
    val outputDevice = OutputDeviceMonitor(context, store, appScope)
    val lyrics = LyricsRepository(context, store, playback, appScope)
    val tagEditor = TagEditor(context)
    /** Podcast search, subscriptions and episode downloads (the Podcasts tab). */
    val podcasts = PodcastRepository(context, library, appScope)
    /** Free public-domain audiobooks (LibriVox, from the Internet Archive). */
    val freeAudiobooks = FreeAudiobookRepository(context, library, appScope)
    /** Audiobook bookmarks and each book's last-played file (the Audiobooks tab). */
    val audiobookStore = AudiobookStore(context, appScope)
    /** Chapters read from audiobook files. */
    val chapters = ChapterRepository(context)
    /** Internet radio (the Radio tab): stations, favorites, recently played. */
    val radio = RadioRepository(context, appScope)
    /** AutoEq headphone correction profiles (Equalizer -> Effects). */
    val headphoneProfiles = HeadphoneProfileRepository(context)
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

    /** Plays [station] (it replaces the queue) and adds it to Recently played. */
    fun playStation(station: RadioStation) {
        playback.playStation(station)
        radio.played(station)
    }

    init {
        // Pending settings are saved as soon as the app leaves the screen, in case it's closed.
        appScope.launch { appVisible.collect { visible -> if (!visible) store.flush() } }
        // Remember which file of a book was playing, for the book's progress and for Play.
        appScope.launch {
            playback.state.map { it.current }.distinctUntilChangedBy { it?.uri }.collect { track ->
                if (track != null && library.isAudiobook(track.uri)) audiobookStore.setLastPart(bookKey(track), track.uri)
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
