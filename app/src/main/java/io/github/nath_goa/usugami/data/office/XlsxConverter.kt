package io.github.nath_goa.usugami.data.office

import android.graphics.Canvas
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.core.SheetRange
import io.github.nath_goa.usugami.core.SheetHeaders
import io.github.nath_goa.usugami.core.SheetRefs
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * How spreadsheets go onto pages, as chosen before converting them. Null means as each sheet's
 * file says.
 */
data class SheetOptions(
    val paper: PageSize? = null,
    /** True for landscape, false for portrait. */
    val landscape: Boolean? = null,
    /**
     * Shrinks a sheet's columns to fit the page's width, down to half size, so a wide sheet isn't cut
     * into narrow pages across; a sheet whose file says how to fit itself on pages keeps that.
     */
    val fitWidth: Boolean = true,
    /**
     * Light grey lines between the cells of every sheet that shows them on screen. Without, they print
     * only on the sheets set to print them, as in Excel. The sheets' own borders are always drawn.
     */
    val gridlines: Boolean = false,
)

/**
 * Excel workbooks (.xlsx, and .xlsm and templates, whose macros are left alone) to PDF, laid out on
 * the phone the way Excel prints them: each visible sheet that has anything on it starts a page, and
 * its print area (or everything on it, from A1) goes onto pages down and then across, with its title
 * rows and columns repeated, at its own paper size, orientation, margins and scale or "Fit to".
 *
 * What converts: cell values as their number formats show them (dates, times, percentages,
 * currencies, fractions...), and formulas' last values; fonts, sizes, bold, italic, underlined and
 * struck-through text, colours, rich text; fills, borders of every style, alignment, indents, wrapped,
 * shrunk and turned text; text running on into empty cells beside it; merged cells; column widths and
 * row heights, hidden rows, columns and sheets; manual page breaks; pictures placed on the sheet.
 *
 * Headers and footers print with their page numbers, page counts, dates, and sheet and file names.
 * Pages are numbered through the whole PDF, as Excel numbers a whole workbook printed at once: a
 * sheet goes on from the sheets before it unless it sets its own first page number.
 *
 * What is simplified: the phone's fonts stand in for the workbook's, drawn to their widths; patterned
 * and gradient fills become a plain colour; wrapped and rich text underlined twice is underlined
 * once. Charts, shapes, comments, conditional formatting, table styles, sparklines and header
 * pictures are left out, but the values that data bars and icon sets hide stay hidden.
 */
