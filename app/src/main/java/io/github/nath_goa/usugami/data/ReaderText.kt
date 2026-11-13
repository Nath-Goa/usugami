package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.GlyphBox
import io.github.nath_goa.usugami.core.PageFrame
import io.github.nath_goa.usugami.core.PageGlyphs
import io.github.nath_goa.usugami.core.Redaction
import io.github.nath_goa.usugami.core.TextGlyph
import java.io.Writer
import kotlin.math.max

/**
 * Reads page [index] (zero-based) of a PDF for selecting its text in the reader (TextSelection): its
 * glyphs in reading order, each with what it reads as, where it shows on the page as shown, its line
 * and whether a word starts with it. It reads as Redact's Find text does (Redactor.Finder), in the
 * order the PDF draws the text, so a column's lines stay together: a line's words in the order they
 * are read, also in Hebrew and Arabic, whose brackets read as their mirror images
 * (Redactor.readingOrder), and each glyph boxed where it is drawn (Redactor.glyphBox), on a turned or
 * cropped page too. A glyph that reads as nothing or only white space is left out, and a word starts
 * after it, as after a gap PDFBox finds between two words; so is one boxed wholly outside the page.
 *
 * As PDF to images or text reads a page (PageTextReader), each glyph is handed to PDFBox placed in
 * its own direction, so that text running up or down the page as shown (written across a page turned
 * a quarter) is found in lines along it rather than a line to each letter; and a letter of writing
 * down the page (a vertical font), which PDFBox hands over a letter to a line, carries on the column
 * as Find text and that tool decide it (see [carriesOn]).
 */
internal class PageGlyphReader(private val index: Int) : PDFTextStripper() {
    private val glyphs = ArrayList<TextGlyph>()

    /** The line being read, its glyphs left to right as drawn; null between two words. */
    private val line = ArrayList<TextPosition?>()

    /** The page as shown. */
    private var frame: PageFrame? = null

    /** The line the glyph read last is on; -1 before the first. */
    private var lineNumber = -1

    /** A break between lines comes before the next glyph, unless it carries on a column (see [carriesOn]). */
    private var pendingBreak = false

    /** The next glyph starts a word. */
    private var startsWord = true

    /** The glyph read last, as PDFBox gave it. */
    private var last: TextPosition? = null

    init {
        sortByPosition = false
        // Text extraction counts pages from 1.
        startPage = index + 1
        endPage = index + 1
    }

    /** The page's glyphs. Throws what PDFBox throws on a page it can't read. */
    fun read(doc: PDDocument): PageGlyphs {
        writeText(doc, NoWriter)
        return PageGlyphs(glyphs.toList())
    }

    override fun startPage(page: PDPage) {
        glyphs.clear()
        line.clear()
        frame = page.shownFrame()
        lineNumber = -1
        pendingBreak = false
        startsWord = true
        last = null
    }

    override fun processTextPosition(text: TextPosition) = super.processTextPosition(alongItsLine(text))

    /**
     * [glyph], placed in its own direction (as PageTextReader.alongItsLine): in the order drawn, PDFBox
     * finds a line's end where the next glyph is no longer beside the last on the page as shown, so
     * text running up or down the page as shown would break into a line at nearly every letter. Placed
     * in its own direction, as PDFBox places glyphs when it sorts them by position, its lines are found
     * along it; its box (Redactor.glyphBox) is the same, worked out from its matrix and font alone.
     */
    private fun alongItsLine(glyph: TextPosition): TextPosition {
        val direction = glyph.dir.toInt()
        if (direction == glyph.rotation) return glyph
        return TextPosition(
            direction, glyph.pageWidth, glyph.pageHeight, glyph.textMatrix, glyph.endX, glyph.endY, glyph.height,
            glyph.individualWidths.firstOrNull() ?: 0f, glyph.widthOfSpace, glyph.unicode, glyph.characterCodes, glyph.font,
            glyph.fontSize, glyph.fontSizeInPt.toInt(),
        )
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        line += textPositions
    }

    override fun writeWordSeparator() {
        line += null
    }

    override fun writeLineSeparator() = breakLine()
    override fun writeParagraphStart() = breakLine()
    override fun writeParagraphEnd() = breakLine()
    override fun startArticle(isLTR: Boolean) = breakLine()
    override fun endArticle() = breakLine()

    /** A break between lines: the line read so far goes in first. */
    private fun breakLine() {
        endLine()
        pendingBreak = true
    }

    /** The line read so far, in the order it is read, into the page's glyphs. */
    private fun endLine() {
        val frame = frame
        if (frame == null) {
            line.clear()
            return
        }
        for ((glyph, mirrored) in Redactor.readingOrder(line)) {
            if (glyph == null) {
                startsWord = true
                continue
            }
            val unicode = glyph.unicode.orEmpty()
            // Reads as nothing, or only white space: left out, and the next starts a word.
            if (unicode.isBlank()) {
                startsWord = true
                continue
            }
            val box = Redactor.glyphBox(glyph, frame) ?: continue
            if (box.right < 0f || box.bottom < 0f || box.left > frame.shownWidth || box.top > frame.shownHeight) continue
            if (lineNumber < 0) {
                lineNumber = 0
                startsWord = true
            } else if (pendingBreak) {
                when (carriesOn(last, glyph)) {
                    Carry.LETTER -> Unit
                    Carry.WORD -> startsWord = true
                    null -> {
                        lineNumber++
                        startsWord = true
                    }
                }
            }
            pendingBreak = false
            glyphs += TextGlyph(if (mirrored) Redactor.mirror(unicode) else unicode, box, lineNumber, startsWord)
            startsWord = false
            last = glyph
        }
        line.clear()
    }

    /** How a glyph carries on the line before it down a column (see [carriesOn]). */
    private enum class Carry {
        /** The next letter of the same word. */
        LETTER,

        /** The next word. */
        WORD,
    }

    /**
     * How [next] carries on [last] down a line of vertical writing, which PDFBox hands over a letter to
     * a line: the next cell down the line (Redaction.follows) with no more than a quarter of a letter
     * between their cells is the next [Carry.LETTER], as Find text decides it (Finder.carriesOn);
     * further apart, the next [Carry.WORD], as PDF to images or text writes it (PageTextReader.carriesOn).
     * Null when [next] starts another line: text that isn't vertical never carries on.
     */
    private fun carriesOn(last: TextPosition?, next: TextPosition): Carry? {
        if (last?.font?.isVertical != true || next.font?.isVertical != true) return null
        val frame = frame ?: return null
        val a: GlyphBox = Redactor.glyphBox(last, frame) ?: return null
        val b: GlyphBox = Redactor.glyphBox(next, frame) ?: return null
        if (!Redaction.follows(a, b)) return null
        // Along the line, as Redaction.follows measures it.
        val gap = if (a.across) max(b.left - a.right, a.left - b.right) else max(b.top - a.bottom, a.top - b.bottom)
        val length = if (a.across) max(a.right - a.left, b.right - b.left) else max(a.bottom - a.top, b.bottom - b.top)
        return if (gap <= 0.25f * length) Carry.LETTER else Carry.WORD
    }

    override fun endPage(page: PDPage) {
        endLine()
    }

    private object NoWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }
}
