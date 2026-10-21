package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DuplicateFinderTest {

    private var nextId = 0
    private fun song(title: String, artist: String = "Artist", seconds: Double = 200.0, file: String = "a.mp3", kbps: Int = 0) =
        Track(uri = "content://t/${nextId++}", title = title, artist = artist, durationMs = (seconds * 1000).toLong(),
            fileName = file, bitrateKbps = kbps)

    private fun groupsOf(vararg tracks: Track, dismissed: Set<String> = emptySet()) =
        findDuplicates(tracks.toList(), dismissed).groups

    private fun assertMatch(a: Track, b: Track, similar: Boolean? = null) {
        val groups = groupsOf(a, b)
        assertEquals("${a.title} / ${b.title} should match", 1, groups.size)
        similar?.let { assertEquals("kind of ${a.title} / ${b.title}", it, groups[0].similar) }
    }

    private fun assertNoMatch(a: Track, b: Track) =
        assertTrue("${a.title} (${a.artist}) / ${b.title} (${b.artist}) shouldn't match", groupsOf(a, b).isEmpty())

    // --- Should match ---------------------------------------------------------------

    @Test fun remasterSuffix() = assertMatch(
        song("Don't Stop Me Now", "Queen", 209.0), song("Dont Stop Me Now - 2011 Remaster", "Queen", 210.5), similar = true,
    )

    @Test fun leadingTrackNumber() = assertMatch(song("01 - Yellow", "Coldplay"), song("Yellow", "Coldplay", 201.0), similar = true)

    @Test fun accentsAndCase() = assertMatch(song("Café del Mar", "Energy 52"), song("Cafe Del Mar", "Energy 52"), similar = true)

    @Test fun featuring() = assertMatch(song("Song (feat. X)"), song("Song"))

    @Test fun featuringOutsideBrackets() = assertMatch(song("Song feat. X"), song("Song"), similar = true)

    @Test fun fullWidthLetters() = assertMatch(song("ＹＥＬＬＯＷ", "Ｃｏｌｄｐｌａｙ"), song("Yellow", "Coldplay"), similar = true)

    @Test fun accentedArtist() = assertMatch(song("Halo", "Beyoncé"), song("Halo", "Beyonce"), similar = true)

    @Test fun typo() = assertMatch(song("Bohemian Rhapsody", "Queen"), song("Bohemian Rapsody", "Queen"), similar = true)

    @Test fun wordOrder() = assertMatch(song("Love Me Do", "The Beatles"), song("Love Me Do", "Beatles, The"), similar = true)

    @Test fun missingArtistNeedsCloserTitle() {
        assertMatch(song("Wonderwall", "Oasis"), song("Wonderwall", ""), similar = true)
        assertMatch(song("Wonderwall", "Oasis"), song("Wonderwall", "Unknown"), similar = true)
        assertNoMatch(song("Wonderwalls", "Oasis"), song("Wonderwall", "")) // 0.91 < 0.92
    }

    @Test fun cjkKeptAsIs() {
        assertMatch(song("月亮代表我的心", "邓丽君"), song("01 - 月亮代表我的心", "邓丽君"), similar = true)
        assertNoMatch(song("月亮代表我的心", "邓丽君"), song("月亮代表我的心（现场）", "邓丽君"))
    }

    @Test fun sameNameIsSameSong() = assertMatch(song("Yellow", "Coldplay"), song("yellow!", "COLDPLAY", 201.9), similar = false)

    // --- Shouldn't match ------------------------------------------------------------

    @Test fun live() = assertNoMatch(song("Song"), song("Song (Live)"))

    @Test fun acoustic() = assertNoMatch(song("Song"), song("Song - Acoustic"))

    @Test fun remix() = assertNoMatch(song("Song"), song("Song (Remix)"))

    @Test fun radioEditIsNotAMarker() = assertMatch(song("Song"), song("Song - Radio Edit"))

    @Test fun lengthsFarApart() = assertNoMatch(song("Song", seconds = 200.0), song("Song", seconds = 240.0))

    @Test fun differentArtists() = assertNoMatch(song("Halo", "Beyoncé"), song("Halo", "Depeche Mode"))

    @Test fun helloHallo() = assertNoMatch(song("Hello", "Adele"), song("Hallo", "Die Band"))

    @Test fun similarNeedsLengthWithin3s() {
        assertNoMatch(song("01 - Yellow", "Coldplay", 200.0), song("Yellow", "Coldplay", 203.5))
    }

    @Test fun unknownLengthOnlyExact() {
        assertMatch(song("Yellow", "Coldplay", 0.0), song("Yellow", "Coldplay", 0.0), similar = false)
        assertNoMatch(song("01 - Yellow", "Coldplay", 0.0), song("Yellow", "Coldplay", 0.0))
    }

    // --- Groups ---------------------------------------------------------------------

    @Test fun groupsAreTransitiveAndExactComeFirst() {
        // a-b and b-c are similar; c is 2.5 s from a, so a-c alone wouldn't be Exact.
        val a = song("Apple", "Band", 200.0)
        val b = song("01 - Apple", "Band", 201.0)
        val c = song("Apple (Remastered)", "Band", 202.5)
        val d = song("Zebra", "X", 100.0)
        val e = song("zebra", "x", 100.5)
        val groups = groupsOf(a, b, c, d, e)
        assertEquals(2, groups.size)
        // "Same song" before "Similar names", even though Apple < Zebra.
        assertFalse(groups[0].similar)
        assertEquals(setOf(d.uri, e.uri), groups[0].tracks.map { it.uri }.toSet())
        assertTrue(groups[1].similar)
        assertEquals(setOf(a.uri, b.uri, c.uri), groups[1].tracks.map { it.uri }.toSet())
    }

    @Test fun groupsOfAKindSortAToZ() {
        val groups = groupsOf(song("Zebra"), song("zebra"), song("apple"), song("Apple"))
        assertEquals(listOf("apple", "zebra"), groups.map { it.tracks[0].title.lowercase() })
    }

    @Test fun bestCopy() {
        val mp3 = song("Song", file = "a.mp3", kbps = 320)
        val flac = song("Song", file = "a.flac", kbps = 900)
        val low = song("Song", file = "b.mp3", kbps = 128)
        assertEquals(flac.uri, groupsOf(mp3, flac, low)[0].best)
        // Same bitrate: lossless wins.
        val wav = song("Other", file = "x.wav", kbps = 320)
        val mp3b = song("Other", file = "x.mp3", kbps = 320)
        assertEquals(wav.uri, groupsOf(wav, mp3b)[0].best)
        // Nothing stands out.
        assertEquals(null, groupsOf(song("Same", kbps = 320), song("Same", kbps = 320))[0].best)
    }

    // --- Dismissals -----------------------------------------------------------------

    @Test fun dismissingAGroupHidesIt() {
        val a = song("Yellow", "Coldplay")
        val b = song("01 - Yellow", "Coldplay")
        val dismissed = pairsAmong(listOf(a.uri, b.uri))
        val result = findDuplicates(listOf(a, b), dismissed)
        assertTrue(result.groups.isEmpty())
        assertEquals(1, result.dismissed.size)
    }

    @Test fun aNewCopyBringsBackOnlyItsPairs() {
        val a = song("Yellow", "Coldplay")
        val b = song("01 - Yellow", "Coldplay")
        val dismissed = pairsAmong(listOf(a.uri, b.uri))
        val c = song("Yellow", "Coldplay", 200.5)
        val groups = findDuplicates(listOf(a, b, c), dismissed).groups
        // c matches both a and b, so the three are one group again through c...
        assertEquals(1, groups.size)
        // ...but dismissing c's pairs too ("Not duplicates" on the group) hides it all again.
        val all = dismissed + pairsAmong(listOf(a.uri, b.uri, c.uri))
        assertTrue(findDuplicates(listOf(a, b, c), all).groups.isEmpty())
    }

    @Test fun newCopyOfOneSongOnlyRejoinsThatSong() {
        val a = song("Hello", "Adele", 295.0)
        val b = song("Hello - 2015 Remaster", "Adele", 296.0)
        val dismissed = pairsAmong(listOf(a.uri, b.uri))
        val c = song("Hello", "Adele", 295.0) // a's exact copy; c-b is similar
        val result = findDuplicates(listOf(a, b, c), dismissed + pairKey(b.uri, c.uri))
        assertEquals(1, result.groups.size)
        assertEquals(setOf(a.uri, c.uri), result.groups[0].tracks.map { it.uri }.toSet())
    }

    @Test fun selectionDismissesAgainstTheRestOfTheGroup() {
        val a = song("Yellow", "Coldplay")
        val b = song("yellow", "coldplay")
        val c = song("YELLOW", "Coldplay")
        val pairs = pairsForSelection(listOf(c.uri), listOf(a.uri, b.uri, c.uri))
        assertEquals(setOf(pairKey(a.uri, c.uri), pairKey(b.uri, c.uri)), pairs)
        val groups = findDuplicates(listOf(a, b, c), pairs).groups
        assertEquals(1, groups.size)
        assertEquals(setOf(a.uri, b.uri), groups[0].tracks.map { it.uri }.toSet())
    }

    @Test fun undoRestores() {
        val a = song("Yellow", "Coldplay")
        val b = song("yellow", "Coldplay")
        val before = emptySet<String>()
        val after = before + pairsAmong(listOf(a.uri, b.uri))
        assertTrue(findDuplicates(listOf(a, b), after).groups.isEmpty())
        assertEquals(1, findDuplicates(listOf(a, b), before).groups.size) // undo = put "before" back
    }

    @Test fun pruningDropsMissingSongs() {
        val pairs = setOf(pairKey("a", "b"), pairKey("a", "gone"), pairKey("gone", "z"))
        assertEquals(setOf(pairKey("a", "b")), prunePairs(pairs, setOf("a", "b", "z")))
    }

    @Test fun pairKeyIsOrderless() = assertEquals(pairKey("x", "y"), pairKey("y", "x"))

    // --- No regressions, speed -----------------------------------------------------

    /** The finder as it was before (SmartPlaylists.kt duplicateGroups). */
    private fun oldGroups(tracks: List<Track>): List<Set<String>> {
        val bracketed = Regex("""\s*[(\[（【][^)\]）】]*[)\]）】]""")
        fun normalized(text: String) = bracketed.replace(text.lowercase(), "").filter { it.isLetterOrDigit() }
        val result = ArrayList<Set<String>>()
        for (candidates in tracks.groupBy { normalized(it.title) + "|" + normalized(it.artist) }.values) {
            if (candidates.size < 2) continue
            var cluster = ArrayList<Track>()
            for (track in candidates.sortedBy { it.durationMs }) {
                val last = cluster.lastOrNull()
                if (last != null && track.durationMs - last.durationMs > 2_000) {
                    if (cluster.size > 1) result += cluster.map { it.uri }.toSet()
                    cluster = ArrayList()
                }
                cluster += track
            }
            if (cluster.size > 1) result += cluster.map { it.uri }.toSet()
        }
        return result
    }

    @Test fun everyOldGroupIsStillFound() {
        val random = Random(7)
        val words = listOf("love", "night", "Yellow", "Don't", "stop", "me", "now", "café", "Hello", "(Remastered)", "[2011]", "rain")
        val tracks = List(3_000) {
            val title = List(random.nextInt(1, 4)) { words.random(random) }.joinToString(" ")
            val artist = listOf("Queen", "queen", "Coldplay", "Adele", "")[random.nextInt(5)]
            song(title, artist, seconds = 180.0 + random.nextInt(0, 40) + random.nextDouble())
        }
        val newGroups = findDuplicates(tracks).groups.map { group -> group.tracks.map { it.uri }.toSet() }
        for (old in oldGroups(tracks)) {
            // Old groups never had "live"/"remix" words here, so each must sit inside one new group.
            assertTrue("old group $old not found", newGroups.any { it.containsAll(old) })
        }
    }

    @Test fun tenThousandSongsInUnderASecond() {
        val random = Random(1)
        val syllables = listOf("la", "mo", "ri", "ka", "ne", "so", "tu", "vi", "dar", "len", "go", "pa")
        fun name(parts: Int) = List(parts) { List(random.nextInt(1, 4)) { syllables.random(random) }.joinToString("") }.joinToString(" ")
        val tracks = List(10_000) {
            // Real libraries bunch up around 3-4 minutes; this is denser than most.
            song(name(random.nextInt(1, 5)), name(2), seconds = 150.0 + random.nextInt(0, 150) + random.nextDouble())
        }
        findDuplicates(tracks.take(500)) // warm up the JIT
        val start = System.nanoTime()
        findDuplicates(tracks)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took $ms ms", ms < 1_000)
    }
}
