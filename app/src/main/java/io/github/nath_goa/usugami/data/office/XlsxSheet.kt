package io.github.nath_goa.usugami.data.office

import android.graphics.RectF
import io.github.nath_goa.usugami.core.OfficeUnits
import io.github.nath_goa.usugami.core.SheetDates
import io.github.nath_goa.usugami.core.SheetFormulas
import io.github.nath_goa.usugami.core.SheetPaper
import io.github.nath_goa.usugami.core.SheetRange
import io.github.nath_goa.usugami.core.SheetRefs
import io.github.nath_goa.usugami.core.SheetStrings
import io.github.nath_goa.usugami.core.SheetUnits
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** What a cell holds. A formula whose file keeps no value for it shows the formula itself. */
internal enum class CellKind { NUMBER, TEXT, BOOLEAN, ERROR, FORMULA }

/**
 * A cell as read: its [column] (from 0), its format's place in cellXfs, and what it holds: nothing
 * ([kind] null, a cell with only a format), a [number] (TRUE is 1), or [text].
 */
internal class SheetCell(val column: Int, val format: Int, val kind: CellKind?, val number: Double = 0.0, val text: SheetText? = null)

/**
 * A row as read: its [index] (from 0), its cells in order, the format its empty cells take ([format]
 * -1 for none), and its own height in points when it gives one ([custom] when set by hand).
 */
internal class SheetRow(val index: Int, val cells: List<SheetCell>, val format: Int, val height: Float?, val custom: Boolean, val hidden: Boolean)

/**
 * Reads a worksheet's rows, one row element at a time. Rows and cells without a reference follow the
 * ones before them, and a column given more than once in a row keeps its last cell. The first cell of
 * a formula shared by a block of cells is kept, for the others.
 */
internal class RowReader(private val strings: SharedStrings, private val date1904: Boolean) {
    private val shared = HashMap<String, Triple<Int, Int, String>>()
    private var next = 0

    /** Reads the row element the reader is at. */
    fun read(x: OfficeXml): SheetRow {
        val index = x.intAttr("r")?.minus(1)?.takeIf { it in 0 until SheetRefs.MAX_ROWS } ?: next.coerceAtMost(SheetRefs.MAX_ROWS - 1)
        next = index + 1
        val height = x.attr("ht")?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }?.coerceAtMost(MAX_ROW_HEIGHT)
        val custom = x.flag("customHeight", missing = false)
        val hidden = x.flag("hidden", missing = false)
        val format = if (x.flag("customFormat", missing = false)) x.intAttr("s") ?: -1 else -1
        val cells = ArrayList<SheetCell>()
        var column = 0
        x.children {
            if (x.name != "c") return@children
            val at = x.attr("r")?.let(SheetRefs::cell)?.let(SheetRefs::columnOf) ?: column
            column = at + 1
            if (at >= SheetRefs.MAX_COLUMNS) return@children
            val cell = readCell(x, index, at)
            if (cells.isNotEmpty() && cells[cells.lastIndex].column == at) cells[cells.lastIndex] = cell else cells += cell
            // Columns out of order can come again and again, in a crafted file: each keeps its last cell.
            if (cells.size > 2 * SheetRefs.MAX_COLUMNS) {
                val ordered = cells.associateBy { it.column }.values.sortedBy { it.column }
                cells.clear()
                cells.addAll(ordered)
            }
        }
        // Cells are in order in any file Excel can open; one that isn't is put in order.
        if (cells.zipWithNext().any { (a, b) -> a.column >= b.column }) {
            val ordered = cells.associateBy { it.column }.values.sortedBy { it.column }
            return SheetRow(index, ordered, format, height, custom, hidden)
        }
        return SheetRow(index, cells, format, height, custom, hidden)
    }

    private fun readCell(x: OfficeXml, row: Int, column: Int): SheetCell {
        val format = x.intAttr("s")?.takeIf { it >= 0 } ?: 0
        val type = x.attr("t")
        var value: String? = null
        var formula: String? = null
        var inline: SheetText? = null
        x.children {
            when (x.name) {
                "v" -> value = x.text()
                "f" -> formula = readFormula(x, row, column)
                "is" -> inline = SharedStrings.readItem(x)
            }
        }
        val v = value
        fun text(t: SheetText) = SheetCell(column, format, CellKind.TEXT, text = t)
        fun plain(t: String) = text(SheetText(SheetStrings.unescape(t), null))
        val held = when (type) {
            "s" -> v?.trim()?.toIntOrNull()?.let { strings[it] }?.let(::text)
            "inlineStr" -> inline?.let(::text) ?: v?.let(::plain)
            "str" -> v?.let(::plain)
            "b" -> v?.trim()?.let { SheetCell(column, format, CellKind.BOOLEAN, number = if (it == "1" || it.equals("true", ignoreCase = true)) 1.0 else 0.0) }
            "e" -> v?.trim()?.let { SheetCell(column, format, CellKind.ERROR, text = SheetText(it, null)) }
            "d" -> v?.let { iso -> SheetDates.serial(iso, date1904)?.let { SheetCell(column, format, CellKind.NUMBER, number = it) } ?: plain(iso) }
            // A value kept but empty is a formula's empty text: the cell shows nothing, but isn't empty.
            else -> v?.trim()?.let { n ->
                n.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { SheetCell(column, format, CellKind.NUMBER, number = it) } ?: plain(n)
            }
        }
        // A formula without a value kept for it shows as itself, rather than leave the cell blank.
        return held ?: formula?.let { SheetCell(column, format, CellKind.FORMULA, text = SheetText("=$it", null)) } ?: SheetCell(column, format, null)
    }

    /** A cell's formula: its own, or the shared one moved to it. */
    private fun readFormula(x: OfficeXml, row: Int, column: Int): String? {
        val group = if (x.attr("t") == "shared") x.attr("si") else null
        val text = SheetStrings.unescape(x.text()).trim()
        if (group == null) return text.ifEmpty { null }
        if (text.isNotEmpty()) {
            shared[group] = Triple(row, column, text)
            return text
        }
        val first = shared[group] ?: return null
        return SheetFormulas.shift(first.third, row - first.first, column - first.second)
    }

    companion object {
        /** Excel's tallest row. */
        const val MAX_ROW_HEIGHT = 409.5f
    }
}

