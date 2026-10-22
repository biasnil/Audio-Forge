package com.biasnil.audioforge.widget

import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.lyrics.LyricsState
import com.biasnil.audioforge.playback.PlaybackState
import com.biasnil.audioforge.playback.ShuffleMode

// Home-screen widgets: the styles, and what each one shows. Plain Kotlin,
// tested on the JVM (WidgetModelTest); WidgetRenderer turns a snapshot into
// RemoteViews and WidgetUpdater pushes them when playback changes.

/** What a widget style shows besides the previous / play-pause / next buttons. */
enum class WidgetPart {
    Title,
    Cover,
    /** The cover cut to a circle (Standard). */
    RoundCover,
    /** The cover, blurred, behind everything (when the background is Cover colours). */
    BlurredCover,
    ShuffleAndLove,
    Progress,
    /** The next two songs. */
    UpNext,
    /** The synced lyric line being sung. */
    Lyric,
}

/**
 * The widget styles, in the order Settings -> Widgets lists them, with
 * their size in home-screen cells. Each is its own entry in the launcher's
 * widget picker (see AudioForgeWidgets.kt).
 */
enum class WidgetStyle(val columns: Int, val rows: Int, val parts: Set<WidgetPart>) {
    Mini(3, 1, emptySet()),
    Lite(4, 1, setOf(WidgetPart.ShuffleAndLove)),
    Classic(4, 1, setOf(WidgetPart.Cover, WidgetPart.Title)),
    Simple(3, 2, setOf(WidgetPart.Title, WidgetPart.Progress)),
    Standard(4, 2, setOf(WidgetPart.RoundCover, WidgetPart.Title)),
    Stylish(4, 2, setOf(WidgetPart.Cover, WidgetPart.Title)),
    Practical(
        4, 3,
        setOf(WidgetPart.BlurredCover, WidgetPart.Cover, WidgetPart.Title, WidgetPart.Progress, WidgetPart.ShuffleAndLove),
    ),
    FeatureRich(
        4, 3,
        setOf(
            WidgetPart.BlurredCover, WidgetPart.Title, WidgetPart.Progress, WidgetPart.ShuffleAndLove,
            WidgetPart.UpNext, WidgetPart.Lyric,
        ),
    );

    fun has(part: WidgetPart): Boolean = part in parts
}

/** A widget's background, chosen per widget. */
enum class WidgetBackground(val id: String) {
    /** The cover's colour (the one Now Playing uses), or the blurred cover on Practical / Feature-Rich. */
    CoverColours("cover"),
    Black("black"),
    Translucent("translucent");

