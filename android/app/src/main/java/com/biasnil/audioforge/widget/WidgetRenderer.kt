package com.biasnil.audioforge.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.SystemClock
import android.text.format.DateUtils
import android.view.View
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.biasnil.audioforge.AppLanguage
import com.biasnil.audioforge.MainActivity
import com.biasnil.audioforge.R
import com.biasnil.audioforge.ui.averageColor
import com.biasnil.audioforge.ui.themeColor

/**
 * The cover in the forms the widgets show it, made once per song (off the
 * main thread). [coverArgb] is the darkened average colour (a background
 * white text reads on), [accentArgb] the colour Now Playing tints with
 * (see the model's accentArgb for where widgets use it).
 */
class WidgetArt(
    val cover: Bitmap?,
    val roundCover: Bitmap?,
    val blurred: Bitmap?,
    val coverArgb: Int?,
    val accentArgb: Int?,
) {
    companion object {
        val NONE = WidgetArt(null, null, null, null, null)

        fun of(cover: Bitmap?): WidgetArt {
            if (cover == null) return NONE
            return WidgetArt(
                cover = cover,
                roundCover = circle(cover),
                blurred = blurred(cover),
                coverArgb = cover.averageColor().toArgb(),
                accentArgb = cover.themeColor()?.toArgb(),
            )
        }

        /** The cover centre-cropped to a circle (Standard). */
        private fun circle(cover: Bitmap): Bitmap {
            val size = minOf(cover.width, cover.height)
            val out = createBitmap(size, size)
            val shader = BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setTranslate(-(cover.width - size) / 2f, -(cover.height - size) / 2f) })
            }
            Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
            return out
        }

        /**
         * A blurred, darkened cover for Practical / Feature-Rich's background.
         * RemoteViews can't blur, so the cover is shrunk to a few pixels and
         * scaled back up smoothly (twice, which hides the blocks).
         */
        private fun blurred(cover: Bitmap): Bitmap {
            val tiny = cover.scale(BLUR_PX, BLUR_PX)
            val mid = tiny.scale(BLUR_PX * 4, BLUR_PX * 4)
            val out = createBitmap(BLURRED_SIZE_PX, BLURRED_SIZE_PX)
            val canvas = Canvas(out)
            canvas.drawBitmap(mid, null, Rect(0, 0, BLURRED_SIZE_PX, BLURRED_SIZE_PX), Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawColor(BLUR_SCRIM)
            return out
        }

        private const val BLUR_PX = 12
        private const val BLURRED_SIZE_PX = 192
        /** Darkens the blurred cover so the white text stays readable. */
        private const val BLUR_SCRIM = 0x80000000.toInt()
    }
}

/**
 * Builds one widget's RemoteViews from a [WidgetSnapshot]: the same code for
 * the home screen and the live previews in Settings -> Widgets. The buttons
 * send broadcasts to [WidgetActionReceiver]; tapping anywhere else opens the app.
 */
class WidgetRenderer(private val context: Context) {

