package com.biasnil.audioforge.data

import java.nio.charset.Charset

/** Random access to a file's bytes (a file descriptor on the phone, a byte array in tests). */
interface ByteSource {
    val size: Long

    /** Up to [length] bytes from [offset] (fewer at the end of the file). */
    fun read(offset: Long, length: Int): ByteArray
}

class ByteArraySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset >= bytes.size) return ByteArray(0)
        val end = minOf(bytes.size.toLong(), offset + length).toInt()
        return bytes.copyOfRange(offset.toInt(), end)
    }
}

/**
 * The chapters of an MP4 / M4B audiobook, from either of the two ways
 * they're stored: a QuickTime chapter track (a text track the audio track
 * points to with a 'chap' reference -- what iTunes and most audiobook tools
 * write), or Nero's 'chpl' list in moov/udta. Empty if the file has none or
 * isn't an MP4. Only the 'moov' box is read, not the audio.
 */
fun readMp4Chapters(source: ByteSource): List<Chapter> {
    val moov = findTopLevelBox(source, "moov") ?: return emptyList()
    val box = Box(moov, 0, moov.size)
    val fromTrack = quickTimeChapters(box, source)
    val chapters = fromTrack.ifEmpty { neroChapters(box) }
    return chapters.sortedBy { it.startMs }.distinctBy { it.startMs }
}

/** A box's payload: [data] from [start] (after the header) to [end]. */
private class Box(val data: ByteArray, val start: Int, val end: Int) {
    /** The child boxes, as (type, payload). */
    fun children(): List<Pair<String, Box>> {
        val result = ArrayList<Pair<String, Box>>()
        var at = start
        while (at + 8 <= end) {
            var size = u32(data, at)
            val type = String(data, at + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                if (at + 16 > end) break
                size = u64(data, at + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - at).toLong()
            }
            if (size < header || at + size > end) break
            result += type to Box(data, at + header, (at + size).toInt())
            at += size.toInt()
        }
        return result
    }

    fun child(type: String): Box? = children().firstOrNull { it.first == type }?.second
    fun path(vararg types: String): Box? = types.fold(this as Box?) { box, type -> box?.child(type) }
}

/** The payload of the first top-level box of [type], read whole (moov is small next to the audio). */
private fun findTopLevelBox(source: ByteSource, type: String): ByteArray? {
    var offset = 0L
    while (offset + 8 <= source.size) {
        val header = source.read(offset, 16)
        if (header.size < 8) return null
        var size = u32(header, 0)
        val name = String(header, 4, 4, Charsets.ISO_8859_1)
        var headerSize = 8
        if (size == 1L) {
            if (header.size < 16) return null
            size = u64(header, 8)
            headerSize = 16
        } else if (size == 0L) {
            size = source.size - offset
        }
        if (size < headerSize) return null
        if (name == type) {
            val length = size - headerSize
            if (length > MAX_MOOV_BYTES) return null
            return source.read(offset + headerSize, length.toInt())
        }
        offset += size
    }
    return null
}

/** Nero chapters: version/flags, (4 more bytes in version 1), a count, then start (100 ns units), title length, title. */
private fun neroChapters(moov: Box): List<Chapter> {
    val chpl = moov.path("udta", "chpl") ?: return emptyList()
    val data = chpl.data
    var at = chpl.start
    if (at + 4 > chpl.end) return emptyList()
    val version = data[at].toInt() and 0xff
    at += 4
    if (version != 0) at += 4
    if (at + 1 > chpl.end) return emptyList()
    val count = data[at].toInt() and 0xff
    at += 1
    val chapters = ArrayList<Chapter>()
    repeat(count) {
        if (at + 9 > chpl.end) return chapters
        val start = u64(data, at)
        val length = data[at + 8].toInt() and 0xff
        at += 9
        if (at + length > chpl.end) return chapters
        chapters += Chapter(String(data, at, length, Charsets.UTF_8).trim(), start / 10_000)
        at += length
    }
    return chapters
}

/**
 * QuickTime chapters: the audio track's tref/chap names a text track;
 * each of that track's samples is one chapter -- a 2-byte length and the
 * title -- starting at the sample's time.
 */
private fun quickTimeChapters(moov: Box, source: ByteSource): List<Chapter> {
    val tracks = moov.children().filter { it.first == "trak" }.map { it.second }
    val chapterIds = tracks.flatMap { trak ->
        val chap = trak.path("tref", "chap") ?: return@flatMap emptyList()
        (chap.start until chap.end - 3 step 4).map { u32(chap.data, it) }
    }.toSet()
    if (chapterIds.isEmpty()) return emptyList()
    val textTrack = tracks.firstOrNull { trackId(it) in chapterIds } ?: return emptyList()

    val timescale = mediaTimescale(textTrack) ?: return emptyList()
    val stbl = textTrack.path("mdia", "minf", "stbl") ?: return emptyList()
    val sizes = sampleSizes(stbl) ?: return emptyList()
    val starts = sampleStarts(stbl, sizes.size) ?: return emptyList()
    val offsets = sampleOffsets(stbl, sizes) ?: return emptyList()

    return sizes.indices.map { i ->
        val bytes = if (sizes[i] in 2..MAX_TITLE_BYTES) source.read(offsets[i], sizes[i]) else ByteArray(0)
        Chapter(chapterText(bytes), starts[i] * 1000 / timescale)
    }
}

