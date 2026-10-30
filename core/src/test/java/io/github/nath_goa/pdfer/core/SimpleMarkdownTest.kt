package io.github.nath_goa.pdfer.core

import io.github.nath_goa.pdfer.core.TextBlock.Heading
import io.github.nath_goa.pdfer.core.TextBlock.ListItem
import io.github.nath_goa.pdfer.core.TextBlock.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Test

class SimpleMarkdownTest {
    @Test fun headingsParagraphsAndLists() {
        val blocks = SimpleMarkdown.parse(
            """
            # Title

            First line of a paragraph
            wrapped onto a second line.

            ## Section ##
            - one
              continued
            - two
              - nested
            1. first
            2) second

            ---
            Last.
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                Heading(1, "Title"),
                Paragraph("First line of a paragraph wrapped onto a second line."),
                Heading(2, "Section"),
                ListItem("•", 0, "one continued"),
                ListItem("•", 0, "two"),
                ListItem("•", 1, "nested"),
                ListItem("1.", 0, "first"),
                ListItem("2.", 0, "second"),
                Paragraph("Last."),
            ),
            blocks,
        )
    }

    @Test fun hardLineBreaks() {
        assertEquals(listOf(Paragraph("one\ntwo\nthree")), SimpleMarkdown.parse("one  \ntwo\\\nthree"))
    }

    @Test fun inlineFormatting() {
        val (text, bold) = SimpleMarkdown.inline("See **the [policy](https://example.com/p)** and <https://example.com> or `code`.")
        assertEquals("See the policy (https://example.com/p) and https://example.com or code.", text)
        assertEquals(listOf(4 until 38), bold)
        assertEquals("the policy (https://example.com/p)", text.substring(bold[0]))
        assertEquals("https://example.com", SimpleMarkdown.inline("[https://example.com](https://example.com)").first)
    }

    @Test fun fencedBlocksAreReflowedAsPlainText() {
        val long = "word ".repeat(14).trim() // 69 characters, like a line wrapped at 70
        val blocks = SimpleMarkdown.parse("Intro\n\n```text\n            Centered\n\nTITLE\n$long\n$long\nend.\n\n# not a heading\n```\nAfter")
        assertEquals(
            listOf(
                Paragraph("Intro"),
                Heading(4, "Centered"),
                Paragraph("TITLE\n$long $long end."),
                Paragraph("# not a heading"),
                Paragraph("After"),
            ),
            blocks,
        )
    }

    @Test fun plainTextKeepsShortLinesAndFindsCenteredTitles() {
        val long = "word ".repeat(14).trim()
        val text = """
                            GNU GENERAL PUBLIC LICENSE
                               Version 3, 29 June 2007

            -----------
          $long
            $long
            short.  Last line.

            Name
            Copyright (C) 2026 Someone

                      http://example.com/not-a-title
        """.trimIndent()
        assertEquals(
            listOf(
                Heading(2, "GNU GENERAL PUBLIC LICENSE\nVersion 3, 29 June 2007"),
                Paragraph("$long $long short. Last line."),
                Paragraph("Name\nCopyright (C) 2026 Someone"),
                Paragraph("http://example.com/not-a-title"),
            ),
            SimpleMarkdown.parsePlain(text),
        )
    }

    @Test fun emptyInput() {
        assertEquals(emptyList<TextBlock>(), SimpleMarkdown.parse(""))
        assertEquals(emptyList<TextBlock>(), SimpleMarkdown.parsePlain("\n\n"))
    }
}
