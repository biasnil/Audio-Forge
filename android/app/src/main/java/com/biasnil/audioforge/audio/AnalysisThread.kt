package com.biasnil.audioforge.audio

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * How many songs Key/BPM analysis, and separately genre detection, work on
 * at once: half the phone's cores, 2 to 4. Each has its own threads, so the
 * two can run side by side.
 */
val ANALYSIS_THREADS: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

/** Key/BPM analysis' threads. */
val AnalysisDispatcher: CoroutineDispatcher = backgroundThreads("AudioForge-analysis", ANALYSIS_THREADS)

/** Genre detection's threads. */
val GenreDispatcher: CoroutineDispatcher = backgroundThreads("AudioForge-genre", ANALYSIS_THREADS)

/**
 * Threads at Android's background priority: decoding whole songs is heavy,
 * and on a big library it used to compete with scrolling and playback for
 * the CPU. The screen and the music always come first.
 */
private fun backgroundThreads(name: String, count: Int): CoroutineDispatcher {
    val number = AtomicInteger()
    return Executors.newFixedThreadPool(count) { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "$name-${number.incrementAndGet()}")
    }.asCoroutineDispatcher()
}
