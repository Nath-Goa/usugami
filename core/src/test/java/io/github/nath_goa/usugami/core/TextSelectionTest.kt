package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextSelectionTest {
    /**
     * A line of [text] across the page, as the reader reads it: each character a glyph 10 points wide
     * and 12 tall, from [left], its top at [top]; a space leaves a gap and starts the next word, and is
     * itself left out, as the reader leaves it out. [wordStarts]: characters that start a word besides.
     */
    private fun across(text: String, line: Int, top: Float, left: Float = 0f, wordStarts: Set<Int> = emptySet()): List<TextGlyph> {
        val glyphs = ArrayList<TextGlyph>()
        var x = left
        var starts = true
        for ((i, c) in text.withIndex()) {
            if (c == ' ') {
                starts = true
            } else {
                glyphs += TextGlyph(c.toString(), GlyphBox(x, top, x + 10f, top + 12f, across = true), line, starts || i in wordStarts)
                starts = false
            }
            x += 10f
        }
        return glyphs
    }

    /** A line of [text] down the page (a column of vertical writing, or text on a page turned a quarter), each glyph 12 wide and 10 tall. */
    private fun down(text: String, line: Int, left: Float, top: Float = 0f): List<TextGlyph> =
        text.mapIndexed { i, c -> TextGlyph(c.toString(), GlyphBox(left, top + 10f * i, left + 12f, top + 10f * (i + 1), across = false), line, i == 0) }

    private fun page(vararg lines: List<TextGlyph>) = PageGlyphs(lines.flatMap { it })

    private fun words(page: PageGlyphs, range: IntRange?) = range?.let { TextSelection.text(page, it) }

    @Test
    fun aLongPressTakesTheWordUnderIt() {
        // "Hello world" on the first line; "漢字" and "かな" on the second.
        val page = page(across("Hello world", 0, top = 0f), across("漢字かな", 1, top = 20f))
        // In the middle of "world"'s "r".
        assertEquals(5..9, TextSelection.wordAt(page, 85f, 6f, reach = 3f))
        assertEquals("world", words(page, TextSelection.wordAt(page, 85f, 6f, reach = 3f)))
        // In the gap after "Hello", 2 points from its "o": within reach, the nearer word.
        assertEquals(0..4, TextSelection.wordAt(page, 52f, 6f, reach = 3f))
        // Above the line within reach, below it beyond.
        assertEquals(0..4, TextSelection.wordAt(page, 15f, -2f, reach = 3f))
        assertNull(TextSelection.wordAt(page, 15f, 16f, reach = 1f))
        // Far from any text: nothing.
        assertNull(TextSelection.wordAt(page, 300f, 300f, reach = 3f))
        // A Chinese character or a kana is taken alone.
        assertEquals("字", words(page, TextSelection.wordAt(page, 15f, 26f, reach = 3f)))
        assertEquals("か", words(page, TextSelection.wordAt(page, 25f, 26f, reach = 3f)))
        // A page without text has no words.
        assertNull(TextSelection.wordAt(PageGlyphs(emptyList()), 0f, 0f, reach = 100f))
        // Down the page: the column's glyph where the finger is, and its word.
        val column = page(down("縦書き", 0, left = 100f), down("Up", 1, left = 80f))
        assertEquals("書", words(column, TextSelection.wordAt(column, 106f, 15f, reach = 3f)))
        assertEquals("Up", words(column, TextSelection.wordAt(column, 86f, 15f, reach = 3f)))
    }

    @Test
    fun aHandleStandsOnTheNearestGlyphOfTheNearestLine() {
        val page = page(across("Hello world", 0, top = 0f), across("Second line here", 1, top = 20f))
        // On a glyph: that glyph.
        assertEquals(1, TextSelection.glyphNear(page, 15f, 6f))
        // Past the end of the first line, level with it: its last glyph.
        assertEquals(9, TextSelection.glyphNear(page, 400f, 4f))
        // Between the lines, nearer the second (whose top is at 20): the second's glyph below.
        assertEquals(10 + 2, TextSelection.glyphNear(page, 25f, 17f))
        // Between the lines, nearer the first.
        assertEquals(2, TextSelection.glyphNear(page, 25f, 14f))
        // Far below the text, left of it: the last line's first glyph.
        assertEquals(10, TextSelection.glyphNear(page, -50f, 500f))
        // In the gap between two words: the nearer glyph of the two.
        assertEquals(4, TextSelection.glyphNear(page, 51f, 6f))
        assertEquals(5, TextSelection.glyphNear(page, 59f, 6f))
        // Columns down the page: the column nearest across, and its glyph nearest down it.
        val columns = page(down("縦書き", 0, left = 100f), down("右から", 1, left = 70f))
        assertEquals(2, TextSelection.glyphNear(columns, 118f, 25f))
        assertEquals(3 + 1, TextSelection.glyphNear(columns, 60f, 14f))
        assertNull(TextSelection.glyphNear(PageGlyphs(emptyList()), 0f, 0f))
    }

    @Test
    fun aSelectionOverPagesTakesEachPagesPart() {
        val from = TextPlace(1, 5)
        val to = TextPlace(3, 2)
        for ((a, b) in listOf(from to to, to to from)) {
            // The first page from the glyph on, the pages between whole, the last up to the glyph.
            assertEquals(5..9, TextSelection.onPage(a, b, page = 1, count = 10))
            assertEquals(0..6, TextSelection.onPage(a, b, page = 2, count = 7))
            assertEquals(0..2, TextSelection.onPage(a, b, page = 3, count = 8))
            // Outside: nothing; a page between without text: nothing.
            assertNull(TextSelection.onPage(a, b, page = 0, count = 10))
            assertNull(TextSelection.onPage(a, b, page = 4, count = 10))
            assertNull(TextSelection.onPage(a, b, page = 2, count = 0))
        }
        // On one page, in either order.
        assertEquals(3..6, TextSelection.onPage(TextPlace(2, 6), TextPlace(2, 3), page = 2, count = 9))
        assertEquals(4..4, TextSelection.onPage(TextPlace(2, 4), TextPlace(2, 4), page = 2, count = 9))
        // Places compare by page, then glyph.
        assertEquals(listOf(TextPlace(0, 9), TextPlace(1, 0), TextPlace(1, 3)), listOf(TextPlace(1, 3), TextPlace(0, 9), TextPlace(1, 0)).sorted())
    }

    @Test
    fun linesGetOneBoxEach() {
        val page = page(across("Hello world", 0, top = 0f), across("Second line", 1, top = 20f))
        // From "world"'s "o" to the second line's "c": two lines, each boxed around what is selected of it.
        assertEquals(
            listOf(GlyphBox(70f, 0f, 110f, 12f, across = true), GlyphBox(0f, 20f, 30f, 32f, across = true)),
            TextSelection.lineBoxes(page, 6..12),
        )
        // A column down the page: one box, down it.
        val column = page(down("縦書き", 0, left = 100f))
        assertEquals(listOf(GlyphBox(100f, 0f, 112f, 30f, across = false)), TextSelection.lineBoxes(column, 0..2))
    }

    @Test
    fun textHasSpacesLineBreaksAndPlainLetters() {
        // A gap PDFBox found between the two Chinese characters starts the second as a word: no space
        // between them, though, as PDF to images or text writes them. "ﬁ" is one glyph, the ligature.
        val page = page(
            across("Hello world", 0, top = 0f),
            across("ﬁne", 1, top = 20f),
            across("漢字", 2, top = 40f, wordStarts = setOf(1)),
            across("中文 and", 3, top = 60f),
        )
        assertEquals("Hello world\nfine\n漢字\n中文 and", TextSelection.text(page, page.glyphs.indices))
        // Part of a line, then part of the next.
        assertEquals("lo w", TextSelection.text(page, 3..5))
        assertEquals("rld\nfi", TextSelection.text(page, 7..10))
        // Down the page, one line.
        val column = page(down("縦書き", 0, left = 100f))
        assertEquals("縦書き", TextSelection.text(column, 0..2))
    }

    @Test
    fun handlesSwapEndsInRightToLeftText() {
        // Left to right: the start under the first glyph's left end, the end under the last's right end.
        val latin = page(across("Hello", 0, top = 0f))
        assertEquals(0f to 12f, TextSelection.startHandle(latin, 0))
        assertEquals(50f to 12f, TextSelection.endHandle(latin, 4))
        // Hebrew, read right to left: in reading order each glyph lies left of the one before.
        val hebrew = PageGlyphs("שלום".mapIndexed { i, c -> TextGlyph(c.toString(), GlyphBox(90f - 10f * i, 0f, 100f - 10f * i, 12f, across = true), 0, i == 0) })
        assertEquals(100f to 12f, TextSelection.startHandle(hebrew, 0))
        assertEquals(60f to 12f, TextSelection.endHandle(hebrew, 3))
        // A glyph alone on its line runs as its writing does.
        val alone = PageGlyphs(listOf(TextGlyph("ש", GlyphBox(0f, 0f, 10f, 12f, across = true), 0, true)))
        assertEquals(10f to 12f, TextSelection.startHandle(alone, 0))
        assertEquals(0f to 12f, TextSelection.endHandle(alone, 0))
        // Down the page: the start at the first glyph's top, the end under the last's bottom.
        val column = page(down("縦書き", 0, left = 100f))
        assertEquals(106f to 0f, TextSelection.startHandle(column, 0))
        assertEquals(106f to 30f, TextSelection.endHandle(column, 2))
    }
}
