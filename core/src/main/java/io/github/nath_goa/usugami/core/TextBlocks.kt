package io.github.nath_goa.usugami.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A line of text on a page as shown, as Translate PDF reads it from the PDF's text or from text
 * recognition: its [text] in reading order; its box in points from the page's top-left corner, y
 * down ([left], [top], [right], [bottom]); its font [size] in points; whether it is [bold]; and
 * whether it is written right to left ([rtl]: mostly Hebrew, Arabic, Syriac or Thaana letters).
 */
data class SourceLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val size: Float,
    val bold: Boolean = false,
    val rtl: Boolean = false,
)

/** How a block's lines line up, in the direction it is written: [START] is the left of left-to-right text and the right of right-to-left text. */
enum class BlockAlign { START, CENTER, END }

/**
 * A paragraph of a page, or a heading, a list item, a table's cell: its [lines] in reading order;
 * the [text] to translate, which joins them (see [TextBlocks.group]); its box, the union of its
 * lines' ([left], [top], [right], [bottom]: points on the page as shown, y down); its [size] (the
 * median of its lines'), whether it is [bold] and written right to left ([rtl]), and how its lines
 * line up ([align]).
 */
data class SourceBlock(
    val lines: List<SourceLine>,
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val size: Float,
    val bold: Boolean,
    val rtl: Boolean,
    val align: BlockAlign,
)

/**
 * A page's lines grouped into blocks, each translated as one text and drawn in its place.
 *
 * Lines are taken in reading order: top to bottom, and the lines of one row (tops within half a
 * font size) left to right, or right to left in a row of right-to-left text. Each line continues
 * the open block it fits best under (the nearest above), so the columns of a page grow side by
 * side, or else starts a new block. A line fits under a block's last line when ALL hold:
 * - the same font size (within 15 %) and boldness;
 * - it is the next line down: at least half a font size and at most 1.8 font sizes lower (bottom
 *   to bottom), with at most 0.9 of a font size between the boxes; on a page whose running text is
 *   spaced wider (double spacing), up to a tenth more than its usual line step, at most 3 font sizes;
 * - the two overlap sideways by at least half the narrower, and their starts (left edges of
 *   left-to-right text) are within 1.5 font sizes, or their centres or ends within 1 (centred and
 *   right-aligned text); a paragraph's second line may start up to 4 font sizes before or after its
 *   first (an indented first line, or a hanging indent);
 * - in a block of three lines or more whose lines after the first share a start, it starts within
 *   one font size of it: a line indented (or outdented) from that margin starts the next paragraph,
 *   also where the paragraph before it ends with a full line;
 * - when the last line is narrower than running text (15 font sizes), the line doesn't start with a
 *   capital letter or a digit: such lines are an address's or labels', each an entry of its own;
 * - unless the text is centred or right-aligned, the last line isn't clearly short: it is at least
 *   8 font sizes wide (a narrower one is an entry of a list or a table, "Apples" above "Oranges"),
 *   and it ends no further short of the block's end (its furthest line, the new one included) than
 *   3 font sizes or the new line's first word, which would have fitted on it had the paragraph gone on.
 *
 * A line that starts with a bullet or a number ("•", "-", "–", "*", "1.", "1)", "(1)", "a)", "iv.")
 * always starts a new block, as does the line beside a bullet or a number standing alone, or just
 * after a note's number set smaller than it (a footnote's). A
 * block can't go on past a line below its last line that overlaps it sideways, so a heading or a
 * picture's caption between two parts of a column keeps them apart.
 *
 * Where a page could be read either way, real paragraphs are kept whole and kept apart: a
 * paragraph of five lines ending with a short line, followed by one starting indented, is two blocks
 * (the short line alone says so, the indent too); ending with a full line, it is two blocks all the
 * same, as its lines after the first share a margin the indented line leaves; but a paragraph of
 * two lines has no such margin yet, so a full second line followed by an indented one is read as
 * one paragraph. A column of short table cells is a block for each cell, and a paragraph of a
 * column narrower than 8 font sizes a block for each line.
 *
 * Lines with no text, with a side that isn't a number, or lying wholly off the page are left out.
 * A size that isn't a positive number is taken as the line's height (or 1 point).
 */
object TextBlocks {
    /** Lines whose font sizes differ by more than this share are not one paragraph. */
    internal const val SIZE_TOLERANCE = 0.15f

