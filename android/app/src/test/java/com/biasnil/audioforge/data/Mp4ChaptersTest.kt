package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Mp4ChaptersTest {

    // --- A tiny MP4 writer, just enough boxes for the chapter readers. ---

    private fun u32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
    private fun u64(v: Long) = u32(v ushr 32) + u32(v and 0xffffffffL)

    private fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = payload.fold(ByteArray(0)) { a, b -> a + b }
        return u32(8L + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private fun fullBox(type: String, version: Int, vararg payload: ByteArray) =
        box(type, byteArrayOf(version.toByte(), 0, 0, 0), *payload)

    private fun nero(vararg chapters: Pair<Long, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(chapters.size)
        for ((ms, title) in chapters) {
            out.write(u64(ms * 10_000))
            val bytes = title.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        return fullBox("chpl", 1, u32(0), out.toByteArray())
    }

    private fun tkhd(id: Long) = fullBox("tkhd", 0, u32(0), u32(0), u32(id), u32(0), u32(0))
    private fun mdhd(timescale: Long) = fullBox("mdhd", 0, u32(0), u32(0), u32(timescale), u32(0), u32(0))

    private fun textSample(title: String): ByteArray {
        val bytes = title.toByteArray(Charsets.UTF_8)
        return byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes
    }

    /**
     * ftyp, mdat (the chapter titles), then moov: an audio track whose tref/chap
     * points at a text track with one sample per chapter, one sample per chunk.
     */
    private fun quickTimeFile(timescale: Long, durations: List<Long>, titles: List<String>): ByteArray {
        val ftyp = box("ftyp", "M4B ".toByteArray(), u32(0))
        val samples = titles.map(::textSample)
        val mdat = box("mdat", *samples.toTypedArray())
        var offset = (ftyp.size + 8).toLong()
        val offsets = samples.map { s -> offset.also { offset += s.size } }

        val audio = box("trak", tkhd(1), box("tref", box("chap", u32(2))), box("mdia", mdhd(44_100)))
        val stbl = box(
            "stbl",
            fullBox("stts", 0, u32(durations.size.toLong()), *durations.map { u32(1) + u32(it) }.toTypedArray()),
            fullBox("stsz", 0, u32(0), u32(samples.size.toLong()), *samples.map { u32(it.size.toLong()) }.toTypedArray()),
            fullBox("stsc", 0, u32(1), u32(1), u32(1), u32(1)),
            fullBox("stco", 0, u32(offsets.size.toLong()), *offsets.map { u32(it) }.toTypedArray()),
        )
        val text = box("trak", tkhd(2), box("mdia", mdhd(timescale), box("minf", stbl)))
        return ftyp + mdat + box("moov", box("mvhd", ByteArray(100)), audio, text)
    }

    @Test
    fun readsNeroChapters() {
        val file = box("ftyp", "M4B ".toByteArray()) +
            box("moov", box("udta", nero(0L to "Opening", 61_500L to "Chapter Two", 3_600_000L to "Ünïcode")))
        val chapters = readMp4Chapters(ByteArraySource(file))
        assertEquals(
            listOf(Chapter("Opening", 0), Chapter("Chapter Two", 61_500), Chapter("Ünïcode", 3_600_000)),
            chapters,
        )
    }

    @Test
    fun readsQuickTimeChapterTrack() {
        val file = quickTimeFile(timescale = 1000, durations = listOf(90_000, 120_000, 30_000), titles = listOf("One", "Two", "Three"))
        assertEquals(
            listOf(Chapter("One", 0), Chapter("Two", 90_000), Chapter("Three", 210_000)),
            readMp4Chapters(ByteArraySource(file)),
        )
    }

    @Test
    fun quickTimeTimesUseTheTracksTimescale() {
        val file = quickTimeFile(timescale = 600, durations = listOf(600 * 30, 600 * 45), titles = listOf("A", "B"))
        assertEquals(listOf(0L, 30_000L), readMp4Chapters(ByteArraySource(file)).map { it.startMs })
    }

    @Test
    fun noChaptersOrNotAnMp4() {
        assertTrue(readMp4Chapters(ByteArraySource(box("ftyp", "M4A ".toByteArray()) + box("moov", box("mvhd", ByteArray(100))))).isEmpty())
        assertTrue(readMp4Chapters(ByteArraySource("ID3 not an mp4 file at all".toByteArray())).isEmpty())
        assertTrue(readMp4Chapters(ByteArraySource(ByteArray(0))).isEmpty())
    }

    @Test
    fun truncatedChapterListKeepsWhatItCould() {
        val full = box("moov", box("udta", nero(0L to "One", 1_000L to "Two")))
        val cut = full.copyOf(full.size - 2)
        // The box sizes no longer fit: nothing breaks, and nothing bogus comes out.
        assertTrue(readMp4Chapters(ByteArraySource(cut)).all { it.title == "One" })
    }
}
