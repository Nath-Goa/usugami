package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionTest {
    private fun assertBox(expected: RedactBox, actual: RedactBox) {
        assertEquals("page", expected.page, actual.page)
        assertEquals("left", expected.left, actual.left, 1e-5f)
        assertEquals("top", expected.top, actual.top, 1e-5f)
        assertEquals("right", expected.right, actual.right, 1e-5f)
        assertEquals("bottom", expected.bottom, actual.bottom, 1e-5f)
    }

    @Test fun shownPointsGoBackToWhereTheyCameFrom() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val frame = PageFrame(10f, 20f, 400f, 600f, rotation)
            for ((x, y) in listOf(0f to 0f, 12f to 34f, frame.shownWidth to frame.shownHeight, 100f to 5f)) {
                val (px, py) = frame.toPage(x, y)
                val (sx, sy) = frame.toShown(px, py)
                assertEquals("Rotation $rotation", x, sx, 0.001f)
                assertEquals("Rotation $rotation", y, sy, 0.001f)
            }
        }
        // Turned clockwise, the box's bottom-right corner is shown at the bottom left.
        val turned = PageFrame(10f, 20f, 400f, 600f, 90)
        assertEquals(0f to 0f, turned.toShown(410f, 20f))
        assertEquals(0f to 400f, turned.toShown(10f, 20f))
    }

    @Test fun boxesDrawnEitherWayAreCutToThePage() {
        assertBox(RedactBox(2, 0.1f, 0.2f, 0.5f, 0.6f), Redaction.between(2, 0.5f, 0.6f, 0.1f, 0.2f))
        assertBox(RedactBox(0, 0f, 0f, 1f, 0.3f), Redaction.between(0, -0.2f, 0.3f, 1.4f, -1f))
    }

    @Test fun movingStopsAtTheEdgesAndKeepsTheSize() {
        val box = RedactBox(0, 0.1f, 0.1f, 0.3f, 0.2f)
        assertBox(RedactBox(0, 0.15f, 0.08f, 0.35f, 0.18f), Redaction.moved(box, 0.05f, -0.02f))
        assertBox(RedactBox(0, 0f, 0f, 0.2f, 0.1f), Redaction.moved(box, -1f, -1f))
        assertBox(RedactBox(0, 0.8f, 0.9f, 1f, 1f), Redaction.moved(box, 5f, 5f))
    }

    @Test fun resizingKeepsTheCentreAndAMinimum() {
        val box = RedactBox(1, 0.4f, 0.4f, 0.6f, 0.5f)
        assertBox(RedactBox(1, 0.39f, 0.4f, 0.61f, 0.5f), Redaction.resized(box, 0.02f, 0f))
        val tiny = Redaction.resized(box, -1f, -1f)
        assertEquals(Redaction.MIN_SIZE, tiny.width, 1e-6f)
        assertEquals(Redaction.MIN_SIZE, tiny.height, 1e-6f)
        assertEquals(0.5f, (tiny.left + tiny.right) / 2, 1e-6f)
        // Growing at the edge of the page pushes it back on.
        val atEdge = Redaction.resized(RedactBox(0, 0.9f, 0f, 1f, 0.1f), 0.1f, 0.1f)
        assertBox(RedactBox(0, 0.8f, 0f, 1f, 0.2f), atEdge)
    }

    @Test fun draggingTheCornerKeepsTheOtherOne() {
        val box = RedactBox(0, 0.2f, 0.3f, 0.4f, 0.5f)
        assertBox(RedactBox(0, 0.2f, 0.3f, 0.7f, 0.9f), Redaction.withCorner(box, 0.7f, 0.9f))
        // Dragged past the other corner, it stops at the smallest size.
        val folded = Redaction.withCorner(box, 0f, 0f)
        assertBox(RedactBox(0, 0.2f, 0.3f, 0.2f + Redaction.MIN_SIZE, 0.3f + Redaction.MIN_SIZE), folded)
        assertBox(RedactBox(0, 0.2f, 0.3f, 1f, 1f), Redaction.withCorner(box, 3f, 3f))
    }

    @Test fun newBoxesAreCentredAndDoNotLandOnEachOther() {
        val first = Redaction.default(4, 595f, 842f, 0)
        assertEquals(4, first.page)
        assertEquals(144f / 595f, first.width, 1e-5f)
        assertEquals(24f / 842f, first.height, 1e-5f)
        assertEquals(0.5f, (first.left + first.right) / 2, 1e-5f)
        assertEquals(0.5f, (first.top + first.bottom) / 2, 1e-5f)
        val placed = (0 until 8).map { Redaction.default(0, 595f, 842f, it) }
        assertEquals(8, placed.map { it.top }.distinct().size)
        for (box in placed) assertTrue(box.top >= 0.15f && box.bottom <= 0.85f)
        // A small page still gets a box that fits.
        val label = Redaction.default(0, 100f, 50f, 0)
        assertEquals(0.6f, label.width, 1e-5f)
        assertEquals(0.2f, label.height, 1e-5f)
    }

    @Test fun pagesAreDrawnAt200DpiUnlessHuge() {
        assertEquals(1653 to 2339, Redaction.pixelSize(595f, 842f))
        // A0 would be 6622 × 9362 pixels; it is drawn smaller, keeping its shape.
        val (width, height) = Redaction.pixelSize(2384f, 3370f)
        assertTrue(width.toLong() * height <= Redaction.MAX_PIXELS + 10_000)
        assertEquals(2384f / 3370f, width.toFloat() / height, 0.001f)
        assertEquals(1 to 1, Redaction.pixelSize(0.1f, 0.1f))
    }

    @Test fun pixelBoundsRoundOutwards() {
        val box = RedactBox(0, 0.101f, 0.2f, 0.3005f, 0.40001f)
        assertArrayEquals(intArrayOf(10, 20, 31, 41), Redaction.pixelBounds(box, 100, 100))
        assertArrayEquals(intArrayOf(0, 0, 100, 100), Redaction.pixelBounds(RedactBox(0, 0f, 0f, 1f, 1f), 100, 100))
    }

    @Test fun tappingPicksTheSmallestBoxUnderTheFinger() {
        val boxes = listOf(
            RedactBox(0, 0.1f, 0.1f, 0.9f, 0.9f),
            RedactBox(0, 0.4f, 0.4f, 0.5f, 0.42f),
            RedactBox(1, 0.4f, 0.4f, 0.5f, 0.42f),
        )
        assertEquals(1, Redaction.hit(boxes, 0, 0.45f, 0.41f, 0f, 0f))
        // Just above the thin box, within reach of it.
        assertEquals(1, Redaction.hit(boxes, 0, 0.45f, 0.395f, 0.01f, 0.01f))
        assertEquals(0, Redaction.hit(boxes, 0, 0.2f, 0.2f, 0.01f, 0.01f))
        assertEquals(-1, Redaction.hit(boxes, 0, 0.95f, 0.95f, 0.01f, 0.01f))
        assertEquals(2, Redaction.hit(boxes, 1, 0.45f, 0.41f, 0f, 0f))
    }

    @Test fun boxesSurviveBeingPackedIntoNumbers() {
        val boxes = listOf(RedactBox(0, 0.1f, 0.2f, 0.3f, 0.4f), RedactBox(41, 0f, 0.5f, 1f, 0.75f))
        assertEquals(boxes, Redaction.unpack(Redaction.pack(boxes)))
        assertEquals(emptyList<RedactBox>(), Redaction.unpack(FloatArray(0)))
        assertTrue(Redaction.same(boxes[0], boxes[0].copy(left = 0.10001f)))
        assertFalse(Redaction.same(boxes[0], boxes[0].copy(page = 1)))
    }

    private fun pageText(vararg words: String): PageText = PageText.Builder().apply {
        var glyph = 0
        for (word in words) {
            if (word == " ") space() else for (c in word) glyph(glyph++, c.toString())
        }
    }.build()

    @Test fun findIgnoresCaseAndAccentsAndMatchesAcrossLines() {
        // "Top secret" ends one line; "José Smith" spans two.
        val text = pageText("Top", " ", "SECRET", " ", "José", " ", "Smith", " ", "secretary")
        // Accents are dropped from the text compared, not from the PDF.
        assertEquals("Top SECRET Jose Smith secretary", text.text)
        val secret = text.find("secret")
        assertEquals(2, secret.size)
        assertEquals((3..8).toList(), secret[0])
        assertEquals((18..23).toList(), secret[1])
        assertEquals(listOf((9..17).toList()), text.find("  jose   SMITH "))
        assertEquals(listOf((9..12).toList()), text.find("JOSÉ"))
        assertTrue(text.find("   ").isEmpty())
        assertTrue(text.find("nothing").isEmpty())
    }

    @Test fun matchesDoNotOverlap() {
        val text = pageText("aaaa")
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), text.find("aa"))
    }

    @Test fun glyphsThatReadAsSeveralLettersOrNoneStillMatch() {
        val text = PageText.Builder().apply {
            glyph(0, "ﬁ") // the ligature ﬁ is one glyph
            glyph(1, "n")
            glyph(2, "d")
            glyph(3, "­") // a soft hyphen reads as nothing
            glyph(4, "e")
            glyph(5, " ") // a no-break space is a space
            glyph(6, "x")
        }.build()
        assertEquals("finde x", text.text)
        assertEquals(listOf(listOf(0, 1, 2)), text.find("find"))
        assertEquals(listOf(listOf(0, 1)), text.find("fin"))
        assertEquals(listOf(listOf(4, 6)), text.find("e x"))
    }

    @Test fun devanagariIsMatchedAsWritten() {
        val text = PageText.Builder().apply { "नमस्ते दुनिया".forEachIndexed { i, c -> glyph(i, c.toString()) } }.build()
        assertEquals(1, text.find("दुनिया").size)
        assertTrue("Vowel signs aren't dropped", text.find("दनय").isEmpty())
    }

    @Test fun aMatchGetsOneBoxPerLine() {
        // Two words on one line, 12 points tall, then one on the next line.
        val glyphs = listOf(
            GlyphBox(100f, 100f, 110f, 112f, true), GlyphBox(110f, 100f, 120f, 112f, true),
            GlyphBox(125f, 100f, 140f, 112f, true),
            GlyphBox(50f, 116f, 70f, 128f, true),
        )
        val boxes = Redaction.boxesFor(3, glyphs, 600f, 800f)
        assertEquals(2, boxes.size)
        val pad = Redaction.TEXT_MARGIN * 12f
        assertBox(RedactBox(3, (100f - pad) / 600f, (100f - pad) / 800f, (140f + pad) / 600f, (112f + pad) / 800f), boxes[0])
        assertBox(RedactBox(3, (50f - pad) / 600f, (116f - pad) / 800f, (70f + pad) / 600f, (128f + pad) / 800f), boxes[1])
    }

    @Test fun textRunningDownThePageIsBoxedAlongItsLine() {
        // Letters stacked down the page, each 12 points across.
        val glyphs = (0 until 4).map { GlyphBox(300f, 200f + 10f * it, 312f, 210f + 10f * it, false) }
        val boxes = Redaction.boxesFor(0, glyphs, 600f, 800f)
        assertEquals(1, boxes.size)
        val pad = Redaction.TEXT_MARGIN * 12f
        assertBox(RedactBox(0, (300f - pad) / 600f, (200f - pad) / 800f, (312f + pad) / 600f, (240f + pad) / 800f), boxes[0])
    }

    @Test fun farApartPiecesOfALineGetTheirOwnBoxes() {
        val glyphs = listOf(GlyphBox(50f, 100f, 60f, 110f, true), GlyphBox(400f, 100f, 410f, 110f, true))
        assertEquals(2, Redaction.boxesFor(0, glyphs, 600f, 800f).size)
    }
}