    /** The most space between one line's box and the next's in a paragraph, in font sizes. */
    internal const val MAX_GAP_EM = 0.9f

    /** The furthest a paragraph's next line is below the one before it, bottom to bottom, in font sizes. */
    internal const val MAX_STEP_EM = 1.8f

    /** The least a next line is below the one before it, in font sizes: less, and it is beside it in a row. */
    internal const val MIN_STEP_EM = 0.5f

    /** Starts of lines this close (in font sizes) line up: left-aligned text. */
    internal const val START_EM = 1.5f

    /** Centres of lines this close (in font sizes) line up: centred text. */
    internal const val CENTRE_EM = 1f

    /** Ends of lines this close (in font sizes) line up: right-aligned text. */
    internal const val END_EM = 1f

    /** Lines of one block overlap sideways by at least this share of the narrower. */
    internal const val MIN_OVERLAP = 0.5f

    /** A line ending more than this short of its block's end (in font sizes) ends the paragraph. */
    internal const val SHORT_LINE_EM = 3f

    /** How much wider than its estimate (from the line's width and letters) a word may be. */
    internal const val FIRST_WORD_SLACK = 1.25f

    /** A line narrower than this (in font sizes) is an entry of a list or a table: no line continues it. */
    internal const val MIN_PARAGRAPH_LINE_EM = 8f

    /** How far a paragraph's first line may start after (indent) or before (hanging indent) its second, in font sizes. */
    internal const val FIRST_LINE_INDENT_EM = 4f

    /** A line starting further than this (in font sizes) from the start its block's lines share starts a new paragraph. */
    internal const val NEW_PARAGRAPH_INDENT_EM = 1f

    /** Starts this close (in font sizes) are one margin. */
    internal const val SAME_EDGE_EM = 0.25f

    /** Lines whose tops are this close (in font sizes) are one row, read across. */
    internal const val ROW_EM = 0.5f

    /** A line at least this wide (in font sizes) is running text, whose line step tells the page's line spacing. */
    internal const val LONG_LINE_EM = 15f

    /** On a page spaced wider than [MAX_STEP_EM], a paragraph's lines may be this many times its usual step apart… */
    internal const val SPACING_SLACK = 1.1f

    /** …but never further apart than this, in font sizes. */
    internal const val MAX_SPACED_STEP_EM = 3f

    /** The usual line step is only trusted from this many pairs of lines of running text. */
    internal const val MIN_SPACING_SAMPLES = 4

    /** Steps between lines this close (as a share) are one step. */
    internal const val SAME_STEP = 0.08f

    /** A block's lines whose starts (or ends) are this close, in font sizes, line up there. */
    internal const val ALIGNED_EDGES_EM = 1f

    /** A block's lines whose centres are this close, in font sizes, are centred. */
    internal const val ALIGNED_CENTRES_EM = 0.5f

    /** A single line whose centre is this close to the page's centre, as a share of the page's width, is centred… */
    internal const val CENTRED_SHARE = 0.02f

    /** …when it is narrower than this share of the page. */
    internal const val MAX_CENTRED_WIDTH = 0.8f

    /** Edges this close, as a share of the page's width, are at a margin. */
    internal const val NEAR_EDGE_SHARE = 0.03f

    private const val SOFT_HYPHEN = 0x00AD

    /** A roman number from i to xxxix, in small letters and in capitals. */
    private const val ROMAN = "(?=[ivx])x{0,3}(?:ix|iv|v?i{0,3})"
    private const val CAPITAL_ROMAN = "(?=[IVX])X{0,3}(?:IX|IV|V?I{0,3})"

    /**
     * A bullet or a dash (or a symbol font's bullet, in the private use area), or a number, a letter
     * or a roman number up to 39 counting items: "1.", "1)", "(1)", "a)", "a.", "(a)", "A)", "iv.",
     * "(iv)", "IV.". A letter with a full stop only from a to h, and a capital one or "I." not at
     * all, as a line may start with an initial ("J. Smith") or an abbreviation ("p. 12").
     */
    private const val MARKER_PATTERN = "(?:[•◦▪▫‣⁃∙●○■□►▸▹➢➤✓✔✗✘·*\\-–\\uE000-\\uF8FF]|\\d{1,3}[.)]|\\(\\d{1,3}\\)" +
        "|[a-z]\\)|[a-h]\\.|\\([a-zA-Z]\\)|[A-Z]\\)|$ROMAN[.)]|\\((?:$ROMAN|$CAPITAL_ROMAN)\\)|(?=[IVX]{2})$CAPITAL_ROMAN[.)])"
    private val MARKER = Regex("^$MARKER_PATTERN(?:\\s|$)")
    private val MARKER_ONLY = Regex("^$MARKER_PATTERN$")

