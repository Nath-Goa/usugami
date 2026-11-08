package io.github.nath_goa.usugami.data.office

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.withClip
import androidx.core.graphics.withTranslation
import io.github.nath_goa.usugami.core.HeaderRun
import io.github.nath_goa.usugami.core.SheetHeaders
import io.github.nath_goa.usugami.core.SheetPages
import io.github.nath_goa.usugami.core.SheetRange
import io.github.nath_goa.usugami.core.SheetRefs
import kotlin.math.max
import kotlin.math.min

/**
 * One printed area of a sheet (its print area, or everything on it), laid out on pages: the paper,
 * the scale, and where the rows and columns break between pages. [pages] are the pages in the order
 * they print, as (row band, column band), those with nothing on them left out, as Excel leaves them out.
 */
internal class AreaLayout(
    val plan: SheetPlan,
    val area: SheetRange,
    val pageWidth: Float,
    val pageHeight: Float,
    /** The share sizes on the sheet are printed at. */
    val scale: Float,
    /** The first row (column) of each band of rows (columns), on the sheet. */
    val rowStarts: IntArray,
    val columnStarts: IntArray,
    val titleRows: IntRange?,
    val titleColumns: IntRange?,
    val pages: List<Pair<Int, Int>>,
) {
    fun rowEnd(band: Int): Int = if (band + 1 < rowStarts.size) rowStarts[band + 1] - 1 else area.lastRow
    fun columnEnd(band: Int): Int = if (band + 1 < columnStarts.size) columnStarts[band + 1] - 1 else area.lastColumn

    /** Whether page band [rowBand] repeats the title rows above its own, which it does once past them. */
    fun repeatsRows(rowBand: Int): Boolean = titleRows != null && rowStarts[rowBand] > titleRows.last
    fun repeatsColumns(columnBand: Int): Boolean = titleColumns != null && columnStarts[columnBand] > titleColumns.last

    companion object {
        /** Excel's paper when a file doesn't say: US Letter. */
        private val LETTER = 612.0 to 792.0

        /**
         * Lays out [area] of [plan] with [titles] (repeated rows, then columns, or nulls) as [options]
         * and the sheet's page setup say. Fit columns to page width shrinks the columns to the page's
         * width unless the file sets how to fit the sheet itself, but not below [MIN_FIT_WIDTH] percent;
         * nothing is ever enlarged. Throws [OfficeTooLarge] once the pages with anything on them number
         * more than [limit].
         */
        fun of(plan: SheetPlan, area: SheetRange, titles: Pair<IntRange?, IntRange?>, options: SheetOptions, limit: Int, check: () -> Unit): AreaLayout {
            val setup = plan.setup
            val paper = options.paper?.let { it.shortSide to it.longSide } ?: setup.paper ?: LETTER
            val landscape = options.landscape ?: setup.landscape ?: false
            val width = (if (landscape) paper.second else paper.first).toFloat()
            val height = (if (landscape) paper.first else paper.second).toFloat()
            val margins = margins(setup.margins, width, height)
            val bodyWidth = (width - margins.left - margins.right).toDouble()
            val bodyHeight = (height - margins.top - margins.bottom).toDouble()

            val widths = DoubleArray(area.lastColumn - area.firstColumn + 1) { plan.columns.width(area.firstColumn + it).toDouble() }
            val heights = DoubleArray(area.lastRow - area.firstRow + 1) { plan.rows.height(area.firstRow + it).toDouble() }
            // Titles taller (or wider) than half a page would leave too little room for the rest: they don't repeat.
            val titleRows = titles.first?.takeIf { rows -> rows.sumOf { plan.rows.height(it).toDouble() } <= bodyHeight / 2 }
            val titleColumns = titles.second?.takeIf { columns -> columns.sumOf { plan.columns.width(it).toDouble() } <= bodyWidth / 2 }
            val rowTitle = (titleRows?.sumOf { plan.rows.height(it).toDouble() } ?: 0.0) to (titleRows?.last?.minus(area.firstRow) ?: Int.MIN_VALUE)
            val columnTitle = (titleColumns?.sumOf { plan.columns.width(it).toDouble() } ?: 0.0) to (titleColumns?.last?.minus(area.firstColumn) ?: Int.MIN_VALUE)
            val rowBreaks = plan.rowBreaks.mapNotNullTo(HashSet()) { (it - area.firstRow).takeIf { i -> i > 0 } }
            val columnBreaks = plan.columnBreaks.mapNotNullTo(HashSet()) { (it - area.firstColumn).takeIf { i -> i > 0 } }

            val shrunk = if (options.fitWidth && !setup.fitToPage) {
                SheetPages.fitScale(widths, heights, bodyWidth, bodyHeight, 1, 0, emptySet(), rowBreaks, columnTitle, rowTitle, most = setup.scale)
            } else null
            // Smaller is too small to read on paper: a sheet wider still goes on more pages across.
            val floor = min(MIN_FIT_WIDTH, setup.scale)
            val percent = when {
                setup.fitToPage -> SheetPages.fitScale(widths, heights, bodyWidth, bodyHeight, setup.fitWidth, setup.fitHeight, emptySet(), emptySet(), columnTitle, rowTitle)
                shrunk != null -> max(shrunk, floor)
                else -> setup.scale
            }
            val scale = percent / 100.0
            val breaksApply = !setup.fitToPage
            // Fitted to one page across, the sheet's own breaks between columns don't apply, as in Excel's "Fit to".
            val onePageAcross = shrunk != null && shrunk >= floor
            val rowStarts = SheetPages.bands(heights, bodyHeight, scale, if (breaksApply) rowBreaks else emptySet(), rowTitle.first, rowTitle.second)
                .map { it + area.firstRow }.toIntArray()
            val columnStarts = SheetPages.bands(widths, bodyWidth, scale, if (breaksApply && !onePageAcross) columnBreaks else emptySet(), columnTitle.first, columnTitle.second)
                .map { it + area.firstColumn }.toIntArray()
            val layout = AreaLayout(plan, area, width, height, scale.toFloat(), rowStarts, columnStarts, titleRows, titleColumns, emptyList())
            val pages = layout.pagesWithContent(setup.downThenOver, limit, check)
            return AreaLayout(plan, area, width, height, scale.toFloat(), rowStarts, columnStarts, titleRows, titleColumns, pages)
        }

        /** The smallest Fit columns to page width shrinks a sheet to, in percent: half size. */
        const val MIN_FIT_WIDTH = 50

        /** [margins], shrunk together if they leave less than a tenth of the paper. */
        private fun margins(margins: SheetMargins, width: Float, height: Float): SheetMargins {
            val across = (width * 0.9f / max(0.01f, margins.left + margins.right)).coerceAtMost(1f)
            val down = (height * 0.9f / max(0.01f, margins.top + margins.bottom)).coerceAtMost(1f)
            return SheetMargins(
                margins.left * across, margins.right * across, margins.top * down, margins.bottom * down,
                min(margins.header, margins.top * down), min(margins.footer, margins.bottom * down),
            )
        }
    }

    /** The paper's margins, as laid out. */
    val margins: SheetMargins get() = margins(plan.setup.margins, pageWidth, pageHeight)

    /**
     * The pages with anything on them, in the order they print: a cell with something in it, a format
     * that shows or text running on from a cell, part of a merged block with something in it, or a
     * picture. They are found from what the sheet holds rather than by looking at each page, as a sheet
     * reaching far down and across has millions. Throws [OfficeTooLarge] once they number more than [limit].
     */
    private fun pagesWithContent(downThenOver: Boolean, limit: Int, check: () -> Unit): List<Pair<Int, Int>> {
        val found = HashSet<Long>()
        fun mark(cells: SheetRange) {
            val shown = cells.intersect(area) ?: return
            val columnBands = band(columnStarts, shown.firstColumn)..band(columnStarts, shown.lastColumn)
            for (r in band(rowStarts, shown.firstRow)..band(rowStarts, shown.lastRow)) {
                for (c in columnBands) if (found.add(SheetRefs.pack(r, c)) && found.size > limit) throw OfficeTooLarge()
            }
        }
        // The rows with something in them, the columns of each band of rows joined where they meet.
        var rowBand = -1
        var from = -1
        var to = -1
        plan.content.forEach(area.firstRow, area.lastRow) { row, first, last ->
            val band = band(rowStarts, row)
            if (band != rowBand || first > to + 1 || last < from - 1) {
                if (rowBand >= 0) mark(SheetRange(rowStarts[rowBand], from, rowStarts[rowBand], to))
                check()
                rowBand = band
                from = first
                to = last
            } else {
                from = min(from, first)
                to = max(to, last)
            }
        }
        if (rowBand >= 0) mark(SheetRange(rowStarts[rowBand], from, rowStarts[rowBand], to))
        for (merge in plan.merges) {
            check()
            if (merge.intersect(area) != null && plan.content.any(merge.firstRow, merge.lastRow, merge.firstColumn, merge.lastColumn)) mark(merge)
        }
        for (picture in plan.pictures) {
            val r = picture.rect
            val rows = plan.rows
            val columns = plan.columns
            mark(SheetRange(rows.rowAt(r.top.toDouble()), columns.columnAt(r.left.toDouble()), rows.rowAt(max(r.top, r.bottom - 0.01f).toDouble()), columns.columnAt(max(r.left, r.right - 0.01f).toDouble())))
        }
        return SheetPages.order(found.map { SheetRefs.rowOf(it) to SheetRefs.columnOf(it) }, downThenOver)
    }

    /** The band of [starts] (each band's first row or column) that [index] is in. */
    private fun band(starts: IntArray, index: Int): Int = starts.binarySearch(index).let { if (it >= 0) it else -it - 2 }.coerceAtLeast(0)
}

