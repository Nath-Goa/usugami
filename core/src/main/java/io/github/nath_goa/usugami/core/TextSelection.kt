package io.github.nath_goa.usugami.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A glyph of a page's text as the reader selects it, in reading order: what it reads as ([text],
 * never empty), where it is on the page as shown ([box], points from its top-left corner), the
 * [line] it is on (lines are numbered in reading order, and a line's glyphs come one after another),
 * and whether a word starts with it ([wordStart]; the first glyph of a line always does).
 */
class TextGlyph(val text: String, val box: GlyphBox, val line: Int, val wordStart: Boolean)

/** A page's text for selecting: its glyphs in reading order. */
class PageGlyphs(val glyphs: List<TextGlyph>) {
    val isEmpty: Boolean get() = glyphs.isEmpty()
}

/** A glyph of a document: glyph [glyph] of page [page], both zero-based. */
data class TextPlace(val page: Int, val glyph: Int) : Comparable<TextPlace> {
    override fun compareTo(other: TextPlace): Int = if (page != other.page) page.compareTo(other.page) else glyph.compareTo(other.glyph)
}

/**
 * The reader's text selection, worked out on a page's glyphs (PageGlyphs), in points on the page as
 * shown. A **word** is a run of glyphs on one line from one that starts a word to before the next;
 * a Chinese character, a hiragana or a katakana is a word by itself, as that writing has no spaces.
 * A line may run across the page as shown or down it (writing down the page, or text on a page
 * turned a quarter): everything here measures in both directions.
 */
object TextSelection {
    /**
     * The glyphs of the word at ([x], [y]): the glyph there, or else the nearest within [reach]
     * points of it, and the rest of its word. Null where there is none: blank paper, a page without text.
     */
    fun wordAt(page: PageGlyphs, x: Float, y: Float, reach: Float): IntRange? {
        val glyphs = page.glyphs
        var best = -1
        var bestDistance = Float.MAX_VALUE
        var bestCentre = Float.MAX_VALUE
        for ((i, glyph) in glyphs.withIndex()) {
            val distance = distance(glyph.box, x, y)
            val centre = centreDistance(glyph.box, x, y)
            if (distance < bestDistance || distance == bestDistance && centre < bestCentre) {
                best = i
                bestDistance = distance
                bestCentre = centre
            }
        }
        if (best < 0 || bestDistance > reach) return null
        return wordOf(glyphs, best)
    }

    /** The word glyph [index] of [glyphs] is in. */
    private fun wordOf(glyphs: List<TextGlyph>, index: Int): IntRange {
        if (alone(glyphs[index])) return index..index
        val line = glyphs[index].line
        var first = index
        while (first > 0 && !glyphs[first].wordStart && glyphs[first - 1].line == line && !alone(glyphs[first - 1])) first--
        var last = index
        while (last + 1 < glyphs.size && glyphs[last + 1].line == line && !glyphs[last + 1].wordStart && !alone(glyphs[last + 1])) last++
        return first..last
    }

    /** Whether [glyph] is a word by itself: a Chinese character, a hiragana or a katakana. */
    private fun alone(glyph: TextGlyph): Boolean {
        if (glyph.text.isEmpty()) return false
        val codePoint = glyph.text.codePointAt(0)
        if (Character.isIdeographic(codePoint)) return true
        val script = Character.UnicodeScript.of(codePoint)
        return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
    }

    /**
     * The glyph a handle held at ([x], [y]) stands on: on the line nearest to it, the glyph nearest
     * along that line (where two are as near, the one whose middle is nearer). The nearest line is,
     * of the lines level with the point (a line across the page from its top to its bottom, one down
     * the page from its left to its right side), the one nearest along it: so a handle beside the end
     * of a short line stands on that line's last glyph, and one in a column on that column's line.
     * Level with none (between two lines, above or below the text), it is the line nearest across,
     * then along. Null on a page without text.
     */
    fun glyphNear(page: PageGlyphs, x: Float, y: Float): Int? {
        val glyphs = page.glyphs
        if (glyphs.isEmpty()) return null
        // The nearest line: its glyphs from first to last, which come one after another.
        var lineFirst = 0
        var lineLast = 0
        var bestLevel = false
        var bestAcross = Float.MAX_VALUE
        var bestAlong = Float.MAX_VALUE
        var start = 0
        while (start < glyphs.size) {
            var end = start
            while (end + 1 < glyphs.size && glyphs[end + 1].line == glyphs[start].line) end++
            val box = union(glyphs, start..end)
            // Across the line (up and down for a line across the page, sideways for one down it), and along it.
            val across = if (box.across) gapTo(y, box.top, box.bottom) else gapTo(x, box.left, box.right)
            val along = if (box.across) gapTo(x, box.left, box.right) else gapTo(y, box.top, box.bottom)
            val level = across == 0f
            val nearer = when {
                level != bestLevel -> level
                level -> along < bestAlong
                else -> across < bestAcross || across == bestAcross && along < bestAlong
            }
            if (nearer) {
                lineFirst = start
                lineLast = end
                bestLevel = level
                bestAcross = across
                bestAlong = along
            }
            start = end + 1
        }
        var best = lineFirst
        var bestGlyph = Float.MAX_VALUE
        var bestCentre = Float.MAX_VALUE
        for (i in lineFirst..lineLast) {
            val distance = distance(glyphs[i].box, x, y)
            val centre = centreDistance(glyphs[i].box, x, y)
            if (distance < bestGlyph || distance == bestGlyph && centre < bestCentre) {
                best = i
                bestGlyph = distance
                bestCentre = centre
            }
        }
        return best
    }

    /**
     * The glyphs of [page] (which has [count]) between [from] and [to], both included, in either
     * order: from the first's glyph on its page, whole on the pages between, up to the last's glyph on
     * its page. Null when the page is outside them, or has no glyphs there.
     */
    fun onPage(from: TextPlace, to: TextPlace, page: Int, count: Int): IntRange? {
        val first = minOf(from, to)
        val last = maxOf(from, to)
        if (count <= 0 || page < first.page || page > last.page) return null
        val start = (if (page == first.page) first.glyph else 0).coerceAtLeast(0)
        val end = (if (page == last.page) last.glyph else count - 1).coerceAtMost(count - 1)
        return if (start <= end) start..end else null
    }

    /** One box for each line of [glyphs] (of [page]): the smallest around that line's glyphs among them. */
    fun lineBoxes(page: PageGlyphs, glyphs: IntRange): List<GlyphBox> {
        val all = page.glyphs
        val first = glyphs.first.coerceAtLeast(0)
        val last = glyphs.last.coerceAtMost(all.size - 1)
        val boxes = ArrayList<GlyphBox>()
        var start = first
        while (start <= last) {
            var end = start
            while (end + 1 <= last && all[end + 1].line == all[start].line) end++
            boxes += union(all, start..end)
            start = end + 1
        }
        return boxes
    }

    /**
     * The text of [glyphs] (of [page]), as PDF to images or text writes it: before a glyph that starts
     * a word, ExportText.gap of the characters on either side (a space, or nothing between two Chinese
     * or Japanese characters); a line break before one that starts a line; each line through
     * ExportText.clean (ligatures and presentation forms as their letters, control characters
     * dropped), and a line left with nothing left out.
     */
    fun text(page: PageGlyphs, glyphs: IntRange): String {
        val all = page.glyphs
        val out = StringBuilder()
        val line = StringBuilder()
        fun endLine() {
            val cleaned = ExportText.clean(line.toString())
            line.setLength(0)
            if (cleaned.isEmpty()) return
            if (out.isNotEmpty()) out.append('\n')
            out.append(cleaned)
        }
        var lineNumber: Int? = null
        for (i in glyphs.first.coerceAtLeast(0)..glyphs.last.coerceAtMost(all.size - 1)) {
            val glyph = all[i]
            if (glyph.text.isEmpty()) continue
            if (lineNumber != null && glyph.line != lineNumber) {
                endLine()
            } else if (glyph.wordStart && line.isNotEmpty()) {
                line.append(ExportText.gap(line.codePointBefore(line.length), glyph.text.codePointAt(0)))
            }
            line.append(glyph.text)
            lineNumber = glyph.line
        }
        endLine()
        return out.toString()
    }

    /**
     * Where the start handle hangs, in points: under the outer end of the selection's first glyph
     * [glyph], its left end in text that runs left to right, its right end in text that runs right to
     * left (on its line the next glyph lies to the left), its upper end in a line down the page.
     */
    fun startHandle(page: PageGlyphs, glyph: Int): Pair<Float, Float> {
        val box = page.glyphs[glyph].box
        return when (direction(page.glyphs, glyph)) {
            Direction.RIGHT -> box.left to box.bottom
            Direction.LEFT -> box.right to box.bottom
            Direction.DOWN -> (box.left + box.right) / 2f to box.top
            Direction.UP -> (box.left + box.right) / 2f to box.bottom
        }
    }

    /** Where the end handle hangs, in points: under the outer end of the selection's last glyph [glyph] (see startHandle). */
    fun endHandle(page: PageGlyphs, glyph: Int): Pair<Float, Float> {
        val box = page.glyphs[glyph].box
        return when (direction(page.glyphs, glyph)) {
            Direction.RIGHT -> box.right to box.bottom
            Direction.LEFT -> box.left to box.bottom
            Direction.DOWN -> (box.left + box.right) / 2f to box.bottom
            Direction.UP -> (box.left + box.right) / 2f to box.top
        }
    }

    /** Which way a line runs on the page as shown. */
    private enum class Direction { RIGHT, LEFT, DOWN, UP }

    /**
     * Which way the line of glyph [index] runs, from where the next glyph on it lies (or the one
     * before it); a glyph alone on its line runs as its writing does (right to left for Hebrew and
     * Arabic), or down for a line down the page.
     */
    private fun direction(glyphs: List<TextGlyph>, index: Int): Direction {
        val glyph = glyphs[index]
        val next = glyphs.getOrNull(index + 1)?.takeIf { it.line == glyph.line }
        val before = glyphs.getOrNull(index - 1)?.takeIf { it.line == glyph.line }
        val (from, to) = when {
            next != null -> glyph.box to next.box
            before != null -> before.box to glyph.box
            else -> null to null
        }
        if (from == null || to == null) {
            if (!glyph.box.across) return Direction.DOWN
            val direction = if (glyph.text.isEmpty()) Character.DIRECTIONALITY_LEFT_TO_RIGHT else Character.getDirectionality(glyph.text.codePointAt(0))
            return if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) Direction.LEFT else Direction.RIGHT
        }
        return if (glyph.box.across) {
            if ((to.left + to.right) < (from.left + from.right)) Direction.LEFT else Direction.RIGHT
        } else {
            if ((to.top + to.bottom) < (from.top + from.bottom)) Direction.UP else Direction.DOWN
        }
    }

    /** The smallest box around [glyphs] (indices into [all]), running as the first of them does. */
    private fun union(all: List<TextGlyph>, glyphs: IntRange): GlyphBox {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in glyphs) {
            val box = all[i].box
            left = min(left, box.left)
            top = min(top, box.top)
            right = max(right, box.right)
            bottom = max(bottom, box.bottom)
        }
        return GlyphBox(left, top, right, bottom, all[glyphs.first].box.across)
    }

    /** How far [value] is from the span [low] to [high]: 0 within it. */
    private fun gapTo(value: Float, low: Float, high: Float): Float = max(0f, max(low - value, value - high))

    /** How far ([x], [y]) is from [box]: 0 inside it. */
    private fun distance(box: GlyphBox, x: Float, y: Float): Float {
        val dx = max(0f, max(box.left - x, x - box.right))
        val dy = max(0f, max(box.top - y, y - box.bottom))
        return sqrt(dx * dx + dy * dy)
    }

    /** How far ([x], [y]) is from the middle of [box]. */
    private fun centreDistance(box: GlyphBox, x: Float, y: Float): Float {
        val dx = x - (box.left + box.right) / 2f
        val dy = y - (box.top + box.bottom) / 2f
        return sqrt(dx * dx + dy * dy)
    }
}