    /** A note's number or sign, as a footnote starts with… */
    private val NOTE_MARK = Regex("\\d{1,3}|[*†‡§]{1,3}")

    /** …set smaller than this share of the footnote's size. */
    internal const val NOTE_SIZE = 0.8f

    /**
     * [lines] of a page [pageWidth] × [pageHeight] points big, in any order, grouped into blocks,
     * in the reading order of their first lines (see [TextBlocks]). A block's [SourceBlock.text]
     * joins its lines with a space, or nothing between two characters of writing without spaces
     * (Chinese, Japanese, Thai...); a hyphen ending a line after a letter goes when the next line
     * starts with a small letter ("exam-" "ple": "example") and stays, with no space, before
     * anything else ("Jean-" "Pierre", "COVID-" "19"), and a soft hyphen always goes. Its
     * [SourceBlock.align] comes from how its lines line up: for several lines, where they agree (all
     * starts: [BlockAlign.START]; else all centres: [BlockAlign.CENTER]; else the lines after an
     * indented first: [BlockAlign.START]; else the ends: [BlockAlign.END]; lines all of one width
     * are centred only when centred on the page and not from margin to margin of its other running
     * text); for one line, [BlockAlign.CENTER] when its centre is within 2 % of the page's width of
     * the page's centre and it is narrower than 80 % of the page (and doesn't run from margin to
     * margin of the page's running text). Neither is centred when it starts where a block that isn't
     * centred starts: it only looks centred because the page's margins are alike. A single line is
     * [BlockAlign.END] when it ends within 3 % of the running
     * text's end margin and starts neither near its start margin nor where a column of it starts, or
     * when it ends where a line of one just above or below it ends and starts elsewhere (a total's
     * label over the next), else [BlockAlign.START]. Never throws for any numbers, NaN among them.
     */
    fun group(lines: List<SourceLine>, pageWidth: Float, pageHeight: Float): List<SourceBlock> {
        val usable = lines.mapNotNull { usable(it, pageWidth, pageHeight) }
        if (usable.isEmpty()) return emptyList()
        val spacing = spacing(usable)
        val blocks = ArrayList<Block>()
        for (row in rows(usable)) {
            var afterMarker = false
            var previous: Line? = null
            for (line in row) {
                val target = if (afterMarker || previous != null && notesBeside(previous, line) || MARKER.containsMatchIn(line.text)) {
                    null
                } else {
                    blocks.filter { it.open && continues(it, line, spacing, pageWidth) }.minWithOrNull(
                        compareBy<Block>({ line.bottom - it.last.bottom }, { abs(line.start(it.rtl) - it.last.start(it.rtl)) }),
                    )
                }
                val block = target?.also { it.add(line) } ?: Block(line).also { blocks += it }
                // A block doesn't go on past a line under it.
                for (other in blocks) if (other !== block && other.open && under(line, other)) other.open = false
                afterMarker = MARKER_ONLY.matches(line.text)
                previous = line
            }
        }
        return finish(blocks, pageWidth)
    }

    /**
     * Whether [mark], read just before [line] in its row, is a note's number (or *, †) set smaller
     * just before it, as a footnote starts: the footnote is a block of its own, not the next line of
     * the one above it.
     */
    private fun notesBeside(mark: Line, line: Line): Boolean {
        if (!NOTE_MARK.matches(mark.text) || mark.size >= NOTE_SIZE * line.size) return false
        val gap = if (line.rtl) mark.left - line.right else line.left - mark.right
        return gap <= line.size
    }

    /** A line as grouping works with it: its text trimmed, its box with left ≤ right and top ≤ bottom, a positive size. */
    private class Line(val source: SourceLine, val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float, val size: Float) {
        val bold: Boolean get() = source.bold
        val rtl: Boolean get() = source.rtl
        val width: Float get() = right - left

        /** Where the line starts and ends in the direction of writing [rtl]: right-to-left text is measured mirrored, so its start is the smaller number too. */
        fun start(rtl: Boolean): Float = if (rtl) -right else left
        fun end(rtl: Boolean): Float = if (rtl) -left else right
    }

