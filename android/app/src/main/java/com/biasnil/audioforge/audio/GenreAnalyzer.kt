package com.biasnil.audioforge.audio

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import com.biasnil.audioforge.data.GenreStore
import com.biasnil.audioforge.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Settings -> Detect Genres: runs [GenreClassifier] over the library,
 * [ANALYSIS_THREADS] songs at a time on its own threads (so it doesn't wait
 * for the key/BPM analysis), like [LibraryAnalyzer] (while the app isn't on
 * screen, only when the phone is charging). Results are kept, so stopping
 * costs nothing and a second run only does new or changed songs.
 */
class GenreAnalyzer(
    private val context: Context,
    private val genres: GenreStore,
    private val scope: CoroutineScope,
    /** Whether the app is on screen (MainActivity started). */
    private val appVisible: StateFlow<Boolean>,
) {
    private val _progress = MutableStateFlow<AnalysisProgress?>(null)
    val progress: StateFlow<AnalysisProgress?> = _progress.asStateFlow()
    private var job: Job? = null

    /** Detects the genre of every song that has no result yet. */
    fun start(tracks: List<Track>) {
        if (job != null) return
        val todo = tracks.filter { genres.genreFor(it) == null }
        if (todo.isEmpty()) return
        _progress.value = AnalysisProgress(0, todo.size)
        job = scope.launch {
            try {
                val next = AtomicInteger()
                val done = AtomicInteger()
                // Each worker has its own classifier: one model can't run two songs at once.
                List(ANALYSIS_THREADS) {
                    launch {
                        GenreClassifier(context).use { classifier ->
                            while (true) {
                                val track = todo.getOrNull(next.getAndIncrement()) ?: break
                                waitUntilAllowed()
                                val result = withContext(GenreDispatcher) {
                                    classifier.detect(Uri.parse(track.uri), track.durationMs)
                                }
                                genres.store(track.uri, result)
                                _progress.value = AnalysisProgress(done.incrementAndGet(), todo.size)
                            }
                        }
                    }
                }.joinAll()
            } finally {
                _progress.value = null
                job = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private suspend fun waitUntilAllowed() {
        while (!appVisible.value && !isCharging()) delay(5_000)
    }

    private fun isCharging(): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: return false
        return status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    }
}
