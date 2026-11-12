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

    @Test fun aBoxTouchedIsFoundWhereItIsNow() {
        val a = RedactBox(0, 0.1f, 0.1f, 0.2f, 0.2f)
        val b = RedactBox(1, 0.1f, 0.1f, 0.2f, 0.2f)
        val c = RedactBox(0, 0.5f, 0.5f, 0.6f, 0.6f)
        assertEquals(2, Redaction.find(listOf(a, b, c), 2, c))
        // A box before it deleted meanwhile: it moved up the list.
        assertEquals(1, Redaction.find(listOf(b, c), 2, c))
        // Itself deleted, or changed (moved by a button, say): it is gone, and the box now in its place isn't it.
        assertEquals(-1, Redaction.find(listOf(a, b), 1, c))
        assertEquals(-1, Redaction.find(listOf(a, b, Redaction.moved(c, 0.1f, 0f)), 2, c))
        assertEquals(-1, Redaction.find(emptyList(), 0, a))
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

    /** As text extraction reads a heading set in spaced capitals: a gap between each two letters, a space between the words. */
    @Test fun letterSpacedTextIsFound() {
        val text = PageText.Builder().apply {
            "JANE".forEachIndexed { i, c ->
                if (i > 0) gap()
                glyph(i, c.toString())
            }
            space()
            "DOE".forEachIndexed { i, c ->
                if (i > 0) gap()
                glyph(4 + i, c.toString())
            }
            // Words drawn apart without spaces between them, as some PDFs are.
            gap()
            for ((i, c) in "a".withIndex()) glyph(7 + i, c.toString())
            gap()
            for ((i, c) in "natural".withIndex()) glyph(8 + i, c.toString())
        }.build()
        assertEquals("J A N E D O E a natural", text.text)
        assertEquals(listOf((0..6).toList()), text.find("Jane Doe"))
        assertEquals(listOf((0..3).toList()), text.find("jane"))
        // A gap beside a whole word is a space between words, as before.
        assertTrue(text.find("anatural").isEmpty())
        assertTrue(text.find("Ana").isEmpty())
        assertEquals(listOf((7..14).toList()), text.find("a natural"))
    }

    /** As text extraction reads words broken after a hyphen at the end of a line: a line break follows the hyphen. */
    @Test fun wordsBrokenAfterAHyphenAreFound() {
        val text = PageText.Builder().apply {
            var glyph = 0
            fun word(word: String) = word.forEach { glyph(glyph++, it.toString()) }
            word("jane.smith-")
            lineBreak()
            // A line's break and a paragraph's, in a row.
            lineBreak()
            word("jones@example.com")
            space()
            // A word hyphenated with a soft hyphen, which reads as nothing.
            word("confi")
            glyph(glyph++, "­")
            lineBreak()
            word("dential")
            lineBreak()
            word("end")
            lineBreak()
            word("Bo-")
            lineBreak()
            word("Bob")
            space()
            word("555-")
            lineBreak()
            word("5555")
        }.build()
        assertEquals("jane.smith- jones@example.com confi dential end Bo- Bob 555- 5555", text.text)
        assertEquals(listOf((0..27).toList()), text.find("Jane.Smith-Jones@example.com"))
        assertEquals(1, text.find("smith jones").size)
        assertEquals(listOf((28..32).toList() + (34..40).toList()), text.find("confidential"))
        // A line break after a whole word is a space, as before.
        assertTrue(text.find("dentialend").isEmpty())
        assertEquals(1, text.find("dential end").size)
        // A match across the break doesn't take the first letters of a whole one on the next line.
        assertEquals(listOf((44..49).toList()), text.find("bob"))
        assertEquals(listOf((50..57).toList()), text.find("5555"))
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

    @Test fun typographicApostrophesAndDashesMatchTheKeyboardOnes() {
        // As Word writes them: a curly apostrophe, a non-breaking hyphen and a minus sign.
        val text = pageText("D’Souza", " ", "555‑1234", " ", "10−3", " ", "“quoted”")
        assertEquals(listOf((0..6).toList()), text.find("d'souza"))
        assertEquals(listOf((7..14).toList()), text.find("555-1234"))
        assertEquals(listOf((15..18).toList()), text.find("10-3"))
        assertEquals(listOf((19..26).toList()), text.find("\"quoted\""))
        // Typed with the curly one too.
        assertEquals(1, text.find("D’SOUZA").size)
        // Letters often typed as two: each still boxes the glyph it came from.
        val words = pageText("Straße", " ", "sœur")
        assertEquals(listOf((0..5).toList()), words.find("strasse"))
        assertEquals(listOf((0..5).toList()), words.find("STRAßE"))
        assertEquals(listOf((6..9).toList()), words.find("soeur"))
        // An acute accent written as the apostrophe, and letters with a stroke typed without it.
        val names = pageText("O´Neill", " ", "Wałęsa", " ", "Søren", " ", "Łódź")
        assertEquals(listOf((0..6).toList()), names.find("o'neill"))
        assertEquals(listOf((7..12).toList()), names.find("Walesa"))
        assertEquals(listOf((13..17).toList()), names.find("soren"))
        assertEquals(listOf((18..21).toList()), names.find("LODZ"))
    }

    @Test fun devanagariIsMatchedAsWritten() {
        val text = PageText.Builder().apply { "नमस्ते दुनिया".forEachIndexed { i, c -> glyph(i, c.toString()) } }.build()
        assertEquals(1, text.find("दुनिया").size)
        assertTrue("Vowel signs aren't dropped", text.find("दनय").isEmpty())
    }

    /** One glyph for each character of [text], as text extraction reads CJK text. */
    private fun glyphs(builder: PageText.Builder, text: String, from: Int = 0) {
        var i = 0
        var index = from
        while (i < text.length) {
            val c = text.codePointAt(i)
            builder.glyph(index++, String(Character.toChars(c)))
            i += Character.charCount(c)
        }
    }

    @Test fun chineseAndJapaneseAreFoundAcrossBreaksOfEveryKind() {
        // A word wrapped onto the next line at any character.
        val wrapped = PageText.Builder().apply {
            glyphs(this, "東京")
            lineBreak()
            glyphs(this, "都", 2)
        }.build()
        assertEquals(listOf(listOf(0, 1, 2)), wrapped.find("東京都"))
        // A name with a space in it (U+3000, which reads as a space), found with and without one.
        val name = PageText.Builder().apply { glyphs(this, "山田\u3000太郎") }.build()
        assertEquals(listOf(listOf(0, 1, 3, 4)), name.find("山田太郎"))
        assertEquals(listOf(listOf(0, 1, 3, 4)), name.find("山田 太郎"))
        // A sliver of room between Japanese and a Latin word.
        val mixed = PageText.Builder().apply {
            glyphs(this, "東京")
            gap()
            glyphs(this, "Tower", 2)
        }.build()
        assertEquals(listOf((0..6).toList()), mixed.find("東京tower"))
        assertEquals("山田太郎", PageText.normalize("山田 太郎"))
        assertEquals("Windows版", PageText.normalize("Windows 版"))
        assertEquals("John Smith", PageText.normalize("John  Smith"))
    }

    @Test fun koreanIsFoundAcrossALineBreakButNotAcrossASpace() {
        val wrapped = PageText.Builder().apply {
            glyphs(this, "서울")
            lineBreak()
            glyphs(this, "시", 2)
        }.build()
        assertEquals(listOf(listOf(0, 1, 2)), wrapped.find("서울시"))
        // Korean separates words with spaces: a typed space must match one.
        val spaced = PageText.Builder().apply { glyphs(this, "홍길 동") }.build()
        assertTrue(spaced.find("홍길동").isEmpty())
        assertEquals(listOf(listOf(0, 1, 3)), spaced.find("홍길 동"))
    }

    @Test fun fullWidthAndHalfWidthFormsMatch() {
        val full = PageText.Builder().apply { glyphs(this, "ＡＢＣ１２３") }.build()
        assertEquals(listOf((0..5).toList()), full.find("abc123"))
        // ｶﾞ is two glyphs, ガ one.
        val half = PageText.Builder().apply { glyphs(this, "ｶﾞ") }.build()
        assertEquals(listOf(listOf(0, 1)), half.find("ガ"))
        val whole = PageText.Builder().apply { glyphs(this, "ガ") }.build()
        assertEquals(listOf(listOf(0)), whole.find("ｶﾞ"))
        val unvoiced = PageText.Builder().apply { glyphs(this, "カ") }.build()
        assertTrue(unvoiced.find("ガ").isEmpty())
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

    @Test fun lettersDownAColumnFollowOneAnother() {
        val first = GlyphBox(490f, 442f, 510f, 462f, false)
        assertTrue(Redaction.follows(first, GlyphBox(490f, 462f, 510f, 482f, false)))
        assertFalse("The next column", Redaction.follows(first, GlyphBox(460f, 442f, 480f, 462f, false)))
        assertFalse("15 points further down", Redaction.follows(first, GlyphBox(490f, 477f, 510f, 497f, false)))
        // A column lying on its side, as on a page turned by 90°.
        assertTrue(Redaction.follows(GlyphBox(442f, 490f, 462f, 510f, true), GlyphBox(462f, 490f, 482f, 510f, true)))
    }

    @Test fun addressesAreAlsoReadWithTheirEscapesAsLetters() {
        assertEquals(listOf("mailto:john.smith@example.org"), Redaction.addressForms("mailto:john.smith@example.org"))
        assertTrue("https://example.org/team/Ana Silva" in Redaction.addressForms("https://example.org/team/Ana%20Silva"))
        assertTrue("?who=José" in Redaction.addressForms("?who=Jos%C3%A9"))
        assertTrue("שלום" in Redaction.addressForms("%D7%A9%D7%9C%D7%95%D7%9D"))
        // "+" both as it is (an email address) and as a space (a web form's), and a % that starts no escape kept.
        val forms = Redaction.addressForms("100%+sure?to=jane+doe%2Bwork%zz%4")
        assertTrue(forms.toString(), "100%+sure?to=jane+doe+work%zz%4" in forms)
        assertTrue(forms.toString(), "100% sure?to=jane doe+work%zz%4" in forms)
    }

    @Test fun pagesAreListedAsTheyAreTyped() {
        assertEquals("3", Redaction.pageList(listOf(2)))
        assertEquals("2, 5-7", Redaction.pageList(listOf(1, 4, 5, 6)))
        assertEquals("1, 3, 5, 7, 9, 11, 13, 15…", Redaction.pageList((0 until 20 step 2).toList(), most = 8))
    }
}
