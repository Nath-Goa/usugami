package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.ExportText
import io.github.nath_goa.usugami.core.GlyphBox
import io.github.nath_goa.usugami.core.PageFrame
import io.github.nath_goa.usugami.core.SourceLine
import io.github.nath_goa.usugami.core.isOutOfSpace
import java.io.Writer
import java.util.IdentityHashMap
import kotlin.math.hypot
import kotlin.math.max
import kotlinx.coroutines.CancellationException

/**
 * What [PageLineReader] read on a page: its [lines] to translate, and whether it shows letters
 * ([hasText]): any in its lines, or many ([PageLineReader.TURNED_LETTERS] or more) in text it
 * doesn't translate (turned, or running down the page). A page that does is not a scan to read with
 * text recognition; a scan with a few such letters on it (a watermark, a stamp) still is.
 */
internal class PageLines(val lines: List<SourceLine>, val hasText: Boolean)

/**
 * Reads the lines of the [wanted] pages (zero-based) of a PDF for Translate PDF, each as a
 * [SourceLine] on the page as shown (its crop box, turned by its /Rotate; points from its top-left
 * corner, y down): the line's text, the box around its glyphs (see [Redactor.glyphBox]), its font
 * size (the median of its glyphs'), whether it is bold (most of its glyphs are) and whether it is
 * written right to left (most of its letters are Hebrew, Arabic, Syriac or Thaana). [onStart] is told
 * of each page before it is read, and may throw to stop; [onPage] is given what was read, or null
 * when the page couldn't be read (a font on it naming an encoding PDFBox doesn't have, damaged
 * content). A page without contents is neither started nor given.
 *
 * Each line's text is made as [PageTextReader] makes it (words in reading order, gaps as
 * [ExportText.gap] writes them, cleaned by [ExportText.clean]). Only lines running across the page
 * as shown are given, those of numbers alone too (an amount, a superscript), which stay as they are
 * on the page's picture and mustn't be painted over: text running up or down the page, and text
 * drawn invisibly (as the text layer of a scan is), stay as they are on the picture too. A line is
 * cut where two glyphs next to each other are more than [GAP_EM] font sizes apart, so the cells of a
 * table's row, or items set apart with tabs, are lines of their own; and after a bullet or a note's
 * number that goes apart from its text (see [markEnd]). A glyph that starts more than a
 * point outside the crop box is left out, as the page shows only what is inside it. The storage
 * running full while a page is read is no unreadable page: it goes up as it is.
 */
