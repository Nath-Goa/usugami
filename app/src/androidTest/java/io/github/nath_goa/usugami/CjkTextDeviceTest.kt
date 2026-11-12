package io.github.nath_goa.usugami

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTerminalField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.data.Flattening
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.PdfToolsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Chinese, Japanese and Korean text in fonts PDFBox reads with the CMaps Usugami packs, but must
 * not write in. Every PDF is made here, with fonts that have a made-up name, no font file and no
 * ToUnicode map.
 */
class CjkTextDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)
    private val results = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }
    private var nextId = 3501L

    private fun import(file: File): PdfInput = engine.import(Uri.fromFile(file), nextId++)

    private fun save(file: File, name: String): File = file.copyTo(File(results, name), overwrite = true)

    private fun made(name: String) = File(context.cacheDir, name)

    /** One A4 page saying "PLAIN PAGE". */
    private fun plainPdf(file: File): File = file.also {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            writeText(doc, page, "PLAIN PAGE")
            doc.save(file)
        }
    }

    /** A text note (FreeText comment) at [rect] saying [says], with no look of its own, set in the font [appearance] (a /DA) names. */
    private fun note(page: PDPage, rect: PDRectangle, appearance: String, says: String): PDAnnotation =
        PDAnnotation.createAnnotation(COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ANNOT)
            setName(COSName.SUBTYPE, "FreeText")
            setItem(COSName.RECT, rect.cosArray)
            setString(COSName.CONTENTS, says)
            setString(COSName.DA, appearance)
            setInt(COSName.F, 4)
            setItem(COSName.P, page.cosObject)
        })

    /**
     * [font] with its named encoding replaced by a CMap of the PDF's own that builds on the named
     * one (usecmap), as some PDFs carry them.
     */
    private fun buildingOn(font: COSDictionary): COSDictionary = font.apply {
        val named = getCOSName(COSName.ENCODING).name
        val cmap = "/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/$named usecmap\n" +
            "/CMapName /Usugami-Test-H def\n/CMapType 1 def\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n"
        setItem(COSName.ENCODING, COSStream().apply {
            setName(COSName.TYPE, "CMap")
            createOutputStream().use { it.write(cmap.toByteArray(Charsets.US_ASCII)) }
        })
    }

    /**
     * One A4 page with a form that asks viewers to draw its fields (NeedAppearances), its values
     * set and no look made for them, as some apps fill forms. The form's fonts are [fonts]; each of
     * [fields] is a text field's name, its /DA and its value. [notes] adds comments to the page.
     * Without [listed], the form lists no fields: they are only widgets on the page.
     */
    private fun cjkFormPdf(
        file: File,
        fonts: Map<String, COSDictionary>,
        fields: List<Triple<String, String, String>>,
        listed: Boolean = true,
        notes: (PDPage) -> List<PDAnnotation> = { emptyList() },
    ): File = file.also {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources(COSDictionary().apply {
                setItem(COSName.FONT, COSDictionary().apply { for ((name, font) in fonts) setItem(name, font) })
            })
            form.needAppearances = true
            for ((index, field) in fields.withIndex()) {
                val (name, appearance, value) = field
                val text = PDTextField(form)
                text.partialName = name
                text.defaultAppearance = appearance
                text.widgets.single().apply {
                    rectangle = PDRectangle(72f, 700f - 60f * index, 250f, 24f)
                    this.page = page
                    isPrinted = true
                    page.annotations.add(this)
                }
                if (listed) form.fields.add(text)
                // Setting it through PDFBox would draw it.
                text.cosObject.setString(COSName.V, value)
            }
            page.annotations = page.annotations + notes(page)
            doc.save(file)
        }
    }

    /** [file]'s form fields by name, each with its value and whether a widget of it has a normal look. */
    private fun fieldsOf(file: File): Map<String, Pair<String, Boolean>> = PDDocument.load(file).use { doc ->
        // Without PDFBox's fix-ups, which would draw the fields.
        val form = doc.documentCatalog.getAcroForm(null)!!
        form.fieldTree.filterIsInstance<PDTerminalField>().associate { field ->
            field.partialName to (field.valueAsString to field.widgets.any { it.normalAppearanceStream != null })
        }
    }

    /** The widgets on [file]'s first page by name, each with its value and whether it has a normal look. */
    private fun widgetsOf(file: File): Map<String, Pair<String, Boolean>> = PDDocument.load(file).use { doc ->
        doc.getPage(0).annotations.filterIsInstance<PDAnnotationWidget>().associate { widget ->
            widget.cosObject.getString(COSName.T) to (widget.cosObject.getString(COSName.V) to (widget.normalAppearanceStream != null))
        }
    }

    /** Whether [file]'s form asks viewers to draw its fields (NeedAppearances). */
    private fun asksForLooks(file: File): Boolean = PDDocument.load(file).use { it.documentCatalog.getAcroForm(null)!!.needAppearances }

    // ---- Find text ----

    /** An A4 page added to [doc] that draws [content], with [fonts] and [xobjects] by name. */
    private fun rawPage(doc: PDDocument, fonts: Map<String, COSDictionary>, content: String, xobjects: Map<String, COSBase> = emptyMap()): PDPage =
        PDPage(PDRectangle.A4).also { page ->
            doc.addPage(page)
            page.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply {
                setItem(COSName.FONT, COSDictionary().apply { for ((name, font) in fonts) setItem(name, font) })
                if (xobjects.isNotEmpty()) setItem(COSName.XOBJECT, COSDictionary().apply { for ((name, xobject) in xobjects) setItem(name, xobject) })
            })
            page.setContents(PDStream(doc, content.byteInputStream(Charsets.ISO_8859_1)))
        }

    /** A line of 20-point text in [font] at ([x], [y]): the character codes [hex]. Every glyph of the test fonts is 20 points wide. */
    private fun line(font: String, x: Int, y: Int, hex: String) = "BT /$font 20 Tf $x $y Td <$hex> Tj ET\n"

    /**
     * A simple font as fonts of TeX are in older PDFs: no font file and no ToUnicode map, its one
     * glyph at [code] named [glyph]. By default a big bracket of the maths fonts at code 0, named
     * parenleftbig, a name PDFBox knows from its own list.
     */
    private fun bracketFont(code: Int = 0, glyph: String = "parenleftbig"): COSDictionary = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FONT)
        setItem(COSName.SUBTYPE, COSName.TYPE1)
        setName(COSName.BASE_FONT, "Usugami-Test-Math")
        setInt(COSName.FIRST_CHAR, code)
        setInt(COSName.LAST_CHAR, code)
        setItem(COSName.WIDTHS, COSArray().apply { add(COSInteger.get(500)) })
        setItem(COSName.ENCODING, COSDictionary().apply {
            setItem(COSName.TYPE, COSName.ENCODING)
            setItem(COSName.DIFFERENCES, COSArray().apply {
                add(COSInteger.get(code.toLong()))
                add(COSName.getPDFName(glyph))
            })
        })
        setItem(COSName.FONT_DESC, COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT_DESC)
            setName(COSName.FONT_NAME, "Usugami-Test-Math")
            setInt(COSName.FLAGS, 4)
            setItem(COSName.FONT_BBOX, COSArray().apply { listOf(0L, -120L, 1000L, 880L).forEach { add(COSInteger.get(it)) } })
            setInt(COSName.ITALIC_ANGLE, 0)
            setInt(COSName.ASCENT, 880)
            setInt(COSName.DESCENT, -120)
            setInt(COSName.CAP_HEIGHT, 700)
            setInt(COSName.STEM_V, 80)
        })
    }

    /**
     * Asserts that [box] covers the cells of the 20-point glyphs drawn from [x0] to [x1] along the
     * baseline [y] (in points, y up, on an A4 page), and reaches no more than 8 points beyond them.
     */
    private fun assertCovers(what: String, box: RedactBox, x0: Int, x1: Int, y: Int) {
        val width = PDRectangle.A4.width
        val height = PDRectangle.A4.height
        val left = box.left * width
        val right = box.right * width
        val top = (1 - box.top) * height
        val bottom = (1 - box.bottom) * height
        // The test fonts reach 0.12 em below the baseline and 0.88 em above it.
        val (low, high) = y - 2.4f to y + 17.6f
        val shown = "$what: %.1f to %.1f across, %.1f to %.1f up".format(left, right, bottom, top)
        assertTrue("$shown covers $x0 to $x1, $low to $high", left <= x0 + 0.1f && right >= x1 - 0.1f && bottom <= low + 0.1f && top >= high - 0.1f)
        assertTrue("$shown reaches no more than 8 points beyond them", left >= x0 - 8 && right <= x1 + 8 && bottom >= low - 8 && top <= y + 20 + 8)
    }

    /**
     * Fonts as TCPDF, FPDF, iText and older Acrobat and Distiller write them, without a ToUnicode
     * map, one line in each: the encodings of Japanese, Simplified and Traditional Chinese and
     * Korean, a Unicode one, Identity-H with a CJK collection, and a TrueType font. In 1.6.5 the
     * search failed or found nothing.
     */
    @Test fun findTextReadsCjkFontsThatHaveNoToUnicodeMap() {
        val lines = listOf(
            cjkFont("90ms-RKSJ-H", "Japan1", 2) to "938C8B9E9373", // 東京都
            cjkFont("GBKp-EUC-H", "GB1", 2) to "B1B1BEA9CAD0", // 北京市
            cjkFont("ETen-B5-H", "CNS1", 0) to "A578A55FA5AB", // 台北市
            cjkFont("KSCms-UHC-H", "Korea1", 1) to "BCADBFEFBDC3", // 서울시
            cjkFont("UniGB-UTF16-H", "GB1", 5) to "53174EAC5E02", // 北京市
            cjkFont("Identity-H", "Japan1", 6) to "0C6606980C4E", // 東京都, as Adobe-Japan1 CIDs
            cjkFont("90msp-RKSJ-H", "Japan1", 2, "CIDFontType2") to "938C8B9E9373", // 東京都
        )
        fun baseline(line: Int) = 700 - 40 * line
        val file = made("cjk_fonts.pdf").also { file ->
            PDDocument().use { doc ->
                val fonts = lines.indices.associate { "C${it + 1}" to lines[it].first }
                rawPage(doc, fonts, lines.indices.joinToString("") { line("C${it + 1}", 72, baseline(it), lines[it].second) })
                doc.save(file)
            }
        }
        val source = import(file)
        for ((query, expected) in listOf("東京都" to listOf(0, 5, 6), "北京市" to listOf(1, 4), "台北市" to listOf(2), "서울시" to listOf(3))) {
            val search = engine.searchText(source, query)
            assertEquals("$query: no page left unread", emptyList<Int>(), search.unread)
            assertTrue("$query: one box for each match", search.matches.all { it.size == 1 })
            val boxes = search.matches.map { it.single() }.sortedBy { it.top }
            assertEquals("$query: found on lines ${expected.map { it + 1 }}", expected.size, boxes.size)
            for ((box, line) in boxes.zip(expected)) assertCovers("$query on line ${line + 1}", box, 72, 132, baseline(line))
        }
    }

    /**
     * Words wrapped onto the next line at any character, a name with a space in it, full-width
     * and half-width letters, and a Korean word wrapped inside it.
     */
    @Test fun findTextMatchesCjkAsItIsWritten() {
        val file = made("cjk_written.pdf").also { file ->
            PDDocument().use { doc ->
                val fonts = mapOf("C1" to cjkFont("90ms-RKSJ-H", "Japan1", 2), "C2" to cjkFont("KSCms-UHC-H", "Korea1", 1))
                rawPage(
                    doc, fonts,
                    line("C1", 72, 700, "938C8B9E") + // 東京, wrapped…
                        line("C1", 72, 670, "9373") + // …都 on the next line
                        line("C1", 72, 620, "8E529363814091BE9859") + // 山田　太郎, with an ideographic space
                        line("C1", 72, 570, "826082618262") + // ＡＢＣ
                        line("C1", 72, 520, "B6DE") + // ｶﾞ, half-width: two glyphs
                        line("C2", 72, 450, "BCADBFEF") + // 서울, wrapped…
                        line("C2", 72, 420, "BDC3"), // …시
                )
                doc.save(file)
            }
        }
        val source = import(file)
        fun found(query: String): List<RedactBox> {
            val search = engine.searchText(source, query)
            assertEquals("$query: no page left unread", emptyList<Int>(), search.unread)
            assertEquals("$query: one match", 1, search.matches.size)
            return search.matches.single()
        }

        val tokyo = found("東京都")
        assertEquals("A box on each line", 2, tokyo.size)
        assertCovers("東京", tokyo[0], 72, 112, 700)
        assertCovers("都", tokyo[1], 72, 92, 670)
        for (query in listOf("山田太郎", "山田 太郎")) assertCovers(query, found(query).single(), 72, 172, 620)
        assertCovers("abc", found("abc").single(), 72, 132, 570)
        assertCovers("ガ", found("ガ").single(), 72, 112, 520)
        val seoul = found("서울시")
        assertEquals("A box on each line", 2, seoul.size)
        assertCovers("서울", seoul[0], 72, 112, 450)
        assertCovers("시", seoul[1], 72, 92, 420)
    }

    /**
     * Asserts that [box], on a page shown [shownWidth] × [shownHeight] points big, covers the shown
     * area from [x0] to [x1] across and [y0] to [y1] down from the top, and reaches no more than 8
     * points beyond it.
     */
    private fun assertCoversShown(what: String, box: RedactBox, shownWidth: Float, shownHeight: Float, x0: Float, x1: Float, y0: Float, y1: Float) {
        val left = box.left * shownWidth
        val right = box.right * shownWidth
        val top = box.top * shownHeight
        val bottom = box.bottom * shownHeight
        val shown = "$what: %.1f to %.1f across, %.1f to %.1f down".format(left, right, top, bottom)
        assertTrue("$shown covers $x0 to $x1, $y0 to $y1", left <= x0 + 0.1f && right >= x1 - 0.1f && top <= y0 + 0.1f && bottom >= y1 - 0.1f)
        assertTrue("$shown reaches no more than 8 points beyond it", left >= x0 - 8 && right <= x1 + 8 && top >= y0 - 8 && bottom <= y1 + 8)
    }

    /**
     * Japanese written down the page in two columns, as a vertical font draws it: a word down a
     * column is found with one box over its cells, digits in a column are part of its word, and a
     * word wrapped into the next column gets a box in each. On a page turned a quarter, the column
     * lies on its side and gets one box along it. Korean down the page with room between two words
     * and no space glyph is found with the space typed and without it.
     */
    @Test fun findTextFindsWritingDownThePage() {
        val columns = "BT /V1 20 Tf 500 400 Td <67714EAC90FD00320030003200345E74> Tj ET\n" + // 東京都2024年, down from y 400 at x 490 to 510
            "BT /V1 20 Tf 470 400 Td <6E2F533A> Tj ET\n" // 港区, down from y 400 at x 460 to 480
        val file = made("cjk_vertical.pdf").also { file ->
            PDDocument().use { doc ->
                val fonts = mapOf("V1" to cjkFont("UniJIS-UCS2-V", "Japan1", 6))
                rawPage(doc, fonts, columns)
                rawPage(doc, fonts, columns).rotation = 90
                // 서울 down from y 400, then 6 points of room (more than a quarter of a letter), then 특별시 down to y 294.
                rawPage(
                    doc, mapOf("K1" to cjkFont("UniKS-UCS2-V", "Korea1", 1)),
                    "BT /K1 20 Tf 500 400 Td <C11CC6B8> Tj ET\nBT /K1 20 Tf 500 354 Td <D2B9BCC4C2DC> Tj ET\n",
                )
                doc.save(file)
            }
        }
        val source = import(file)
        val width = PDRectangle.A4.width
        val height = PDRectangle.A4.height
        // The matches of query on the upright page. Each glyph is a cell 20 points high, the first from y 400 down.
        fun upright(query: String): List<List<RedactBox>> {
            val search = engine.searchText(source, query)
            assertEquals("$query: no page left unread", emptyList<Int>(), search.unread)
            return search.matches.filter { it.first().page == 0 }
        }
        // y down from the top of the shown page for a cell from [from] down to [to] (in points, y up).
        fun down(from: Int, to: Int) = height - from to height - to

        val tokyo = upright("東京都")
        assertEquals("東京都: one match", 1, tokyo.size)
        val box = tokyo.single().single()
        val (top, bottom) = down(400, 340)
        assertCoversShown("東京都", box, width, height, 490f, 510f, top, bottom)
        assertTrue("東京都 is not wider than its column", (box.right - box.left) * width <= 30f)

        val withYear = upright("京都2024")
        assertEquals("京都2024: one match", 1, withYear.size)
        val (from, to) = down(380, 260)
        assertCoversShown("京都2024", withYear.single().single(), width, height, 490f, 510f, from, to)

        val minato = upright("港区")
        assertEquals("港区: one match", 1, minato.size)
        val (high, low) = down(400, 360)
        assertCoversShown("港区", minato.single().single(), width, height, 460f, 480f, high, low)

        val wrapped = upright("年港")
        assertEquals("年港: one match", 1, wrapped.size)
        assertEquals("年港: a box in each column", 2, wrapped.single().size)
        val (yearTop, yearBottom) = down(260, 240)
        assertCoversShown("年", wrapped.single()[0], width, height, 490f, 510f, yearTop, yearBottom)
        val (portTop, portBottom) = down(400, 380)
        assertCoversShown("港", wrapped.single()[1], width, height, 460f, 480f, portTop, portBottom)

        // Turned a quarter, the page is shown 842 × 595 and the column runs across it, 490 to 510 from the top.
        val turned = engine.searchText(source, "東京都").matches.filter { it.first().page == 1 }
        assertEquals("東京都 on the turned page: one match", 1, turned.size)
        assertCoversShown("東京都 turned", turned.single().single(), height, width, 340f, 400f, 490f, 510f)

        for (query in listOf("서울 특별시", "서울특별시")) {
            val search = engine.searchText(source, query)
            assertEquals("$query: no page left unread", emptyList<Int>(), search.unread)
            val seoul = search.matches.filter { it.first().page == 2 }
            assertEquals("$query: one match", 1, seoul.size)
            val (seoulTop, seoulBottom) = down(400, 294)
            assertCoversShown(query, seoul.single().single(), width, height, 490f, 510f, seoulTop, seoulBottom)
        }
    }

    /**
     * A page is listed when a glyph on it reads as no letters, when a form on it can't be read, or
     * when the page can't be; what can be read is searched all the same, but not a page that
     * failed. A big bracket of TeX's maths fonts, which PDFBox reads by its name, lists no page;
     * a glyph at a control code that reads as a space, under a name PDFBox doesn't know, does.
     */
    @Test fun findTextTellsWhichPagesItCouldNotRead() {
        val file = made("cjk_unread.pdf").also { file ->
            PDDocument().use { doc ->
                val beijing = line("C1", 72, 600, "53174EAC5E02") // 北京市
                // 1: Chinese PDFBox reads.
                rawPage(doc, mapOf("C1" to cjkFont("UniGB-UCS2-H", "GB1", 2)), beijing)
                // 2: the same, then a line in a font that names a CMap PDFBox doesn't have.
                val missing = line("C2", 72, 560, "6771")
                rawPage(doc, mapOf("C1" to cjkFont("UniGB-UCS2-H", "GB1", 2), "C2" to cjkFont("UniJIS2004-UTF16-H", "Japan1", 6)), beijing + missing)
                // 3: glyphs of a font with no ToUnicode map and a character collection of its own, and SECRET NAME.
                writeText(doc, rawPage(doc, mapOf("C1" to cjkFont("Identity-H", "Identity", 0)), line("C1", 72, 600, "000100020003")), "SECRET NAME")
                // 4: a Chinese character outside the first plane, which the CJK tables can't name.
                rawPage(doc, mapOf("C1" to cjkFont("UniJIS-UTF16-H", "Japan1", 6)), line("C1", 72, 600, "D842DFB7"))
                // 5: a form that can't be read placed on the page before SECRET NAME.
                val form = PDFormXObject(doc).apply {
                    bBox = PDRectangle.A4
                    resources = PDResources(COSDictionary().apply {
                        setItem(COSName.FONT, COSDictionary().apply { setItem("C2", cjkFont("UniJIS2004-UTF16-H", "Japan1", 6)) })
                    })
                    cosObject.createOutputStream().use { it.write(missing.toByteArray(Charsets.ISO_8859_1)) }
                }
                writeText(doc, rawPage(doc, emptyMap(), "q /Fm Do Q\n", mapOf("Fm" to form.cosObject)), "SECRET NAME")
                // 6: a big bracket of a maths font, at code 0.
                rawPage(doc, mapOf("M1" to bracketFont()), "BT /M1 20 Tf 72 600 Td <00> Tj ET\n")
                // 7: "fi" of an old TeX font at code 12, a form feed, under a name PDFBox doesn't know.
                rawPage(doc, mapOf("M1" to bracketFont(12, "a12")), "BT /M1 20 Tf 72 600 Td <0C> Tj ET\n")
                doc.save(file)
            }
        }
        val source = import(file)
        val chinese = engine.searchText(source, "北京市")
        assertEquals("Found on the page read, not on the page that couldn't be", listOf(0), chinese.matches.map { it.single().page })
        assertEquals(listOf(1, 2, 3, 4, 6), chinese.unread)
        val secret = engine.searchText(source, "secret name")
        assertEquals("Found on pages with text that couldn't be read", listOf(2, 4), secret.matches.map { it.single().page })
        assertEquals(listOf(1, 2, 3, 4, 6), secret.unread)
    }

    /**
     * A form whose fields are set in CJK fonts PDFBox can't write in, one it would write in wrong
     * codes (a TrueType font that isn't embedded) and one it stops on: Merge PDF, Reorganize PDF
     * and Flatten PDF leave them as they are for viewers to draw, as 1.6.5 did, and the preview has
     * Android draw them. So are such a form's widgets when it lists no fields, and a field set in a
     * font whose CMap builds on a CJK one. A form that only carries such a font is still drawn by
     * PDFBox. A text note set in one is drawn without its text, and keeps it.
     */
    @Test fun formsSetInCjkFontsAreJoinedAndFlattenedAsTheyAre() {
        val mincho = "/Mincho 12 Tf 0 g"
        val fonts = mapOf("Mincho" to cjkFont("90ms-RKSJ-H", "Japan1", 2, "CIDFontType2"), "Kozuka" to cjkFont("UniJIS-UTF16-H", "Japan1", 6))
        val fields = listOf(Triple("a", mincho, "123"), Triple("b", "/Kozuka 12 Tf 0 g", "456"))
        val form = import(cjkFormPdf(made("cjk_form.pdf"), fonts, fields) { page ->
            listOf(
                note(page, PDRectangle(72f, 400f, 250f, 40f), "/Helv 12 Tf 0 g", "A note"),
                note(page, PDRectangle(72f, 300f, 250f, 40f), mincho, "Note two"),
            )
        })
        val plain = import(plainPdf(made("cjk_plain.pdf")))
        val asTheyAre = mapOf("a" to ("123" to false), "b" to ("456" to false))

        // Each result is checked before the next tool runs, which clears it away.
        val joined = save(engine.merge(listOf(form, plain), null, "joined").file, "cjk_form_joined.pdf")
        assertEquals("Merge PDF leaves the fields as they are", asTheyAre, fieldsOf(joined))
        assertTrue("and asks viewers to draw them", asksForLooks(joined))

        val result = engine.flatten(form, "flat")
        assertEquals("Both fields stay fields", 2, result.keptFields)
        val flat = save(result.file.file, "cjk_form_flattened.pdf")
        assertEquals(asTheyAre, fieldsOf(flat))
        assertTrue(asksForLooks(flat))
        PDDocument.load(flat).use { doc ->
            assertEquals("Only the fields' widgets are left", listOf("Widget", "Widget"), doc.getPage(0).annotations.map { it.subtype })
            val text = PDFTextStripper().getText(doc)
            assertTrue("The note in Helvetica is drawn into the page: $text", text.contains("A note"))
            assertFalse("The note in a CJK font is drawn without its text: $text", text.contains("Note two"))
        }

        assertEquals("The preview has Android draw the value", listOf(0), engine.findText(form, "123").map { it.single().page })

        val pages = listOf(PdfPageRef(form.id, 0), PdfPageRef(plain.id, 0))
        val rearranged = save(engine.reorganize(form, listOf(plain), pages, "rearranged").file, "cjk_form_reorganized.pdf")
        assertEquals("Reorganize PDF leaves the fields of the user's own PDF as they are", asTheyAre, fieldsOf(rearranged))
        assertTrue(asksForLooks(rearranged))

        val carried = import(cjkFormPdf(
            made("cjk_carried.pdf"),
            mapOf("Helv" to PDType1Font.HELVETICA.cosObject, "Kozuka" to cjkFont("UniJIS-UTF16-H", "Japan1", 6)),
            listOf(Triple("c", "/Helv 12 Tf 0 g", "789")),
        ))
        val joinedCarried = save(engine.merge(listOf(carried, plain), null, "carried").file, "cjk_carried_joined.pdf")
        assertEquals("A field in Helvetica is drawn though its form carries a CJK font", mapOf("c" to ("789" to true)), fieldsOf(joinedCarried))
        assertFalse(asksForLooks(joinedCarried))

        // PDFBox makes fields of the widgets on the pages of a form that lists none, and draws those.
        val unlisted = import(cjkFormPdf(made("cjk_unlisted.pdf"), fonts, fields, listed = false))
        val joinedUnlisted = save(engine.merge(listOf(unlisted, plain), null, "unlisted").file, "cjk_unlisted_joined.pdf")
        assertEquals("Merge PDF leaves the widgets of a form that lists no fields as they are", asTheyAre, widgetsOf(joinedUnlisted))
        assertTrue(asksForLooks(joinedUnlisted))

        val built = import(cjkFormPdf(
            made("cjk_usecmap.pdf"),
            mapOf("Built" to buildingOn(cjkFont("UniJIS-UTF16-H", "Japan1", 6))),
            listOf(Triple("e", "/Built 12 Tf 0 g", "135")),
        ))
        val joinedBuilt = save(engine.merge(listOf(built, plain), null, "usecmap").file, "cjk_usecmap_joined.pdf")
        assertEquals("and a field in a font whose CMap builds on a CJK one", mapOf("e" to ("135" to false)), fieldsOf(joinedBuilt))
        assertTrue(asksForLooks(joinedBuilt))

        PDDocument.load(form.file).use { doc ->
            val note = doc.getPage(0).annotations.single { it.contents == "Note two" }
            val look = Flattening.appearanceOf(doc, note)
            assertNotNull("The note in a CJK font has its box and border", look)
            val content = String(look!!.contentStream.toByteArray(), Charsets.ISO_8859_1)
            assertFalse("but no text: $content", content.contains("Tj"))
            assertEquals("and keeps what it says", "Note two", note.cosObject.getString(COSName.CONTENTS))
        }
    }
}