    private fun usable(line: SourceLine, pageWidth: Float, pageHeight: Float): Line? {
        val text = line.text.trim()
        if (text.isEmpty()) return null
        if (!line.left.isFinite() || !line.top.isFinite() || !line.right.isFinite() || !line.bottom.isFinite()) return null
        val left = min(line.left, line.right)
        val right = max(line.left, line.right)
        val top = min(line.top, line.bottom)
        val bottom = max(line.top, line.bottom)
        // Wholly off the page, it isn't on the page's picture either.
        if (pageWidth.isFinite() && pageWidth > 0f && (right < 0f || left > pageWidth)) return null
        if (pageHeight.isFinite() && pageHeight > 0f && (bottom < 0f || top > pageHeight)) return null
        val size = when {
            line.size.isFinite() && line.size > 0f -> line.size
            bottom > top -> bottom - top
            else -> 1f
        }
        return Line(line, text, left, top, right, bottom, size)
    }

    /** A block being grouped. */
    private class Block(first: Line) {
        val lines = arrayListOf(first)
        var open = true
        val bold = first.bold
        var left = first.left
        var top = first.top
        var right = first.right
        var bottom = first.bottom
        var size = first.size
        var rtl = first.rtl
        val last: Line get() = lines.last()

        fun add(line: Line) {
            lines += line
            left = min(left, line.left)
            top = min(top, line.top)
            right = max(right, line.right)
            bottom = max(bottom, line.bottom)
            size = median(lines.map { it.size })
            // Written the way most of its letters are.
            val rtlLetters = lines.sumOf { if (it.rtl) it.text.length else 0 }
            val ltrLetters = lines.sumOf { if (it.rtl) 0 else it.text.length }
            rtl = if (rtlLetters != ltrLetters) rtlLetters > ltrLetters else lines[0].rtl
        }

        /** How far its lines reach towards their end, in the direction [rtl]. */
        fun end(rtl: Boolean): Float = if (rtl) -left else right

        /**
         * The start the lines after its first share, in the direction [rtl], once there are two of
         * them; null while there aren't, when they don't share one, or when the block is centred
         * (its lines' centres agree and its first line starts elsewhere).
         */
        fun margin(rtl: Boolean): Float? {
            if (lines.size < 3) return null
            val starts = lines.drop(1).map { it.start(rtl) }
            if (spread(starts) > SAME_EDGE_EM * size) return null
            val margin = median(starts)
            val centres = lines.map { (it.start(rtl) + it.end(rtl)) / 2 }
            if (spread(centres) <= ALIGNED_CENTRES_EM * size && abs(lines[0].start(rtl) - margin) > START_EM * size) return null
            return margin
        }
    }

    /**
     * The most a paragraph's lines may be apart, bottom to bottom and box to box, in font sizes:
     * [MAX_STEP_EM] and [MAX_GAP_EM], or more on a page whose running text is spaced wider (see
     * [SPACING_SLACK]), measured from the step between each long line and the nearest line below it.
     */
    private class Spacing(val maxStep: Float, val maxGap: Float)

    private fun spacing(lines: List<Line>): Spacing {
        val steps = ArrayList<Float>()
        for (a in lines) {
            if (a.width < LONG_LINE_EM * a.size) continue
            var nearest = Float.MAX_VALUE
            for (b in lines) {
                if (b === a || !sameSize(a.size, b.size)) continue
                val step = b.bottom - a.bottom
                if (step < MIN_STEP_EM * a.size || step >= nearest) continue
                if (overlaps(a.left, a.right, b.left, b.right)) nearest = step
            }
            if (nearest != Float.MAX_VALUE) steps += nearest / a.size
        }
        if (steps.size < MIN_SPACING_SAMPLES) return Spacing(MAX_STEP_EM, MAX_GAP_EM)
        val maxStep = max(MAX_STEP_EM, min(commonest(steps) * SPACING_SLACK, MAX_SPACED_STEP_EM))
        return Spacing(maxStep, MAX_GAP_EM + (maxStep - MAX_STEP_EM))
    }

    /**
     * The step most of [steps] are near (within [SAME_STEP] of it; the smaller of two as common):
     * the step between a paragraph's lines. Not their median, which on a page of short paragraphs
     * is the wider step between two of them, as often as not.
     */
    private fun commonest(steps: List<Float>): Float =
        steps.sorted().maxWith(compareBy<Float> { s -> steps.count { abs(it - s) <= SAME_STEP * s } }.thenByDescending { it })

