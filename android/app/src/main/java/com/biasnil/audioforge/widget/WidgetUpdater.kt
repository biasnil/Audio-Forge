package com.biasnil.audioforge.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.content.edit
import androidx.media3.common.Player
import com.biasnil.audioforge.data.CoverArtLoader
import com.biasnil.audioforge.data.SongDataStore
import com.biasnil.audioforge.lyrics.LyricsRepository
import com.biasnil.audioforge.playback.PlaybackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps the home-screen widgets in step with playback. They're redrawn when
 * what they show changes -- play/pause, the song, shuffle, Loved, a seek,
 * the queue and the lyric line (Feature-Rich) -- never on a timer: the
 * elapsed time counts by itself (see [chronometerBase]) and the progress bar
 * moves on the next change. Main thread only.
 */
class WidgetUpdater(
    context: Context,
    private val playback: PlaybackManager,
    songData: SongDataStore,
    lyrics: LyricsRepository,
    lyricLine: StateFlow<Int>,
    private val coverArt: CoverArtLoader,
    scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val manager = AppWidgetManager.getInstance(appContext)
    private val renderer = WidgetRenderer(appContext)
    val backgrounds = WidgetBackgrounds(appContext)

    private val seeks = MutableStateFlow(0)
    private val refreshes = MutableStateFlow(0)
    /** What each style's widgets show now ([keyFor]), so unchanged ones aren't redrawn. */
    private val drawn = HashMap<WidgetStyle, WidgetSnapshot>()
    /** The current song's art, by "uri@cover version". */
    private var art: Pair<String, WidgetArt>? = null

    private val seekListener = object : Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) seeks.value++
        }
    }

    /** What the widgets show; the Settings -> Widgets previews follow it too. */
    val snapshot: StateFlow<WidgetSnapshot> = combine(
        playback.state,
        playback.queueState,
        songData.stats,
        combine(lyrics.state, lyricLine) { state, index -> currentLyricText(state, index) },
        combine(coverArt.version, seeks) { version, seekCount -> version to seekCount },
    ) { state, queue, stats, line, (version, seekCount) ->
        val loved = state.current?.let { stats[it.uri]?.favorite } == true
        widgetSnapshot(state, queue.upcoming.map { it.item }, loved, line, seekCount, version)
    }.stateIn(scope, SharingStarted.Eagerly, WidgetSnapshot())

    init {
        // A crossfade hands playback to the other player; follow its seeks.
        scope.launch {
            var watched: Player? = null
            playback.sessionPlayer.collect { player ->
                watched?.removeListener(seekListener)
                player.addListener(seekListener)
                watched = player
            }
        }
        scope.launch {
            combine(snapshot, refreshes) { current, _ -> current }.collectLatest { draw(it) }
        }
    }

    /** Redraws every widget: one was added, the launcher asked, or a background changed. */
    fun refresh() {
        drawn.clear()
        refreshes.value++
    }

    /** A live preview for Settings -> Widgets: [style] as it would look now with [background]. */
    suspend fun preview(style: WidgetStyle, background: WidgetBackground): RemoteViews {
        val current = snapshot.value
        return renderer.render(style, current, background, artFor(current), playback.positionMs())
    }

    /** Whether the launcher lets the app add a widget itself (Settings -> Widgets' Add buttons). */
    val canPin: Boolean get() = manager.isRequestPinAppWidgetSupported

    /**
     * Asks the launcher to add a [style] widget; the user confirms there. The
     * launcher then tells [WidgetActionReceiver] the new widget's id, which
     * gets [background]. False if the launcher can't.
     */
    fun requestPin(style: WidgetStyle, background: WidgetBackground): Boolean {
        val callback = PendingIntent.getBroadcast(
            appContext,
            style.ordinal,
            Intent(appContext, WidgetActionReceiver::class.java)
                .setAction(WidgetActionReceiver.ACTION_PINNED)
                .putExtra(WidgetActionReceiver.EXTRA_BACKGROUND, background.id),
            // Mutable: the launcher adds the new widget's id.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return manager.requestPinAppWidget(ComponentName(appContext, style.provider), null, callback)
    }

    private suspend fun draw(current: WidgetSnapshot) {
        for (style in WidgetStyle.entries) {
            val ids = manager.getAppWidgetIds(ComponentName(appContext, style.provider))
            if (ids.isEmpty()) {
                drawn.remove(style)
                continue
            }
            val key = current.keyFor(style)
            if (drawn[style] == key) continue
            val songArt = artFor(current)
            val position = playback.positionMs()
            for (id in ids) {
                manager.updateAppWidget(id, renderer.render(style, current, backgrounds[id], songArt, position))
            }
            drawn[style] = key
        }
    }

    private suspend fun artFor(current: WidgetSnapshot): WidgetArt {
        val uri = current.trackUri ?: return WidgetArt.NONE
        val key = "$uri@${current.coverVersion}"
        art?.let { (cachedKey, cached) -> if (cachedKey == key) return cached }
        val track = playback.state.value.current?.takeIf { it.uri == uri } ?: return WidgetArt.NONE
        val made = withContext(Dispatchers.Default) { WidgetArt.of(coverArt.load(track, COVER_PX)) }
        art = key to made
        return made
    }

    private companion object {
        /** Big enough for Stylish's half-widget cover; widget bitmaps count against a memory limit. */
        const val COVER_PX = 360
    }
}

/**
 * Each widget's background, by widget id. Set from Settings -> Widgets when
 * adding one, or from the widget's own settings on the home screen
 * ([WidgetConfigActivity]). Kept in its own small preferences file.
 */
class WidgetBackgrounds(context: Context) {
    private val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    operator fun get(widgetId: Int): WidgetBackground = WidgetBackground.fromId(prefs.getString(key(widgetId), null))

    operator fun set(widgetId: Int, background: WidgetBackground) = prefs.edit { putString(key(widgetId), background.id) }

    fun remove(widgetIds: IntArray) = prefs.edit { widgetIds.forEach { remove(key(it)) } }

    /** After a restore from backup the widgets have new ids. */
    fun move(oldIds: IntArray, newIds: IntArray) {
        val moved = oldIds.map { this[it] }
        remove(oldIds)
        newIds.zip(moved).forEach { (id, background) -> this[id] = background }
    }

    private fun key(widgetId: Int) = "background_$widgetId"
}
