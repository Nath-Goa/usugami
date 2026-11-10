package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.office.OfficeConverter
import io.github.nath_goa.usugami.data.office.OfficeEncrypted
import io.github.nath_goa.usugami.data.office.OfficeInput
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.data.office.OfficeLegacyFormat
import io.github.nath_goa.usugami.data.office.OfficeNotRecognized
import io.github.nath_goa.usugami.data.office.OfficeNothingToConvert
import io.github.nath_goa.usugami.data.office.SheetOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * Excel workbooks converted on the phone, checked by reading the PDFs back: what cells show, where
 * text goes, which pages there are and how big, and files refused. The workbooks are written here.
 */
class ExcelConverterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)
    private val converter = OfficeConverter(context, engine)
    private val work = File(context.cacheDir, "excel-test").apply { mkdirs() }
    private var nextId = 7001L
    private val locale = Locale.getDefault()

    // Numbers and dates are written as the phone's region writes them: the tests read them in English.
    @Before fun englishNumbers() {
        Locale.setDefault(Locale.US)
    }

    @After fun tearDown() {
        Locale.setDefault(locale)
        work.deleteRecursively()
    }

    private fun import(file: File): OfficeInput = converter.import(Uri.fromFile(file), nextId++, OfficeKind.EXCEL)

    private fun convert(file: File, options: SheetOptions = SheetOptions()): File =
        converter.convert(import(file), file.nameWithoutExtension, PageSize.A5, options, {}, {}).file

    /** Each page's text, with line breaks and runs of spaces as single spaces. */
    private fun pageTexts(file: File): List<String> = PDDocument.load(file).use { doc ->
        (1..doc.numberOfPages).map { n -> PDFTextStripper().apply { startPage = n; endPage = n }.getText(doc).replace(Regex("""\s+"""), " ") }
    }

    /** Each page's size in points. */
    private fun pageSizes(file: File): List<Pair<Float, Float>> = PDDocument.load(file).use { doc ->
        doc.pages.map { it.mediaBox.width to it.mediaBox.height }
    }

    /** Whether page [number] (from 1) of [file] has anything dark in [area], in points from its top left corner. */
    private fun dark(file: File, number: Int, area: RectF): Boolean = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use { renderer ->
            renderer.openPage(number - 1).use { page ->
                val scale = 3
                val bitmap = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ((area.top * scale).toInt() until (area.bottom * scale).toInt()).any { y ->
                    ((area.left * scale).toInt() until (area.right * scale).toInt()).any { x -> Color.luminance(bitmap.getPixel(x, y)) < 0.5f }
                }
            }
        }
    }

    /** The first letter of [word] on page [number] (from 1), in points from the page's top left corner. */
    private fun word(file: File, number: Int, word: String): TextPosition = PDDocument.load(file).use { doc ->
        val glyphs = ArrayList<TextPosition>()
        object : PDFTextStripper() {
            override fun processTextPosition(text: TextPosition) {
                glyphs += text
            }
        }.apply { startPage = number; endPage = number }.getText(doc)
        val text = glyphs.joinToString("") { it.unicode }
        val at = text.indexOf(word)
        assertTrue("\"$word\" is on page $number: $text", at >= 0)
        glyphs[at]
    }

    // ---- Writing small workbooks ----

    private class Sheet(val name: String, val body: String, val hidden: Boolean = false)

    private fun zip(name: String, parts: Map<String, String>): File = File(work, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, content) in parts) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }

    /** A workbook of [sheets] with [STYLES] and shared strings [strings]; [names] are its defined names' XML. */
    private fun workbook(name: String, vararg sheets: Sheet, strings: List<String> = emptyList(), names: String = ""): File {
        val parts = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/></Types>""",
            "_rels/.rels" to rels("officeDocument" to "xl/workbook.xml"),
            "xl/workbook.xml" to """<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="$MAIN" xmlns:r="$OFFICE_RELS"><sheets>""" +
                sheets.withIndex().joinToString("") { (i, s) -> """<sheet name="${s.name}" sheetId="${i + 1}"${if (s.hidden) " state=\"hidden\"" else ""} r:id="rId${i + 3}"/>""" } +
                "</sheets>${if (names.isEmpty()) "" else "<definedNames>$names</definedNames>"}</workbook>",
            "xl/_rels/workbook.xml.rels" to rels("styles" to "styles.xml", "sharedStrings" to "sharedStrings.xml", *sheets.indices.map { "worksheet" to "worksheets/sheet${it + 1}.xml" }.toTypedArray()),
            "xl/styles.xml" to STYLES,
            "xl/sharedStrings.xml" to """<?xml version="1.0" encoding="UTF-8"?><sst xmlns="$MAIN">${strings.joinToString("") { "<si><t>$it</t></si>" }}</sst>""",
        )
        sheets.forEachIndexed { i, s -> parts["xl/worksheets/sheet${i + 1}.xml"] = """<?xml version="1.0" encoding="UTF-8"?><worksheet xmlns="$MAIN" xmlns:r="$OFFICE_RELS">${s.body}</worksheet>""" }
        return zip(name, parts)
    }

    private fun rels(vararg rels: Pair<String, String>) =
        """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            rels.withIndex().joinToString("") { (i, r) -> """<Relationship Id="rId${i + 1}" Type="$OFFICE_RELS/${r.first}" Target="${r.second}"/>""" } +
            "</Relationships>"

    private fun text(ref: String, text: String, style: Int = 0) = """<c r="$ref" s="$style" t="inlineStr"><is><t>$text</t></is></c>"""
    private fun number(ref: String, value: Double, style: Int = 0) = """<c r="$ref" s="$style"><v>$value</v></c>"""
    private fun row(index: Int, vararg cells: String, attrs: String = "") = """<row r="$index"$attrs>${cells.joinToString("")}</row>"""

    // ---- Cells ----

    @Test fun cellsShowTheirValuesAsTheirFormatsSay() {
        val data = listOf(
            row(1, text("A1", "Shared:"), """<c r="B1" t="s"><v>0</v></c>"""),
            row(2, number("A2", 1234.5, 1), number("B2", 45366.0, 2), number("C2", 0.125, 3)),
            row(3, """<c r="A3" t="b"><v>1</v></c>""", """<c r="B3" t="e"><v>#DIV/0!</v></c>"""),
            // A formula with its last value, and one whose file kept none, which shows itself.
            row(4, """<c r="A4"><f>SUM(A2:A2)</f><v>1234.5</v></c>""", """<c r="B4"><f>A2*2</f></c>"""),
        )
        val columns = """<cols><col min="1" max="3" width="14" customWidth="1"/></cols>"""
        val pdf = convert(workbook("values.xlsx", Sheet("Values", "$columns<sheetData>${data.joinToString("")}</sheetData>"), strings = listOf("from the table")))
        val page = pageTexts(pdf).single()
        for (shown in listOf("Shared:", "from the table", "1,234.50", "15-Mar-24", "12.5%", "TRUE", "#DIV/0!", "1234.5", "=A2*2")) {
            assertTrue("\"$shown\" in $page", shown in page)
        }
    }

    @Test fun numbersAreGroupedAsThePhonesRegionGroupsThem() {
        Locale.setDefault(Locale.forLanguageTag("en-IN"))
        val columns = """<cols><col min="1" max="1" width="20" customWidth="1"/></cols>"""
        val pdf = convert(workbook("lakhs.xlsx", Sheet("Lakhs", "$columns<sheetData>${row(1, number("A1", 1234567.891, 1))}</sheetData>")))
        val page = pageTexts(pdf).single()
        assertTrue("\"12,34,567.89\" in $page", "12,34,567.89" in page)
    }

    @Test fun textRunsOnIntoEmptyCellsAndNumbersTooWideShowHashes() {
        val data = row(1, text("A1", "A sentence running on past its column"), number("E1", 123456789.123, 1)) +
            row(2, text("A2", "Stopped here by"), number("B2", 7.0))
        // Column B is wide, with B2's number on its right.
        val columns = """<col min="1" max="1" width="9.140625" customWidth="1"/><col min="2" max="2" width="30" customWidth="1"/><col min="3" max="5" width="9.140625" customWidth="1"/>"""
        val pdf = convert(workbook("overflow.xlsx", Sheet("Text", "<cols>$columns</cols><sheetData>$data</sheetData>")))
        val page = pageTexts(pdf).single()
        // Column A is 48 points wide: the sentence's last word starts well past it.
        val start = word(pdf, 1, "A sentence").xDirAdj
        assertTrue(word(pdf, 1, "column").xDirAdj - start > 48f)
        assertTrue("A number too wide for its column shows as #: $page", "###" in page && "123,456,789" !in page)
        // Text that would run into a cell with something in it is cut off at its own cell's edge: none of it shows in B2.
        val baseline = word(pdf, 1, "Stopped").yDirAdj
        val b2 = 0.7f * 72 + 48f
        assertFalse("A2's text shows in B2", dark(pdf, 1, RectF(b2 + 1f, baseline - 7f, b2 + 100f, baseline - 1f)))
    }

    @Test fun hebrewTextStartsAtItsCellsRightUnlessItIsSetToTheLeft() {
        // Column A is 210 points wide, from the page's 0.7 inch margin.
        val columns = """<cols><col min="1" max="1" width="40" customWidth="1"/></cols>"""
        val data = row(1, text("A1", "שלום")) + row(2, text("A2", "ברוך", 5)) + row(3, text("A3", "תודה", 6)) + row(4, text("A4", "Latin"))
        val pdf = convert(workbook("hebrew.xlsx", Sheet("Hebrew", "$columns<sheetData>$data</sheetData>")))
        val middle = 0.7f * 72 + 105f
        // Each word's first letter is its rightmost: on one line, wrapped, and wrapped at the left.
        assertTrue("General, on one line", word(pdf, 1, "ש").xDirAdj > middle + 50f)
        assertTrue("General, wrapped", word(pdf, 1, "ב").xDirAdj > middle + 50f)
        assertTrue("At the left, wrapped", word(pdf, 1, "ת").xDirAdj < middle)
        assertTrue("Latin text", word(pdf, 1, "Latin").xDirAdj < middle - 50f)
    }

    @Test fun mergedCellsShowTheirTextOnceAcrossThemAndWrappedTextGrowsItsRow() {
        val data = row(1, text("A1", "Centred title", 4), """<c r="B1" s="4"/>""", """<c r="C1" s="4"/>""", """<c r="D1" s="4"/>""") +
            row(2, text("A2", "alpha bravo charlie delta echo golf", 5), text("B2", "Beside"), text("E2", "SELF-EMPLOYED", 5))
        val columns = """<col min="1" max="4" width="9.140625" customWidth="1"/><col min="5" max="5" width="12" customWidth="1"/>"""
        val body = """<cols>$columns</cols><sheetData>$data</sheetData><mergeCells count="1"><mergeCell ref="A1:D1"/></mergeCells>"""
        val pdf = convert(workbook("merged.xlsx", Sheet("Merged", body)))
        // The title is centred across the four 48-point columns, not in the first.
        val title = word(pdf, 1, "Centred")
        val margin = 0.7f * 72
        assertTrue("centred at ${title.xDirAdj}", title.xDirAdj > margin + 48f && title.xDirAdj < margin + 96f)
        // The wrapped words go on lines one under another, in a row grown to fit them.
        assertTrue(word(pdf, 1, "golf").yDirAdj - word(pdf, 1, "alpha").yDirAdj > 20f)
        assertEquals(1, pageTexts(pdf).single().split("Centred").size - 1)
        // A word too long for its cell breaks after its hyphen, not inside the word after it.
        assertTrue(word(pdf, 1, "EMPLOYED").yDirAdj - word(pdf, 1, "SELF").yDirAdj > 10f)
    }

    @Test fun hiddenRowsColumnsAndSheetsAreLeftOut() {
        // The hidden column's text has an empty cell beside it to run on into, and C3's value only a data bar.
        val data = row(1, text("A1", "Shown"), text("B1", "HiddenColumn"), text("D1", "AlsoShown")) +
            row(2, text("A2", "HiddenRow"), attrs = """ hidden="1"""") +
            row(3, text("A3", "LastRow"), number("C3", 4321.0))
        val bar = """<conditionalFormatting sqref="C3"><cfRule type="dataBar" priority="1"><dataBar showValue="0"><cfvo type="min"/><cfvo type="max"/><color rgb="FF638EC6"/></dataBar></cfRule></conditionalFormatting>"""
        val body = """<cols><col min="2" max="2" width="20" hidden="1"/></cols><sheetData>$data</sheetData>$bar"""
        val pdf = convert(
            workbook(
                "hidden.xlsx",
                Sheet("First", body),
                Sheet("Secret", "<sheetData>${row(1, text("A1", "HiddenSheet"))}</sheetData>", hidden = true),
                Sheet("Second", "<sheetData>${row(1, text("A1", "SecondSheet"))}</sheetData>"),
                Sheet("Empty", "<sheetData/>"),
            ),
        )
        val pages = pageTexts(pdf)
        // Each visible sheet with something on it starts a page; the empty one makes none.
        assertEquals(2, pages.size)
        assertTrue(pages[0], "Shown" in pages[0] && "AlsoShown" in pages[0] && "LastRow" in pages[0])
        assertTrue(pages[1], "SecondSheet" in pages[1])
        assertFalse(pages.toString(), pages.any { "Hidden" in it || "4321" in it })
    }

    @Test fun pagesAreNumberedThroughTheWholeWorkbook() {
        val footer = "<headerFooter><oddFooter>&amp;CPage &amp;P of &amp;N</oddFooter></headerFooter>"
        val pages = pageTexts(
            convert(
                workbook(
                    "numbered.xlsx",
                    Sheet("One", "<sheetData>${row(1, text("A1", "First"))}</sheetData>$footer"),
                    Sheet("Two", "<sheetData>${row(1, text("A1", "Second"))}</sheetData>$footer"),
                ),
            ),
        )
        assertTrue(pages.toString(), "Page 1 of 2" in pages[0] && "Page 2 of 2" in pages[1])
    }

    @Test fun onlyPagesWithSomethingOnThemPrintHoweverFarTheSheetReaches() {
        // A sentence running on from A1 onto the page beside, and a cell at the sheet's last row and column.
        val sentence = "Quarterly figures for every branch are kept on this sheet, one row for each month, with totals for each region, Ending"
        val data = row(1, text("A1", sentence)) + row(1048576, text("XFD1048576", "Far"))
        val pages = pageTexts(convert(workbook("far.xlsx", Sheet("Far", "<sheetData>$data</sheetData>")), SheetOptions(fitWidth = false)))
        assertEquals(pages.toString(), 3, pages.size)
        assertTrue(pages[1], "Ending" in pages[1])
        assertTrue(pages[2], "Far" in pages[2])
    }

    @Test fun pagesFollowTheFilesPaperOrientationAndPrintTitles() {
        // 120 rows on landscape A4, with the first row repeated at the top of each page.
        val data = (1..120).joinToString("") { r -> row(r, if (r == 1) text("A1", "Heading") else text("A$r", "Row$r")) }
        val body = """<sheetData>$data</sheetData><pageSetup paperSize="9" orientation="landscape"/>"""
        val pdf = convert(workbook("titles.xlsx", Sheet("Long", body), names = """<definedName name="_xlnm.Print_Titles" localSheetId="0">Long!${'$'}1:${'$'}1</definedName>"""))
        val sizes = pageSizes(pdf)
        assertTrue("several pages", sizes.size >= 3)
        for ((w, h) in sizes) {
            assertEquals(842f, w, 1f)
            assertEquals(595f, h, 1f)
        }
        val pages = pageTexts(pdf)
        assertTrue(pages.all { "Heading" in it })
        assertTrue("Every row once", (2..120).all { r -> pages.count { Regex("""\bRow$r\b""").containsMatchIn(it) } == 1 })
        // A paper chosen for all of them goes over the file's.
        val letter = convert(workbook("letter.xlsx", Sheet("Long", body)), SheetOptions(paper = PageSize.LETTER, landscape = false))
        assertEquals(612f to 792f, pageSizes(letter).first())
    }

    @Test fun aWideSheetFitsThePageWidthUnlessAskedNotTo() {
        // Columns of 48 points on portrait Letter: 15 fit one page across when shrunk, while 30 would
        // be too small to read, so they go on two pages at half size, or on three unshrunk.
        fun sheet(columns: Int): Sheet {
            val data = (1..5).joinToString("") { r -> row(r, *(0 until columns).map { c -> number("${columnName(c)}$r", (r * 100 + c).toDouble()) }.toTypedArray()) }
            return Sheet("Wide", "<sheetData>$data</sheetData>")
        }
        val fitted = pageTexts(convert(workbook("fitted.xlsx", sheet(15))))
        assertEquals(1, fitted.size)
        assertTrue("514" in fitted[0])
        val halved = pageTexts(convert(workbook("halved.xlsx", sheet(30))))
        assertEquals(2, halved.size)
        val split = pageTexts(convert(workbook("split.xlsx", sheet(30)), SheetOptions(fitWidth = false)))
        assertEquals(3, split.size)
        // Every column on one page only, whole.
        for (pages in listOf(halved, split)) assertTrue((0 until 30).all { c -> pages.count { "${500 + c}" in it } == 1 })
    }

    private fun columnName(index: Int): String = if (index < 26) "${'A' + index}" else "A${'A' + index - 26}"

    // ---- Files that aren't converted ----

    private fun refused(file: File): Exception {
        val id = nextId
        val problem = try {
            import(file)
            null
        } catch (e: Exception) {
            e
        }
        assertTrue("${file.name} was accepted", problem != null)
        assertFalse(File(context.cacheDir, "office-inputs/$id.xlsx").exists())
        return problem!!
    }

    @Test fun oldEncryptedAndOtherFilesAreRefusedWithTheirOwnMessage() {
        val compound = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        assertTrue(refused(File(work, "old.xls").apply { writeBytes(compound + ByteArray(2048)) }) is OfficeLegacyFormat)
        val name = "EncryptedPackage".flatMap { listOf(it.code.toByte(), 0) }.toByteArray()
        assertTrue(refused(File(work, "locked.xlsx").apply { writeBytes(compound + ByteArray(1000) + name + ByteArray(1000)) }) is OfficeEncrypted)
        assertTrue(refused(File(work, "notes.xlsx").apply { writeText("Not a workbook at all.") }) is OfficeNotRecognized)
        // A Word document picked in Excel to PDF.
        val document = zip(
            "letter.xlsx",
            mapOf(
                "_rels/.rels" to rels("officeDocument" to "word/document.xml"),
                "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body/></w:document>""",
            ),
        )
        assertTrue(refused(document) is OfficeNotRecognized)
        // A workbook with nothing on its visible sheets converts to nothing, and says so.
        val empty = import(workbook("empty.xlsx", Sheet("Empty", "<sheetData/>")))
        try {
            converter.convert(empty, "empty", PageSize.A5, SheetOptions(), {}, {})
            fail("Converted a workbook with nothing in it")
        } catch (_: OfficeNothingToConvert) {
        }
    }

    @Test fun cancellingLeavesNothingBehind() {
        val data = (1..2000).joinToString("") { r -> row(r, text("A$r", "Line $r"), number("B$r", r.toDouble())) }
        val input = import(workbook("long.xlsx", Sheet("Long", "<sheetData>$data</sheetData>")))
        var cancelled = false
        try {
            converter.convert(input, "cancelled", PageSize.A5, SheetOptions(), { if (cancelled) throw CancellationException("Cancelled") }) {
                if (it.done >= 3) cancelled = true
            }
            fail("Not cancelled")
        } catch (_: CancellationException) {
        }
        assertFalse(File(context.cacheDir, "output/tools/cancelled.pdf").exists())
        assertFalse("Working files removed", File(context.cacheDir, "office-work").exists())
        assertTrue("The picked file stays, for another try", input.file.isFile)
        converter.delete(input)
    }

    private fun assertEquals(expected: Float, actual: Float, tolerance: Float) =
        assertTrue("expected $expected, got $actual", abs(expected - actual) <= tolerance)

    private companion object {
        const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        const val OFFICE_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

        /** Formats 1 to 3 are #,##0.00, d-mmm-yy and 0.0%; 4 is centred, 5 wraps. */
        val STYLES = """<?xml version="1.0" encoding="UTF-8"?><styleSheet xmlns="$MAIN">
            <numFmts count="1"><numFmt numFmtId="164" formatCode="0.0%"/></numFmts>
            <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
            <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
            <borders count="1"><border/></borders>
            <cellXfs count="7"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/><xf numFmtId="4"/><xf numFmtId="15"/><xf numFmtId="164"/>
            <xf numFmtId="0"><alignment horizontal="center"/></xf><xf numFmtId="0"><alignment wrapText="1"/></xf>
            <xf numFmtId="0"><alignment horizontal="left" wrapText="1"/></xf></cellXfs></styleSheet>"""
    }
}
