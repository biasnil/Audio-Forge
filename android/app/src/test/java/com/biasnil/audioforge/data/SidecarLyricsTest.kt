package com.biasnil.audioforge.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SidecarLyricsTest {

    @Test
    fun replacesTheTitleAndArtistLines() {
        val lrc = "[ti:Old]\n[ar:Someone]\n[00:01.00]Hello\n"
        assertEquals("[ti:New \$1 name]\n[ar:Band]\n[00:01.00]Hello\n", withLrcTags(lrc, "New \$1 name", "Band"))
    }

    @Test
    fun leavesWhatDidntChange() {
        val lrc = "[ti:Old]\n[ar:Someone]\n[00:01.00]Hello\n"
        assertEquals("[ti:Old]\n[ar:Other]\n[00:01.00]Hello\n", withLrcTags(lrc, null, "Other"))
    }

    @Test
    fun addsATagTheFileDoesntHave() {
        assertEquals("[ti:Song]\n[00:01.00]Hello\n", withLrcTags("[00:01.00]Hello\n", "Song", null))
    }

    @Test
    fun aBracketInTheNameCantBreakTheTag() {
        // A "]" would end the tag early, so it's left out.
        assertEquals("[ti:Live [at x]\n", withLrcTags("[ti:a]\n", "Live [at x]", null))
    }
}