/**
 * The cells of a page as read in the second pass: its rows, by index, and every merged block's first
 * cell read so far (a block can start on an earlier page).
 */
internal class PageCells(val rows: Map<Int, SheetRow>, private val anchors: Map<Long, SheetCell>) {
    fun cell(row: Int, column: Int): SheetCell? =
        rows[row]?.cells?.let { cells -> cells.binarySearchBy(column) { it.column }.takeIf { it >= 0 }?.let(cells::get) }
            ?: anchors[SheetRefs.pack(row, column)]
}

/**
 * Draws the pages of a laid-out sheet area: fills, gridlines, borders, text and pictures, in that
 * order, as Excel prints them, and the sheet's header and footer. Text that doesn't wrap runs on into
 * empty cells beside it; numbers too wide for their cell show as ####. Sizes on the sheet are points,
 * drawn at the area's scale. Light gridlines show on the sheets set to print them, as in Excel, and
 * with [gridlines] also on those that show them on screen.
 */
internal class SheetPainter(
    private val styles: SheetStyles,
    private val texts: CellTexts,
    private val images: OfficeImages,
    private val gridlines: Boolean,
) {
    private val fillPaint = Paint()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.SQUARE }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = GRID }

    /** A part of a page: [rows] by [columns] of the sheet, its top left corner at ([x], [y]) points in the page's content. */
    private class Region(val rows: IntRange, val columns: IntRange, val x: Float, val y: Float, val width: Float, val height: Float)

    /** Where [cell]'s text goes: across the cells from [first] to [last] of its row. */
    private class Placed(val cell: SheetCell, val format: CellFormat, val first: Int, val last: Int)

    /**
     * Draws page ([rowBand], [columnBand]) of [layout] on [canvas] (in layout units), its cells from
     * [cells]: the title rows and columns first when it repeats them, then its own.
     */
    fun draw(canvas: Canvas, layout: AreaLayout, rowBand: Int, columnBand: Int, cells: PageCells) {
        val plan = layout.plan
        val rowParts = listOfNotNull(layout.titleRows?.takeIf { layout.repeatsRows(rowBand) }, layout.rowStarts[rowBand]..layout.rowEnd(rowBand))
        val columnParts = listOfNotNull(layout.titleColumns?.takeIf { layout.repeatsColumns(columnBand) }, layout.columnStarts[columnBand]..layout.columnEnd(columnBand))
        fun height(rows: IntRange) = (plan.rows.offset(rows.last + 1) - plan.rows.offset(rows.first)).toFloat()
        fun width(columns: IntRange) = (plan.columns.offset(columns.last + 1) - plan.columns.offset(columns.first)).toFloat()
        val regions = ArrayList<Region>()
        var y = 0f
        for (rows in rowParts) {
            var x = 0f
            for (columns in columnParts) {
                regions += Region(rows, columns, x, y, width(columns), height(rows))
                x += width(columns)
            }
            y += height(rows)
        }
        val contentWidth = columnParts.sumOf { width(it).toDouble() }.toFloat() * layout.scale
        val contentHeight = rowParts.sumOf { height(it).toDouble() }.toFloat() * layout.scale
        val margins = layout.margins
        val bodyWidth = layout.pageWidth - margins.left - margins.right
        val bodyHeight = layout.pageHeight - margins.top - margins.bottom
        val left = margins.left + if (plan.setup.centerHorizontally) max(0f, (bodyWidth - contentWidth) / 2) else 0f
        val top = margins.top + if (plan.setup.centerVertically) max(0f, (bodyHeight - contentHeight) / 2) else 0f
        val placed = HashMap<Int, List<Placed>>()
        val grid = plan.gridlinesPrinted || (gridlines && plan.gridlinesShown)
        // The merged blocks of more than one cell over the page's rows, found once for all of them.
        val merges = plan.merges.filter { m -> (m.firstRow != m.lastRow || m.firstColumn != m.lastColumn) && rowParts.any { m.firstRow <= it.last && m.lastRow >= it.first } }
        canvas.withTranslation(left * U, top * U) {
            scale(layout.scale, layout.scale)
            for (region in regions) drawRegion(this, layout, region, cells, merges, placed, grid)
        }
    }

    private fun drawRegion(
        canvas: Canvas,
        layout: AreaLayout,
        region: Region,
        cells: PageCells,
        pageMerges: List<SheetRange>,
        placed: HashMap<Int, List<Placed>>,
        grid: Boolean,
    ) {
        val plan = layout.plan
        val columns = plan.columns
        val rows = plan.rows
        val firstX = columns.offset(region.columns.first)
        val firstY = rows.offset(region.rows.first)
        fun x(column: Int) = (region.x + columns.offset(column) - firstX).toFloat()
        fun y(row: Int) = (region.y + rows.offset(row) - firstY).toFloat()
        val clip = RectF(region.x, region.y, region.x + region.width, region.y + region.height)
        val visibleRows = region.rows.filter { rows.height(it) > 0f }
        val columnList = region.columns.toList()
        if (visibleRows.isEmpty() || columnList.isEmpty()) return
        val bounds = SheetRange(region.rows.first, region.columns.first, region.rows.last, region.columns.last)
        val merges = pageMerges.filter { it.intersect(bounds) != null }
        // Which merged block each cell of the region is in, or -1.
        val mergeOf = Array(visibleRows.size) { IntArray(columnList.size) { -1 } }
        val rowSlot = HashMap<Int, Int>(visibleRows.size * 2).also { map -> visibleRows.forEachIndexed { i, r -> map[r] = i } }
        merges.forEachIndexed { m, merge ->
            for (r in max(merge.firstRow, region.rows.first)..min(merge.lastRow, region.rows.last)) {
                val i = rowSlot[r] ?: continue
                for (c in max(merge.firstColumn, region.columns.first)..min(merge.lastColumn, region.columns.last)) mergeOf[i][c - region.columns.first] = m
            }
        }
        // Every cell's format: its own, else its row's, else its column's.
        val formats = Array(visibleRows.size) { i ->
            val row = cells.rows[visibleRows[i]]
            IntArray(columnList.size) { j -> row?.format?.takeIf { it >= 0 } ?: columns.format(columnList[j]).takeIf { it >= 0 } ?: 0 }.also { line ->
                row?.cells?.forEach { cell -> if (cell.column in region.columns) line[cell.column - region.columns.first] = cell.format }
            }
        }
        fun mergeRect(merge: SheetRange) = RectF(x(merge.firstColumn), y(merge.firstRow), x(merge.lastColumn + 1), y(merge.lastRow + 1))
        val rowPlaced = visibleRows.associateWith { r -> placed.getOrPut(r) { place(plan, cells.rows[r], layout.area, pageMerges) } }

        canvas.withClip(clip.left * U, clip.top * U, clip.right * U, clip.bottom * U) {
            // Fills, in runs of one colour along each row, so no seams show between cells.
            for ((i, r) in visibleRows.withIndex()) {
                var runStart = -1
                var runColor = 0
                fun flush(end: Int) {
                    if (runStart >= 0) fillRect(this, x(columnList[runStart]), y(r), x(columnList[end - 1] + 1), y(r) + rows.height(r), runColor)
                    runStart = -1
                }
                for (j in columnList.indices) {
                    val color = if (mergeOf[i][j] >= 0) null else styles.format(formats[i][j]).fill
                    if (color != runColor || color == null) flush(j)
                    if (color != null && runStart < 0) {
                        runStart = j
                        runColor = color
                    }
                }
                flush(columnList.size)
            }
            for (merge in merges) {
                val anchor = cells.cell(merge.firstRow, merge.firstColumn)
                val format = styles.format(anchor?.format ?: formatOf(plan, cells, merge.firstRow, merge.firstColumn))
                format.fill?.let { color -> mergeRect(merge).let { fillRect(this, it.left, it.top, it.right, it.bottom, color) } }
            }

            if (grid) drawGrid(this, layout, visibleRows, columnList, mergeOf, rowPlaced, ::x, ::y)

            // Borders: each cell's own sides, but those inside a merged block.
            for ((i, r) in visibleRows.withIndex()) {
                for ((j, c) in columnList.withIndex()) {
                    val borders = styles.format(formats[i][j]).borders ?: continue
                    val merge = mergeOf[i][j].takeIf { it >= 0 }?.let(merges::get)
                    val x0 = x(c)
                    val x1 = x(c + 1)
                    val y0 = y(r)
                    val y1 = y(r + 1)
                    if (merge == null || r == merge.firstRow) borders.top?.let { line(this, it, x0, y0, x1, y0, layout.scale) }
                    if (merge == null || r == merge.lastRow) borders.bottom?.let { line(this, it, x0, y1, x1, y1, layout.scale) }
                    if (merge == null || c == merge.firstColumn) borders.left?.let { line(this, it, x0, y0, x0, y1, layout.scale) }
                    if (merge == null || c == merge.lastColumn) borders.right?.let { line(this, it, x1, y0, x1, y1, layout.scale) }
                    if (merge == null) {
                        borders.diagonalUp?.let { line(this, it, x0, y1, x1, y0, layout.scale) }
                        borders.diagonalDown?.let { line(this, it, x0, y0, x1, y1, layout.scale) }
                    }
                }
            }
        }

        // Text, each in the cells it takes, cut off at the region's edge.
        val hidden = plan.valuesHidden.filter { it.firstRow <= region.rows.last && it.lastRow >= region.rows.first }
        for (r in visibleRows) {
            for (p in rowPlaced.getValue(r)) {
                if (p.last < region.columns.first || p.first > region.columns.last) continue
                if (hidden.any { it.contains(r, p.cell.column) }) continue
                val box = RectF(x(p.first), y(r), x(p.last + 1), y(r + 1))
                val shown = texts.shown(p.cell, p.format, columns.width(p.cell.column)) ?: continue
                if (shown.align == SheetAlign.CENTER && p.first != p.last) {
                    // Centred text running on stays centred on its own cell, cut off where the cells it runs into end.
                    val centre = (x(p.cell.column) + x(p.cell.column + 1)) / 2
                    val half = max(centre - box.left, box.right - centre)
                    val cut = RectF(box)
                    if (cut.intersect(clip)) texts.draw(canvas, shown, p.format, RectF(centre - half, box.top, centre + half, box.bottom), cut)
                } else {
                    texts.draw(canvas, shown, p.format, box, clip)
                }
            }
        }
        for (merge in merges) {
            if (hidden.any { it.contains(merge.firstRow, merge.firstColumn) }) continue
            val anchor = cells.cell(merge.firstRow, merge.firstColumn)?.takeIf { it.kind != null } ?: continue
            val format = styles.format(anchor.format)
            val box = mergeRect(merge)
            val shown = texts.shown(anchor, format, box.width()) ?: continue
            texts.draw(canvas, shown, format, box, clip)
        }

        // Pictures, over the cells.
        val sheetLeft = columns.offset(region.columns.first).toFloat()
        val sheetTop = rows.offset(region.rows.first).toFloat()
        val sheetRight = sheetLeft + region.width
        val sheetBottom = sheetTop + region.height
        canvas.withClip(clip.left * U, clip.top * U, clip.right * U, clip.bottom * U) {
            for (picture in plan.pictures) {
                val r = picture.rect
                if (r.right <= sheetLeft || r.left >= sheetRight || r.bottom <= sheetTop || r.top >= sheetBottom) continue
                val target = RectF(
                    (region.x + r.left - sheetLeft) * U, (region.y + r.top - sheetTop) * U,
                    (region.x + r.right - sheetLeft) * U, (region.y + r.bottom - sheetTop) * U,
                )
                ImagePicture(images, picture.path, r.width() * layout.scale, r.height() * layout.scale, picture.crop).draw(this, target)
            }
        }
    }

    /** The format a cell without one of its own takes: its row's, else its column's, else the Normal style. */
    private fun formatOf(plan: SheetPlan, cells: PageCells, row: Int, column: Int): Int =
        cells.rows[row]?.format?.takeIf { it >= 0 } ?: plan.columns.format(column).takeIf { it >= 0 } ?: 0

    /**
     * Where the text of each cell of [row] goes. Text that doesn't wrap and is wider than its cell runs
     * on into the empty cells beside it, the way it lines up: right of it, left of it, or both ways when
     * centred; Center Across Selection centres it over the empty cells after it that ask for the same.
     * Numbers never run on; nor does text in the merged blocks [merges] (those over the page's rows),
     * which [draw] places in its block. A hidden column's text doesn't print, so it goes nowhere.
     */
    private fun place(plan: SheetPlan, row: SheetRow?, area: SheetRange, merges: List<SheetRange>): List<Placed> {
        if (row == null || row.cells.isEmpty()) return emptyList()
        val filled = row.cells.filter { it.kind != null }.mapTo(HashSet()) { it.column }
        val merged = merges.filter { row.index in it.firstRow..it.lastRow }
        fun inMerge(column: Int) = merged.any { column in it.firstColumn..it.lastColumn }
        fun free(column: Int) = column in area.firstColumn..area.lastColumn && column !in filled && !inMerge(column)
        val out = ArrayList<Placed>()
        for (cell in row.cells) {
            if (cell.kind == null || cell.column !in area.firstColumn..area.lastColumn || plan.columns.width(cell.column) <= 0f || inMerge(cell.column)) continue
            val format = styles.format(cell.format)
            var first = cell.column
            var last = cell.column
            val runsOn = cell.kind == CellKind.TEXT || cell.kind == CellKind.FORMULA
            if (format.horizontal == SheetAlign.CENTER_ACROSS) {
                while (free(last + 1) && styles.format(formatOf(plan, row, last + 1)).horizontal == SheetAlign.CENTER_ACROSS) last++
            } else if (runsOn && !format.wraps && !format.shrink && format.rotation == 0 && format.horizontal != SheetAlign.FILL) {
                val shown = texts.shown(cell, format, plan.columns.width(cell.column))
                val right = shown != null && shown.align != SheetAlign.RIGHT && free(last + 1)
                val left = shown != null && (shown.align == SheetAlign.RIGHT || shown.align == SheetAlign.CENTER) && free(first - 1)
                // Measured only when it has somewhere to run on to: in a full table, most text has not.
                if (shown != null && (right || left)) {
                    val need = texts.width(shown) + 2 * CellTexts.PAD + format.indent * texts.indentStep - plan.columns.width(cell.column)
                    if (need > 0f) {
                        val side = if (shown.align == SheetAlign.CENTER) need / 2 else need
                        if (right) {
                            var gained = 0f
                            while (gained < side && free(last + 1)) gained += plan.columns.width(++last)
                        }
                        if (left) {
                            var gained = 0f
                            while (gained < side && free(first - 1)) gained += plan.columns.width(--first)
                        }
                    }
                }
            }
            out += Placed(cell, format, first, last)
        }
        return out
    }

    private fun formatOf(plan: SheetPlan, row: SheetRow, column: Int): Int =
        row.cells.firstOrNull { it.column == column }?.format ?: row.format.takeIf { it >= 0 } ?: plan.columns.format(column).takeIf { it >= 0 } ?: 0

    /**
     * Light grey lines between all the cells, as Excel prints them with Gridlines on, except inside
     * merged blocks and where text runs on across a cell's side.
     */
    private fun drawGrid(
        canvas: Canvas,
        layout: AreaLayout,
        visibleRows: List<Int>,
        columnList: List<Int>,
        mergeOf: Array<IntArray>,
        rowPlaced: Map<Int, List<Placed>>,
        x: (Int) -> Float,
        y: (Int) -> Float,
    ) {
        gridPaint.strokeWidth = GRID_WIDTH / layout.scale * U
        val columns = layout.plan.columns
        // Across, at the top of each row and the bottom of the last.
        for (i in 0..visibleRows.size) {
            val lineY = if (i < visibleRows.size) y(visibleRows[i]) else y(visibleRows.last() + 1)
            var from = -1
            for (j in 0..columnList.size) {
                val inside = j < columnList.size && i in 1 until visibleRows.size && mergeOf[i][j] >= 0 && mergeOf[i][j] == mergeOf[i - 1][j]
                val end = j == columnList.size || inside || columns.width(columnList[j]) <= 0f
                if (end && from >= 0) {
                    canvas.drawLine(x(columnList[from]) * U, lineY * U, x(columnList[j - 1] + 1) * U, lineY * U, gridPaint)
                    from = -1
                }
                if (!end && from < 0) from = j
            }
        }
        // Down, at the left of each column and the right of the last.
        val runsOver = visibleRows.map { r -> rowPlaced[r].orEmpty().filter { it.last > it.first } }
        for (j in 0..columnList.size) {
            if (j in 1 until columnList.size && columns.width(columnList[j - 1]) <= 0f) continue
            val column = if (j < columnList.size) columnList[j] else columnList.last() + 1
            val lineX = x(column)
            var from = -1
            for (i in 0..visibleRows.size) {
                val inside = i < visibleRows.size && j in 1 until columnList.size &&
                    ((mergeOf[i][j] >= 0 && mergeOf[i][j] == mergeOf[i][j - 1]) || runsOver[i].any { column > it.first && column <= it.last })
                val end = i == visibleRows.size || inside
                if (end && from >= 0) {
                    canvas.drawLine(lineX * U, y(visibleRows[from]) * U, lineX * U, y(visibleRows[i - 1] + 1) * U, gridPaint)
                    from = -1
                }
                if (!end && from < 0) from = i
            }
        }
    }

    /**
     * Draws the sheet's header and footer on its page [number] (from 1), numbered [page] of the PDF's
     * [pages], in the top and bottom margins, their left, centre and right parts lined up with the
     * paper's side margins. [fields] gives the sheet's and the file's names and today's date and time.
     */
    fun drawHeaderFooter(canvas: Canvas, layout: AreaLayout, number: Int, page: Int, pages: Int, fields: (page: Int, pages: Int) -> SheetHeaders.Fields) {
        val setup = layout.plan.setup
        val texts = setup.headerFooter ?: return
        val first = texts.differentFirst && number == 1
        val even = texts.differentOddEven && page % 2 == 0
        val header = if (first) texts.firstHeader else if (even) texts.evenHeader else texts.oddHeader
        val footer = if (first) texts.firstFooter else if (even) texts.evenFooter else texts.oddFooter
        val shown = fields(page, pages)
        val scale = if (texts.scaleWithDoc) layout.scale else 1f
        header?.let { drawHeaderParts(canvas, layout, SheetHeaders.parts(it, shown), scale, top = true) }
        footer?.let { drawHeaderParts(canvas, layout, SheetHeaders.parts(it, shown), scale, top = false) }
    }

    private fun drawHeaderParts(canvas: Canvas, layout: AreaLayout, parts: List<List<HeaderRun>>, scale: Float, top: Boolean) {
        val margins = layout.margins
        val width = layout.pageWidth - margins.left - margins.right
        val font = styles.defaultFont
        val base = font.style.copy(size = font.size * scale)
        for ((i, runs) in parts.withIndex()) {
            if (runs.isEmpty()) continue
            val rich = RichText()
            for (run in runs) {
                rich.append(
                    run.text,
                    TextStyle(
                        font = run.font ?: font.name,
                        size = (run.size ?: font.size) * scale,
                        bold = run.bold,
                        italic = run.italic,
                        underline = run.underline,
                        strike = run.strike,
                        color = run.color?.let { it or OPAQUE } ?: Color.BLACK,
                        baseline = run.baseline,
                    ),
                )
            }
            val align = when (i) {
                0 -> Align.START
                1 -> Align.CENTER
                else -> Align.END
            }
            val block = ParagraphBlock(
                ParagraphSpec(rich.text, base, max(1, (width * U).toInt()), align, lineScale = OfficeFonts.lineScale(base), directionFromText = false, avoidSingleLines = false),
            )
            val y = if (top) margins.header else layout.pageHeight - margins.footer - block.height / U
            block.draw(canvas, margins.left * U, y * U)
        }
    }

    private fun fillRect(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        fillPaint.color = color
        // A hair over the bottom, so that the next row's fill leaves no seam between them.
        canvas.drawRect(left * U, top * U, right * U, (bottom + SEAM) * U, fillPaint)
    }

    /** A border line from ([x0], [y0]) to ([x1], [y1]), never printed finer than [MIN_LINE] at [scale]. */
    private fun line(canvas: Canvas, line: CellLine, x0: Float, y0: Float, x1: Float, y1: Float, scale: Float) {
        val width = max(line.width, MIN_LINE / scale)
        linePaint.color = line.color
        linePaint.pathEffect = line.dashes?.let { d -> DashPathEffect(FloatArray(d.size) { d[it] * U }, 0f) }
        if (!line.double) {
            linePaint.strokeWidth = width * U
            canvas.drawLine(x0 * U, y0 * U, x1 * U, y1 * U, linePaint)
            return
        }
        // Two thin lines, a line's width either side of the cell's edge.
        linePaint.strokeWidth = width / 3 * U
        val horizontal = y0 == y1
        val dx = if (horizontal) 0f else width / 3
        val dy = if (horizontal) width / 3 else 0f
        canvas.drawLine((x0 - dx) * U, (y0 - dy) * U, (x1 - dx) * U, (y1 - dy) * U, linePaint)
        canvas.drawLine((x0 + dx) * U, (y0 + dy) * U, (x1 + dx) * U, (y1 + dy) * U, linePaint)
    }

    private companion object {
        val GRID = Color.rgb(0xC0, 0xC0, 0xC0)
        const val OPAQUE = 0xFF shl 24
        /** Gridlines are hairlines, a quarter of a point on paper at any scale. */
        const val GRID_WIDTH = 0.25f
        const val MIN_LINE = 0.25f
        const val SEAM = 0.05f
    }
}
