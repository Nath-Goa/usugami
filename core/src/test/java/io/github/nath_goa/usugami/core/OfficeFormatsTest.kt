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
    }
}
