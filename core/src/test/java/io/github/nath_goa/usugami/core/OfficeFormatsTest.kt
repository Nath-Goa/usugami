package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class OfficeFormatsTest {
    @Test fun unitsBecomePoints() {
        assertEquals(72f, OfficeUnits.emu(914_400L), 0.001f)
        assertEquals(36f, OfficeUnits.twips(720), 0.001f)
        assertEquals(11f, OfficeUnits.halfPoints(22), 0.001f)
        assertEquals(108f, OfficeUnits.cssLength("1.5in")!!, 0.001f)
        assertEquals(120f, OfficeUnits.cssLength(" 120pt ")!!, 0.001f)
        assertEquals(72f, OfficeUnits.cssLength("2.54cm")!!, 0.001f)
        assertEquals(75f, OfficeUnits.cssLength("100")!!, 0.001f)
        assertNull(OfficeUnits.cssLength("auto"))
        assertEquals(mapOf("width" to "120pt", "height" to "4cm", "position" to "absolute"), OfficeUnits.cssStyle("position:absolute; WIDTH:120pt;height:4cm;"))
    }

    @Test fun wordTabStopsAddToTheStyles() {
        // A footer style's centre and right stops; the paragraph adds one and clears the centre one.
        val style = TabStops(listOf(234f, 468f))
        val own = TabStops(listOf(504f), cleared = listOf(234f))
        assertEquals(listOf(468f, 504f), own.over(style).set)
        assertEquals(listOf(234f, 468f, 504f), TabStops(listOf(504f)).over(style).set)
        // Cleared further up, a stop set nearer still counts.
        assertEquals(listOf(234f, 468f, 504f), TabStops(listOf(234f)).over(own).over(style).set)
    }

    @Test fun textAfterTabsEndsAtRightStopsAndCentresOnCentreOnes() {
        // Word's header style: "Title", a 40-point "Draft" centred at 234, a 30-point "Page 1" against 468.
        val header = TabStops(listOf(234f, 468f), aligned = mapOf(234f to TabAlign.CENTER, 468f to TabAlign.RIGHT))
        assertEquals(listOf(214f, 438f), header.positions(0f, listOf(50f, 40f, 30f), 36f))
        // A left stop, then default stops past the last one; text too wide for its right stop goes on after the text before.
        assertEquals(listOf(100f, 180f), TabStops(listOf(100f)).positions(0f, listOf(10f, 70f, 5f), 36f))
        assertEquals(listOf(201f), TabStops(listOf(250f), aligned = mapOf(250f to TabAlign.RIGHT)).positions(0f, listOf(200f, 80f), 36f))
        // In other units: these stops' points ten times over.
        assertEquals(listOf(4380f), TabStops(listOf(468f), aligned = mapOf(468f to TabAlign.RIGHT)).positions(0f, listOf(500f, 300f), 360f, 10f))
        // A paragraph's own stops over its style's keep each stop's kind, and a cleared one loses it.
        assertEquals(mapOf(468f to TabAlign.RIGHT), TabStops(listOf(500f), cleared = listOf(234f)).over(header).aligned)
    }

    @Test fun percentagesAsOfficeAndStrictOpenXmlWriteThem() {
        assertEquals(90_000, OfficeUnits.percentage("90000"))
        assertEquals(90_000, OfficeUnits.percentage(" 90% "))
        assertEquals(-5_500, OfficeUnits.percentage("-5.5%"))
        assertNull(OfficeUnits.percentage("NaN%"))
        assertNull(OfficeUnits.percentage("lots"))
    }

    @Test fun recognizesZipsAndCompoundFiles() {
        assertEquals(OfficeContainer.ZIP, OfficeFileType.container(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0, 0, 0)))
        val compound = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        assertEquals(OfficeContainer.COMPOUND, OfficeFileType.container(compound))
        assertEquals(OfficeContainer.OTHER, OfficeFileType.container("{\\rtf1\\ansi".toByteArray()))
        assertEquals(OfficeContainer.OTHER, OfficeFileType.container(byteArrayOf(0x50, 0x4B)))
    }

    @Test fun findsTheEncryptedPackageStream() {
        val name = "EncryptedPackage".flatMap { listOf(it.code.toByte(), 0) }.toByteArray()
        // Far enough in that it straddles two reads.
        val encrypted = ByteArray(65_530) + name + ByteArray(100)
        assertTrue(OfficeFileType.isEncryptedPackage(ByteArrayInputStream(encrypted)))
        val legacy = ByteArray(70_000) + "WordDocument".flatMap { listOf(it.code.toByte(), 0) }.toByteArray()
        assertFalse(OfficeFileType.isEncryptedPackage(ByteArrayInputStream(legacy)))
        // Plain ASCII doesn't count: the directory stores names in UTF-16.
        assertFalse(OfficeFileType.isEncryptedPackage(ByteArrayInputStream("EncryptedPackage".toByteArray())))
    }

    @Test fun numbersListItemsInEachFormat() {
        val f = ListNumbering.Format.entries
        assertEquals(listOf("4", "04", "d", "D", "iv", "IV", "", ""), f.map { ListNumbering.format(4, it) })
        assertEquals("aa", ListNumbering.format(27, ListNumbering.Format.LOWER_LETTER))
        assertEquals("Z".repeat(30), ListNumbering.format(780, ListNumbering.Format.UPPER_LETTER))
        assertEquals("2000000000", ListNumbering.format(2_000_000_000, ListNumbering.Format.LOWER_LETTER))
        assertEquals("MCMXCIV", ListNumbering.format(1994, ListNumbering.Format.UPPER_ROMAN))
        assertEquals("4000", ListNumbering.format(4000, ListNumbering.Format.UPPER_ROMAN))
        assertEquals(ListNumbering.Format.LOWER_LETTER, ListNumbering.wordFormat("lowerLetter"))
        assertEquals(ListNumbering.Format.DECIMAL, ListNumbering.wordFormat("chineseCounting"))
    }

    @Test fun fillsInWordLevelText() {
        val formats = listOf(ListNumbering.Format.DECIMAL, ListNumbering.Format.LOWER_LETTER, ListNumbering.Format.LOWER_ROMAN)
        assertEquals("3.", ListNumbering.wordLabel("%1.", intArrayOf(3, 1, 1), formats))
        assertEquals("3.b)", ListNumbering.wordLabel("%1.%2)", intArrayOf(3, 2, 1), formats))
        assertEquals("(ii)", ListNumbering.wordLabel("(%3)", intArrayOf(1, 1, 2), formats))
        assertEquals("Step 2:", ListNumbering.wordLabel("Step %1:", intArrayOf(2), formats))
        // A level that isn't there stays as written.
        assertEquals("%5", ListNumbering.wordLabel("%5", intArrayOf(1), formats))
    }

    @Test fun readsPowerPointAutonumberSchemes() {
        assertEquals(Triple(ListNumbering.Format.DECIMAL, "", "."), ListNumbering.powerPointScheme("arabicPeriod"))
        assertEquals(Triple(ListNumbering.Format.LOWER_LETTER, "(", ")"), ListNumbering.powerPointScheme("alphaLcParenBoth"))
        assertEquals(Triple(ListNumbering.Format.UPPER_ROMAN, "", ")"), ListNumbering.powerPointScheme("romanUcParenR"))
        assertEquals(Triple(ListNumbering.Format.DECIMAL, "", ""), ListNumbering.powerPointScheme("arabicPlain"))
    }

    @Test fun readsPageFieldFormats() {
        assertEquals(ListNumbering.Format.LOWER_ROMAN, ListNumbering.fieldFormat(" PAGE  \\* roman  \\* MERGEFORMAT "))
        assertEquals(ListNumbering.Format.UPPER_ROMAN, ListNumbering.fieldFormat("PAGE \\* ROMAN"))
        assertEquals(ListNumbering.Format.LOWER_LETTER, ListNumbering.fieldFormat("PAGE \\*alphabetic"))
        assertEquals(ListNumbering.Format.UPPER_LETTER, ListNumbering.fieldFormat("PAGE \\* ALPHABETIC"))
        assertEquals(ListNumbering.Format.DECIMAL, ListNumbering.fieldFormat("PAGE \\* Arabic \\* MERGEFORMAT"))
        // No number format asked for: the section's own.
        assertNull(ListNumbering.fieldFormat("PAGE \\* MERGEFORMAT"))
        assertNull(ListNumbering.fieldFormat("PAGE"))
    }

    @Test fun symbolFontBulletsBecomeUnicode() {
        assertEquals("\u2022", SymbolFonts.bullet("Symbol", "\uF0B7"))
        assertEquals("\u25AA", SymbolFonts.bullet("Wingdings", "\uF0A7"))
        assertEquals("\u25AA", SymbolFonts.bullet("Wingdings", "\u00A7"))
        assertEquals("\u2713", SymbolFonts.bullet("Wingdings", "\u00FC"))
        // Unknown symbols become a plain bullet; ordinary fonts keep their text.
        assertEquals("\u2022", SymbolFonts.bullet("Wingdings", "#"))
        assertEquals("o", SymbolFonts.bullet("Courier New", "o"))
        assertEquals("\u2013", SymbolFonts.bullet("Arial", "\u2013"))
    }

    @Test fun insertedSymbolsBecomeTheirOwnCharacters() {
        // Insert > Symbol in the Symbol font: Greek letters and maths, not bullets.
        assertEquals("\u03BC", SymbolFonts.character("Symbol", "\uF06D"))
        assertEquals("\u00B0", SymbolFonts.character("Symbol", "\uF0B0"))
        assertEquals("\u00B1", SymbolFonts.character("Symbol", "\uF0B1"))
        assertEquals("\u03B1", SymbolFonts.character("Symbol", "\uF061"))
        assertEquals("\u2264", SymbolFonts.character("Symbol", "\uF0A3"))
        assertEquals("\u03A9", SymbolFonts.character("Symbol", "\uF057"))
        assertEquals("\u2022", SymbolFonts.character("Symbol", "\uF0B7"))
        assertEquals("7", SymbolFonts.character("Symbol", "\uF037"))
        assertEquals("\u03C0", SymbolFonts.character("symbol", "p"))
        // Check boxes in forms, ticked or not, in Wingdings and Wingdings 2.
        assertEquals("\u2611", SymbolFonts.character("Wingdings", "\uF0FE"))
        assertEquals("\u25FB", SymbolFonts.character("Wingdings", "\uF0A8"))
        assertEquals("\u2612", SymbolFonts.character("Wingdings", "\uF0FD"))
        assertEquals("\u25AA", SymbolFonts.character("Wingdings", "\uF0A7"))
        assertEquals("\u2611", SymbolFonts.character("Wingdings 2", "\uF052"))
        assertEquals("\u25A1", SymbolFonts.character("Wingdings 2", "\uF0A3"))
        assertEquals("\u2713", SymbolFonts.character("Wingdings 2", "\uF050"))
        // A symbol that isn't known shows as such, not as a bullet; ordinary characters stay.
        assertEquals("\uFFFD", SymbolFonts.character("Wingdings", "\uF023"))
        assertEquals("\uFFFD", SymbolFonts.character("Webdings", "\uF061"))
        assertEquals("\u2713", SymbolFonts.character("Segoe UI Symbol", "\u2713"))
        assertEquals("a", SymbolFonts.character("Arial", "a"))
        // List bullets are read as before.
        assertEquals("\u2022", SymbolFonts.bullet("Symbol", "\uF06D"))
        // Runs of text: a whole run in a symbol font, or the private-use characters of any run.
        assertEquals("\u2713 \u2713", SymbolFonts.text("Wingdings", "\u00FC \u00FC"))
        assertEquals("\u03B1+\u03B2", SymbolFonts.text("Symbol", "a+b"))
        assertEquals("Done \u2713", SymbolFonts.text("Calibri", "Done \uF0FC", symbol = "Wingdings"))
        assertEquals("abc", SymbolFonts.text("Calibri", "abc", symbol = "Wingdings"))
    }

    @Test fun parsesAndModifiesColors() {
        assertEquals(0xFF4472C4.toInt(), OfficeColors.hex("4472C4"))
        assertNull(OfficeColors.hex("auto"))
        assertNull(OfficeColors.hex("12345"))
        assertEquals(0xFFFFFF00.toInt(), OfficeColors.highlight("yellow"))
        assertNull(OfficeColors.highlight("none"))
        // "Accent 1, darker 25%" and "lighter 40%", as PowerPoint writes them.
        val accent = 0xFF4472C4.toInt()
        assertEquals(0xFF2F5597.toInt(), OfficeColors.modify(accent, listOf("lumMod" to 75_000)))
        assertEquals(0xFF8FAADC.toInt(), OfficeColors.modify(accent, listOf("lumMod" to 60_000, "lumOff" to 40_000)))
        assertEquals(0xFF808080.toInt(), OfficeColors.modify(0xFFFFFFFF.toInt(), listOf("shade" to 50_196)))
        assertEquals(0x80FF0000.toInt(), OfficeColors.modify(0xFFFF0000.toInt(), listOf("alpha" to 50_196)))
        assertEquals(0xFF00FFFF.toInt(), OfficeColors.modify(0xFFFF0000.toInt(), listOf("comp" to 0)))
        assertTrue(OfficeColors.isDark(0xFF1F3864.toInt()))
        assertFalse(OfficeColors.isDark(0xFFFFF2CC.toInt()))
    }

    @Test fun tableColumnsFitTheSpace() {
        assertEquals(listOf(100f, 200f), TableColumns.widths(listOf(100f, 200f), 2, 450f))
        // Too wide: scaled down, keeping proportions.
        assertEquals(listOf(150f, 300f), TableColumns.widths(listOf(200f, 400f), 2, 450f))
        // No grid: equal columns.
        assertEquals(listOf(150f, 150f, 150f), TableColumns.widths(emptyList(), 3, 450f))
        assertEquals(listOf(100f, 100f), TableColumns.widths(emptyList(), 2, 450f, preferred = 200f))
        // A missing column gets the average width.
        assertEquals(listOf(100f, 300f, 200f), TableColumns.widths(listOf(100f, 300f), 3, 600f))
    }

    @Test fun keepsSingleLinesOffPageEdges() {
        val lines = intArrayOf(10, 20, 30, 40, 50)
        assertEquals(5, PageBreaking.linesThatFit(lines, 55, avoidSingleLines = true, force = false))
        assertEquals(3, PageBreaking.linesThatFit(lines, 35, avoidSingleLines = true, force = false))
        // Four would fit, but the fifth would be alone on the next page.
        assertEquals(3, PageBreaking.linesThatFit(lines, 45, avoidSingleLines = true, force = false))
        assertEquals(4, PageBreaking.linesThatFit(lines, 45, avoidSingleLines = false, force = false))
        // A first line alone at the bottom moves on with the rest.
        assertEquals(0, PageBreaking.linesThatFit(lines, 15, avoidSingleLines = true, force = false))
        // Three lines with room for two: one line would be stranded either way, so all move.
        assertEquals(0, PageBreaking.linesThatFit(intArrayOf(10, 20, 30), 25, avoidSingleLines = true, force = false))
        // Unless they're lines in the middle of a paragraph laid out in parts.
        assertEquals(2, PageBreaking.linesThatFit(intArrayOf(10, 20, 30), 25, avoidSingleLines = true, force = false, keepLast = false))
        assertEquals(1, PageBreaking.linesThatFit(lines, 15, avoidSingleLines = true, force = false, keepFirst = false))
        // On an empty page something has to go, however tall.
        assertEquals(1, PageBreaking.linesThatFit(intArrayOf(900, 950), 800, avoidSingleLines = true, force = true))
        assertEquals(0, PageBreaking.linesThatFit(IntArray(0), 800, avoidSingleLines = true, force = true))
    }

    @Test fun groupsStretchTheirChildren() {
        val group = GroupFrame(OfficeRect(100f, 50f, 200f, 100f), 0f to 0f, 400f to 100f)
        assertEquals(OfficeRect(150f, 60f, 100f, 50f), group.place(OfficeRect(100f, 10f, 200f, 50f)))
        // Children placed with an offset of their own.
        val offset = GroupFrame(OfficeRect(0f, 0f, 100f, 100f), 1000f to 1000f, 100f to 100f)
        assertEquals(OfficeRect(10f, 20f, 30f, 40f), offset.place(OfficeRect(1010f, 1020f, 30f, 40f)))
        // A flipped group mirrors its children inside its rectangle, and their own flips and turns.
        val flipped = GroupFrame(OfficeRect(0f, 0f, 100f, 100f), 0f to 0f, 100f to 100f, flipH = true)
        assertEquals(OfficeRect(60f, 20f, 30f, 40f), flipped.place(OfficeRect(10f, 20f, 30f, 40f)))
        assertEquals(Triple(-30f, true, false), flipped.mirror(30f, flipH = false, flipV = false))
        val upsideDown = GroupFrame(OfficeRect(0f, 0f, 100f, 100f), 0f to 0f, 100f to 100f, flipV = true)
        assertEquals(OfficeRect(10f, 40f, 30f, 40f), upsideDown.place(OfficeRect(10f, 20f, 30f, 40f)))
        // Flipped both ways is half a turn: the child's turn stays.
        val both = GroupFrame(OfficeRect(0f, 0f, 100f, 100f), 0f to 0f, 100f to 100f, flipH = true, flipV = true)
        assertEquals(Triple(30f, false, true), both.mirror(30f, flipH = true, flipV = false))
    }

    @Test fun tableStylePartsAndBorders() {
        val flags = setOf("firstRow", "bandRow", "firstCol", "bandCol", "lastCol")
        // The header row and column aren't banded: banding starts after them.
        assertEquals(listOf("wholeTbl", "firstCol", "firstRow"), SlideTableStyles.parts(flags, 0, 4, 0, 1, 3))
        assertEquals(listOf("wholeTbl", "band1H", "band1V"), SlideTableStyles.parts(flags, 1, 4, 1, 2, 3))
        assertEquals(listOf("wholeTbl", "band2H", "band2V", "lastCol"), SlideTableStyles.parts(flags, 2, 4, 2, 3, 3))
        // The whole table's top and bottom are its outline; between rows its insideH draws.
        assertEquals("top", SlideTableStyles.border("wholeTbl", "top", outer = true))
        assertEquals("insideH", SlideTableStyles.border("wholeTbl", "top", outer = false))
        assertEquals("insideV", SlideTableStyles.border("wholeTbl", "right", outer = false))
        // A row's top and bottom are its own, its left and right only at the table's edges.
        assertEquals("bottom", SlideTableStyles.border("firstRow", "bottom", outer = false))
        assertEquals("insideV", SlideTableStyles.border("band1H", "left", outer = false))
        assertEquals("left", SlideTableStyles.border("band1H", "left", outer = true))
        // A column's sides are its own.
        assertEquals("right", SlideTableStyles.border("firstCol", "right", outer = false))
        assertEquals("insideH", SlideTableStyles.border("lastCol", "top", outer = false))
    }
}
