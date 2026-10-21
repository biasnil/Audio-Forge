package com.biasnil.audioforge.data

import com.biasnil.audioforge.audio.GenreClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GenreTaxonomyTest {

    private fun decide(vararg scores: Pair<String, Float>) = GenreTaxonomy.decide(scores.toMap(), 200_000)

    @Test fun everyLabelIsInTheBundledClassMap() {
        // Unit tests run in the module folder; labels are looked up by name, so a typo would silently never score.
        val names = File("src/main/assets/yamnet_class_map.csv").readLines().drop(1).map(GenreClassifier::displayName).toSet()
        assertEquals(521, names.size)
        val missing = GenreTaxonomy.TABLE.map { it.label }.filter { it !in names } +
            listOf(GenreTaxonomy.MUSIC_LABEL, GenreTaxonomy.SPEECH_LABEL).filter { it !in names }
        assertTrue("missing: $missing", missing.isEmpty())
    }

    @Test fun eachLabelBelongsToOneGenre() {
        val labels = GenreTaxonomy.TABLE.map { it.label }
        assertEquals(labels.size, labels.toSet().size)
    }

    @Test fun electronicHouse() {
        val g = decide("Music" to 0.9f, "Electronic music" to 0.3f, "House music" to 0.4f, "Techno" to 0.05f, "Pop music" to 0.1f)
        assertEquals(DetectedGenre.Status.OK, g.status)
        assertEquals("Electronic", g.broad)
        assertEquals("House", g.subgenre)
        assertEquals("Electronic · House", g.label)
        assertEquals(0.75 / 0.85, g.confidence, 1e-6)
    }

    @Test fun subgenreNeedsAShareOfTheGenre() {
        val g = decide("Music" to 0.9f, "Rock music" to 0.5f, "Punk rock" to 0.1f)
        assertEquals("Rock", g.broad)
        assertNull(g.subgenre) // 0.1 < 0.4 x 0.6
    }

    @Test fun speechIsNotMusic() {
        assertEquals(DetectedGenre.Status.NOT_MUSIC, decide("Speech" to 0.8f, "Music" to 0.05f).status)
        // Quiet music with little speech is still judged.
        assertEquals(DetectedGenre.Status.OK, decide("Speech" to 0.01f, "Music" to 0.1f, "Jazz" to 0.2f).status)
    }

    @Test fun tooLittleEvidenceIsUnknown() {
        assertEquals(DetectedGenre.Status.UNKNOWN, decide("Music" to 0.9f, "Guitar" to 0.6f, "Pop music" to 0.02f).status)
    }

    @Test fun noClearWinnerIsUnknown() {
        val g = decide("Music" to 0.9f, "Pop music" to 0.2f, "Rock music" to 0.2f, "Jazz" to 0.2f, "Blues" to 0.2f)
        assertEquals(DetectedGenre.Status.UNKNOWN, g.status)
        assertNull(g.broad)
    }

    @Test fun keepsTheTopFive() {
        val g = decide("Music" to 0.9f, "Jazz" to 0.5f, "Swing music" to 0.4f, "Piano" to 0.3f, "Speech" to 0.01f, "Blues" to 0.2f, "Guitar" to 0.1f)
        assertEquals(listOf("Music", "Jazz", "Swing music", "Piano", "Blues"), g.top.map { it.first })
    }
}
