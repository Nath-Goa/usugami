package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpreadsheetsTest {

    @Test
    fun cellReferencesReadFromA1ToXfd1048576() {
        assertEquals(SheetRefs.pack(0, 0), SheetRefs.cell("A1"))
        assertEquals(SheetRefs.pack(2, 27), SheetRefs.cell("\$AB\$3"))
        val last = SheetRefs.cell("XFD1048576")!!
        assertEquals(SheetRefs.MAX_ROWS - 1, SheetRefs.rowOf(last))
        assertEquals(SheetRefs.MAX_COLUMNS - 1, SheetRefs.columnOf(last))
        // Past the last column or row, or not a reference at all.
        assertNull(SheetRefs.cell("XFE1"))
        assertNull(SheetRefs.cell("A1048577"))
        assertNull(SheetRefs.cell("A0"))
        assertNull(SheetRefs.cell("1A"))
        assertEquals("XFD", SheetRefs.columnName(SheetRefs.MAX_COLUMNS - 1))
        assertEquals("AA", SheetRefs.columnName(26))
    }

    @Test
    fun rangesAndDefinedNamesGiveTheirAreas() {
        assertEquals(SheetRange(0, 0, 19, 5), SheetRefs.range("\$A\$1:\$F\$20"))
        // Corners in either order, whole rows and whole columns.
        assertEquals(SheetRange(0, 0, 19, 5), SheetRefs.range("F20:A1"))
        assertEquals(SheetRange(0, 0, 1, SheetRefs.MAX_COLUMNS - 1), SheetRefs.range("\$1:\$2"))
        assertEquals(SheetRange(0, 1, SheetRefs.MAX_ROWS - 1, 2), SheetRefs.range("B:C"))
        val areas = SheetRefs.areas("'It''s, here'!\$A\$1:\$B\$2,'It''s, here'!\$D\$4,#REF!")
        assertEquals(listOf("It's, here" to SheetRange(0, 0, 1, 1), "It's, here" to SheetRange(3, 3, 3, 3)), areas)
    }

    @Test
    fun columnWidthsTurnIntoPointsAsExcelDrawsThem() {
        // 8.43 characters, stored as 9.140625, is Excel's 64 pixel column.
        assertEquals(48.0, SheetUnits.columnPoints(9.140625), 0.001)
        assertEquals(48.0, SheetUnits.defaultColumnPoints(8), 0.001)
        assertEquals(0.0, SheetUnits.columnPoints(0.0), 0.001)
        assertEquals(7, SheetUnits.digitWidth("Calibri", 11.0))
        assertEquals(7, SheetUnits.digitWidth("Arial", 10.0))
    }

    @Test
    fun rowsAndColumnsSplitIntoPagesThatFit() {
        val rows = DoubleArray(10) { 15.0 }
        assertArrayEquals(intArrayOf(0, 4, 8), SheetPages.bands(rows, space = 60.0))
        // At half size they all fit; a manual break still starts a page.
        assertArrayEquals(intArrayOf(0, 6), SheetPages.bands(rows, space = 80.0, scale = 0.5, breaks = setOf(6)))
        // A row taller than the page has one of its own; hidden rows take no room.
        assertArrayEquals(intArrayOf(0, 1, 2), SheetPages.bands(doubleArrayOf(10.0, 100.0, 0.0, 10.0), space = 50.0))
        // Title rows (the first two) repeat on the pages after them, taking their room.
        assertArrayEquals(intArrayOf(0, 4, 6, 8), SheetPages.bands(rows, space = 60.0, titleSize = 30.0, titleEnd = 1))
        assertEquals(3, SheetPages.count(rows, space = 60.0))
    }

    @Test
    fun fitToShrinksToTheLargestWholePercentThatFits() {
        val columns = DoubleArray(20) { 48.0 }
        val rows = DoubleArray(100) { 15.0 }
        // 960 points of columns on 480 points of paper: 50%.
        assertEquals(50, SheetPages.fitScale(columns, rows, 480.0, 700.0, pagesWide = 1, pagesTall = 0))
        // Never enlarged past the most asked for, never below 10%.
        assertEquals(100, SheetPages.fitScale(DoubleArray(2) { 48.0 }, rows, 480.0, 700.0, 1, 0))
        assertEquals(150, SheetPages.fitScale(DoubleArray(2) { 48.0 }, rows, 480.0, 700.0, 1, 0, most = 150))
        assertEquals(SheetPages.MIN_SCALE, SheetPages.fitScale(columns, DoubleArray(10_000) { 15.0 }, 480.0, 700.0, 1, 1))
        // Whole columns: 960 points fit two pages of 470 at 97%, ten columns a page.
        assertEquals(97, SheetPages.fitScale(DoubleArray(20) { 48.0 }, rows, 470.0, 700.0, 2, 0))
    }

    @Test
    fun pagesGoDownThenOverUnlessAskedOtherwise() {
        val pages = listOf(1 to 1, 0 to 1, 1 to 0, 0 to 0)
        assertEquals(listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), SheetPages.order(pages, downThenOver = true))
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0, 1 to 1), SheetPages.order(pages, downThenOver = false))
    }

    @Test
    fun paperSizesComeByNumberOrMeasure() {
        assertEquals(595.3, SheetPaper.size(9)!!.first, 0.1)
        assertEquals(792.0, SheetPaper.size(1)!!.second, 0.1)
        assertNull(SheetPaper.size(999))
        assertEquals(595.3, SheetPaper.length("210mm")!!, 0.1)
        assertEquals(612.0, SheetPaper.length("8.5in")!!, 0.1)
        assertNull(SheetPaper.length("wide"))
        // Sizes no paper has are left out, for the paper's number or Letter.
        assertNull(SheetPaper.length("1e308mm"))
        assertNull(SheetPaper.length("100000in"))
        assertNull(SheetPaper.length("0.0001mm"))
    }

    @Test
    fun coloursComeFromThePaletteAndTint() {
        assertEquals(0xFFFF0000.toInt(), SheetColors.indexed(10))
        assertEquals(0xFF000000.toInt(), SheetColors.indexed(64))
        // Excel puts the background theme colour first.
        assertEquals("lt1", SheetColors.themeKey(0))
        assertEquals("dk1", SheetColors.themeKey(1))
        // The alpha byte is ignored, as Excel ignores it.
        assertEquals(0xFF4472C4.toInt(), SheetColors.rgb("004472C4"))
        // Halfway to white, and halfway to black.
        assertEquals(0xFFFF8080.toInt(), SheetColors.tint(0xFFFF0000.toInt(), 0.5))
        assertEquals(0xFF800000.toInt(), SheetColors.tint(0xFFFF0000.toInt(), -0.5))
    }

    @Test
    fun datesOfTypeDBecomeExcelsDayNumbers() {
        assertEquals(1.0, SheetDates.serial("1900-01-01")!!, 0.0)
        // Excel's 29 February 1900 that never was puts later dates a day on.
        assertEquals(61.0, SheetDates.serial("1900-03-01")!!, 0.0)
        assertEquals(45366.5, SheetDates.serial("2024-03-15T12:00:00")!!, 1e-9)
        assertEquals(0.0, SheetDates.serial("1904-01-01", date1904 = true)!!, 0.0)
        assertEquals(0.25, SheetDates.serial("06:00:00")!!, 1e-9)
        assertNull(SheetDates.serial("15 March"))
    }

    @Test
    fun escapedCharactersComeBack() {
        assertEquals("Line\r\nnext", SheetStrings.unescape("Line_x000D_\nnext"))
        assertEquals("_x000D_", SheetStrings.unescape("_x005F_x000D_"))
        assertEquals("plain_text", SheetStrings.unescape("plain_text"))
    }

    @Test
    fun sharedFormulasMoveTheirRelativeReferences() {
        assertEquals("SUM(B2:C2)*\$D\$1", SheetFormulas.shift("SUM(B1:C1)*\$D\$1", 1, 0))
        assertEquals("B\$1+\$A2", SheetFormulas.shift("A\$1+\$A1", 1, 1))
        // Text, sheet names and function names stay; a reference off the sheet is #REF!.
        assertEquals("IF(A3=\"A1\",'B1 sheet'!A3,LOG10(A3))", SheetFormulas.shift("IF(A2=\"A1\",'B1 sheet'!A2,LOG10(A2))", 1, 0))
        assertEquals("#REF!+1", SheetFormulas.shift("A1+1", -1, 0))
        assertEquals("1E+5*C1", SheetFormulas.shift("1E+5*B1", 0, 1))
    }

    @Test
    fun headersAndFootersFillInTheirFields() {
        val fields = SheetHeaders.Fields(page = 2, pages = 5, sheet = "Sales", file = "Q3.xlsx", date = "1/2/26", time = "9:30")
        val parts = SheetHeaders.parts("&L&\"Arial,Bold\"&A&CPage &P of &N&R&D && &F", fields)
        assertEquals(listOf(HeaderRun("Sales", bold = true, font = "Arial")), parts[0])
        assertEquals("Page 2 of 5", parts[1].joinToString("") { it.text })
        assertEquals("1/2/26 & Q3.xlsx", parts[2].joinToString("") { it.text })
        // Text before any part is the centre's; sizes and colours change the look of what follows.
        val centre = SheetHeaders.parts("Draft &14&KFF0000&P+1", fields)[1]
        assertEquals(listOf(HeaderRun("Draft "), HeaderRun("3", size = 14f, color = 0xFF0000)), centre)
    }
}
