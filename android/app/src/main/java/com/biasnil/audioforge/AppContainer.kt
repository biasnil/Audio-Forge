package com.biasnil.audioforge

import android.content.Context
import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.CoverArtLoader
import com.biasnil.audioforge.data.LibraryRepository
import com.biasnil.audioforge.lyrics.LyricsRepository
import com.biasnil.audioforge.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide singletons, created once in [AudioForgeApplication]. */
class AppContainer(context: Context) {
    /** Main-thread scope that lives as long as the app process. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val store = AppStore(context, appScope)
    val library = LibraryRepository(context, store, appScope)
    val coverArt = CoverArtLoader(context)
    val playback = PlaybackManager(context, store, appScope)
    val lyrics = LyricsRepository(context, store, playback, appScope)
}
