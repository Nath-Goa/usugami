package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Translate PDF translates, and how a long block is cut for the translator and joined again. */
class TranslatableTextTest {
    private fun worth(text: String) = TranslatableText.isWorthTranslating(text)

    @Test fun addressesAreNotTranslated() {
        for (address in listOf(
            "https://example.com/report?id=3", "www.example.org", "(www.example.org)", "example.co.uk/about",
            "jane.doe@example.com", "<jane.doe@example.com>", "mailto:info@example.com", "report_final.pdf",
            "C:\\Program Files\\Usugami", "/home/jane/notes.txt", "~/Documents", "https://a.example www.b.example",
        )) {
            assertFalse(address, worth(address))
        }
        // An address among words is translated with them.
        assertTrue(worth("Write to jane.doe@example.com for a copy."))
        assertTrue(worth("Visit www.example.org"))
    }

    @Test fun numbersDatesAmountsAndCodesAreNotTranslated() {
        for (text in listOf("12.50", "A", "03/04/2025", "$1,200.00", "€ 3,50", "+33 1 23 45 67 89", "12:30", "A-12-B", "Q3", "p. 12", "", "  ", "—", "2025年3月")) {
            assertFalse(text, worth(text))
        }
        // An id: one run of more than 40 characters without a space, with digits.
        assertFalse(worth("INV2025000123ABCDEFGHIJKLMNOPQRSTUVWXYZ0042"))
    }

    @Test fun wordsAreTranslated() {
        // "U.S.A." has no word of two letters, but no digit either: an abbreviation, not a code.
        for (text in listOf("Invoice 12", "Total", "No.", "12 kg", "Page 3 of 10", "東京", "नमस्ते", "Étude", "U.S.A.", "e.g.")) {
            assertTrue(text, worth(text))
        }
        // A long word without digits is still a word.
        assertTrue(worth("Donaudampfschifffahrtsgesellschaftskapitänsmütze"))
    }

    /** A paragraph of [count] sentences of different lengths, each ending with a full stop. */
    private fun sentences(count: Int): String = (1..count).joinToString(" ") { i ->
        "Sentence $i of the report says " + "something more ".repeat(i % 7) + "and ends here."
    }

    @Test fun aLongParagraphIsCutAtTheEndsOfItsSentences() {
        val text = sentences(35)
        assertEquals(3175, text.length)
        val pieces = TextChunks.split(text)
        assertTrue(pieces.size >= 3)
        for (piece in pieces) {
            assertTrue(piece.length <= TextChunks.MAX_CHARS)
            assertTrue(piece, piece.endsWith("and ends here."))
            assertTrue(piece, piece.startsWith("Sentence "))
        }
        assertEquals(text, TextChunks.join(pieces))
        // Each piece takes as many whole sentences as fit: the next one wouldn't have.
        for (i in 0 until pieces.size - 1) {
            val next = pieces[i + 1].indexOf(". ").let { if (it < 0) pieces[i + 1].length else it + 1 }
            assertTrue(pieces[i].length + 1 + next > TextChunks.MAX_CHARS)
        }
    }

    @Test fun aSentenceLongerThanAPieceIsCutBetweenWords() {
        val words = (1..700).joinToString(" ") { "word$it" }
        val pieces = TextChunks.split(words, max = 1200)
        for (piece in pieces) {
            assertTrue(piece.length <= 1200)
            assertTrue(piece, piece.split(' ').all { it.matches(Regex("word\\d+")) })
        }
        assertEquals(words, TextChunks.join(pieces))
        // A single "word" longer than a piece (an address, say) is cut where it must be.
        val long = "x".repeat(30)
        assertEquals(listOf("x".repeat(12), "x".repeat(12), "x".repeat(6)), TextChunks.split(long, max = 12))
    }

    @Test fun chineseIsCutAfterItsFullStopsAndJoinedWithoutSpaces() {
        val sentence = "这是一个关于翻译的很长的句子，它没有空格。"
        val text = sentence.repeat(150)
        assertEquals(3150, text.length)
        val pieces = TextChunks.split(text)
        assertTrue(pieces.size >= 3)
        for (piece in pieces) {
            assertTrue(piece.length <= TextChunks.MAX_CHARS)
            assertTrue(piece.endsWith("。"))
        }
        assertEquals(text, TextChunks.join(pieces))
        // Without any full stop, between any two characters.
        val unbroken = "翻译".repeat(1300)
        val cut = TextChunks.split(unbroken)
        assertEquals(listOf(1200, 1200, 200), cut.map { it.length })
        assertEquals(unbroken, TextChunks.join(cut))
    }

    @Test fun piecesNeverSplitACharacter() {
        // An emoji is two chars; a cut at 3 would fall between its halves.
        val text = "ab\uD83D\uDE00cd"
        val pieces = TextChunks.split(text, max = 3)
        assertEquals(listOf("ab", "\uD83D\uDE00c", "d"), pieces)
        // é written as e and its accent stays together.
        assertEquals(listOf("ab", "e\u0301c"), TextChunks.split("abe\u0301c", max = 3))
    }

    @Test fun joiningPutsSpacesOnlyBetweenSpacedWriting() {
        assertEquals("One. Two.", TextChunks.join(listOf("One.", " Two. ")))
        assertEquals("東京。大阪。", TextChunks.join(listOf("東京。", "大阪。")))
        assertEquals("ภาษาไทยภาษาไทย", TextChunks.join(listOf("ภาษาไทย", "ภาษาไทย")))
        assertEquals("東京。 Osaka.", TextChunks.join(listOf("東京。", "Osaka.")))
        assertEquals("한국어 문장", TextChunks.join(listOf("한국어", "문장")))
        assertEquals("", TextChunks.join(listOf(" ", "")))
        assertEquals(emptyList<String>(), TextChunks.split("   "))
        assertEquals(listOf("Short."), TextChunks.split("  Short.  "))
    }
}
