package com.biasnil.audioforge.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.biasnil.audioforge.AppContainer
import com.biasnil.audioforge.AudioForgeApplication
import com.biasnil.audioforge.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private val Context.container: AppContainer get() = (applicationContext as AudioForgeApplication).container

/**
 * One home-screen widget style. Each style is its own provider, so each is
 * its own entry in the launcher's widget picker (res/xml/widget_info_*.xml).
 * Drawing is all [WidgetUpdater]'s; the system only says when to redraw.
 */
abstract class AudioForgeWidget : AppWidgetProvider() {
    /** A widget was added, or the launcher restarted. */
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) =
        context.container.widgets.refresh()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) =
        context.container.widgets.backgrounds.remove(appWidgetIds)

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) =
        context.container.widgets.backgrounds.move(oldWidgetIds, newWidgetIds)
}

class MiniWidget : AudioForgeWidget()
class LiteWidget : AudioForgeWidget()
class ClassicWidget : AudioForgeWidget()
class SimpleWidget : AudioForgeWidget()
class StandardWidget : AudioForgeWidget()
class StylishWidget : AudioForgeWidget()
class PracticalWidget : AudioForgeWidget()
class FeatureRichWidget : AudioForgeWidget()

val WidgetStyle.provider: Class<out AudioForgeWidget>
    get() = when (this) {
        WidgetStyle.Mini -> MiniWidget::class.java
        WidgetStyle.Lite -> LiteWidget::class.java
        WidgetStyle.Classic -> ClassicWidget::class.java
        WidgetStyle.Simple -> SimpleWidget::class.java
        WidgetStyle.Standard -> StandardWidget::class.java
        WidgetStyle.Stylish -> StylishWidget::class.java
        WidgetStyle.Practical -> PracticalWidget::class.java
        WidgetStyle.FeatureRich -> FeatureRichWidget::class.java
    }

/**
 * The widgets' buttons, and the launcher's "widget added" callback from
 * Settings -> Widgets. Not exported: only this app's PendingIntents reach it.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        val playback = container.playback
        when (val action = intent.action) {
            ACTION_SHUFFLE -> playback.cycleShuffleMode()
            ACTION_LOVE -> playback.state.value.current?.let { container.songData.toggleFavorite(it.uri) }
            ACTION_PLAY_PAUSE, ACTION_NEXT, ACTION_PREVIOUS -> {
                val pending = goAsync()
                container.appScope.launch {
                    try {
                        transport(context.applicationContext, container, action)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_PINNED -> {
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    container.widgets.backgrounds[id] = WidgetBackground.fromId(intent.getStringExtra(EXTRA_BACKGROUND))
                    container.widgets.refresh()
                }
            }
        }
    }

    /**
     * Play/pause, next, previous. The app may not be running: connecting a
     * MediaController starts PlaybackService (as MainActivity does), so the
     * music gets its notification and keeps playing. A tap on a widget is
     * one of the moments Android lets that service go to the foreground.
     */
    private suspend fun transport(context: Context, container: AppContainer, action: String) {
        val future = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java)),
        ).buildAsync()
        try {
            withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { done ->
                    future.addListener({ done.resume(Unit) }, ContextCompat.getMainExecutor(context))
                }
            }
            val playback = container.playback
            // Right after the app starts, the last queue is still being restored (it waits for the library scan).
            if (playback.state.value.current == null) {
                withTimeoutOrNull(RESTORE_TIMEOUT_MS) { playback.state.first { it.current != null } }
            }
            when (action) {
                ACTION_PLAY_PAUSE -> playback.togglePlayPause()
                ACTION_NEXT -> playback.next()
                ACTION_PREVIOUS -> playback.previous()
            }
            // Gives the session time to see the new state (and go to the foreground) before letting go.
            delay(HOLD_MS)
        } finally {
            MediaController.releaseFuture(future)
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.biasnil.audioforge.widget.PLAY_PAUSE"
        const val ACTION_NEXT = "com.biasnil.audioforge.widget.NEXT"
        const val ACTION_PREVIOUS = "com.biasnil.audioforge.widget.PREVIOUS"
        const val ACTION_SHUFFLE = "com.biasnil.audioforge.widget.SHUFFLE"
        const val ACTION_LOVE = "com.biasnil.audioforge.widget.LOVE"
        const val ACTION_PINNED = "com.biasnil.audioforge.widget.PINNED"
        const val EXTRA_BACKGROUND = "background"

        // A broadcast gets about 10 s in all.
        private const val CONNECT_TIMEOUT_MS = 2_000L
        private const val RESTORE_TIMEOUT_MS = 5_000L
        private const val HOLD_MS = 1_000L
    }
}