/**
 * Every column's width in points (0 when hidden), and the format its empty cells take (-1 for none).
 * Kept for the columns up to the last that differs from those after it, which all have [tailWidth]
 * and [tailFormat], so that each of a workbook's many sheets takes little memory.
 */
internal class ColumnWidths(private val widths: FloatArray, private val formats: IntArray, private val tailWidth: Float, private val tailFormat: Int) {
    private val offsets = DoubleArray(widths.size + 1).also { o -> for (i in widths.indices) o[i + 1] = o[i] + widths[i] }

    fun width(column: Int): Float = when {
        column < 0 || column >= SheetRefs.MAX_COLUMNS -> 0f
        column < widths.size -> widths[column]
        else -> tailWidth
    }

    fun format(column: Int): Int = when {
        column < 0 || column >= SheetRefs.MAX_COLUMNS -> -1
        column < formats.size -> formats[column]
        else -> tailFormat
    }

    /** How far [column]'s left edge is from the sheet's, in points. */
    fun offset(column: Int): Double {
        val c = column.coerceIn(0, SheetRefs.MAX_COLUMNS)
        return if (c <= widths.size) offsets[c] else offsets[widths.size] + (c - widths.size).toDouble() * tailWidth
    }

    /** The column [x] points from the sheet's left edge is in. */
    fun columnAt(x: Double): Int {
        val end = offsets[widths.size]
        if (x >= end) {
            if (tailWidth <= 0f) return SheetRefs.MAX_COLUMNS - 1
            return (widths.size + floor((x - end) / tailWidth)).coerceAtMost(SheetRefs.MAX_COLUMNS - 1.0).toInt()
        }
        var low = 0
        var high = widths.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (offsets[mid] <= x) low = mid else high = mid - 1
        }
        return low
    }
}

/**
 * Every row's height in points (0 when hidden): rows listed with their own height, the others the
 * sheet's default. Kept as sorted arrays, so a sheet with a million rows takes a few megabytes.
 */
internal class RowHeights(private val rows: IntArray, private val heights: FloatArray, private val default: Float) {
    /** For each listed row, the heights of the listed rows before it less the default's. */
    private val extra = DoubleArray(rows.size + 1).also { e -> for (i in rows.indices) e[i + 1] = e[i] + heights[i] - default }

    fun height(row: Int): Float {
        val i = rows.binarySearch(row)
        return if (i >= 0) heights[i] else default
    }

    /** How far [row]'s top edge is from the sheet's, in points. */
    fun offset(row: Int): Double {
        val i = rows.binarySearch(row).let { if (it >= 0) it else -it - 1 }
        return default.toDouble() * row + extra[i]
    }

    /** The row [y] points from the sheet's top edge is in. */
    fun rowAt(y: Double): Int {
        var low = 0
        var high = SheetRefs.MAX_ROWS - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (offset(mid) <= y) low = mid else high = mid - 1
        }
        return low
    }
}

