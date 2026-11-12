package io.github.nath_goa.usugami.core

import kotlin.math.abs
import kotlin.math.min

/** A sheet [width] × [height] points with [columns] × [rows] cells. */
data class SheetGrid(val columns: Int, val rows: Int, val width: Float, val height: Float)

/**
 * Where a page lands on its sheet: its lower-left corner ([x], [y]) from the sheet's lower-left
 * corner, its size there, and [scale]: points on the sheet for a point of the page.
 */
data class SheetSlot(val x: Float, val y: Float, val width: Float, val height: Float, val scale: Float)

/**
 * The geometry of Page layout's Pages per sheet: which way round the sheet goes and how its cells
 * are cut, where each page lands in its cell, and the matrix that puts it there as it is shown.
 */
object SheetLayout {
    val COUNTS = listOf(2, 4, 6, 9)
    val PAPERS = listOf(PageSize.A4, PageSize.LETTER)

    /** Points round the sheet (1/4 inch, printers' unprintable edge). */
    const val MARGIN = 18f

    /** Points between two cells (1/8 inch). */
    const val GAP = 9f

    /** The border round each page, when it is on: its width in points and its gray (0 black, 1 white). */
    const val BORDER_WIDTH = 0.5f
    const val BORDER_GRAY = 0.5f

    /** How close to US Letter, in points, a page is still taken for it. */
    private const val LETTER_SLACK = 2f

    /** By how much a later candidate grid must beat an earlier one to be chosen (0.01%). */
    private const val BETTER_BY = 1e-4

    /** LETTER when a page [width] × [height] is US Letter (either way round, within 2 points), else A4. */
    fun paperLike(width: Float, height: Float): PageSize {
        val letterWidth = PageSize.LETTER.shortSide.toFloat()
        val letterHeight = PageSize.LETTER.longSide.toFloat()
        fun near(a: Float, b: Float) = abs(a - b) <= LETTER_SLACK
        val letter = (near(width, letterWidth) && near(height, letterHeight)) ||
            (near(width, letterHeight) && near(height, letterWidth))
        return if (letter) PageSize.LETTER else PageSize.A4
    }

    /**
     * The grid that lets [pages] (their shown sizes, width to height) come out biggest: of the
     * candidates (a portrait sheet with no more columns than rows, a landscape sheet with no more
     * rows than columns, a portrait sheet with more columns than rows, a landscape sheet with more
     * rows than columns), the first whose total area of pages drawn is not beaten by more than
     * 0.01%. The whole PDF gets one grid, so a cover the other way round doesn't turn the sheets.
     */
    fun grid(perSheet: Int, paper: PageSize, pages: List<Pair<Float, Float>>): SheetGrid {
        // The cells as (fewer, more): columns × rows on a portrait sheet, rows × columns on a landscape one.
        val (fewer, more) = when (perSheet) {
            2 -> 1 to 2
            4 -> 2 to 2
            6 -> 2 to 3
            9 -> 3 to 3
            else -> throw IllegalArgumentException("Pages per sheet must be one of $COUNTS, not $perSheet")
        }
        require(paper.shortSide > 0.0) { "A sheet needs a paper size, not $paper" }
        val short = paper.shortSide.toFloat()
        val long = paper.longSide.toFloat()
        val candidates = buildList {
            add(SheetGrid(fewer, more, short, long))
            add(SheetGrid(more, fewer, long, short))
            if (fewer != more) {
                add(SheetGrid(more, fewer, short, long))
                add(SheetGrid(fewer, more, long, short))
            }
        }
        val scores = candidates.map { grid ->
            pages.sumOf { (w, h) -> slot(grid, 0, w, h)?.let { it.width.toDouble() * it.height } ?: 0.0 }
        }
        val best = scores.max()
        return candidates[scores.indexOfFirst { it >= best * (1 - BETTER_BY) }]
    }

    /** How many sheets [pageCount] pages make at [perSheet] a sheet, the last one perhaps not full. */
    fun sheets(pageCount: Int, perSheet: Int): Int {
        require(perSheet > 0 && pageCount >= 0)
        return (pageCount + perSheet - 1) / perSheet
    }

    /**
     * Where the page in cell [position] (0 until perSheet, across then down) lands: scaled (down or
     * up) to fit its cell, centred. Null for a page with no width or height, also one so small that
     * its scale is past what a Float holds.
     */
    fun slot(grid: SheetGrid, position: Int, pageWidth: Float, pageHeight: Float): SheetSlot? {
        require(position in 0 until grid.columns * grid.rows) { "No cell $position on a ${grid.columns}×${grid.rows} sheet" }
        if (!(pageWidth > 0f && pageHeight > 0f) || !pageWidth.isFinite() || !pageHeight.isFinite()) return null
        val cellWidth = (grid.width - 2 * MARGIN - (grid.columns - 1) * GAP) / grid.columns
        val cellHeight = (grid.height - 2 * MARGIN - (grid.rows - 1) * GAP) / grid.rows
        val column = position % grid.columns
        val row = position / grid.columns
        val cellLeft = MARGIN + column * (cellWidth + GAP)
        val cellBottom = grid.height - MARGIN - (row + 1) * cellHeight - row * GAP
        val scale = min(cellWidth / pageWidth, cellHeight / pageHeight)
        val width = pageWidth * scale
        val height = pageHeight * scale
        // A damaged crop box far under a point each way (1e-38) scales past what a Float holds, and a
        // very thin one to 0 across: such a page has no size to draw, and an infinite matrix can't be
        // written.
        if (!scale.isFinite() || !(width > 0f && height > 0f) || !width.isFinite() || !height.isFinite()) return null
        return SheetSlot(cellLeft + (cellWidth - width) / 2, cellBottom + (cellHeight - height) / 2, width, height, scale)
    }

    /**
     * The `cm` matrix (a, b, c, d, e, f) that takes the page's own coordinates to the sheet's, for a
     * page [frame] shows: turned by its /Rotate, so that what shows at its top-left lands at the
     * slot's top-left, and its crop box filling [slot].
     */
    fun matrix(frame: PageFrame, slot: SheetSlot): FloatArray {
        // A frame's matrix only ever turns and moves, so it can always be undone.
        val inv = checkNotNull(Areas.inverted(frame.matrix))
        val s = slot.scale
        return floatArrayOf(s * inv[0], s * inv[1], s * inv[2], s * inv[3], s * inv[4] + slot.x, s * inv[5] + slot.y)
    }
}