private fun trackId(trak: Box): Long? {
    val tkhd = trak.child("tkhd") ?: return null
    val version = tkhd.data[tkhd.start].toInt() and 0xff
    val at = tkhd.start + if (version == 1) 20 else 12
    return if (at + 4 <= tkhd.end) u32(tkhd.data, at) else null
}

private fun mediaTimescale(trak: Box): Long? {
    val mdhd = trak.path("mdia", "mdhd") ?: return null
    val version = mdhd.data[mdhd.start].toInt() and 0xff
    val at = mdhd.start + if (version == 1) 20 else 12
    return if (at + 4 <= mdhd.end) u32(mdhd.data, at).takeIf { it > 0 } else null
}

/** stsz: one size for all samples, or one per sample. */
private fun sampleSizes(stbl: Box): IntArray? {
    val stsz = stbl.child("stsz") ?: return null
    val d = stsz.data
    val fixed = u32(d, stsz.start + 4).toInt()
    val count = u32(d, stsz.start + 8).toInt()
    if (count < 0 || count > MAX_CHAPTERS) return null
    if (fixed != 0) return IntArray(count) { fixed }
    if (stsz.start + 12 + count * 4 > stsz.end) return null
    return IntArray(count) { u32(d, stsz.start + 12 + it * 4).toInt() }
}

/** stts: runs of (sample count, duration) -> each sample's start, in the track's timescale. */
private fun sampleStarts(stbl: Box, count: Int): LongArray? {
    val stts = stbl.child("stts") ?: return null
    val d = stts.data
    val entries = u32(d, stts.start + 4).toInt()
    val starts = LongArray(count)
    var sample = 0
    var time = 0L
    for (e in 0 until entries) {
        val at = stts.start + 8 + e * 8
        if (at + 8 > stts.end) return null
        val run = u32(d, at)
        val delta = u32(d, at + 4)
        for (n in 0 until run) {
            if (sample >= count) return starts
            starts[sample++] = time
            time += delta
        }
    }
    return starts
}

/** stsc + stco/co64: where each sample is in the file. */
private fun sampleOffsets(stbl: Box, sizes: IntArray): LongArray? {
    val chunkOffsets = stbl.child("stco")?.let { stco ->
        val n = u32(stco.data, stco.start + 4).toInt()
        if (stco.start + 8 + n * 4 > stco.end) return null
        LongArray(n) { u32(stco.data, stco.start + 8 + it * 4) }
    } ?: stbl.child("co64")?.let { co64 ->
        val n = u32(co64.data, co64.start + 4).toInt()
        if (co64.start + 8 + n * 8 > co64.end) return null
        LongArray(n) { u64(co64.data, co64.start + 8 + it * 8) }
    } ?: return null
    val stsc = stbl.child("stsc") ?: return null
    val entries = u32(stsc.data, stsc.start + 4).toInt()
    // (first chunk, samples per chunk), 1-based chunks.
    val runs = (0 until entries).map {
        val at = stsc.start + 8 + it * 12
        if (at + 8 > stsc.end) return null
        u32(stsc.data, at).toInt() to u32(stsc.data, at + 4).toInt()
    }
    val offsets = LongArray(sizes.size)
    var sample = 0
    for (chunk in 1..chunkOffsets.size) {
        val perChunk = runs.lastOrNull { it.first <= chunk }?.second ?: return null
        var at = chunkOffsets[chunk - 1]
        repeat(perChunk) {
            if (sample >= sizes.size) return offsets
            offsets[sample] = at
            at += sizes[sample]
            sample++
        }
    }
    return offsets
}

/** A text sample: a 2-byte length, then UTF-8 (or UTF-16 with a byte-order mark). */
private fun chapterText(bytes: ByteArray): String {
    if (bytes.size < 2) return ""
    val length = minOf(((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff), bytes.size - 2)
    if (length <= 0) return ""
    val utf16 = length >= 2 && ((bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()) || (bytes[2] == 0xFF.toByte() && bytes[3] == 0xFE.toByte()))
    val charset = if (utf16) Charset.forName("UTF-16") else Charsets.UTF_8
    return String(bytes, 2, length, charset).trim()
}

private fun u32(d: ByteArray, at: Int): Long =
    ((d[at].toLong() and 0xff) shl 24) or ((d[at + 1].toLong() and 0xff) shl 16) or
        ((d[at + 2].toLong() and 0xff) shl 8) or (d[at + 3].toLong() and 0xff)

private fun u64(d: ByteArray, at: Int): Long = (u32(d, at) shl 32) or u32(d, at + 4)

/** moov of a very long book (many hours, a sample table per track) stays well under this. */
private const val MAX_MOOV_BYTES = 64L * 1024 * 1024
private const val MAX_CHAPTERS = 10_000
private const val MAX_TITLE_BYTES = 4_096