    companion object {
        val DEFAULT = CoverColours

        fun fromId(id: String?): WidgetBackground = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** One of the next songs, for Feature-Rich. */
data class UpNextSong(val title: String, val artist: String)

/**
 * Everything a widget shows, apart from the cover image and the playback
 * position (read when the widget is drawn, see [progressOf]). Widgets are
 * redrawn only when this changes -- see [keyFor].
 */
data class WidgetSnapshot(
    val trackUri: String? = null,
    val title: String = "",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val shuffleOn: Boolean = false,
    val loved: Boolean = false,
    val durationMs: Long = 0,
    val rate: Float = 1f,
    /** Bumped on every seek, so the widgets with a progress bar redraw. */
    val seekCount: Int = 0,
    /** CoverArtLoader.version: bumped when a cover is changed. */
    val coverVersion: Int = 0,
    val upNext: List<UpNextSong> = emptyList(),
    val lyricLine: String? = null,
    /** A radio station: [title] is the station, [artist] what it says is playing ("" if nothing). */
    val isRadio: Boolean = false,
) {
    val hasTrack: Boolean get() = trackUri != null
}

/** How many of the next songs Feature-Rich lists. */
const val UP_NEXT_COUNT = 2

/** The progress bar's maximum; [progressOf] is out of this. */
const val PROGRESS_MAX = 1000

fun widgetSnapshot(
    state: PlaybackState,
    upcoming: List<Track>,
    loved: Boolean,
    lyricLine: String?,
    seekCount: Int,
    coverVersion: Int,
): WidgetSnapshot {
    val track = state.current ?: return WidgetSnapshot(seekCount = seekCount, coverVersion = coverVersion)
    return WidgetSnapshot(
        trackUri = track.uri,
        title = track.title,
        artist = if (track.isRadio) state.liveTitle.orEmpty() else track.artist,
        isRadio = track.isRadio,
        isPlaying = state.isPlaying,
        shuffleOn = state.shuffleMode != ShuffleMode.Off,
        loved = loved,
        durationMs = state.durationMs.takeIf { it > 0 } ?: track.durationMs,
        rate = state.rate,
        seekCount = seekCount,
        coverVersion = coverVersion,
        upNext = upcoming.take(UP_NEXT_COUNT).map { UpNextSong(it.title, it.artist) },
        lyricLine = lyricLine,
    )
}

/**
 * The part of [snapshot] that [style] shows: two snapshots with the same key
 * draw the same widget, so it isn't redrawn (widget updates are costly and
 * the launcher may throttle them). Mini, for example, ignores the lyric line,
 * and only the styles with a progress bar redraw on a seek. The song and its
 * cover always count: every style can take its background from the cover.
 */
fun WidgetSnapshot.keyFor(style: WidgetStyle): WidgetSnapshot = copy(
    title = if (style.has(WidgetPart.Title)) title else "",
    artist = if (style.has(WidgetPart.Title)) artist else "",
    shuffleOn = shuffleOn && style.has(WidgetPart.ShuffleAndLove),
    loved = loved && style.has(WidgetPart.ShuffleAndLove),
    durationMs = if (style.has(WidgetPart.Progress)) durationMs else 0,
    rate = if (style.has(WidgetPart.Progress)) rate else 1f,
    seekCount = if (style.has(WidgetPart.Progress)) seekCount else 0,
    upNext = if (style.has(WidgetPart.UpNext)) upNext else emptyList(),
    lyricLine = if (style.has(WidgetPart.Lyric)) lyricLine else null,
)

/** The progress bar's fill, 0..[PROGRESS_MAX]; 0 when the length isn't known. */
fun progressOf(positionMs: Long, durationMs: Long): Int {
    if (durationMs <= 0) return 0
    return (positionMs.coerceIn(0, durationMs) * PROGRESS_MAX / durationMs).toInt()
}

/**
 * The elapsed time is a Chronometer, which counts by itself from the last
 * known position, so the widget isn't redrawn every second. Its base, in
 * SystemClock.elapsedRealtime() time, makes it show [positionMs] at [nowMs].
 */
fun chronometerBase(nowMs: Long, positionMs: Long): Long = nowMs - positionMs.coerceAtLeast(0)

/**
 * Whether the elapsed time keeps counting: only while playing at normal
 * speed (a Chronometer counts in real time, so at 1.5x it would fall
 * behind); otherwise it shows the position it was drawn with.
 */
fun elapsedTicks(isPlaying: Boolean, rate: Float): Boolean = isPlaying && rate == 1f

/** The synced line being sung ("♪" for an instrumental gap), or null before the first line / without synced lyrics. */
fun currentLyricText(lyrics: LyricsState, index: Int): String? {
    val synced = lyrics as? LyricsState.Synced ?: return null
    return synced.lines.getOrNull(index)?.text?.ifBlank { "♪" }
}

/** Fallback for Cover colours when the song has no cover: the app's dark window colour. */
const val DEFAULT_BACKGROUND_ARGB = 0xFF1E1F22.toInt()

/** The widget's background colour; [coverArgb] is the cover's (darkened) colour, null without a cover. */
fun backgroundArgb(background: WidgetBackground, coverArgb: Int?): Int = when (background) {
    WidgetBackground.CoverColours -> coverArgb ?: DEFAULT_BACKGROUND_ARGB
    WidgetBackground.Black -> 0xFF000000.toInt()
    WidgetBackground.Translucent -> 0x99000000.toInt()
}

const val WHITE_ARGB = 0xFFFFFFFF.toInt()

/**
 * The colour of shuffle-on, the progress bar and the lyric line: the cover's
 * accent [accentArgb] (as on Now Playing) over black or translucent, but white
 * over Cover colours, where the accent is too close to the background to read.
 */
fun accentArgb(background: WidgetBackground, accentArgb: Int?): Int =
    accentArgb?.takeIf { background != WidgetBackground.CoverColours } ?: WHITE_ARGB
