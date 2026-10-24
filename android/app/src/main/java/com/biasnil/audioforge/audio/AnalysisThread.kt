package com.biasnil.audioforge.audio

import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * The one thread Key/BPM analysis and genre detection run on, at Android's
 * background priority: decoding whole songs is heavy, and on a big library
 * it used to compete with scrolling and playback for the CPU. Now the
 * screen and the music always come first; analysis just takes longer.
 */
val AnalysisDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor { task ->
    Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        task.run()
    }, "AudioForge-analysis")
}.asCoroutineDispatcher()
