package com.biasnil.audioforge.data

import org.json.JSONObject
import java.net.URLEncoder

// Free audiobooks: LibriVox's public-domain recordings, as kept by the
// Internet Archive (archive.org), which needs no account or key. What's
// sent: the search words, and which book is opened or downloaded.
// Plain Kotlin, tested on the JVM; FreeAudiobookRepository does the requests.

/** A book in search results. [runtime] is as the Archive gives it ("13:06:44"); may be empty. */
data class FreeBook(
    val id: String,
    val title: String,
    val author: String,
    val runtime: String = "",
)

/** One file of a book (usually a chapter or a few), in reading order. */
data class FreeBookPart(val fileName: String, val title: String, val durationMs: Long)

/** A book's page: its parts, cover and description. */
data class FreeBookDetails(
    val book: FreeBook,
    val parts: List<FreeBookPart>,
    val coverFileName: String?,
    val description: String,
)

private const val ARCHIVE = "https://archive.org"

/** The most downloaded LibriVox books whose title or author matches [term]. */
fun freeBookSearchUrl(term: String): String {
    val words = term.trim().replace(Regex("[()\":]"), " ").trim()
    val query = "collection:librivoxaudio AND (title:($words) OR creator:($words))"
    return "$ARCHIVE/advancedsearch.php?q=" + encode(query) +
        "&fl[]=identifier&fl[]=title&fl[]=creator&fl[]=runtime&sort[]=downloads+desc&rows=50&output=json"
}

fun freeBookDetailsUrl(id: String): String = "$ARCHIVE/metadata/" + encode(id)

fun freeBookFileUrl(id: String, fileName: String): String =
    "$ARCHIVE/download/" + encode(id) + "/" + encode(fileName).replace("+", "%20")

/** The Archive's small square picture of the book, for lists. */
fun freeBookThumbnailUrl(id: String): String = "$ARCHIVE/services/img/" + encode(id)

private fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

fun parseFreeBookSearch(json: String): List<FreeBook> {
    val docs = JSONObject(json).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
    return (0 until docs.length()).mapNotNull { i ->
        val doc = docs.optJSONObject(i) ?: return@mapNotNull null
        val id = doc.optString("identifier")
        if (id.isEmpty()) return@mapNotNull null
        FreeBook(id, firstString(doc, "title").ifEmpty { id }, firstString(doc, "creator"), firstString(doc, "runtime"))
    }
}

/**
 * A book's details from its Archive metadata: the 64 kbps MP3s (small, and
 * every LibriVox book has them) in file-name order, which is reading order.
 */
fun parseFreeBookDetails(id: String, json: String): FreeBookDetails {
    val root = JSONObject(json)
    val meta = root.optJSONObject("metadata") ?: JSONObject()
    val files = root.optJSONArray("files")
    val parts = ArrayList<FreeBookPart>()
    var cover: String? = null
    if (files != null) {
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val name = file.optString("name")
            when (file.optString("format")) {
                "64Kbps MP3" -> parts += FreeBookPart(
                    fileName = name,
                    title = partTitle(file.optString("title")).ifEmpty { name.substringBeforeLast('.') },
                    durationMs = parseLength(file.optString("length")),
                )
                "JPEG" -> if (cover == null && file.optString("source") == "original") cover = name
            }
        }
    }
    parts.sortBy { it.fileName.lowercase() }
    val book = FreeBook(
        id = id,
        title = firstString(meta, "title").ifEmpty { id },
        author = firstString(meta, "creator"),
        runtime = firstString(meta, "runtime"),
    )
    return FreeBookDetails(book, parts, cover, htmlToText(firstString(meta, "description")))
}

/** A part's title without the "07 - " LibriVox puts in front (the app numbers parts itself). */
fun partTitle(title: String): String = title.trim().replace(NUMBER_PREFIX, "").ifEmpty { title.trim() }

private val NUMBER_PREFIX = Regex("^\\d{1,3}\\s*[-–.:]\\s+")

/** "18:52", "1:02:03" or "1132.5" (seconds) -> ms; 0 if unknown. */
fun parseLength(text: String): Long {
    if (text.isBlank()) return 0
    if (':' !in text) return ((text.toDoubleOrNull() ?: return 0) * 1000).toLong()
    var seconds = 0.0
    for (piece in text.split(':')) seconds = seconds * 60 + (piece.toDoubleOrNull() ?: return 0)
    return (seconds * 1000).toLong()
}

/** The folder a book goes in, under the phone's Audiobooks folder: "Author - Title". */
fun freeBookFolder(book: FreeBook): String =
    safeFileName(if (book.author.isBlank()) book.title else "${book.author} - ${book.title}").ifEmpty { book.id }

/** A part's file name: numbered, so the files sort in reading order whatever their titles. */
fun freeBookPartFileName(index: Int, part: FreeBookPart): String =
    "%03d %s.mp3".format(index + 1, safeFileName(part.title).ifEmpty { "Part ${index + 1}" })

/** Archive fields are a string, or a list of them when there are several (the first is used). */
private fun firstString(obj: JSONObject, key: String): String =
    obj.optJSONArray(key)?.optString(0) ?: obj.optString(key)

/** Descriptions come as HTML: tags out, line breaks kept, entities decoded. */
fun htmlToText(html: String): String =
    html.replace(Regex("(?i)<br\\s*/?>|</p>|</div>"), "\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .replace(' ', ' ')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\s*\\n\\s*(\\n\\s*)+"), "\n\n")
        .trim()
