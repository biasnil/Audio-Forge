package com.biasnil.audioforge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBlocksTest {

    @Test
    fun headingsAndParagraphs() {
        val blocks = parseTextBlocks("# Title\r\nFirst line\n   second line  \n\n\nNext paragraph\n# Contact\nme@example.com\n")
        assertEquals(
            listOf(
                TextBlock.Heading("Title"),
                TextBlock.Paragraph("First line second line"),
                TextBlock.Paragraph("Next paragraph"),
                TextBlock.Heading("Contact"),
                TextBlock.Paragraph("me@example.com"),
            ),
            blocks,
        )
    }

    @Test
    fun hashWithoutSpaceIsText() {
        assertEquals(listOf(TextBlock.Paragraph("#hashtag")), parseTextBlocks("#hashtag"))
    }
}