    /** [lines] in rows, top to bottom, each row in reading order. Ties are broken by everything a line has, so the order never depends on the order given. */
    private fun rows(lines: List<Line>): List<List<Line>> {
        val sorted = lines.sortedWith(
            compareBy<Line>({ it.top }, { it.left }, { it.bottom }, { it.right }, { it.size }, { it.text }, { it.bold }, { it.rtl }),
        )
        val rows = ArrayList<List<Line>>()
        var row = ArrayList<Line>()
        for (line in sorted) {
            val first = row.firstOrNull()
            if (first != null && line.top - first.top > ROW_EM * max(first.size, line.size)) {
                rows += across(row)
                row = ArrayList()
            }
            row += line
        }
        rows += across(row)
        return rows
    }

    /** A row's lines in reading order: left to right, or right to left when most of them are right-to-left text. */
    private fun across(row: List<Line>): List<Line> =
        if (row.count { it.rtl } * 2 > row.size) row.sortedByDescending { it.right } else row.sortedBy { it.left }

    /** Whether [line] goes on [block] as its next line (see [TextBlocks]). */
    private fun continues(block: Block, line: Line, spacing: Spacing, pageWidth: Float): Boolean {
        val prev = block.last
        val em = block.size
        if (!sameSize(em, line.size) || line.bold != block.bold) return false
        val step = line.bottom - prev.bottom
        if (step < MIN_STEP_EM * em || step > spacing.maxStep * em) return false
        if (line.top - prev.bottom > spacing.maxGap * em) return false
        val rtl = block.rtl
        val prevStart = prev.start(rtl)
        val prevEnd = prev.end(rtl)
        val start = line.start(rtl)
        val end = line.end(rtl)
        if (!overlaps(prevStart, prevEnd, start, end)) return false
        val shift = start - prevStart
        val startsAlike = abs(shift) <= START_EM * em || block.lines.size == 1 && abs(shift) <= FIRST_LINE_INDENT_EM * em
        val centresAlike = abs((start + end) / 2 - (prevStart + prevEnd) / 2) <= CENTRE_EM * em
        val endsAlike = abs(end - prevEnd) <= END_EM * em
        if (!startsAlike && !centresAlike && !endsAlike) return false
        // Indented (or outdented) from the margin the block's lines share: the next paragraph.
        val margin = block.margin(rtl)
        if (margin != null && abs(start - margin) > NEW_PARAGRAPH_INDENT_EM * em) return false
        // A line narrower than running text over one starting with a capital or a digit: the lines of
        // an address or of labels, each an entry of its own ("Mrs Sophie Durand" over "Purchasing
        // Department", "Invoice no. 12" over "Date: 3 May"), however they are aligned.
        if (prevEnd - prevStart < LONG_LINE_EM * em && startsAnEntry(line.text)) return false
        // Centred text: the centres agree and the starts don't, or both lines are centred on the page.
        val centred = centresAlike && (abs(shift) > SAME_EDGE_EM * em || centredOnPage(prev.left, prev.right, pageWidth) && centredOnPage(line.left, line.right, pageWidth))
        if (startsAlike && !centred) {
            // A short line ends its paragraph: a list's or a table's entry, or a paragraph's last line.
            if (prevEnd - prevStart < MIN_PARAGRAPH_LINE_EM * em) return false
            val reach = max(block.end(rtl), end)
            if (reach - prevEnd > max(SHORT_LINE_EM * em, FIRST_WORD_SLACK * firstWordWidth(line))) return false
        }
        return true
    }

    /** Whether [text] starts with a capital letter or a digit, as an entry of an address or a list of labels does. */
    private fun startsAnEntry(text: String): Boolean {
        if (text.isEmpty()) return false
        val first = text.codePointAt(0)
        return Character.isUpperCase(first) || Character.isTitleCase(first) || Character.isDigit(first)
    }

    /** About how wide [line]'s first word is with the space after it, from the line's width and letters: a Chinese or Japanese character, or a Thai one, counts as a word. */
    private fun firstWordWidth(line: Line): Float {
        val text = line.text
        if (text.isEmpty()) return 0f
        val first = text.codePointAt(0)
        val letters = if (WordSpacing.unspaced(first)) Character.charCount(first) else text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
        return line.width * (letters + 1) / text.length
    }

