package io.github.nath_goa.usugami.core

import io.github.nath_goa.usugami.core.TextBlock.Heading
import io.github.nath_goa.usugami.core.TextBlock.ListItem
import io.github.nath_goa.usugami.core.TextBlock.Paragraph
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

    // Laid out as tools/google_notices.py writes third_party/google-notices*.txt.
    @Test fun plainTextTitleAndBoxedNames() {
        val rule = "-".repeat(80)
        val text = """
            Open-source notices in Google libraries
            =======================================

            Libraries:
              com.google.android.gms:play-services-mlkit-text-recognition-common:19.1.0
              com.google.mlkit:vision-common:17.3.0


            $rule
            Abseil
            Bazel Platforms
            $rule

            Some notice text.

                                GNU GENERAL PUBLIC LICENSE
                                   Version 2, June 1991

            $rule
            The next section applies to the files in:
            jre_emul/android/time
            $rule
            Redistribution is permitted.
            $rule
            Text between rules that close nothing is not a title.

            An underline further down
            =========================
        """.trimIndent()
        assertEquals(
            listOf(
                Heading(1, "Open-source notices in Google libraries"),
                Paragraph("Libraries:\ncom.google.android.gms:play-services-mlkit-text-recognition-common:19.1.0\ncom.google.mlkit:vision-common:17.3.0"),
                Heading(2, "Abseil\nBazel Platforms"),
                Paragraph("Some notice text."),
                // Titles inside a component's notice rank below its name.
                Heading(3, "GNU GENERAL PUBLIC LICENSE\nVersion 2, June 1991"),
                Heading(2, "The next section applies to the files in:\njre_emul/android/time"),
                Paragraph("Redistribution is permitted.\nText between rules that close nothing is not a title."),
                Paragraph("An underline further down"),
            ),
            SimpleMarkdown.parsePlain(text),
        )
    }

    @Test fun indentedClausesAndQuotationsAreNotTitles() {
        val text = """
            1. Grant of rights. You may reproduce and share the material under these terms
            and conditions, as long as you keep the notices described below with every copy.

                    ii. a copyright notice;

                    a notice that refers to this Public License;

                    (c)    Representations.

                    V.1. In General.

                    B. Any new file that contains any part of the Original Code or
                    previous Modifications.

                    The obligations of Section 3 apply to Netscape, except to
                    the extent specified in this Amendment.

                                          Preamble
        """.trimIndent()
        assertEquals(
            listOf(
                Paragraph(
                    "1. Grant of rights. You may reproduce and share the material under these terms " +
                        "and conditions, as long as you keep the notices described below with every copy.",
                ),
                Paragraph("ii. a copyright notice;"),
                Paragraph("a notice that refers to this Public License;"),
                Paragraph("(c) Representations."),
                Paragraph("V.1. In General."),
                Paragraph("B. Any new file that contains any part of the Original Code or previous Modifications."),
                Paragraph("The obligations of Section 3 apply to Netscape, except to the extent specified in this Amendment."),
                Heading(2, "Preamble"),
            ),
            SimpleMarkdown.parsePlain(text),
        )
    }

    @Test fun emptyInput() {
        assertEquals(emptyList<TextBlock>(), SimpleMarkdown.parse(""))
        assertEquals(emptyList<TextBlock>(), SimpleMarkdown.parsePlain("\n\n"))
    }
}