/** A picture on a sheet: part [path], cropped by [crop] as in Office, in [rect] (points from the sheet's top left corner). */
internal class SheetPicture(val path: String, val crop: FloatArray?, val rect: RectF)

/** Margins in points. */
internal class SheetMargins(val left: Float, val right: Float, val top: Float, val bottom: Float, val header: Float, val footer: Float)

/** A sheet's header and footer texts (in Excel's codes, such as "&CPage &P"), and when each applies. */
internal class HeaderFooter(
    val oddHeader: String?,
    val oddFooter: String?,
    val evenHeader: String?,
    val evenFooter: String?,
    val firstHeader: String?,
    val firstFooter: String?,
    val differentFirst: Boolean,
    val differentOddEven: Boolean,
    val scaleWithDoc: Boolean,
)

/** How a sheet prints, as its file says. */
internal class PageSetup(
    /** The paper's short and long sides in points, or null when the file doesn't say (US Letter). */
    val paper: Pair<Double, Double>?,
    /** True for landscape, false for portrait, null when the file doesn't say (portrait). */
    val landscape: Boolean?,
    val scale: Int,
    /** "Fit to" [fitWidth] pages wide by [fitHeight] tall (0 for any number), instead of [scale]. */
    val fitToPage: Boolean,
    val fitWidth: Int,
    val fitHeight: Int,
    val margins: SheetMargins,
    val centerHorizontally: Boolean,
    val centerVertically: Boolean,
    val downThenOver: Boolean,
    val firstPageNumber: Int?,
    val headerFooter: HeaderFooter?,
)

/** Which columns each row has something on paper in, for leaving out pages with nothing on them. */
internal class RowContent(private val rows: IntArray, private val first: IntArray, private val last: IntArray) {
    /** Whether any row from [top] to [bottom] has something in a column from [left] to [right]. */
    fun any(top: Int, bottom: Int, left: Int, right: Int): Boolean {
        var i = rows.binarySearch(top).let { if (it >= 0) it else -it - 1 }
        while (i < rows.size && rows[i] <= bottom) {
            if (first[i] <= right && last[i] >= left) return true
            i++
        }
        return false
    }

    /** Calls [action] with each row from [top] to [bottom] that has something, in order, and its first and last columns with something. */
    fun forEach(top: Int, bottom: Int, action: (row: Int, first: Int, last: Int) -> Unit) {
        var i = rows.binarySearch(top).let { if (it >= 0) it else -it - 1 }
        while (i < rows.size && rows[i] <= bottom) {
            action(rows[i], first[i], last[i])
            i++
        }
    }
}

/**
 * Everything a worksheet's pages are laid out from, read in a first pass through it: its columns and
 * rows, the cells with anything on paper ([used]), merged cells, pictures, page breaks and how it prints.
 */
internal class SheetPlan(
    val name: String,
    val part: String,
    val columns: ColumnWidths,
    val rows: RowHeights,
    val used: SheetRange?,
    /** Each merged block once, in the order the file gives them. */
    val merges: List<SheetRange>,
    val pictures: List<SheetPicture>,
    val setup: PageSetup,
    /** Rows and columns a page break comes before, from 0. */
    val rowBreaks: Set<Int>,
    val columnBreaks: Set<Int>,
    val content: RowContent,
    /** Whether the sheet shows gridlines on screen, and whether it is set to print them. */
    val gridlinesShown: Boolean,
    val gridlinesPrinted: Boolean,
    /** Cells whose values don't show: data bars and icon sets can show only the bar or the icon. */
    val valuesHidden: List<SheetRange>,
)

/**
 * The first pass through a worksheet ([plan]): everything but drawing. Rows whose height wasn't set
 * by hand grow to fit their wrapped text and larger fonts, as Excel fits them. Pictures are read from
 * the sheet's drawing; charts and shapes are left out. A sheet with more row elements than a sheet has
 * rows, which only a crafted file has, is refused as [OfficeTooLarge].
 */