internal class XlsxConverter(
    private val pkg: OfficePackage,
    private val pdf: PagedPdf,
    private val images: OfficeImages,
    private val options: SheetOptions,
    /** The workbook's file name, for headers and footers that show it. */
    private val fileName: String,
    private val check: () -> Unit,
    private val progress: (page: Int, total: Int) -> Unit,
) {
    private lateinit var styles: SheetStyles
    private lateinit var strings: SharedStrings
    private lateinit var texts: CellTexts
    private lateinit var painter: SheetPainter
    private var date1904 = false
    private var drawn = 0

    /** A sheet as the workbook lists it: its name, whether it shows, and its part. */
    private class WorkbookSheet(val name: String, val visible: Boolean, val part: String?)

    /**
     * Converts every visible sheet with anything on it; returns the number of pages. Throws
     * [OfficeNothingToConvert] when there's none, and [OfficeTooLarge] when the pages would number
     * more than [MAX_PAGES].
     */
    fun convert(): Int {
        val main = pkg.mainPart() ?: "xl/workbook.xml"
        val sheets = ArrayList<WorkbookSheet>()
        // Defined names by the sheet they belong to (its place in the workbook's list).
        val printAreas = HashMap<Int, String>()
        val printTitles = HashMap<Int, String>()
        pkg.xml(main, check)?.use { x ->
            x.children {
                when (x.name) {
                    "workbookPr" -> date1904 = x.flag("date1904", missing = false)
                    "sheets" -> x.children {
                        if (x.name != "sheet") return@children
                        val relationship = x.relId()?.let { pkg.relationships(main)[it] }?.takeUnless { it.external }
                        val worksheet = relationship?.type?.endsWith("/worksheet") == true
                        sheets += WorkbookSheet(
                            x.attr("name").orEmpty(),
                            x.attr("state").let { it == null || it == "visible" },
                            relationship?.target?.takeIf { worksheet },
                        )
                    }
                    "definedNames" -> x.children {
                        if (x.name != "definedName") return@children
                        val name = x.attr("name")
                        val sheet = x.intAttr("localSheetId") ?: return@children
                        val text = x.text()
                        when (name) {
                            "_xlnm.Print_Area" -> printAreas[sheet] = text
                            "_xlnm.Print_Titles" -> printTitles[sheet] = text
                        }
                    }
                }
            }
        }
        val theme = OfficeTheme.read(pkg, pkg.targetOfType(main, "/theme"), check)
        // Numbers and dates are written as the phone's region writes them, as Excel writes them on a computer.
        val numbers = CellNumbers(Locale.getDefault(Locale.Category.FORMAT))
        styles = SheetStyles.read(pkg, pkg.targetOfType(main, "/styles"), theme, numbers, check)
        strings = SharedStrings.read(pkg, pkg.targetOfType(main, "/sharedStrings"), check)
        texts = CellTexts(styles, theme, numbers, date1904)
        painter = SheetPainter(styles, texts, images, options.gridlines)
        val planner = SheetPlanner(pkg, styles, strings, texts, theme, date1904, check)

        // Every sheet is laid out before any is drawn: the page count is known, and checked, first.
        val laidOut = ArrayList<List<AreaLayout>>()
        var total = 0
        for ((index, sheet) in sheets.withIndex()) {
            check()
            val part = sheet.part?.takeIf { sheet.visible && pkg.has(it) } ?: continue
            val plan = planner.plan(sheet.name, part)
            val used = plan.used ?: continue
            val titles = titles(printTitles[index])
            val layouts = ArrayList<AreaLayout>()
            for (area in areas(printAreas[index], used)) {
                check()
                val layout = AreaLayout.of(plan, area, titles, options, MAX_PAGES - total, check)
                total += layout.pages.size
                if (layout.pages.isNotEmpty()) layouts += layout
            }
            if (layouts.isNotEmpty()) laidOut += layouts
        }
        if (total == 0) throw OfficeNothingToConvert()
        // Headers and footers show the date and time the PDF is made, as the phone writes them.
        val now = Date()
        val date = DateFormat.getDateInstance(DateFormat.SHORT).format(now)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(now)
        var page = 0
        for (layouts in laidOut) {
            val name = layouts[0].plan.name
            val first = layouts[0].plan.setup.firstPageNumber ?: (page + 1)
            var number = 0
            for (layout in layouts) {
                draw(layout, total) { canvas ->
                    number++
                    page = first + number - 1
                    painter.drawHeaderFooter(canvas, layout, number, page, total) { shown, count -> SheetHeaders.Fields(shown, count, name, fileName, date, time) }
                }
            }
        }
        return pdf.pageCount
    }

    /**
     * The areas of a sheet that print: those of its print area ([printArea], a defined name's formula),
     * each on pages of its own, or everything from A1 to the last cell [used] reaches. Whole rows or
     * columns stop where the sheet's cells do. An area named more than once prints once.
     */
    private fun areas(printArea: String?, used: SheetRange): List<SheetRange> {
        val given = printArea?.let { SheetRefs.areas(it) }?.map { it.second }.orEmpty()
        if (given.isEmpty()) return listOf(SheetRange(0, 0, used.lastRow, used.lastColumn))
        return given.mapNotNull { area ->
            val lastRow = if (area.lastRow == SheetRefs.MAX_ROWS - 1) used.lastRow else area.lastRow
            val lastColumn = if (area.lastColumn == SheetRefs.MAX_COLUMNS - 1) used.lastColumn else area.lastColumn
            SheetRange(area.firstRow, area.firstColumn, lastRow, lastColumn).takeIf { it.firstRow <= it.lastRow && it.firstColumn <= it.lastColumn }
        }.distinct()
    }

    /** The rows and the columns repeated on each page, from a sheet's Print_Titles ([text]). */
    private fun titles(text: String?): Pair<IntRange?, IntRange?> {
        var rows: IntRange? = null
        var columns: IntRange? = null
        for ((_, range) in text?.let { SheetRefs.areas(it) }.orEmpty()) {
            if (range.firstColumn == 0 && range.lastColumn == SheetRefs.MAX_COLUMNS - 1) rows = range.firstRow..range.lastRow
            else if (range.firstRow == 0 && range.lastRow == SheetRefs.MAX_ROWS - 1) columns = range.firstColumn..range.lastColumn
        }
        return rows to columns
    }

    /**
     * Draws [layout]'s pages, reading the sheet once more for each run of pages that goes down it
     * (once for each band of columns when pages go down and then across), keeping only the rows of the
     * page being drawn, the title rows and the first cells of merged blocks.
     */
    private fun draw(layout: AreaLayout, total: Int, headerFooter: (Canvas) -> Unit) {
        val runs = ArrayList<MutableList<Pair<Int, Int>>>()
        for (page in layout.pages) {
            val run = runs.lastOrNull()
            if (run != null && run.last().first <= page.first) run += page else runs += mutableListOf(page)
        }
        val anchors = layout.plan.merges.mapTo(HashSet()) { SheetRefs.pack(it.firstRow, it.firstColumn) }
        for (run in runs) {
            val reader = RowReader(strings, date1904)
            val kept = HashMap<Long, SheetCell>()
            val titleRows = HashMap<Int, SheetRow>()
            val bandRows = HashMap<Int, SheetRow>()
            var next = 0
            fun drawUntil(row: Int) {
                while (next < run.size && layout.rowEnd(run[next].first) < row) {
                    val band = run[next].first
                    val rows = HashMap<Int, SheetRow>(bandRows).apply { putAll(titleRows) }
                    while (next < run.size && run[next].first == band) {
                        drawPage(layout, run[next], PageCells(rows, kept), total, headerFooter)
                        next++
                    }
                    bandRows.clear()
                }
            }
            pkg.xml(layout.plan.part, check)?.use { x ->
                x.children {
                    if (x.name == "sheetData") x.children {
                        if (x.name != "row") return@children
                        check()
                        val row = reader.read(x)
                        for (cell in row.cells) {
                            val key = SheetRefs.pack(row.index, cell.column)
                            if (key in anchors) kept[key] = cell
                        }
                        if (layout.titleRows?.contains(row.index) == true) titleRows[row.index] = row
                        drawUntil(row.index)
                        if (next < run.size && row.index in layout.rowStarts[run[next].first]..layout.rowEnd(run[next].first)) bandRows[row.index] = row
                    }
                }
            }
            drawUntil(Int.MAX_VALUE)
        }
    }

    private fun drawPage(layout: AreaLayout, page: Pair<Int, Int>, cells: PageCells, total: Int, headerFooter: (Canvas) -> Unit) {
        check()
        val canvas = pdf.startPage(layout.pageWidth, layout.pageHeight)
        painter.draw(canvas, layout, page.first, page.second, cells)
        headerFooter(canvas)
        pdf.keepText(texts.takeDrawn())
        pdf.finishPage()
        progress(++drawn, total)
    }

    companion object {
        /** More pages than anyone would read on a phone, and about as many as it can make in a few minutes. */
        const val MAX_PAGES = 5000
    }
}