    /** Whether [line] is below [block]'s last line (the next row down or further) and overlaps it sideways. */
    private fun under(line: Line, block: Block): Boolean =
        line.bottom - block.last.bottom >= MIN_STEP_EM * block.size && overlaps(line.left, line.right, block.left, block.right)

    /** Whether two spans overlap by at least [MIN_OVERLAP] of the narrower. */
    private fun overlaps(aStart: Float, aEnd: Float, bStart: Float, bEnd: Float): Boolean =
        min(aEnd, bEnd) - max(aStart, bStart) >= MIN_OVERLAP * min(aEnd - aStart, bEnd - bStart)

    private fun sameSize(a: Float, b: Float): Boolean = max(a, b) <= min(a, b) * (1 + SIZE_TOLERANCE)

    private fun centredOnPage(left: Float, right: Float, pageWidth: Float): Boolean = pageWidth.isFinite() && pageWidth > 0f &&
        abs((left + right) / 2 - pageWidth / 2) <= CENTRED_SHARE * pageWidth && right - left < MAX_CENTRED_WIDTH * pageWidth

    /** The page's running text: its blocks of several lines that are left-aligned, and the margins they keep. */
    private class Body(val blocks: List<Block>) {
        val left = blocks.minOf { it.left }
        val right = blocks.maxOf { it.right }
    }

    private fun Body?.fromMarginToMargin(left: Float, right: Float, pageWidth: Float): Boolean =
        this != null && abs(left - this.left) <= NEAR_EDGE_SHARE * pageWidth && abs(right - this.right) <= NEAR_EDGE_SHARE * pageWidth

    private fun finish(blocks: List<Block>, pageWidth: Float): List<SourceBlock> {
        // Blocks of several lines first, from how their lines line up; null for lines all of one
        // width, which are left-aligned or centred, as the page tells.
        val linedUp = HashMap<Block, BlockAlign?>()
        for (block in blocks) if (block.lines.size > 1) linedUp[block] = lineUp(block)
        val running = blocks.filter { it.lines.size > 1 && (linedUp[it] ?: BlockAlign.START) == BlockAlign.START }
        val align = HashMap<Block, BlockAlign>()
        for (block in blocks) {
            if (block.lines.size < 2) continue
            align[block] = linedUp[block] ?: run {
                val others = running.filter { it !== block }
                val body = if (others.isEmpty()) null else Body(others)
                val centred = centredOnPage(block.left, block.right, pageWidth) && !body.fromMarginToMargin(block.left, block.right, pageWidth)
                if (centred && !startsWithAnother(block, blocks, pageWidth)) BlockAlign.CENTER else BlockAlign.START
            }
        }
        // Then single lines, against the margins and columns of the running text.
        val bodyBlocks = blocks.filter { align[it] == BlockAlign.START }
        val body = if (bodyBlocks.isEmpty()) null else Body(bodyBlocks)
        for (block in blocks) if (block.lines.size == 1) align[block] = single(block, body, blocks, pageWidth)
        return blocks.map { block ->
            SourceBlock(
                block.lines.map { it.source }, text(block.lines), block.left, block.top, block.right, block.bottom,
                block.size, block.bold, block.rtl, align.getValue(block),
            )
        }
    }

    /** How the lines of a block of several lines line up; null when they are all of one width (see [finish]). */
    private fun lineUp(block: Block): BlockAlign? {
        val rtl = block.rtl
        val lines = block.lines
        val tolerance = ALIGNED_EDGES_EM * block.size
        val starts = lines.map { it.start(rtl) }
        val ends = lines.map { it.end(rtl) }
        val startsAgree = spread(starts) <= tolerance
        if (startsAgree && spread(ends) <= tolerance) return null
        if (startsAgree) return BlockAlign.START
        if (spread(lines.map { (it.start(rtl) + it.end(rtl)) / 2 }) <= ALIGNED_CENTRES_EM * block.size) return BlockAlign.CENTER
        // An indented (or hanging) first line, the others lined up.
        if (lines.size >= 3 && spread(starts.drop(1)) <= tolerance) return BlockAlign.START
        // A paragraph's last line is short; right-aligned lines end together all the same.
        if (spread(if (lines.size >= 3) ends.dropLast(1) else ends) <= tolerance) {
            // Two lines ending together, the first starting a little later: an indented paragraph more likely than right-aligned text.
            val indent = starts[0] - starts[1]
            return if (lines.size == 2 && indent > 0f && indent <= FIRST_LINE_INDENT_EM * block.size) BlockAlign.START else BlockAlign.END
        }
        return BlockAlign.START
    }

