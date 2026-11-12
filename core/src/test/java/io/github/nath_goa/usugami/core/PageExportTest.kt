package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageExportTest {

    @Test
    fun imageSizesFollowTheDpiWithinTheCaps() {
        val a4 = 595.28f to 841.89f
        assertEquals(ExportImages.Size(827, 1169, false), ExportImages.pixelSize(a4.first, a4.second, ExportImageSize.SMALL.dpi))
        assertEquals(ExportImages.Size(1654, 2339, false), ExportImages.pixelSize(a4.first, a4.second, ExportImageSize.MEDIUM.dpi))
        assertEquals(ExportImages.Size(2480, 3508, false), ExportImages.pixelSize(a4.first, a4.second, ExportImageSize.LARGE.dpi))

        // A0 at 300 dpi would be 9933 × 14043: as many pixels as the cap allows, in the page's shape.
        val a0 = ExportImages.pixelSize(2383.94f, 3370.39f, 300)
        assertTrue(a0.reduced)
        val pixels = a0.width.toLong() * a0.height
        assertTrue("$pixels pixels", pixels <= ExportImages.MAX_PIXELS && pixels > ExportImages.MAX_PIXELS * 0.999)
        assertTrue("${a0.width} × ${a0.height}", abs(a0.height - a0.width * 3370.39 / 2383.94) <= 1)

        // A strip 200 × 20000 points: the long side's cap holds.
        val strip = ExportImages.pixelSize(200f, 20000f, 300)
        assertTrue(strip.reduced)
        assertEquals(ExportImages.MAX_SIDE, strip.height)
        assertEquals(81, strip.width)

        // A page of a point, or less, still gets a pixel.
        assertEquals(ExportImages.Size(1, 1, false), ExportImages.pixelSize(1f, 1f, 100))
        assertEquals(ExportImages.Size(1, 1, false), ExportImages.pixelSize(0.1f, 0.1f, 100))
        val thin = ExportImages.pixelSize(1f, 100_000f, 300)
        assertEquals(ExportImages.Size(1, ExportImages.MAX_SIDE, true), thin)

        // Each try after running out of memory has half the pixels, until the long side would be under 1000.
        val large = ExportImages.pixelSize(a4.first, a4.second, 300)
        val half = ExportImages.smaller(large)!!
        assertTrue(half.reduced)
        val ratio = half.width.toDouble() * half.height / (large.width.toDouble() * large.height)
        assertTrue("$ratio", ratio <= 0.5 && ratio > 0.49)
        assertTrue(abs(half.height - half.width * 841.89 / 595.28) <= 1)
        var size: ExportImages.Size? = large
        var tries = 0
        while (size != null) {
            assertTrue(maxOf(size.width, size.height) >= ExportImages.MIN_LONG_SIDE)
            size = ExportImages.smaller(size)
            tries++
        }
        assertEquals("3508, 2480, 1753, 1239 on the long side", 4, tries)
        assertNull(ExportImages.smaller(ExportImages.Size(700, 990, false)))
    }

    @Test
    fun imagesAreNamedAsSplitNamesItsPages() {
        assertEquals(listOf("Report_p1", "Report_p3", "Report_p10"), ExportImages.names("Report", listOf(0, 2, 9)))
        assertEquals(listOf("Report_p1", "Report_p3", "Report_p10"), ExportImages.names("Report.pdf", listOf(0, 2, 9)))
        assertEquals(listOf("Report_p1", "Report_p3", "Report_p10"), ExportImages.names(" Report.JPG ", listOf(0, 2, 9)))
        assertEquals(listOf("Report_p1"), ExportImages.names("Report.jpeg", listOf(0)))
        assertEquals(listOf("Usugami_p1"), ExportImages.names("", listOf(0)))
        assertEquals(listOf("Usugami_p1"), ExportImages.names(".png", listOf(0)))
        assertEquals(listOf("a_b_c_p2"), ExportImages.names("a/b:c", listOf(1)))

        // A long name is cut once, alike for all, so the images sort together.
        val long = ExportImages.names("x".repeat(300), listOf(0, 9, 99))
        val bases = long.map { it.substringBeforeLast("_p") }.toSet()
        assertEquals(1, bases.size)
        assertEquals(listOf("_p1", "_p10", "_p100"), long.map { it.removePrefix(bases.single()) })
        assertTrue(long.all { it.toByteArray().size <= 200 })

        assertEquals("Report_images", ExportImages.resultName("Report"))
        assertEquals("Report_images", ExportImages.resultName("Report.png"))
        assertEquals("Report_images", ExportImages.resultName("Report.PDF"))
        assertEquals("Usugami_images", ExportImages.resultName("  "))
    }

    @Test
    fun aLineIsCleanedAndTextIsLetterOrDigit() {
        assertEquals("fine office", ExportText.clean("ﬁne ofﬁce"))
        // The Arabic lam-alef ligature, in its final shape, is the two letters.
        assertEquals("لا", ExportText.clean("ﻻ"))
        assertEquals("abc\td", ExportText.clean("a\u0007b�c\td￼"))
        assertEquals("  indented", ExportText.clean("  indented  \t "))
        assertEquals("hyphen-", ExportText.clean("hyphen-"))
        // A byte order mark, which a ToUnicode map can put before each letter, isn't text.
        assertEquals("ab", ExportText.clean("a\uFEFFb"))
        // A vowel mark's presentation form (fatha, U+FE76) is the mark on its letter, with no space between.
        assertEquals("\u0643\u064E", ExportText.clean("\u0643\uFE76"))
        // Only presentation forms change: "²", "½" and full-width letters stay as they are.
        assertEquals("x² ½ ＡＢ", ExportText.clean("x² ½ ＡＢ"))
        // "Allah" after an alef doesn't get a second alef.
        assertEquals("الله", ExportText.clean("اﷲ"))

        assertFalse(ExportText.hasText(""))
        assertFalse(ExportText.hasText(" \n"))
        assertFalse(ExportText.hasText("•—…"))
        assertTrue(ExportText.hasText("a"))
        assertTrue(ExportText.hasText("٣"))
        assertTrue(ExportText.hasText("7"))
        assertTrue(ExportText.hasText("東"))

        fun gap(before: String, after: String) = ExportText.gap(before.codePointAt(0), after.codePointAt(0))
        assertEquals(" ", gap("a", "b"))
        assertEquals(" ", gap("東", "T"))
        assertEquals(" ", gap("7", "年"))
        assertEquals("", gap("東", "京"))
        assertEquals("", gap("。", "あ"))
        assertEquals("", gap("コ", "ー"))
        // Full-width punctuation, and half-width kana's long vowel mark.
        assertEquals("", gap("\u597D", "\uFF0C"))
        assertEquals("", gap("\uFF0C", "\u4E16"))
        assertEquals("", gap("\uFF7A", "\uFF70"))
        assertEquals(" ", gap("a", "\uFF0C"))
        // Korean puts spaces between its words.
        assertEquals(" ", gap("한", "국"))
        assertEquals(" ", ExportText.gap(-1, "東".codePointAt(0)))
    }

    @Test
    fun textFileNamesPageListsAndThePreview() {
        assertEquals("Report", ExportText.baseName("Report"))
        assertEquals("Report", ExportText.baseName("Report.txt"))
        assertEquals("Report", ExportText.baseName("Report.TXT "))
        assertEquals("Report", ExportText.baseName("Report.pdf"))
        assertEquals("Usugami", ExportText.baseName(""))
        assertEquals("Usugami", ExportText.baseName(".txt"))

        assertEquals("3,5-7", ExportText.pagesListed(listOf(6, 2, 4, 5, 4)))
        val odd = ExportText.pagesListed((0 until 400 step 2).toList())
        assertTrue(odd, odd.length <= 40)
        assertTrue(odd, odd.startsWith("1,3,5,") && odd.endsWith(",…,399"))

        assertEquals("short\ntext", ExportText.preview("short\ntext"))
        // Lines of 100 characters with their line breaks: cut at the last break within the limit.
        val lines = (1..300).joinToString("") { "x".repeat(99) + "\n" }
        val preview = ExportText.preview(lines)
        assertTrue(preview.length <= ExportText.PREVIEW_CHARS)
        assertEquals(19_999, preview.length)
        assertTrue(lines.startsWith(preview + "\n"))
        // One long line of emoji, each two chars, with the limit falling inside one: it is left out whole.
        val emoji = "a" + "😀".repeat(15_000)
        val cut = ExportText.preview(emoji)
        assertEquals(ExportText.PREVIEW_CHARS - 1, cut.length)
        assertFalse(Character.isHighSurrogate(cut.last()))
    }

    @Test
    fun recognisedLinesComeInReadingOrder() {
        fun box(left: Float, top: Float, right: Float, bottom: Float) =
            OcrQuad(listOf(OcrPoint(left, top), OcrPoint(right, top), OcrPoint(right, bottom), OcrPoint(left, bottom)))

        val bottomFirst = listOf(
            "third line" to box(0f, 200f, 400f, 220f),
            "second line" to box(0f, 100f, 400f, 120f),
            "  first line " to box(0f, 0f, 400f, 20f),
        )
        assertEquals("first line\nsecond line\nthird line\n", ExportText.ofLines(bottomFirst))

        // Two columns of four lines, given row by row: the left column, then the right one.
        val rows = (0 until 4).flatMap { row ->
            val top = row * 30f
            listOf("left ${row + 1}" to box(0f, top, 200f, top + 20f), "right ${row + 1}" to box(300f, top, 500f, top + 20f))
        }
        assertEquals((1..4).joinToString("") { "left $it\n" } + (1..4).joinToString("") { "right $it\n" }, ExportText.ofLines(rows))

        val empty = listOf(
            "words" to box(0f, 0f, 200f, 20f),
            "   " to box(0f, 30f, 200f, 50f),
            "\u0007\u0008" to box(0f, 60f, 200f, 80f),
        )
        assertEquals("words\n", ExportText.ofLines(empty))
        assertEquals("", ExportText.ofLines(emptyList()))
    }
}
