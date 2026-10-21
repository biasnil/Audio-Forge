package com.biasnil.audioforge.widget

import com.biasnil.audioforge.data.Track
import com.biasnil.audioforge.lyrics.LyricsState
import com.biasnil.audioforge.lyrics.SyncedLyricLine
import com.biasnil.audioforge.playback.PlaybackState
import com.biasnil.audioforge.playback.ShuffleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModelTest {

    private fun track(title: String, artist: String = "Artist", durationMs: Long = 200_000) =
        Track(uri = "content://t/$title", title = title, artist = artist, durationMs = durationMs)

    private val playing = PlaybackState(current = track("Song"), isPlaying = true, durationMs = 180_000)

    @Test fun nothingPlaying() {
        val snapshot = widgetSnapshot(PlaybackState(), emptyList(), loved = false, lyricLine = null, seekCount = 3, coverVersion = 1)
        assertFalse(snapshot.hasTrack)
        assertFalse(snapshot.isPlaying)
    }

    @Test fun snapshotOfTheCurrentSong() {
        val snapshot = widgetSnapshot(
            playing.copy(shuffleMode = ShuffleMode.Smart),
            listOf(track("A", "X"), track("B", "Y"), track("C")),
            loved = true, lyricLine = "la la", seekCount = 0, coverVersion = 0,
        )
        assertEquals("Song", snapshot.title)
        assertTrue(snapshot.shuffleOn)
        assertTrue(snapshot.loved)
        assertEquals(180_000L, snapshot.durationMs)
        assertEquals(listOf(UpNextSong("A", "X"), UpNextSong("B", "Y")), snapshot.upNext)
    }

    @Test fun durationFallsBackToTheTrackLength() {
        val snapshot = widgetSnapshot(playing.copy(durationMs = 0), emptyList(), false, null, 0, 0)
        assertEquals(200_000L, snapshot.durationMs)
    }

    @Test fun miniIgnoresEverythingButPlayPause() {
        val base = widgetSnapshot(playing, emptyList(), false, null, 0, 0)
        val changed = base.copy(title = "Other", loved = true, seekCount = 5, lyricLine = "x", upNext = listOf(UpNextSong("a", "b")))
        assertEquals(base.keyFor(WidgetStyle.Mini), changed.keyFor(WidgetStyle.Mini))
        assertNotEquals(base.keyFor(WidgetStyle.Mini), base.copy(isPlaying = false).keyFor(WidgetStyle.Mini))
    }

    @Test fun onlyProgressStylesRedrawOnSeek() {
        val base = widgetSnapshot(playing, emptyList(), false, null, 0, 0)
        val seeked = base.copy(seekCount = 1)
        assertEquals(base.keyFor(WidgetStyle.Classic), seeked.keyFor(WidgetStyle.Classic))
        assertNotEquals(base.keyFor(WidgetStyle.Simple), seeked.keyFor(WidgetStyle.Simple))
        assertNotEquals(base.keyFor(WidgetStyle.Practical), seeked.keyFor(WidgetStyle.Practical))
    }

    @Test fun onlyFeatureRichFollowsTheLyrics() {
        val base = widgetSnapshot(playing, emptyList(), false, "one", 0, 0)
        val next = base.copy(lyricLine = "two")
        assertEquals(base.keyFor(WidgetStyle.Practical), next.keyFor(WidgetStyle.Practical))
        assertNotEquals(base.keyFor(WidgetStyle.FeatureRich), next.keyFor(WidgetStyle.FeatureRich))
    }

    @Test fun lovedMattersOnlyWithTheHeart() {
        val base = widgetSnapshot(playing, emptyList(), false, null, 0, 0)
        assertEquals(base.keyFor(WidgetStyle.Standard), base.copy(loved = true).keyFor(WidgetStyle.Standard))
        assertNotEquals(base.keyFor(WidgetStyle.Lite), base.copy(loved = true).keyFor(WidgetStyle.Lite))
    }

    @Test fun progress() {
        assertEquals(0, progressOf(10_000, 0))
        assertEquals(500, progressOf(90_000, 180_000))
        assertEquals(PROGRESS_MAX, progressOf(200_000, 180_000))
        assertEquals(0, progressOf(-5, 180_000))
    }

    @Test fun chronometerShowsThePositionNow() {
        assertEquals(1_000_000L - 42_000L, chronometerBase(1_000_000, 42_000))
        assertTrue(elapsedTicks(isPlaying = true, rate = 1f))
        assertFalse(elapsedTicks(isPlaying = true, rate = 1.5f))
        assertFalse(elapsedTicks(isPlaying = false, rate = 1f))
    }

    @Test fun lyricLine() {
        val lyrics = LyricsState.Synced(listOf(SyncedLyricLine(0, "first"), SyncedLyricLine(5_000, "")))
        assertEquals("first", currentLyricText(lyrics, 0))
        assertEquals("♪", currentLyricText(lyrics, 1))
        assertNull(currentLyricText(lyrics, -1))
        assertNull(currentLyricText(LyricsState.Plain(listOf("a")), 0))
    }

    @Test fun backgrounds() {
        assertEquals(0xFF123456.toInt(), backgroundArgb(WidgetBackground.CoverColours, 0xFF123456.toInt()))
        assertEquals(DEFAULT_BACKGROUND_ARGB, backgroundArgb(WidgetBackground.CoverColours, null))
        assertEquals(0xFF000000.toInt(), backgroundArgb(WidgetBackground.Black, 0xFF123456.toInt()))
        assertEquals(WidgetBackground.Black, WidgetBackground.fromId("black"))
        assertEquals(WidgetBackground.DEFAULT, WidgetBackground.fromId(null))
        assertEquals(WidgetBackground.DEFAULT, WidgetBackground.fromId("gone"))
    }

    @Test fun accentOnlyWhereItReads() {
        val purple = 0xFF8020FF.toInt()
        assertEquals(WHITE_ARGB, accentArgb(WidgetBackground.CoverColours, purple))
        assertEquals(purple, accentArgb(WidgetBackground.Black, purple))
        assertEquals(purple, accentArgb(WidgetBackground.Translucent, purple))
        assertEquals(WHITE_ARGB, accentArgb(WidgetBackground.Black, null))
    }
}