    /**
     * Whether [block] starts where another of [blocks] starts (within [ALIGNED_EDGES_EM] font
     * sizes), one that isn't centred on the page: then it is lined up with it, and only looks centred
     * because the page's margins are alike, as a long line or a justified paragraph does.
     */
    private fun startsWithAnother(block: Block, blocks: List<Block>, pageWidth: Float): Boolean = blocks.any { other ->
        other !== block && !centredOnPage(other.left, other.right, pageWidth) &&
            (if (block.rtl) abs(other.right - block.right) else abs(other.left - block.left)) <= ALIGNED_EDGES_EM * block.size
    }

    /**
     * Whether [block], one line, ends where a line just above or below it ends (within
     * [SAME_EDGE_EM] font sizes) and starts elsewhere (more than twice that apart): lines of their
     * own right-aligned together, wherever on the page, as a total's labels beside their amounts are.
     */
    private fun endsWithTheLineBeside(block: Block, blocks: List<Block>): Boolean {
        val rtl = block.rtl
        val em = block.size
        val start = if (rtl) -block.right else block.left
        return blocks.any { other ->
            if (other === block || other.lines.size != 1 || other.rtl != rtl || !sameSize(em, other.size)) return@any false
            val step = abs(other.bottom - block.bottom)
            val otherStart = if (rtl) -other.right else other.left
            step >= MIN_STEP_EM * em && step <= MAX_STEP_EM * em && overlaps(block.left, block.right, other.left, other.right) &&
                abs(other.end(rtl) - block.end(rtl)) <= SAME_EDGE_EM * em && abs(otherStart - start) > 2 * SAME_EDGE_EM * em
        }
    }

    /** How a block of one line is aligned, measured against the page, its running text [body] and its other [blocks] (see [group]). */
    private fun single(block: Block, body: Body?, blocks: List<Block>, pageWidth: Float): BlockAlign {
        val left = block.left
        val right = block.right
        if (centredOnPage(left, right, pageWidth) && !body.fromMarginToMargin(left, right, pageWidth) && !startsWithAnother(block, blocks, pageWidth)) {
            return BlockAlign.CENTER
        }
        if (endsWithTheLineBeside(block, blocks)) return BlockAlign.END
        if (body == null || !pageWidth.isFinite() || pageWidth <= 0f) return BlockAlign.START
        val near = NEAR_EDGE_SHARE * pageWidth
        val atEnd = if (block.rtl) abs(left - body.left) <= near else abs(right - body.right) <= near
        val awayFromStart = if (block.rtl) body.right - right > near else left - body.left > near
        if (!atEnd || !awayFromStart) return BlockAlign.START
        // A line starting where a column of the running text starts is left-aligned in that column.
        val inColumn = body.blocks.any {
            overlaps(left, right, it.left, it.right) && (if (block.rtl) abs(right - it.right) else abs(left - it.left)) <= near
        }
        return if (inColumn) BlockAlign.START else BlockAlign.END
    }

    /** The text of [lines], joined as [group] says. */
    private fun text(lines: List<Line>): String {
        val out = StringBuilder()
        for (line in lines) {
            val text = line.text
            if (out.isNotEmpty()) {
                val last = out.codePointBefore(out.length)
                val next = text.codePointAt(0)
                val hyphen = (last == '-'.code || last == '‐'.code) && out.length >= 2 && Character.isLetterOrDigit(out.codePointBefore(out.length - 1))
                when {
                    // A word broken at a soft hyphen.
                    last == SOFT_HYPHEN -> out.setLength(out.length - 1)
                    // "exam-" "ple": a word broken at the line's end; "Jean-" "Pierre" and "COVID-" "19" keep their hyphen.
                    hyphen -> if (Character.isLowerCase(next) && Character.isLetter(out.codePointBefore(out.length - 1))) out.setLength(out.length - 1)
                    else -> out.append(WordSpacing.between(last, next))
                }
            }
            out.append(text)
        }
        return out.toString()
    }

    private fun spread(values: List<Float>): Float = values.max() - values.min()

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}