    fun render(
        style: WidgetStyle,
        snapshot: WidgetSnapshot,
        background: WidgetBackground,
        art: WidgetArt,
        positionMs: Long,
    ): RemoteViews {
        // Strings in the app's own language (Android 12 doesn't apply it to this context).
        val text = AppLanguage.localized(context)
        val views = RemoteViews(context.packageName, style.layout)
        val accent = accentArgb(background, art.accentArgb)

        views.setColorStateList(
            android.R.id.background, "setBackgroundTintList",
            ColorStateList.valueOf(backgroundArgb(background, art.coverArgb)),
        )
        if (style.has(WidgetPart.BlurredCover)) {
            val blurred = art.blurred.takeIf { background == WidgetBackground.CoverColours }
            if (blurred != null) views.setImageViewBitmap(R.id.widget_backdrop, blurred)
            views.setViewVisibility(R.id.widget_backdrop, if (blurred != null) View.VISIBLE else View.GONE)
        }
        views.setOnClickPendingIntent(android.R.id.background, openApp())

        if (style.has(WidgetPart.Title)) {
            views.setTextViewText(R.id.widget_title, if (snapshot.hasTrack) snapshot.title else text.getString(R.string.app_name))
            views.setTextViewText(
                R.id.widget_artist,
                when {
                    !snapshot.hasTrack -> text.getString(R.string.auto_nothing_playing)
                    snapshot.isRadio && snapshot.artist.isEmpty() -> text.getString(R.string.radio_live)
                    snapshot.artist.isEmpty() -> text.getString(R.string.unknown_artist)
                    else -> snapshot.artist
                },
            )
        }
        if (style.has(WidgetPart.Cover) || style.has(WidgetPart.RoundCover)) {
            val round = style.has(WidgetPart.RoundCover)
            val bitmap = if (round) art.roundCover else art.cover
            if (style == WidgetStyle.Stylish) {
                // Its cover fills half the widget: no cover shows the panel behind, with its note.
                if (bitmap != null) views.setImageViewBitmap(R.id.widget_cover, bitmap) else views.setImageViewResource(R.id.widget_cover, 0)
                views.setViewVisibility(R.id.widget_cover_note, if (bitmap != null) View.GONE else View.VISIBLE)
            } else if (bitmap != null) {
                views.setImageViewBitmap(R.id.widget_cover, bitmap)
            } else {
                views.setImageViewResource(
                    R.id.widget_cover,
                    if (round) R.drawable.widget_cover_placeholder_round else R.drawable.widget_cover_placeholder,
                )
            }
        }

        views.setImageViewResource(R.id.widget_play, if (snapshot.isPlaying) R.drawable.widget_ic_pause else R.drawable.widget_ic_play)
        views.setContentDescription(R.id.widget_play, text.getString(if (snapshot.isPlaying) R.string.pause else R.string.play))
        views.setOnClickPendingIntent(R.id.widget_play, action(WidgetActionReceiver.ACTION_PLAY_PAUSE))
        views.setOnClickPendingIntent(R.id.widget_prev, action(WidgetActionReceiver.ACTION_PREVIOUS))
        views.setOnClickPendingIntent(R.id.widget_next, action(WidgetActionReceiver.ACTION_NEXT))

        if (style.has(WidgetPart.ShuffleAndLove)) {
            // Shuffle on: the accent; off: dimmed white.
            views.setInt(R.id.widget_shuffle, "setColorFilter", if (snapshot.shuffleOn) accent else WHITE_ARGB)
            views.setInt(R.id.widget_shuffle, "setImageAlpha", if (snapshot.shuffleOn) 255 else DIMMED_ALPHA)
            views.setOnClickPendingIntent(R.id.widget_shuffle, action(WidgetActionReceiver.ACTION_SHUFFLE))
            views.setImageViewResource(
                R.id.widget_love,
                if (snapshot.loved) R.drawable.widget_ic_heart else R.drawable.widget_ic_heart_outline,
            )
            views.setInt(R.id.widget_love, "setColorFilter", if (snapshot.loved) LOVED_RED else WHITE_ARGB)
            views.setContentDescription(R.id.widget_love, text.getString(if (snapshot.loved) R.string.unlove else R.string.love))
            views.setOnClickPendingIntent(R.id.widget_love, action(WidgetActionReceiver.ACTION_LOVE))
        }

        if (style.has(WidgetPart.Progress)) {
            views.setProgressBar(R.id.widget_progress, PROGRESS_MAX, progressOf(positionMs, snapshot.durationMs), false)
            views.setColorStateList(R.id.widget_progress, "setProgressTintList", ColorStateList.valueOf(accent))
            views.setChronometer(
                R.id.widget_elapsed,
                chronometerBase(SystemClock.elapsedRealtime(), positionMs),
                null,
                elapsedTicks(snapshot.isPlaying, snapshot.rate),
            )
            // In the Chronometer's own format ("01:35"), so the two times match.
            views.setTextViewText(R.id.widget_duration, DateUtils.formatElapsedTime(snapshot.durationMs / 1000))
        }

        if (style.has(WidgetPart.Lyric)) {
            views.setTextViewText(R.id.widget_lyric, snapshot.lyricLine.orEmpty())
            views.setViewVisibility(R.id.widget_lyric, if (snapshot.lyricLine != null) View.VISIBLE else View.GONE)
            views.setTextColor(R.id.widget_lyric, accent)
        }

        if (style.has(WidgetPart.UpNext)) {
            views.setViewVisibility(R.id.widget_up_next_label, if (snapshot.upNext.isEmpty()) View.GONE else View.VISIBLE)
            listOf(R.id.widget_next1, R.id.widget_next2).forEachIndexed { index, id ->
                val song = snapshot.upNext.getOrNull(index)
                if (song != null) {
                    val artist = song.artist.ifEmpty { text.getString(R.string.unknown_artist) }
                    views.setTextViewText(id, "${song.title}  •  $artist")
                }
                views.setViewVisibility(id, if (song != null) View.VISIBLE else View.GONE)
            }
        }
        return views
    }

    private fun action(action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, WidgetActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** MainActivity is singleTop: back to the running app, not a second copy. */
    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        OPEN_APP_REQUEST,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        /** The heart on Now Playing. */
        const val LOVED_RED = 0xFFFF2D55.toInt()
        const val DIMMED_ALPHA = 110
        /** Not 0, which PlaybackService's notification intent uses. */
        const val OPEN_APP_REQUEST = 1
    }
}

/** The layout of each style (res/layout/widget_*.xml). */
val WidgetStyle.layout: Int
    get() = when (this) {
        WidgetStyle.Mini -> R.layout.widget_mini
        WidgetStyle.Lite -> R.layout.widget_lite
        WidgetStyle.Classic -> R.layout.widget_classic
        WidgetStyle.Simple -> R.layout.widget_simple
        WidgetStyle.Standard -> R.layout.widget_standard
        WidgetStyle.Stylish -> R.layout.widget_stylish
        WidgetStyle.Practical -> R.layout.widget_practical
        WidgetStyle.FeatureRich -> R.layout.widget_feature_rich
    }
