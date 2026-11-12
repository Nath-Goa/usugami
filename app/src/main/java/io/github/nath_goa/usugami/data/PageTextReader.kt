package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.ExportText
import io.github.nath_goa.usugami.core.PageFrame
import io.github.nath_goa.usugami.core.Redaction
import io.github.nath_goa.usugami.core.isOutOfSpace
import java.io.Writer
import kotlin.math.max
import kotlinx.coroutines.CancellationException

/**
 * Reads the text of the [wanted] pages (zero-based) of a PDF for PDF to images or text, a line of
 * the page to a line of the text, in the order the PDF draws them (so a column's lines stay
 * together). [onStart] is told of each page before it is read, and may throw to stop; [onPage] is
 * given its text, each line ending with "\n", or null when the page couldn't be read (a font on it
 * naming an encoding PDFBox doesn't have, damaged content). A page without contents, a blank one,
 * is neither started nor given.
 *
 * Each line is read as Redact's Find text reads it: the words of a line of Hebrew or Arabic in the
 * order they are read (see [Redactor.readingOrder]), a gap PDFBox finds between two words written
 * as a space, but not between two Chinese or Japanese characters (see [ExportText.gap]), and the
 * line cleaned (see [ExportText.clean]). Lines are found along the text's own direction, also on
 * a turned page (see [alongItsLine]). A glyph that starts more than a point outside the page's crop
 * box is left out, as the page shows only what is inside it. Writing down the page (a vertical
 * font), which PDFBox hands over a letter to a line, comes out a column to a line, a gap between
 * two words down it written as a gap between words of any line (see [carriesOn]). The storage
 * running full while a page is read is no unreadable page: it goes up as it is.
 */
