package com.biasnil.audioforge.audio

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import com.biasnil.audioforge.data.AnalysisStore
import com.biasnil.audioforge.data.AppStore
import com.biasnil.audioforge.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** (done, total) of a running analysis. */
data class AnalysisProgress(val done: Int, val total: Int)

/**
 * Runs the key/BPM analysis over the library, two songs at a time, in the
 * background (the iOS app's LibraryAnalyzer). When every song has been
 * analyzed, the Smart Options unlock. Analysis decodes whole songs and warms
 * the phone, so while the app isn't on screen it only carries on when the
 * phone is charging.
 */
class LibraryAnalyzer(
    private val context: Context,
    private val analysis: AnalysisStore,
    private val store: AppStore,
    private val scope: CoroutineScope,
    /** Whether the app is on screen (MainActivity started). */
    private val appVisible: StateFlow<Boolean>,
    /** Shared with genre detection, so the two never decode at once. */
    private val analysisLock: Mutex,
) {
    private val _progress = MutableStateFlow<AnalysisProgress?>(null)
    val progress: StateFlow<AnalysisProgress?> = _progress.asStateFlow()
    private var job: Job? = null

    val isRunning: Boolean get() = job != null

    /** Analyzes every song that hasn't been yet. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(tracks: List<Track>) {
        if (job != null) return
        val todo = tracks.filter { analysis.analysisFor(it) == null }
        if (todo.isEmpty()) {
            store.update { it.copy(analysisCompleted = true) }
            return
        }
        _progress.value = AnalysisProgress(0, todo.size)
        job = scope.launch {
            var done = 0
            var cancelled = false
            try {
                analysisLock.withLock {
                    val permits = Semaphore(2)
                    todo.map { track ->
                        async {
                            permits.withPermit {
                                waitUntilAllowed()
                                val result = withContext(Dispatchers.Default) { AudioAnalyzer.analyze(context, Uri.parse(track.uri)) }
                                if (result != null) analysis.store(track.uri, result)
                                done++
                                _progress.value = AnalysisProgress(done, todo.size)
                            }
                        }
                    }.awaitAll()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled = true
                throw e
            } finally {
                if (!cancelled) store.update { it.copy(analysisCompleted = true) }
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