internal class SheetPlanner(
    private val pkg: OfficePackage,
    private val styles: SheetStyles,
    private val strings: SharedStrings,
    private val texts: CellTexts,
    private val theme: OfficeTheme,
    private val date1904: Boolean,
    private val check: () -> Unit,
) {
    /** Plans sheet [name], the worksheet part [part]. */
    fun plan(name: String, part: String): SheetPlan {
        val digit = SheetUnits.digitWidth(styles.defaultFont.name, styles.defaultFont.size.toDouble())
        var defaultWidth = SheetUnits.defaultColumnPoints(8, digit)
        var defaultHeight: Float? = null
        var zeroHeight = false
        val columnSpans = ArrayList<IntArray>()
        val columnWidths = ArrayList<Float?>()
        val columnHidden = ArrayList<Boolean>()
        val rowIndices = IntList()
        val rowHeights = FloatList()
        val rowFlags = IntList()
        val fits = Fits()
        val contentRows = IntList()
        val contentFirst = IntList()
        val contentLast = IntList()
        var used: SheetRange? = null
        val merges = LinkedHashSet<SheetRange>()
        val valuesHidden = ArrayList<SheetRange>()
        val rowBreaks = HashSet<Int>()
        val columnBreaks = HashSet<Int>()
        var drawing: String? = null
        var fitToPage = false
        var gridlinesShown = true
        var reach = -1
        var printOptions = BooleanArray(3)
        var margins = SheetMargins(0.7f * 72, 0.7f * 72, 0.75f * 72, 0.75f * 72, 0.3f * 72, 0.3f * 72)
        var setupAttrs: Map<String, String> = emptyMap()
        var headerFooter: HeaderFooter? = null
        var columns: ColumnWidths? = null
        val reader = RowReader(strings, date1904)

        fun columns(): ColumnWidths = columns ?: run {
            val size = (columnSpans.maxOfOrNull { it[1] } ?: -1) + 1
            val widths = FloatArray(size) { defaultWidth.toFloat() }
            val formats = IntArray(size) { -1 }
            for ((i, span) in columnSpans.withIndex()) {
                check()
                val width = when {
                    columnHidden[i] -> 0f
                    else -> columnWidths[i] ?: defaultWidth.toFloat()
                }
                for (c in span[0]..span[1]) {
                    widths[c] = width
                    formats[c] = span[2]
                }
            }
            // The columns after those defined are as wide as the default, unless the last ones defined
            // reach the sheet's end; at the end, those like the columns after them are kept as one.
            val reachesEnd = size == SheetRefs.MAX_COLUMNS
            val tailWidth = if (reachesEnd) widths[size - 1] else defaultWidth.toFloat()
            val tailFormat = if (reachesEnd) formats[size - 1] else -1
            var kept = size
            while (kept > 0 && widths[kept - 1] == tailWidth && formats[kept - 1] == tailFormat) kept--
            ColumnWidths(widths.copyOf(kept), formats.copyOf(kept), tailWidth, tailFormat).also { columns = it }
        }

        pkg.xml(part, check)?.use { x ->
            x.children {
                when (x.name) {
                    "sheetPr" -> x.children { if (x.name == "pageSetUpPr") fitToPage = x.flag("fitToPage", missing = false) }
                    "sheetViews" -> x.children { if (x.name == "sheetView") gridlinesShown = x.flag("showGridLines", missing = true) }
                    "sheetFormatPr" -> {
                        x.attr("defaultRowHeight")?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.let { defaultHeight = it.coerceAtMost(RowReader.MAX_ROW_HEIGHT) }
                        zeroHeight = x.flag("zeroHeight", missing = false)
                        val base = x.intAttr("baseColWidth")
                        val given = x.attr("defaultColWidth")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
                        defaultWidth = given?.let { SheetUnits.columnPoints(it, digit) } ?: SheetUnits.defaultColumnPoints(base ?: 8, digit)
                    }
                    "cols" -> x.children {
                        // Excel writes one for each column at most: any more come from a crafted file.
                        if (x.name != "col" || columnSpans.size >= SheetRefs.MAX_COLUMNS) return@children
                        val min = (x.intAttr("min") ?: return@children) - 1
                        val max = ((x.intAttr("max") ?: (min + 1)) - 1).coerceAtMost(SheetRefs.MAX_COLUMNS - 1)
                        if (min !in 0..max) return@children
                        val width = x.attr("width")?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
                        columnSpans += intArrayOf(min, max, x.intAttr("style") ?: -1)
                        columnWidths += width?.let { SheetUnits.columnPoints(it, digit).toFloat() }
                        columnHidden += x.flag("hidden", missing = false) || (width != null && width <= 0.0)
                    }
                    "sheetData" -> {
                        val widths = columns()
                        val fallbackHeight = defaultHeight ?: rowHeightFor(styles.defaultFont)
                        x.children {
                            if (x.name != "row") return@children
                            check()
                            val row = reader.read(x)
                            rowIndices.add(row.index)
                            rowHeights.add(row.height ?: Float.NaN)
                            rowFlags.add((if (row.custom) CUSTOM else 0) or (if (row.hidden) HIDDEN else 0))
                            if (rowIndices.size > SheetRefs.MAX_ROWS) throw OfficeTooLarge()
                            var first = -1
                            var last = -1
                            for (cell in row.cells) {
                                val format = styles.format(cell.format)
                                // Hidden cells print nothing, so they don't make a page.
                                if ((cell.kind == null && !format.showsEmpty) || row.hidden || widths.width(cell.column) <= 0f) continue
                                if (first < 0) first = cell.column
                                last = cell.column
                                if (cell.kind != null && !row.custom) {
                                    fitting(cell, format, widths.width(cell.column), sized = row.height != null)
                                        ?.takeIf { it > (row.height ?: fallbackHeight) + 0.1f }
                                        ?.let { fits.add(row.index, cell.column, it) }
                                }
                            }
                            if (first >= 0) {
                                // Text running on past the row's last cell with something in it prints there too.
                                val rowReach = overflowReach(row, widths)
                                reach = max(reach, rowReach)
                                contentRows.add(row.index)
                                contentFirst.add(first)
                                contentLast.add(max(last, rowReach))
                                val cells = SheetRange(row.index, first, row.index, last)
                                used = used?.union(cells) ?: cells
                            }
                        }
                    }
                    "mergeCells" -> x.children { if (x.name == "mergeCell") x.attr("ref")?.let(SheetRefs::range)?.let { merges += it } }
                    "conditionalFormatting" -> readValuesHidden(x)?.let { valuesHidden += it }
                    "printOptions" -> printOptions = booleanArrayOf(
                        x.flag("horizontalCentered", missing = false), x.flag("verticalCentered", missing = false), x.flag("gridLines", missing = false),
                    )
                    "pageMargins" -> {
                        fun inches(key: String, default: Float) = (x.attr(key)?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f }?.coerceAtMost(20f) ?: default) * 72
                        margins = SheetMargins(inches("left", 0.7f), inches("right", 0.7f), inches("top", 0.75f), inches("bottom", 0.75f), inches("header", 0.3f), inches("footer", 0.3f))
                    }
                    "pageSetup" -> setupAttrs = PAGE_SETUP_KEYS.mapNotNull { key -> x.attr(key)?.let { key to it } }.toMap()
                    "headerFooter" -> headerFooter = readHeaderFooter(x)
                    "rowBreaks" -> x.children { if (x.name == "brk") x.intAttr("id")?.takeIf { it > 0 }?.let { rowBreaks += it } }
                    "colBreaks" -> x.children { if (x.name == "brk") x.intAttr("id")?.takeIf { it > 0 }?.let { columnBreaks += it } }
                    "drawing" -> drawing = pkg.target(part, x.relId())
                }
            }
        }
        val widths = columns()
        val default = if (zeroHeight) 0f else defaultHeight ?: rowHeightFor(styles.defaultFont)
        val blocks = merges.toList()
        val rows = rowHeights(rowIndices, rowHeights, rowFlags, fits, blocks, default, defaultHeight ?: rowHeightFor(styles.defaultFont))
        val pictures = drawing?.let { readPictures(it, widths, rows) }.orEmpty()
        val content = rowContent(contentRows, contentFirst, contentLast)
        // A merged block prints whole when anything in it does, such as a title centred across it.
        for (merge in blocks) {
            check()
            if (content.any(merge.firstRow, merge.lastRow, merge.firstColumn, merge.lastColumn)) used = used?.union(merge) ?: merge
        }
        // Text running on past the last column with anything in it still prints: the area reaches as far.
        used = used?.let { if (reach > it.lastColumn) it.copy(lastColumn = reach) else it }
        for (picture in pictures) {
            val r = picture.rect
            val covered = SheetRange(rows.rowAt(r.top.toDouble()), widths.columnAt(r.left.toDouble()), rows.rowAt(max(r.top, r.bottom - 0.01f).toDouble()), widths.columnAt(max(r.left, r.right - 0.01f).toDouble()))
            used = used?.union(covered) ?: covered
        }
        return SheetPlan(
            name, part, widths, rows, used, blocks, pictures,
            pageSetup(setupAttrs, fitToPage, margins, printOptions, headerFooter),
            rowBreaks, columnBreaks,
            content,
            gridlinesShown, printOptions[2],
            valuesHidden,
        )
    }

    /**
     * The cells whose values a conditional format ([x], the reader being at it) hides: those of a data
     * bar or an icon set set to show only the bar or the icon. Null when it hides none.
     */
    private fun readValuesHidden(x: OfficeXml): List<SheetRange>? {
        val ranges = x.attr("sqref")
        var hides = false
        x.children {
            if (x.name == "cfRule") x.children { if ((x.name == "dataBar" || x.name == "iconSet") && !x.flag("showValue", missing = true)) hides = true }
        }
        if (!hides || ranges == null) return null
        return ranges.splitToSequence(' ').filter { it.isNotEmpty() }.take(MAX_HIDDEN_RANGES).mapNotNull(SheetRefs::range).toList()
    }

    /**
     * The last column the text of [row]'s cells runs on into, past their own (see SheetPainter's
     * placing of text), or -1 when none does. Merged cells, read later, aren't known yet: the column
     * found is at most as far as the text could run. A hidden column's text doesn't print, so it
     * doesn't run on, but it still stops the text of the cells before it.
     */
    private fun overflowReach(row: SheetRow, widths: ColumnWidths): Int {
        var reach = -1
        var nextFilled = SheetRefs.MAX_COLUMNS
        for (k in row.cells.indices.reversed()) {
            val cell = row.cells[k]
            if (cell.kind == null) continue
            val blocked = nextFilled
            nextFilled = cell.column
            if (cell.kind != CellKind.TEXT && cell.kind != CellKind.FORMULA || blocked == cell.column + 1) continue
            val format = styles.format(cell.format)
            if (format.wraps || format.shrink || format.rotation != 0) continue
            val width = widths.width(cell.column)
            if (width <= 0f) continue
            val shown = texts.shown(cell, format, width) ?: continue
            if (shown.align != SheetAlign.LEFT && shown.align != SheetAlign.CENTER) continue
            val need = texts.width(shown) + 2 * CellTexts.PAD + format.indent * texts.indentStep
            if (need <= width) continue
            val start = widths.offset(cell.column)
            val end = if (shown.align == SheetAlign.CENTER) start + (width + need) / 2 else start + need
            reach = max(reach, minOf(blocked - 1, widths.columnAt(end)))
        }
        return reach
    }

    /** Which columns each row has something in, in the order of the rows, as [RowContent] looks them up. */
    private fun rowContent(rows: IntList, first: IntList, last: IntList): RowContent {
        if ((1 until rows.size).all { rows[it - 1] < rows[it] }) return RowContent(rows.toArray(), first.toArray(), last.toArray())
        // Rows out of order, in a file Excel wouldn't write.
        val order = (0 until rows.size).sortedBy { rows[it] }
        return RowContent(IntArray(order.size) { rows[order[it]] }, IntArray(order.size) { first[order[it]] }, IntArray(order.size) { last[order[it]] })
    }

    /**
     * How tall [cell]'s row has to be for its text, in points, when it is text that wraps, rich text or
     * text larger than the default font; null for a cell that fits any row the default font fits. A row
     * whose file gives its height ([sized]) has been fitted by Excel already: only text that wraps is
     * measured there, as the phone's fonts can wrap it onto more lines.
     */
    private fun fitting(cell: SheetCell, format: CellFormat, width: Float, sized: Boolean): Float? {
        val wraps = format.wraps || format.rotation == CellTexts.STACKED
        if (sized && !wraps) return null
        val rich = cell.text?.runs != null
        if (!wraps && !rich && format.font.size <= styles.defaultFont.size) return null
        if (format.rotation in 1..180) return null
        val shown = texts.shown(cell, format, width) ?: return null
        if (shown.text.isEmpty()) return null
        return texts.height(shown, format, width)
    }

    /** The height Excel gives a row in [font] when the sheet doesn't say: a line's, in whole pixels. */
    private fun rowHeightFor(font: SheetFont): Float = (texts.lineHeight(font) / 0.75f).roundToInt() * 0.75f

    /**
     * Every row's height: hidden rows none, rows set by hand as set, the others as given (or the
     * default) but at least as tall as their cells need ([fits]), except cells merged with others,
     * which Excel doesn't fit rows to.
     */
    private fun rowHeights(
        indices: IntList,
        heights: FloatList,
        flags: IntList,
        fits: Fits,
        merges: List<SheetRange>,
        default: Float,
        listedDefault: Float,
    ): RowHeights {
        // How tall each row with cells to fit needs to be, in order of the rows, found by going down the
        // rows and the merged blocks together: each block is kept from its first row until past its last.
        val neededRows = IntList()
        val neededHeights = FloatList()
        val blocks = merges.filter { it.firstRow != it.lastRow || it.firstColumn != it.lastColumn }.sortedBy { it.firstRow }
        val over = ArrayList<SheetRange>()
        var nextBlock = 0
        for (i in fits.inRowOrder()) {
            val row = fits.rows[i]
            if (neededRows.size == 0 || neededRows[neededRows.size - 1] != row) {
                check()
                over.removeAll { it.lastRow < row }
                while (nextBlock < blocks.size && blocks[nextBlock].firstRow <= row) {
                    if (blocks[nextBlock].lastRow >= row) over += blocks[nextBlock]
                    nextBlock++
                }
                neededRows.add(row)
                neededHeights.add(0f)
            }
            if (merged(fits.first[i], fits.last[i], over)) continue
            val last = neededHeights.size - 1
            neededHeights[last] = max(neededHeights[last], fits.heights[i])
        }
        val count = indices.size
        // Rows are in order in any file Excel can open; those of one that isn't are put in order.
        val sorted = (1 until count).all { indices[it - 1] <= indices[it] }
        val order: IntArray = if (sorted) IntArray(count) { it } else (0 until count).sortedBy { indices[it] }.toIntArray()
        val rows = IntList()
        val values = FloatList()
        var nextNeeded = 0
        for (i in order) {
            val row = indices[i]
            if (rows.size > 0 && rows[rows.size - 1] == row) continue
            while (nextNeeded < neededRows.size && neededRows[nextNeeded] < row) nextNeeded++
            val needed = if (nextNeeded < neededRows.size && neededRows[nextNeeded] == row) neededHeights[nextNeeded] else 0f
            val flag = flags[i]
            val given = heights[i]
            val height = when {
                flag and HIDDEN != 0 -> 0f
                flag and CUSTOM != 0 -> if (given.isNaN()) listedDefault else given
                else -> max(if (given.isNaN()) listedDefault else given, needed)
            }
            rows.add(row)
            values.add(height.coerceAtMost(RowReader.MAX_ROW_HEIGHT))
        }
        return RowHeights(rows.toArray(), values.toArray(), default)
    }

    /** Whether every column from [first] to [last] is in one of the merged blocks [over]. */
    private fun merged(first: Int, last: Int, over: List<SheetRange>): Boolean {
        var column = first
        while (column <= last) {
            val block = over.firstOrNull { column in it.firstColumn..it.lastColumn } ?: return false
            column = block.lastColumn + 1
        }
        return true
    }

    private fun readHeaderFooter(x: OfficeXml): HeaderFooter {
        val differentFirst = x.flag("differentFirst", missing = false)
        val differentOddEven = x.flag("differentOddEven", missing = false)
        val scaleWithDoc = x.flag("scaleWithDoc", missing = true)
        val texts = HashMap<String, String>()
        // Excel keeps each to 255 characters. One far longer, in a crafted file, would take minutes to lay out.
        x.children { texts[x.name] = SheetStrings.unescape(x.text().take(MAX_HEADER_CHARS)) }
        return HeaderFooter(
            texts["oddHeader"], texts["oddFooter"], texts["evenHeader"], texts["evenFooter"], texts["firstHeader"], texts["firstFooter"],
            differentFirst, differentOddEven, scaleWithDoc,
        )
    }

    private fun pageSetup(attrs: Map<String, String>, fitToPage: Boolean, margins: SheetMargins, options: BooleanArray, headerFooter: HeaderFooter?): PageSetup {
        fun int(key: String) = attrs[key]?.trim()?.toIntOrNull()
        val width = SheetPaper.length(attrs["paperWidth"])
        val height = SheetPaper.length(attrs["paperHeight"])
        val paper = if (width != null && height != null) minOf(width, height) to maxOf(width, height) else int("paperSize")?.let(SheetPaper::size)
        val landscape = when (attrs["orientation"]) {
            "landscape" -> true
            "portrait" -> false
            else -> null
        }
        val useFirst = attrs["useFirstPageNumber"]?.let { it == "1" || it == "true" } == true
        return PageSetup(
            paper = paper,
            landscape = landscape,
            scale = (int("scale") ?: 100).coerceIn(10, 400),
            fitToPage = fitToPage,
            fitWidth = (int("fitToWidth") ?: 1).coerceAtLeast(0),
            fitHeight = (int("fitToHeight") ?: 1).coerceAtLeast(0),
            margins = margins,
            centerHorizontally = options[0],
            centerVertically = options[1],
            downThenOver = attrs["pageOrder"] != "overThenDown",
            firstPageNumber = if (useFirst) int("firstPageNumber") else null,
            headerFooter = headerFooter,
        )
    }

    /**
     * The pictures in drawing part [path], placed by their anchors: between two cells, from a cell
     * with a size, or at a place on the sheet. Charts, shapes and groups are left out.
     */
    private fun readPictures(path: String, columns: ColumnWidths, rows: RowHeights): List<SheetPicture> {
        val pictures = ArrayList<SheetPicture>()
        val scheme: (String) -> Int? = { theme.colors[it] }
        pkg.xml(path, check)?.use { x ->
            x.children {
                val anchor = x.name
                if (anchor != "twoCellAnchor" && anchor != "oneCellAnchor" && anchor != "absoluteAnchor") return@children
                var from: Pair<Double, Double>? = null
                var to: Pair<Double, Double>? = null
                var size: Pair<Double, Double>? = null
                var image: Fill.Image? = null
                fun marker(): Pair<Double, Double> {
                    var column = 0
                    var columnOffset = 0L
                    var row = 0
                    var rowOffset = 0L
                    x.children {
                        val value = x.text().trim()
                        when (x.name) {
                            "col" -> column = value.toIntOrNull()?.coerceIn(0, SheetRefs.MAX_COLUMNS - 1) ?: 0
                            "colOff" -> columnOffset = value.toLongOrNull() ?: 0L
                            "row" -> row = value.toIntOrNull()?.coerceIn(0, SheetRefs.MAX_ROWS - 1) ?: 0
                            "rowOff" -> rowOffset = value.toLongOrNull() ?: 0L
                        }
                    }
                    return columns.offset(column) + OfficeUnits.emu(columnOffset) to rows.offset(row) + OfficeUnits.emu(rowOffset)
                }
                fun picture() {
                    x.children { if (x.name == "blipFill") (readFill(x, pkg, path, scheme) as? Fill.Image)?.let { image = it } }
                }
                x.children {
                    when (x.name) {
                        "from" -> from = marker()
                        "to" -> to = marker()
                        "pos" -> from = OfficeUnits.emu(x.longAttr("x") ?: 0L).toDouble() to OfficeUnits.emu(x.longAttr("y") ?: 0L).toDouble()
                        "ext" -> size = OfficeUnits.emu(x.longAttr("cx") ?: 0L).toDouble() to OfficeUnits.emu(x.longAttr("cy") ?: 0L).toDouble()
                        "pic" -> picture()
                        "AlternateContent" -> x.children { if (image == null) x.children { if (x.name == "pic") picture() } }
                    }
                }
                val start = from ?: return@children
                val picture = image ?: return@children
                val end = if (anchor == "twoCellAnchor") to else size?.let { (w, h) -> start.first + w to start.second + h }
                if (end == null || end.first <= start.first || end.second <= start.second) return@children
                pictures += SheetPicture(picture.path, picture.crop, RectF(start.first.toFloat(), start.second.toFloat(), end.first.toFloat(), end.second.toFloat()))
            }
        }
        return pictures
    }

    private companion object {
        const val CUSTOM = 1
        const val HIDDEN = 2
        const val MAX_HEADER_CHARS = 1024

        /** Far more blocks of cells than a conditional format has, unless crafted. */
        const val MAX_HIDDEN_RANGES = 10_000
        val PAGE_SETUP_KEYS = listOf(
            "paperSize", "paperWidth", "paperHeight", "scale", "fitToWidth", "fitToHeight", "orientation", "pageOrder",
            "firstPageNumber", "useFirstPageNumber",
        )
    }
}

