package io.github.nath_goa.usugami.core

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * A page as a viewer shows it: its crop box ([left], [bottom], [width], [height], in the page's own
 * coordinates) turned clockwise by [rotation], the page's /Rotate.
 *
 * Page numbers and watermarks are laid out on the shown page, whose origin is its bottom-left corner
 * as shown, with y up. [matrix] takes those points to the page's own coordinates, so a number put at
 * the shown bottom of a page turned sideways still ends up at its bottom.
 */
class PageFrame(val left: Float, val bottom: Float, val width: Float, val height: Float, rotation: Int) {
    /** 0, 90, 180 or 270. */
    val rotation: Int = Math.floorMod(rotation, 360).also { require(it % 90 == 0) { "Rotation must be a multiple of 90, not $rotation" } }

    private val sideways get() = rotation == 90 || rotation == 270
    val shownWidth: Float get() = if (sideways) height else width
    val shownHeight: Float get() = if (sideways) width else height

    /** a, b, c, d, e, f as in a PDF `cm` operator: page x = a·x + c·y + e and page y = b·x + d·y + f. */
    val matrix: FloatArray
        get() = when (rotation) {
            0 -> floatArrayOf(1f, 0f, 0f, 1f, left, bottom)
            // The shown bottom-left corner is the box's bottom-right corner, and shown x runs up the page.
            90 -> floatArrayOf(0f, 1f, -1f, 0f, left + width, bottom)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f, left + width, bottom + height)
            // The shown bottom-left corner is the box's top-left corner, and shown x runs down the page.
            else -> floatArrayOf(0f, -1f, 1f, 0f, left, bottom + height)
        }

    /** The page's own coordinates of the shown point ([x], [y]). */
    fun toPage(x: Float, y: Float): Pair<Float, Float> {
        val m = matrix
        return (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])
    }

    /** The shown point (origin at its bottom-left corner, y up) at the page's own coordinates ([x], [y]). */
    fun toShown(x: Float, y: Float): Pair<Float, Float> {
        val dx = x - left
        val dy = y - bottom
        return when (rotation) {
            0 -> dx to dy
            90 -> dy to width - dx
            180 -> width - dx to height - dy
            else -> height - dy to dx
        }
    }
}

/** Where page numbers go on the shown page. */
enum class PageNumberPosition(val top: Boolean, val column: Int) {
    TOP_LEFT(true, 0), TOP_CENTER(true, 1), TOP_RIGHT(true, 2),
    BOTTOM_LEFT(false, 0), BOTTOM_CENTER(false, 1), BOTTOM_RIGHT(false, 2),
}

/** Font size of page numbers on A4 or Letter paper, in points; on other pages it scales (see [PageNumbers.fontSize]). */
enum class PageNumberSize(val points: Float) { SMALL(9f), MEDIUM(12f), LARGE(16f) }

object PageNumbers {
    /**
     * The pages, zero-based, that get a number: all but those in [skip], written like "1" or
     * "1-2,5" (blank skips none). Throws [PageSelectionException] for input it can't use.
     *
     * Each page shows its own page number, so skipping a cover leaves page 2 numbered "2": the
     * numbers match the ones the user typed and the ones PDF viewers show.
     */
    fun numbered(skip: String, pageCount: Int): List<Int> {
        require(pageCount > 0)
        if (skip.isBlank()) return (0 until pageCount).toList()
        val skipped = PdfPageSelection.selected(skip, pageCount).toSet()
        return (0 until pageCount).filterNot { it in skipped }
    }

    /**
     * The pages to number in each of several PDFs of [pageCounts] pages, all skipping the pages in
     * [skip], as [numbered] does for one. The pages to skip are read against the longest PDF: "4" is
     * fine while one of them has a page 4, and a shorter one has no page 4 to skip. A PDF with every
     * page skipped has none to number. Throws [PageSelectionException] for input it can't use.
     */
    fun numberedEach(skip: String, pageCounts: List<Int>): List<List<Int>> {
        require(pageCounts.isNotEmpty() && pageCounts.all { it > 0 })
        val longest = numbered(skip, pageCounts.max())
        return pageCounts.map { count -> longest.filter { it < count } }
    }

    /**
     * The shorter side of the paper the sizes and margin are for, in points: about A4's and
     * Letter's (595 and 612), which look as they did before numbers scaled with the page.
     */
    private const val PAPER_SIDE = 600f

    /** The margin on that paper: half an inch. */
    private const val PAPER_MARGIN = 36f

    /**
     * How much larger than on A4 or Letter paper a number and its margin are on a page shown
     * [shownWidth] × [shownHeight] points: in proportion to its shorter side, so a number on a
     * small label is as small beside it, and one on a poster as large.
     */
    fun scale(shownWidth: Float, shownHeight: Float): Float = min(shownWidth, shownHeight) / PAPER_SIDE

    /** The number's font size on a page shown [shownWidth] × [shownHeight] points (see [scale]). */
    fun fontSize(size: PageNumberSize, shownWidth: Float, shownHeight: Float): Float = size.points * scale(shownWidth, shownHeight)

    /** Distance from the shown page's edges to the number: half an inch on A4 or Letter paper (see [scale]). */
    fun margin(shownWidth: Float, shownHeight: Float): Float = PAPER_MARGIN * scale(shownWidth, shownHeight)

    /**
     * Where the number's baseline starts on the shown page. [textWidth] is the number's width and
     * [textHeight] the height of its digits above the baseline. A number at the bottom sits on the
     * margin; one at the top hangs from it.
     */
    fun origin(position: PageNumberPosition, shownWidth: Float, shownHeight: Float, textWidth: Float, textHeight: Float): Pair<Float, Float> {
        val margin = margin(shownWidth, shownHeight)
        val x = when (position.column) {
            0 -> margin
            1 -> (shownWidth - textWidth) / 2
            else -> shownWidth - margin - textWidth
        }
        val y = if (position.top) shownHeight - margin - textHeight else margin
        return x to y
    }
}

object WatermarkLayout {
    /** Along the diagonal, the text spans at most this share of it. */
    const val MAX_LENGTH = 0.7f

    /** The text is at most this share of the page's shorter side tall, so a short word isn't huge. */
    const val MAX_HEIGHT = 0.18f

    /**
     * How to draw a stamp of [stampWidth] × [stampHeight] on the shown page: centred on
     * ([centerX], [centerY]), turned anticlockwise by [degrees] and scaled by [scale].
     */
    data class Placement(val centerX: Float, val centerY: Float, val degrees: Float, val scale: Float)

    /** Centred, along the diagonal from the shown bottom-left corner to the top-right one. */
    fun place(shownWidth: Float, shownHeight: Float, stampWidth: Float, stampHeight: Float): Placement {
        require(shownWidth > 0 && shownHeight > 0 && stampWidth > 0 && stampHeight > 0)
        val diagonal = hypot(shownWidth, shownHeight)
        val scale = min(MAX_LENGTH * diagonal / stampWidth, MAX_HEIGHT * min(shownWidth, shownHeight) / stampHeight)
        val degrees = Math.toDegrees(atan2(shownHeight, shownWidth).toDouble()).toFloat()
        return Placement(shownWidth / 2, shownHeight / 2, degrees, scale)
    }
}
