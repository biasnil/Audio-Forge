package com.biasnil.audioforge.lyrics

import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.SongDataStore
import com.biasnil.audioforge.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Follows the synced lyric line being sung, for outside the app: the lock
 * screen shows it in place of the artist (if Settings > Lyrics on Lock
 * Screen & Android Auto is on), and Android Auto's Lyrics list marks it.
 * Checks four times a second while playing, once a second while paused.
 */
class LyricLineTracker(
    private val playback: PlaybackManager,
    private val lyrics: LyricsRepository,
    private val songData: SongDataStore,
    private val store: AppStore,
    scope: CoroutineScope,
) {
    private val _index = MutableStateFlow(-1)

    /** Index of the line being sung in the current synced lyrics; -1 = none. */
    val index: StateFlow<Int> = _index.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                update()
                delay(if (playback.state.value.isPlaying) 250 else 1_000)
            }
        }
    }

    private fun update() {
        val track = playback.state.value.current
        val lyricsState = lyrics.state.value
        var index = -1
        if (track != null && lyricsState is LyricsState.Synced) {
            val offset = songData.statsFor(track.uri).lyricsOffsetMs
            index = currentLyricIndex(lyricsState.lines, playback.positionMs() - offset)
        }
        _index.value = index
        val line = if (store.settings.value.lyricsOnNowPlaying && index >= 0 && lyricsState is LyricsState.Synced) {
            lyricsState.lines[index].text.ifEmpty { "♪" }
        } else null
        playback.setNowPlayingLine(line)
    }
}