/**
 * The cells rows grow to fit (see [SheetPlanner]): runs of cells side by side in a row that need the
 * same height, from column [first] to [last], kept without an object for each, as there can be millions.
 */
private class Fits {
    val rows = IntList()
    val first = IntList()
    val last = IntList()
    val heights = FloatList()

    /** The cell at [row] and [column] needs [height] points. */
    fun add(row: Int, column: Int, height: Float) {
        val n = rows.size
        if (n > 0 && rows[n - 1] == row && last[n - 1] == column - 1 && heights[n - 1] == height) {
            last[n - 1] = column
            return
        }
        rows.add(row)
        first.add(column)
        last.add(column)
        heights.add(height)
    }

    /** The runs' places, by row: as read in any file Excel can open, sorted in one that isn't. */
    fun inRowOrder(): IntArray {
        val count = rows.size
        if ((1 until count).all { rows[it - 1] <= rows[it] }) return IntArray(count) { it }
        return (0 until count).sortedBy { rows[it] }.toIntArray()
    }
}

/** A growable list of ints, without boxing each: rows can number a million. */
internal class IntList {
    private var items = IntArray(64)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    operator fun get(index: Int): Int = items[index]
    operator fun set(index: Int, value: Int) {
        items[index] = value
    }

    fun toArray(): IntArray = items.copyOf(size)
}

/** A growable list of floats, as [IntList]. */
internal class FloatList {
    private var items = FloatArray(64)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    operator fun get(index: Int): Float = items[index]
    operator fun set(index: Int, value: Float) {
        items[index] = value
    }

    fun toArray(): FloatArray = items.copyOf(size)
}
