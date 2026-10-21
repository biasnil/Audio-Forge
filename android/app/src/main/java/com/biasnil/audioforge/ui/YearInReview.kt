package com.biasnil.audioforge.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.biasnil.audioforge.R
import com.biasnil.audioforge.data.ListeningStats
import com.biasnil.audioforge.data.RankedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Shares [year]'s stats as an image ("AudioForge Year in Review"): drawn
 * here, saved in the cache and handed to the share sheet. Nothing is uploaded
 * by the app; the user picks where it goes.
 */
suspend fun shareYearInReview(context: Context, year: Int, stats: ListeningStats, title: String) {
    val file = withContext(Dispatchers.Default) {
        val bitmap = drawYearInReview(context, year, stats, title)
        val dir = File(context.cacheDir, SHARED_DIR).apply { mkdirs() }
        File(dir, "year-in-review-$year.png").also { out ->
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri) // lets the share sheet preview it
    context.startActivity(Intent.createChooser(send, title))
}

/** Folder in the cache the share sheet reads from (res/xml/file_paths.xml). */
private const val SHARED_DIR = "shared"

/** A 4:5 card: the year, hours, plays, top five songs and artists, and the top genre. */
private fun drawYearInReview(context: Context, year: Int, stats: ListeningStats, title: String): Bitmap {
    val width = 1080
    val height = 1350
    val margin = 80f
    val locale: Locale = context.resources.configuration.locales[0]
    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)

    // Night-blue to violet, with a warm glow top right.
    canvas.drawPaint(Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(0xFF14152B.toInt(), 0xFF2E1A5C.toInt(), 0xFF5B1F6E.toInt()), null, Shader.TileMode.CLAMP)
    })
    canvas.drawCircle(width * 0.85f, height * 0.12f, 520f, Paint().apply {
        shader = RadialGradient(width * 0.85f, height * 0.12f, 520f, 0x66FF7A45, 0x00FF7A45, Shader.TileMode.CLAMP)
    })

    fun paint(size: Float, bold: Boolean = false, color: Int = WHITE) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
    fun Canvas.text(value: String, x: Float, y: Float, paint: TextPaint, maxWidth: Float = width - margin - x) =
        drawText(TextUtils.ellipsize(value, paint, maxWidth, TextUtils.TruncateAt.END).toString(), x, y, paint)

    canvas.text(title, margin, 140f, paint(44f, color = SOFT))
    canvas.text(year.toString(), margin, 300f, paint(150f, bold = true))

    val (amount, unit) = amountParts(context, stats.listenedMs, locale)
    canvas.text(amount, margin, 460f, paint(110f, bold = true, color = ACCENT))
    canvas.text(unit, margin, 520f, paint(40f, color = SOFT))
    canvas.text(context.resources.getQuantityString(R.plurals.stats_plays, stats.plays, stats.plays), margin, 580f, paint(40f, color = SOFT))

    val column = (width - 2 * margin - 40f) / 2
    fun Canvas.topFive(header: String, items: List<RankedItem>, x: Float) {
        text(header, x, 700f, paint(38f, bold = true, color = ACCENT), column)
        items.take(5).forEachIndexed { index, item ->
            val y = 770f + index * 92f
            text("${index + 1}  ${item.name}", x, y, paint(36f, bold = true), column)
            if (item.detail.isNotEmpty()) text(item.detail, x + 44f, y + 38f, paint(28f, color = SOFT), column - 44f)
        }
    }
    canvas.topFive(context.getString(R.string.stats_top_songs), stats.songs, margin)
    canvas.topFive(context.getString(R.string.stats_top_artists), stats.artists, margin + column + 40f)

    stats.genres.firstOrNull()?.let { genre ->
        canvas.text(context.getString(R.string.year_in_review_top_genre, genre.name), margin, 1250f, paint(40f, bold = true))
    }
    canvas.text(context.getString(R.string.app_name), margin, 1310f, paint(30f, color = SOFT))
    return bitmap
}

private const val WHITE = 0xFFFFFFFF.toInt()
private const val SOFT = 0xB3FFFFFF.toInt()
private const val ACCENT = 0xFFFFB38A.toInt()