internal class PageTextReader(
    private val wanted: Set<Int>,
    private val onStart: (index: Int) -> Unit,
    private val onPage: (index: Int, text: String?) -> Unit,
) : PDFTextStripper() {
    /** The page's text so far. */
    private val text = StringBuilder()

    /** The text of the page read last, once it is read to its end. */
    private var pageText: String? = null

    /** The line being read, its glyphs left to right as drawn; null between two words. */
    private val line = ArrayList<TextPosition?>()

    /** The line read last, not yet written: the next may carry it on (see [carriesOn]). Null when there is none. */
    private var open: StringBuilder? = null

    /** The last glyph of [open], in the order it is read. */
    private var openLast: TextPosition? = null

    /** The page being read, as shown, and its crop box's size in the page's own directions. */
    private var frame: PageFrame? = null
    private var cropWidth = 0f
    private var cropHeight = 0f

    /** Reads the wanted pages, in one pass over the page tree. */
    fun read(doc: PDDocument) {
        require(wanted.isNotEmpty())
        startPage = wanted.min() + 1
        endPage = wanted.max() + 1
        writeText(doc, NoWriter)
    }

    override fun processPage(page: PDPage) {
        val index = currentPageNo - 1
        if (index !in wanted) return
        // Both outside the try: what they throw (to stop, or a file that can't be written) is no unreadable page.
        onStart(index)
        val read = try {
            super.processPage(page)
            pageText
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The pages after it would fail the same way: the storage full is said as such.
            if (e.isOutOfSpace()) throw e
            null
        }
        onPage(index, read)
    }

    override fun startPage(page: PDPage) {
        text.setLength(0)
        pageText = null
        line.clear()
        open = null
        openLast = null
        frame = page.shownFrame()
        val box = page.cropBox
        cropWidth = box.width
        cropHeight = box.height
    }

    /**
     * A glyph is read only when it starts within the crop box, with a point to spare for text set on
     * its edge. Text extraction measures from the crop box's corner, in the page's own directions.
     */
    override fun processTextPosition(text: TextPosition) {
        val x = text.textMatrix.translateX
        val y = text.textMatrix.translateY
        if (x >= -SPARE && x <= cropWidth + SPARE && y >= -SPARE && y <= cropHeight + SPARE) super.processTextPosition(alongItsLine(text))
    }

    /**
     * [glyph], placed in its own direction. Read in the order drawn, text extraction finds a line's
     * end where the next glyph is no longer beside the last on the page as shown, so text that runs
     * up or down the page as shown (written across a page turned a quarter, or turned on an upright
     * page) would break into a new line at nearly every letter wider than its font is tall ("T" /
     * "urned page"). Placed in its own direction, as text extraction places glyphs when it sorts them
     * by position, its lines are found along it. A glyph already so placed is left as it is.
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

    override fun writeLineSeparator() = endLine()
    override fun writeParagraphStart() = endLine()
    override fun writeParagraphEnd() = endLine()
    override fun startArticle(isLTR: Boolean) = endLine()
    override fun endArticle() = endLine()

    /**
     * The line read so far, in the order it is read: held as the [open] line, or added to it when its
     * first glyph carries on the open line's last down a column, after a gap when it is the next word
     * (see [carriesOn]). A line without glyphs changes nothing.
     */
    private fun endLine() {
        val made = StringBuilder()
        var first: TextPosition? = null
        var last: TextPosition? = null
        var gap = false
        for ((glyph, mirrored) in Redactor.readingOrder(line)) {
            if (glyph == null) {
                // Written only between two glyphs, once, as the characters on its two sides ask.
                gap = true
                continue
            }
            val unicode = glyph.unicode.orEmpty().let { if (mirrored) Redactor.mirror(it) else it }
            if (first == null) first = glyph
            last = glyph
            if (unicode.isEmpty()) continue
            if (gap && made.isNotEmpty()) made.append(ExportText.gap(made.codePointBefore(made.length), unicode.codePointAt(0)))
            gap = false
            made.append(unicode)
        }
        line.clear()
        if (first == null) return
        val held = open
        val carry = if (held != null) carriesOn(openLast, first) else null
        if (held == null || carry == null) {
            writeOpen()
            open = made
        } else {
            // A space, or nothing between two Chinese or Japanese characters, as between words of a line.
            if (carry == Carry.WORD && held.isNotEmpty() && made.isNotEmpty()) {
                held.append(ExportText.gap(held.codePointBefore(held.length), made.codePointAt(0)))
            }
            held.append(made)
        }
        openLast = last
    }

    /** Writes the [open] line into the page's text, cleaned, unless nothing is left of it. */
    private fun writeOpen() {
        val held = open ?: return
        val cleaned = ExportText.clean(held.toString())
        if (cleaned.isNotEmpty()) text.append(cleaned).append('\n')
        open = null
        openLast = null
    }

    /** How a glyph carries on the line before it down a column (see [carriesOn]). */
    private enum class Carry {
        /** The next letter of the same word: nothing between them. */
        LETTER,

        /** The next word: a gap between them, as between words of a line. */
        WORD,
    }

    /**
     * How [next] carries on [last] down a line of vertical writing, which text extraction hands over
     * a letter to a line: the next cell down the line ([Redaction.follows]) is the next [Carry.LETTER]
     * when there is no more than a quarter of a letter between their cells, as Redact's Find text
     * decides it (Finder.carriesOn), and the next [Carry.WORD] when they are further apart (a space
     * between words the PDF makes by moving the text, with no space glyph, as in 서울 특별시).
     * Null when [next] starts another line. Text that isn't vertical never carries on, so its lines
     * are written as they come.
     */
    private fun carriesOn(last: TextPosition?, next: TextPosition): Carry? {
        if (last?.font?.isVertical != true || next.font?.isVertical != true) return null
        val frame = frame ?: return null
        val a = Redactor.glyphBox(last, frame) ?: return null
        val b = Redactor.glyphBox(next, frame) ?: return null
        if (!Redaction.follows(a, b)) return null
        // Along the line, as Redaction.follows measures it.
        val gap = if (a.across) max(b.left - a.right, a.left - b.right) else max(b.top - a.bottom, a.top - b.bottom)
        val length = if (a.across) max(a.right - a.left, b.right - b.left) else max(a.bottom - a.top, b.bottom - b.top)
        return if (gap <= 0.25f * length) Carry.LETTER else Carry.WORD
    }

    override fun endPage(page: PDPage) {
        endLine()
        writeOpen()
        pageText = text.toString()
    }

    private object NoWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }

    private companion object {
        /** How far outside the crop box, in points, a glyph may start and still be read. */
        const val SPARE = 1f
    }
}