internal class PageLineReader(
    private val wanted: Set<Int>,
    private val onStart: (index: Int) -> Unit,
    private val onPage: (index: Int, lines: PageLines?) -> Unit,
) : PDFTextStripper() {
    /** The line being read, its glyphs as text extraction gives them; null between two words. */
    private val line = ArrayList<TextPosition?>()

    /** The page's lines so far. */
    private val lines = ArrayList<SourceLine>()

    /** The letters of the page so far in the lines given, and in text that isn't (see [PageLines.hasText]). */
    private var lineLetters = 0
    private var turnedLetters = 0

    /** What was read of the page, once it is read to its end. */
    private var read: PageLines? = null

    /** The page being read, as shown, and its crop box's size in the page's own directions. */
    private var frame: PageFrame? = null
    private var cropWidth = 0f
    private var cropHeight = 0f

    /** Whether each font met is bold, by the font: asked once each. */
    private val boldFonts = IdentityHashMap<PDFont, Boolean>()

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
        val result = try {
            super.processPage(page)
            read
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The pages after it would fail the same way: the storage full is said as such.
            if (e.isOutOfSpace()) throw e
            null
        }
        onPage(index, result)
    }

    override fun startPage(page: PDPage) {
        line.clear()
        lines.clear()
        lineLetters = 0
        turnedLetters = 0
        read = null
        frame = page.shownFrame()
        val box = page.cropBox
        cropWidth = box.width
        cropHeight = box.height
    }

    /**
     * A glyph is read only when it is drawn (filled or stroked, not as the invisible text a scan's
     * text layer is) and starts within the crop box, with a point to spare for text set on its edge.
     * Text extraction measures from the crop box's corner, in the page's own directions.
     */
    override fun processTextPosition(text: TextPosition) {
        val mode = graphicsState.textState.renderingMode
        if (!mode.isFill && !mode.isStroke) return
        val x = text.textMatrix.translateX
        val y = text.textMatrix.translateY
        if (x < -SPARE || x > cropWidth + SPARE || y < -SPARE || y > cropHeight + SPARE) return
        super.processTextPosition(alongItsLine(text))
    }

    /** As [PageTextReader] places a glyph: in its own direction, so that text extraction finds lines along it. */
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
     * The line read so far, cut into pieces where it doesn't run across the page or where two
     * glyphs next to each other are far apart (see [GAP_EM]), each piece a line of the page.
     */
    private fun endLine() {
        val frame = frame
        if (line.isEmpty() || frame == null) {
            line.clear()
            return
        }
        var piece = ArrayList<Placed?>()
        var last: Placed? = null
        for (glyph in line) {
            if (glyph == null) {
                piece += null
                continue
            }
            val box = Redactor.glyphBox(glyph, frame)
            if (box == null || !box.across) {
                // Text that runs up or down the page as shown isn't translated.
                turnedLetters += lettersOf(glyph)
                addLine(piece)
                piece = ArrayList()
                last = null
                continue
            }
            lineLetters += lettersOf(glyph)
            val placed = Placed(glyph, box, sizeOf(glyph))
            if (last != null) {
                val gap = max(box.left - last.box.right, last.box.left - box.right)
                if (gap > GAP_EM * max(placed.size, last.size)) {
                    addLine(piece)
                    piece = ArrayList()
                }
            }
            piece += placed
            last = placed
        }
        addLine(piece)
        line.clear()
    }

    /** How many letters [glyph] shows: none when it stands for something unreadable. */
    private fun lettersOf(glyph: TextPosition): Int {
        val unicode = glyph.unicode.orEmpty()
        return if (Redactor.readable(unicode)) unicode.count(Char::isLetter) else 0
    }

    /** A glyph with its box on the page as shown and its font size. */
    private class Placed(val glyph: TextPosition, val box: GlyphBox, val size: Float)

    /**
     * Where the rest of [piece] starts after its first word, when that word is a mark that goes
     * apart from the line's text: a bullet or an item's number set apart from it by more than
     * [MARKER_GAP_EM] font sizes (the text's second line starts under the text, not the mark), or a
     * note's number (or *, †) smaller than the text after it, as footnotes start. -1 for none, and
     * always in right-to-left text, whose first word drawn is its last read.
     */
    private fun markEnd(piece: List<Placed?>): Int {
        val end = piece.indexOfFirst { it == null }
        if (end <= 0) return -1
        val word = piece.subList(0, end).filterNotNull()
        val rest = piece.subList(end, piece.size).filterNotNull()
        if (word.isEmpty() || rest.isEmpty()) return -1
        val mark = word.joinToString("") { it.glyph.unicode.orEmpty() }.trim()
        if (rightToLeft(rest.joinToString("") { it.glyph.unicode.orEmpty() })) return -1
        val size = median(rest.map { it.size })
        val gap = rest.first().box.left - word.last().box.right
        val listed = LIST_MARK.matches(mark) && gap > MARKER_GAP_EM * size
        val noted = NOTE_MARK.matches(mark) && median(word.map { it.size }) < SMALL_MARK * size
        return if (listed || noted) end else -1
    }

    /** Adds [piece] as a line of the page, unless it is blank: a line of numbers too, which stays as it is on the page. */
    private fun addLine(piece: List<Placed?>) {
        val placed = piece.filterNotNull()
        if (placed.isEmpty()) return
        val mark = markEnd(piece)
        if (mark > 0) {
            addLine(piece.subList(0, mark))
            addLine(piece.subList(mark, piece.size))
            return
        }
        val byGlyph = IdentityHashMap<TextPosition, Placed>()
        for (p in placed) byGlyph[p.glyph] = p
        // Made as PageTextReader makes a line: in the order it is read, with gaps between its words.
        val made = StringBuilder()
        var gap = false
        for ((glyph, mirrored) in Redactor.readingOrder(piece.map { it?.glyph })) {
            if (glyph == null) {
                gap = true
                continue
            }
            val unicode = glyph.unicode.orEmpty().let { if (mirrored) Redactor.mirror(it) else it }
            if (unicode.isEmpty()) continue
            if (gap && made.isNotEmpty()) made.append(ExportText.gap(made.codePointBefore(made.length), unicode.codePointAt(0)))
            gap = false
            made.append(unicode)
        }
        val text = ExportText.clean(made.toString()).trim()
        if (text.isEmpty()) return
        val bold = placed.count { isBold(it.glyph.font) } * 2 > placed.size
        lines += SourceLine(
            text,
            placed.minOf { it.box.left }, placed.minOf { it.box.top }, placed.maxOf { it.box.right }, placed.maxOf { it.box.bottom },
            median(placed.map { it.size }), bold, rightToLeft(text),
        )
    }

    /**
     * The glyph's font size in points of the page: how long one unit up in its text space is on
     * the page (its text rendering matrix's "up"), which, unlike the size text extraction gives in
     * whole points, takes in a page drawn scaled down.
     */
    private fun sizeOf(glyph: TextPosition): Float {
        val trm = glyph.textMatrix
        return hypot(trm.shearX, trm.scaleY).takeIf { it.isFinite() && it > 0f } ?: glyph.fontSizeInPt.takeIf { it > 0f } ?: 1f
    }

    /** Whether [font] is bold: by its name (Bold, Black, Heavy, Semibold…), its weight (700 and more) or its flag. */
    private fun isBold(font: PDFont?): Boolean {
        if (font == null) return false
        return boldFonts.getOrPut(font) {
            val name = try { font.name } catch (_: RuntimeException) { null }.orEmpty()
            val descriptor = font.fontDescriptor
            BOLD_NAME.containsMatchIn(name) || descriptor != null && (descriptor.fontWeight >= 700f || descriptor.isForceBold)
        }
    }

    override fun endPage(page: PDPage) {
        endLine()
        read = PageLines(ArrayList(lines), hasText = lineLetters > 0 || turnedLetters >= TURNED_LETTERS)
    }

    private object NoWriter : Writer() {
        override fun write(cbuf: CharArray, off: Int, len: Int) = Unit
        override fun flush() = Unit
        override fun close() = Unit
    }

    companion object {
        /** How far outside the crop box, in points, a glyph may start and still be read. */
        private const val SPARE = 1f

        /** Glyphs further apart than this many font sizes are in lines of their own. */
        const val GAP_EM = 1.5f

        /**
         * A page with this many letters or more in text it doesn't translate (a turned table, a page of
         * text running up it) has text; fewer (a watermark, a stamp, a code) don't make a scan text.
         */
        const val TURNED_LETTERS = 40

        private val BOLD_NAME = Regex("bold|black|heavy", RegexOption.IGNORE_CASE)

        /** A bullet, a dash, a symbol font's bullet, or an item's number or letter: "1.", "(2)", "a)", "iv.". */
        private val LIST_MARK = Regex("[•◦▪▫‣⁃∙●○■□►▸▹➢➤✓✔✗✘·*\\-–—\\uE000-\\uF8FF]|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z][.)]|\\(?[ivxIVX]{1,5}[.)]")

        /** A note's number or sign, as footnotes start with. */
        private val NOTE_MARK = Regex("\\d{1,3}|[*†‡§]{1,3}")

        /** A mark this many font sizes or more before its text goes apart from it… */
        private const val MARKER_GAP_EM = 0.5f

        /** …as does a note's number smaller than this share of the text's size. */
        private const val SMALL_MARK = 0.8f

        /** Whether most of [text]'s letters are written right to left: Hebrew, Arabic, Syriac, Thaana. */
        fun rightToLeft(text: String): Boolean {
            var rtl = 0
            var letters = 0
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                if (!Character.isLetter(cp)) continue
                letters++
                val direction = Character.getDirectionality(cp)
                if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) rtl++
            }
            return rtl * 2 > letters
        }

        fun median(values: List<Float>): Float {
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        }
    }
}
