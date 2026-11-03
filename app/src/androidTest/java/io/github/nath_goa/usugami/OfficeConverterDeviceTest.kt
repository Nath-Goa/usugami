package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.office.OfficeConverter
import io.github.nath_goa.usugami.data.office.OfficeEmbeddedContent
import io.github.nath_goa.usugami.data.office.OfficeEncrypted
import io.github.nath_goa.usugami.data.office.OfficeInput
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.data.office.OfficeLegacyFormat
import io.github.nath_goa.usugami.data.office.OfficeNotRecognized
import io.github.nath_goa.usugami.data.office.OfficeNothingToConvert
import io.github.nath_goa.usugami.data.office.OfficeProgress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

/**
 * Word and PowerPoint files converted on the phone, checked by reading the PDFs back: page sizes,
 * text and its order, fonts, pictures and where they go, slides left out, and files refused. The
 * small documents are written here; report.docx and deck.pptx come from tools/make_office_fixtures.py.
 */
class OfficeConverterDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)
    private val converter = OfficeConverter(context, engine)
    private val results = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }
    private val work = File(context.cacheDir, "office-test").apply { mkdirs() }
    private var nextId = 3001L

    @After fun tearDown() {
        work.deleteRecursively()
    }

    private fun asset(name: String): File = File(work, name).also { target ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
    }

    private fun import(file: File, kind: OfficeKind): OfficeInput = converter.import(Uri.fromFile(file), nextId++, kind)

    /** Converts [file], keeping a copy of the PDF in test-results for a look at it. */
    private fun convert(file: File, kind: OfficeKind, name: String, progress: (OfficeProgress) -> Unit = {}): File {
        val pdf = converter.convert(import(file, kind), name, {}, progress).file
        return pdf.copyTo(File(results, "$name.pdf"), overwrite = true)
    }

    /** Each page's text, with line breaks and runs of spaces as single spaces. */
    private fun pageTexts(file: File): List<String> = PDDocument.load(file).use { doc ->
        (1..doc.numberOfPages).map { n -> PDFTextStripper().apply { startPage = n; endPage = n }.getText(doc).replace(Regex("""\s+"""), " ") }
    }

    /**
     * The size [glyph] is drawn at, in points. Pages are drawn scaled (in tenths of a point), which
     * TextPosition.fontSizeInPt leaves out; the rendering matrix has it.
     */
    private fun size(glyph: TextPosition) = glyph.yScale

    /** The characters of page [number] (1-based), with their fonts and sizes. */
    private fun glyphs(file: File, number: Int): List<TextPosition> = PDDocument.load(file).use { doc ->
        ArrayList<TextPosition>().also { found ->
            object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    found += text
                }
            }.apply { startPage = number; endPage = number }.getText(doc)
        }
    }

    /** The first letter of the first place [word] is written on page [number]. */
    private fun word(file: File, number: Int, word: String): TextPosition {
        val all = glyphs(file, number)
        // A glyph can stand for more than one letter, such as the "fi" ligature.
        val starts = IntArray(all.size)
        val text = StringBuilder()
        all.forEachIndexed { i, glyph ->
            starts[i] = text.length
            text.append(glyph.unicode)
        }
        val at = text.indexOf(word)
        assertTrue("\"$word\" is on page $number", at >= 0)
        return all[starts.indexOfFirst { it >= at }]
    }

    /** Page [index] of [file], [scale] pixels per point, on white. */
    private fun render(file: File, index: Int, scale: Float): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(index).use { page ->
                    Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    private fun near(expected: Float, actual: Float, tolerance: Float = 1.5f) =
        assertTrue("expected $expected, got $actual", abs(expected - actual) <= tolerance)

    // ---- Writing small Office files ----

    private fun zip(name: String, parts: Map<String, Any>): File = File(work, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, content) in parts) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(if (content is ByteArray) content else (content as String).toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(70, 130, 180))
            drawCircle(width * 0.7f, height * 0.35f, height * 0.2f, Paint().apply { color = Color.YELLOW })
        }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
    }

    private fun relationships(vararg rels: Pair<String, String>) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="$PKG_RELS">""" +
            rels.withIndex().joinToString("") { (i, rel) -> """<Relationship Id="rId${i + 1}" Type="$OFFICE_RELS/${rel.first}" Target="${rel.second}"/>""" } +
            "</Relationships>"

    private fun contentTypes(vararg overrides: Pair<String, String>) =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/>""" +
            overrides.joinToString("") { (part, type) -> """<Override PartName="$part" ContentType="$type"/>""" } + "</Types>"

    private fun para(text: String, props: String = "", run: String = "") =
        """<w:p><w:pPr>$props</w:pPr><w:r><w:rPr>$run</w:rPr><w:t xml:space="preserve">$text</w:t></w:r></w:p>"""

    private fun listItem(text: String, numId: Int, level: Int) = para(text, """<w:numPr><w:ilvl w:val="$level"/><w:numId w:val="$numId"/></w:numPr>""")

    /** The numbering of [wordDocument]: numId 1 is numbered (1., then a.), numId 2 bulleted. */
    private val listNumbering = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:numbering xmlns:w="$W">
        <w:abstractNum w:abstractNumId="0">
          <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl>
          <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%2."/><w:pPr><w:ind w:left="1440" w:hanging="360"/></w:pPr></w:lvl>
        </w:abstractNum>
        <w:abstractNum w:abstractNumId="1"><w:lvl w:ilvl="0"><w:numFmt w:val="bullet"/><w:lvlText w:val="•"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>
        <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
        </w:numbering>"""

    /**
     * An A5 document with a Times New Roman body and a bold 18-point heading, a numbered and a
     * bulleted list, a picture, a 70-row table with a header row, a page break, and a landscape A5
     * section at the end. Other documents give their own [body] (US Letter with one-inch margins when
     * it has no section properties), [numbering], and [parts] linked from the document by [rels], whose
     * ids go on from rId4 (rId2 is the picture, 300 × 200 pixels).
     */
    private fun wordDocument(
        body: String = defaultBody(),
        name: String = "notes.docx",
        numbering: String = listNumbering,
        rels: List<Pair<String, String>> = emptyList(),
        parts: Map<String, Any> = emptyMap(),
    ): File {
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$W">
            <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman"/><w:sz w:val="22"/></w:rPr></w:rPrDefault>
            <w:pPrDefault><w:pPr><w:spacing w:after="120"/></w:pPr></w:pPrDefault></w:docDefaults>
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>
            <w:style w:type="paragraph" w:styleId="Heading1"><w:name w:val="heading 1"/><w:basedOn w:val="Normal"/>
              <w:pPr><w:keepNext/><w:spacing w:before="240"/></w:pPr><w:rPr><w:b/><w:sz w:val="36"/><w:color w:val="1F3864"/></w:rPr></w:style>
            </w:styles>"""
        val document = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document $WORD_NAMESPACES><w:body>$body</w:body></w:document>"""
        return zip(
            name,
            linkedMapOf<String, Any>(
                "[Content_Types].xml" to contentTypes("/word/document.xml" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"),
                "_rels/.rels" to relationships("officeDocument" to "word/document.xml"),
                "word/document.xml" to document,
                "word/_rels/document.xml.rels" to relationships("styles" to "styles.xml", "image" to "media/picture.png", "numbering" to "numbering.xml", *rels.toTypedArray()),
                "word/styles.xml" to styles,
                "word/numbering.xml" to numbering,
                "word/media/picture.png" to png(300, 200),
            ).apply { putAll(parts) },
        )
    }

    /**
     * A picture [w] × [h] points big (pic:pic), from relationship [rel], at ([x], [y]) in a group;
     * [xfrm] and [fill] add to its frame and picture, and [hidden] hides it as Word's Selection Pane does.
     */
    private fun picture(w: Int, h: Int, rel: String = "rId2", x: Int = 0, y: Int = 0, xfrm: String = "", fill: String = "", hidden: Boolean = false) =
        """<pic:pic><pic:nvPicPr><pic:cNvPr id="9" name="p"${if (hidden) " hidden=\"1\"" else ""}/><pic:cNvPicPr/></pic:nvPicPr>
        <pic:blipFill><a:blip r:embed="$rel"/>$fill<a:stretch><a:fillRect/></a:stretch></pic:blipFill>
        <pic:spPr><a:xfrm$xfrm><a:off x="${x * EMU}" y="${y * EMU}"/><a:ext cx="${w * EMU}" cy="${h * EMU}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>"""

    /** A run with a drawing [w] × [h] points big in the text, holding [graphic] (of the kind [uri] names). */
    private fun inlineDrawing(w: Int, h: Int, graphic: String, docPr: String = "", uri: String = PICTURE_URI) =
        """<w:r><w:drawing><wp:inline><wp:extent cx="${w * EMU}" cy="${h * EMU}"/><wp:docPr id="1" name="Drawing"$docPr/>
        <a:graphic><a:graphicData uri="$uri">$graphic</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"""

    /** The box [image] covers on a page [pageHeight] points high, however it's turned: in points from the top left corner. */
    private fun bounds(image: PlacedImage, pageHeight: Float = 792f): RectF {
        val corners = listOf(image.corner(0f, 0f), image.corner(1f, 0f), image.corner(0f, 1f), image.corner(1f, 1f))
        return RectF(corners.minOf { it.x }, pageHeight - corners.maxOf { it.y }, corners.maxOf { it.x }, pageHeight - corners.minOf { it.y })
    }

    private fun defaultBody(): String = buildString {
        append(para("Quarterly notes", """<w:pStyle w:val="Heading1"/>"""))
        append(para("Plain body text, in the document's own font."))
        append(listItem("First step", 1, 0))
        append(listItem("Second step", 1, 0))
        append(listItem("A detail", 1, 1))
        append(listItem("A bullet", 2, 0))
        // A picture 150 × 100 points, in its own centered paragraph.
        append(
            """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing><wp:inline><wp:extent cx="1905000" cy="1270000"/><wp:docPr id="1" name="Picture 1"/>
            <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="1" name="p"/><pic:cNvPicPr/></pic:nvPicPr>
            <pic:blipFill><a:blip r:embed="rId2"/></pic:blipFill><pic:spPr/></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>""",
        )
        val border = """w:val="single" w:sz="4" w:color="000000""""
        append(
            """<w:tbl><w:tblPr><w:tblBorders><w:top $border/><w:left $border/><w:bottom $border/><w:right $border/><w:insideH $border/><w:insideV $border/></w:tblBorders></w:tblPr>
            <w:tblGrid><w:gridCol w:w="4000"/><w:gridCol w:w="2000"/></w:tblGrid>""",
        )
        append("""<w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc>${para("Item name")}</w:tc><w:tc>${para("Amount")}</w:tc></w:tr>""")
        for (row in 1..70) append("""<w:tr><w:tc>${para("Row $row")}</w:tc><w:tc>${para("${row * 3}")}</w:tc></w:tr>""")
        append("</w:tbl>")
        append("""<w:p><w:r><w:br w:type="page"/></w:r><w:r><w:t>After the break</w:t></w:r></w:p>""")
        // The first section, A5 portrait, ends with this paragraph; the last one is A5 landscape.
        val margins = """<w:pgMar w:top="720" w:bottom="720" w:left="720" w:right="720" w:header="360" w:footer="360"/>"""
        append("""<w:p><w:pPr><w:sectPr><w:pgSz w:w="8391" w:h="11906"/>$margins</w:sectPr></w:pPr></w:p>""")
        append(para("Wide page"))
        append("""<w:sectPr><w:pgSz w:w="11906" w:h="8391" w:orient="landscape"/>$margins</w:sectPr>""")
    }

    private fun slide(shapes: String, hidden: Boolean = false) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <p:sld xmlns:p="$P" xmlns:a="$A" xmlns:r="$OFFICE_RELS"${if (hidden) " show=\"0\"" else ""}><p:cSld><p:spTree>
        <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>$shapes</p:spTree></p:cSld></p:sld>"""

    /** A text box at ([x], [y]), [w] × [h] points, with one paragraph per entry of [paragraphs]. */
    private fun textBox(id: Int, x: Int, y: Int, w: Int, h: Int, vararg paragraphs: String) = """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="Text $id"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>
        <p:spPr><a:xfrm><a:off x="${x * EMU}" y="${y * EMU}"/><a:ext cx="${w * EMU}" cy="${h * EMU}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>
        <p:txBody><a:bodyPr wrap="square"/><a:lstStyle/>${paragraphs.joinToString("")}</p:txBody></p:sp>"""

    private fun run(text: String, size: Int, bold: Boolean = false) = """<a:r><a:rPr lang="en-US" sz="${size * 100}" b="${if (bold) 1 else 0}"/><a:t>$text</a:t></a:r>"""

    /**
     * A 16:9 presentation whose slides are listed in another order than their file names: "First"
     * is slide3.xml. The second slide has a picture 300 × 200 points big at (72, 144); a fourth,
     * hidden slide comes last. With [onlyHidden], every slide is hidden.
     */
    private fun presentation(name: String = "slides.pptx", onlyHidden: Boolean = false): File {
        val title = { text: String -> textBox(2, 36, 36, 888, 72, """<a:p>${run(text, 40, bold = true)}</a:p>""") }
        val bullets = textBox(
            3, 36, 140, 600, 300,
            """<a:p><a:pPr marL="342900" indent="-342900"><a:buChar char="•"/></a:pPr>${run("Body point one", 18)}</a:p>""",
            """<a:p><a:pPr marL="342900" indent="-342900"><a:buChar char="•"/></a:pPr>${run("Body point two", 18)}</a:p>""",
        )
        val picture = """<p:pic><p:nvPicPr><p:cNvPr id="4" name="Picture" descr="A made-up picture"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
            <p:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
            <p:spPr><a:xfrm><a:off x="${72 * EMU}" y="${144 * EMU}"/><a:ext cx="${300 * EMU}" cy="${200 * EMU}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""
        val slideType = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
        return zip(
            name,
            linkedMapOf(
                "[Content_Types].xml" to contentTypes(
                    "/ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
                    "/ppt/slides/slide1.xml" to slideType, "/ppt/slides/slide2.xml" to slideType,
                    "/ppt/slides/slide3.xml" to slideType, "/ppt/slides/slide4.xml" to slideType,
                ),
                "_rels/.rels" to relationships("officeDocument" to "ppt/presentation.xml"),
                "ppt/presentation.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentation xmlns:p="$P" xmlns:r="$OFFICE_RELS" xmlns:a="$A">
                    <p:sldIdLst><p:sldId id="256" r:id="rId3"/><p:sldId id="257" r:id="rId1"/><p:sldId id="258" r:id="rId2"/><p:sldId id="259" r:id="rId4"/></p:sldIdLst>
                    <p:sldSz cx="12192000" cy="6858000"/><p:notesSz cx="6858000" cy="9144000"/></p:presentation>""",
                "ppt/_rels/presentation.xml.rels" to relationships(
                    "slide" to "slides/slide1.xml", "slide" to "slides/slide2.xml", "slide" to "slides/slide3.xml", "slide" to "slides/slide4.xml",
                ),
                "ppt/slides/slide3.xml" to slide(title("First slide title") + bullets, hidden = onlyHidden),
                "ppt/slides/slide1.xml" to slide(title("Second slide title") + picture, hidden = onlyHidden),
                "ppt/slides/_rels/slide1.xml.rels" to relationships("image" to "../media/picture.png"),
                "ppt/slides/slide2.xml" to slide(title("Third slide title"), hidden = onlyHidden),
                "ppt/slides/slide4.xml" to slide(title("Secret draft slide"), hidden = true),
                "ppt/media/picture.png" to png(300, 200),
            ),
        )
    }

    // ---- Word ----

    @Test fun wordKeepsPageSizesTextListsTablesAndPictures() {
        val pdf = convert(wordDocument(), OfficeKind.WORD, "word-notes")
        val texts = pageTexts(pdf)
        PDDocument.load(pdf).use { doc ->
            assertTrue("The table runs over several pages", doc.numberOfPages >= 4)
            doc.pages.forEachIndexed { i, page ->
                val box = page.mediaBox
                if (i < doc.numberOfPages - 1) {
                    near(419.55f, box.width); near(595.3f, box.height)
                } else {
                    // The last section is landscape.
                    near(595.3f, box.width); near(419.55f, box.height)
                }
            }
            // The picture, 150 × 100 points, centered between the half-inch margins.
            val images = imagesOn(doc.getPage(0))
            assertEquals(1, images.size)
            val bottomLeft = images[0].corner(0f, 0f)
            val topRight = images[0].corner(1f, 1f)
            near(150f, topRight.x - bottomLeft.x)
            near(100f, topRight.y - bottomLeft.y)
            near(419.55f / 2, (bottomLeft.x + topRight.x) / 2, 2f)
        }
        val all = texts.joinToString("\n")
        assertTrue(all.contains("Plain body text, in the document's own font."))
        assertTrue("Numbered list", Regex("""1\.\s*First step""").containsMatchIn(all))
        assertTrue("Numbered list", Regex("""2\.\s*Second step""").containsMatchIn(all))
        assertTrue("Second level", Regex("""a\.\s*A detail""").containsMatchIn(all))
        assertTrue("Bullet", Regex("""•\s*A bullet""").containsMatchIn(all))
        // Every row once, in order; the header row on each page the table reaches.
        val rows = Regex("""Row (\d+)""").findAll(all).map { it.groupValues[1].toInt() }.toList()
        assertEquals((1..70).toList(), rows)
        texts.filter { it.contains("Row ") }.forEach { assertTrue("Header row repeated", it.contains("Item name")) }
        // The page break starts a new page, and the landscape section another one.
        val afterBreak = texts.indexOfFirst { it.contains("After the break") }
        assertFalse(texts[afterBreak].contains("Row "))
        assertEquals(texts.lastIndex, texts.indexOfFirst { it.contains("Wide page") })
        assertTrue(afterBreak < texts.lastIndex)
    }

    @Test fun wordHeadingsAreBoldAndLarger() {
        val pdf = convert(wordDocument(), OfficeKind.WORD, "word-heading")
        val heading = word(pdf, 1, "Quarterly")
        val body = word(pdf, 1, "Plain")
        near(18f, size(heading), 0.5f)
        near(11f, size(body), 0.5f)
        assertTrue("Heading font ${heading.font.name}", heading.font.name.orEmpty().contains("Bold"))
        assertFalse("Body font ${body.font.name}", body.font.name.orEmpty().contains("Bold"))
    }

    @Test fun wordReportFixture() {
        val pages = ArrayList<Int>()
        val pdf = convert(asset("report.docx"), OfficeKind.WORD, "report") { if (!it.compressing) pages += it.done }
        val texts = pageTexts(pdf)
        assertEquals(5, texts.size)
        assertEquals("Progress counts the pages", (1..5).toList(), pages.distinct())
        PDDocument.load(pdf).use { doc ->
            // A4, then a landscape section for the watering rota.
            near(595.3f, doc.getPage(0).mediaBox.width); near(841.9f, doc.getPage(0).mediaBox.height)
            near(841.9f, doc.getPage(4).mediaBox.width); near(595.3f, doc.getPage(4).mediaBox.height)
            assertEquals("The garden picture", 1, imagesOn(doc.getPage(0)).size)
        }
        assertTrue(texts[0].contains("Riverside Community Garden"))
        assertTrue(texts[0].contains("Forty-two members"))
        assertTrue("Link text", texts[0].contains("riverside-garden.example"))
        assertTrue("Footer page numbers", texts[0].contains("Page 1") && texts[4].contains("Page 5"))
        assertTrue("Header on every page", texts.all { it.contains("Annual report") })
        assertTrue(texts.any { it.contains("Strawberries") && it.contains("Mostly eaten on the spot") })
        assertTrue(texts[4].contains("Watering rota"))
    }

    @Test fun wordHeaderPicturesBehindTheTextTakeNoRoom() {
        // A letterhead filling the page behind the text, and a picture watermark as Word writes it
        // (VML, centred on the margins and washed out), both in the header.
        val letterhead = """<w:r><w:drawing><wp:anchor behindDoc="1" simplePos="0" relativeHeight="1" locked="0" layoutInCell="1" allowOverlap="1">
            <wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="page"><wp:posOffset>0</wp:posOffset></wp:positionH>
            <wp:positionV relativeFrom="page"><wp:posOffset>0</wp:posOffset></wp:positionV><wp:extent cx="${612 * EMU}" cy="${792 * EMU}"/>
            <wp:wrapNone/><wp:docPr id="1" name="Letterhead"/><a:graphic><a:graphicData uri="$PICTURE_URI">${picture(612, 792, "rId1")}</a:graphicData></a:graphic>
            </wp:anchor></w:drawing></w:r>"""
        val watermark = """<w:r><w:pict><v:shapetype id="_x0000_t75" coordsize="21600,21600" o:spt="75" path="m@4@5l@4@11@9@11@9@5xe"/>
            <v:shape id="WordPictureWatermark" o:spid="_x0000_s2049" type="#_x0000_t75"
              style="position:absolute;margin-left:0;margin-top:0;width:451.2pt;height:338.4pt;z-index:-251657216;mso-position-horizontal:center;mso-position-horizontal-relative:margin;mso-position-vertical:center;mso-position-vertical-relative:margin">
            <v:imagedata r:id="rId1" o:title="mark" gain="19661f" blacklevel="22938f"/></v:shape></w:pict></w:r>"""
        val header = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:hdr $WORD_NAMESPACES>
            <w:p>$letterhead$watermark<w:r><w:t>Letterhead text</w:t></w:r></w:p></w:hdr>"""
        val body = (1..40).joinToString("") { para("Line $it") } +
            """<w:sectPr><w:headerReference w:type="default" r:id="rId4"/><w:pgSz w:w="12240" w:h="15840"/>
            <w:pgMar w:top="1440" w:bottom="1440" w:left="1440" w:right="1440" w:header="720" w:footer="720"/></w:sectPr>"""
        val pdf = convert(
            wordDocument(
                body, "letterhead.docx", rels = listOf("header" to "header1.xml"),
                parts = mapOf("word/header1.xml" to header, "word/_rels/header1.xml.rels" to relationships("image" to "media/picture.png")),
            ),
            OfficeKind.WORD, "word-letterhead",
        )
        val texts = pageTexts(pdf)
        // Forty short lines fill two pages, not one page each.
        assertEquals(2, texts.size)
        assertTrue(texts[0].contains("Letterhead text") && texts[0].contains("Line 1 "))
        assertTrue(texts[1].contains("Line 40"))
        // The body starts below the header, inside the margins.
        val first = word(pdf, 1, "Line 1")
        assertTrue("Line 1 at ${first.yDirAdj}", first.yDirAdj > 72f && first.yDirAdj < 140f)
        PDDocument.load(pdf).use { doc ->
            val images = imagesOn(doc.getPage(0)).map { bounds(it) }
            assertEquals(2, images.size)
            // The letterhead over the whole page, the watermark its own size in the middle of the margins.
            val page = images.single { it.width() > 600f }
            near(0f, page.left); near(0f, page.top); near(612f, page.right); near(792f, page.bottom)
            val mark = images.single { it.width() < 600f }
            near(451.2f, mark.width()); near(338.4f, mark.height())
            near(306f, mark.centerX()); near(396f, mark.centerY())
        }
    }

    @Test fun wordPicturesTurnFlipAndKeepTheirShapeInCells() {
        // Turned a quarter: 150 × 100 points as saved, 100 × 150 on the page. Mirrored: drawn right to left.
        val turned = inlineDrawing(150, 100, picture(150, 100, xfrm = """ rot="5400000""""))
        val flipped = inlineDrawing(150, 100, picture(150, 100, xfrm = """ flipH="1""""))
        // A floating picture 300 × 200 points in a cell 100 points wide: shrunk, not squashed.
        val floating = """<w:r><w:drawing><wp:anchor behindDoc="0" simplePos="0" relativeHeight="1" locked="0" layoutInCell="1" allowOverlap="1">
            <wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="column"><wp:posOffset>0</wp:posOffset></wp:positionH>
            <wp:positionV relativeFrom="paragraph"><wp:posOffset>0</wp:posOffset></wp:positionV><wp:extent cx="${300 * EMU}" cy="${200 * EMU}"/>
            <wp:wrapSquare wrapText="bothSides"/><wp:docPr id="3" name="Float"/><a:graphic><a:graphicData uri="$PICTURE_URI">${picture(300, 200)}</a:graphicData></a:graphic>
            </wp:anchor></w:drawing></w:r>"""
        val body = """<w:p>$turned</w:p><w:p>$flipped</w:p>
            <w:tbl><w:tblPr><w:tblW w:w="2000" w:type="dxa"/></w:tblPr><w:tblGrid><w:gridCol w:w="2000"/></w:tblGrid>
            <w:tr><w:tc><w:p>$floating<w:r><w:t>In a cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"""
        val pdf = convert(wordDocument(body, "turned.docx"), OfficeKind.WORD, "word-turned")
        PDDocument.load(pdf).use { doc ->
            val images = imagesOn(doc.getPage(0))
            assertEquals(3, images.size)
            val (turnedImage, flippedImage, cellImage) = images.sortedBy { bounds(it).top }
            near(100f, bounds(turnedImage).width()); near(150f, bounds(turnedImage).height())
            near(150f, bounds(flippedImage).width()); near(100f, bounds(flippedImage).height())
            assertTrue("Drawn mirrored", flippedImage.corner(1f, 0f).x < flippedImage.corner(0f, 0f).x)
            val cell = bounds(cellImage)
            assertTrue("${cell.width()} wide", cell.width() <= 100f)
            near(1.5f, cell.width() / cell.height(), 0.05f)
        }
    }

    @Test fun wordGroupsDrawEachPictureInItsPlace() {
        // A group 300 × 100 points of two pictures side by side, the second cropped, and a third one hidden.
        val group = """<wpg:wgp><wpg:cNvGrpSpPr/><wpg:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${300 * EMU}" cy="${100 * EMU}"/>
            <a:chOff x="0" y="0"/><a:chExt cx="${300 * EMU}" cy="${100 * EMU}"/></a:xfrm></wpg:grpSpPr>
            ${picture(150, 100)}${picture(150, 100, x = 150, fill = """<a:srcRect l="25000" r="25000"/>""")}${picture(100, 100, x = 100, hidden = true)}</wpg:wgp>"""
        val pdf = convert(wordDocument("""<w:p>${inlineDrawing(300, 100, group, uri = GROUP_URI)}</w:p>""", "group.docx"), OfficeKind.WORD, "word-group")
        PDDocument.load(pdf).use { doc ->
            val images = imagesOn(doc.getPage(0)).map { bounds(it) }.sortedBy { it.left }
            assertEquals(2, images.size)
            for (image in images) {
                near(150f, image.width()); near(100f, image.height())
            }
            near(72f, images[0].left)
            near(222f, images[1].left)
        }
    }

    @Test fun wordLeavesOutWhatTheAuthorHid() {
        val hiddenPicture = inlineDrawing(150, 100, picture(150, 100), docPr = """ hidden="1"""")
        val shownPicture = inlineDrawing(150, 100, picture(150, 100))
        val hiddenBox = """<w:r><w:drawing><wp:anchor behindDoc="0" simplePos="0" relativeHeight="1" locked="0" layoutInCell="1" allowOverlap="1">
            <wp:simplePos x="0" y="0"/><wp:positionH relativeFrom="column"><wp:posOffset>0</wp:posOffset></wp:positionH>
            <wp:positionV relativeFrom="paragraph"><wp:posOffset>0</wp:posOffset></wp:positionV><wp:extent cx="${200 * EMU}" cy="${50 * EMU}"/>
            <wp:wrapNone/><wp:docPr id="4" name="Note" hidden="1"/><a:graphic><a:graphicData uri="$SHAPE_URI"><wps:wsp><wps:cNvSpPr txBox="1"/>
            <wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="${200 * EMU}" cy="${50 * EMU}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></wps:spPr>
            <wps:txbx><w:txbxContent>${para("Secret note")}</w:txbxContent></wps:txbx><wps:bodyPr/></wps:wsp></a:graphicData></a:graphic>
            </wp:anchor></w:drawing></w:r>"""
        val body = """<w:p>$hiddenPicture</w:p><w:p>$shownPicture</w:p><w:p>$hiddenBox<w:r><w:t>Visible text</w:t></w:r></w:p>"""
        val pdf = convert(wordDocument(body, "hidden.docx"), OfficeKind.WORD, "word-hidden")
        val text = pageTexts(pdf).joinToString(" ")
        assertTrue(text.contains("Visible text"))
        assertFalse(text.contains("Secret note"))
        PDDocument.load(pdf).use { doc -> assertEquals("Only the picture not hidden", 1, imagesOn(doc.getPage(0)).size) }
    }

    @Test fun wordPageNumbersFollowTheirFormatAndOddPageSections() {
        fun footer(field: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:ftr $WORD_NAMESPACES><w:p>
            <w:r><w:t xml:space="preserve">Page </w:t></w:r>$field</w:p></w:ftr>"""
        val complexField = """<w:r><w:fldChar w:fldCharType="begin"/></w:r><w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r>
            <w:r><w:fldChar w:fldCharType="separate"/></w:r><w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>"""
        val romanField = """<w:fldSimple w:instr=" PAGE \* ROMAN \* MERGEFORMAT "><w:r><w:t>1</w:t></w:r></w:fldSimple>"""
        // Front matter numbered i, ii; chapter one starts again at 1; chapter two starts on an odd page,
        // so a blank page 2 goes before it, and its footer writes its number in capitals.
        val body = para("Preface") + """<w:p><w:r><w:br w:type="page"/></w:r><w:r><w:t>Preface continued</w:t></w:r></w:p>""" +
            """<w:p><w:pPr><w:sectPr><w:footerReference w:type="default" r:id="rId4"/><w:pgNumType w:fmt="lowerRoman" w:start="1"/></w:sectPr></w:pPr></w:p>""" +
            para("Chapter one") + """<w:p><w:pPr><w:sectPr><w:pgNumType w:start="1"/></w:sectPr></w:pPr></w:p>""" +
            para("Chapter two") + """<w:sectPr><w:footerReference w:type="default" r:id="rId5"/><w:type w:val="oddPage"/></w:sectPr>"""
        val pdf = convert(
            wordDocument(
                body, "numbers.docx", rels = listOf("footer" to "footer1.xml", "footer" to "footer2.xml"),
                parts = mapOf("word/footer1.xml" to footer(complexField), "word/footer2.xml" to footer(romanField)),
            ),
            OfficeKind.WORD, "word-page-numbers",
        )
        val texts = pageTexts(pdf)
        assertEquals(listOf("i", "ii", "1", "2", "III"), texts.map { Regex("""Page (\S+)""").find(it)?.groupValues?.get(1) })
        assertTrue(texts[2].contains("Chapter one"))
        assertFalse("The blank page", texts[3].contains("Chapter"))
        assertTrue(texts[4].contains("Chapter two"))
    }

    @Test fun wordBodyKeptInAnotherFormatIsRefused() {
        // As some apps export to Word: the whole body is a web page inside the file.
        val docx = wordDocument(
            """<w:altChunk r:id="rId4"/><w:sectPr/>""", "chunk.docx", rels = listOf("aFChunk" to "afchunk.htm"),
            parts = mapOf("word/afchunk.htm" to "<html><body><p>Hello chunk</p></body></html>"),
        )
        try {
            converter.convert(import(docx, OfficeKind.WORD), "chunk", {}, {})
            fail("Converted a document whose text Usugami can't read")
        } catch (_: OfficeEmbeddedContent) {
        }
        assertFalse(File(context.cacheDir, "output/tools/chunk.pdf").exists())
    }

    @Test fun wordKeepsPhoneticGuidesSymbolsAndPageBreaksInLists() {
        val ruby = """<w:p><w:r><w:t xml:space="preserve">Before </w:t></w:r><w:r><w:ruby><w:rubyPr/><w:rt><w:r><w:t>reading</w:t></w:r></w:rt>
            <w:rubyBase><w:r><w:t>BaseWord</w:t></w:r></w:rubyBase></w:ruby></w:r><w:r><w:t xml:space="preserve"> After</w:t></w:r></w:p>"""
        // Inserted with Insert > Symbol in the Symbol font: a Greek mu and a plus-minus sign.
        val symbols = """<w:p><w:r><w:t xml:space="preserve">Width 5 </w:t></w:r><w:r><w:sym w:font="Symbol" w:char="F06D"/></w:r><w:r><w:t xml:space="preserve">m </w:t></w:r>
            <w:r><w:sym w:font="Symbol" w:char="F0B1"/></w:r><w:r><w:t xml:space="preserve"> 2</w:t></w:r></w:p>"""
        // A numbered heading starting with a page break, as LibreOffice writes one.
        val methods = """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:br w:type="page"/></w:r><w:r><w:t>Methods</w:t></w:r></w:p>"""
        val body = ruby + symbols + listItem("Introduction", 1, 0) + methods + listItem("Results", 1, 0)
        val texts = pageTexts(convert(wordDocument(body, "phonetic.docx"), OfficeKind.WORD, "word-ruby"))
        assertEquals(2, texts.size)
        assertTrue(texts[0], texts[0].contains("Before BaseWord After"))
        assertFalse(texts[0].contains("reading"))
        assertTrue(texts[0], texts[0].contains("Width 5 μm ± 2"))
        assertTrue(texts[0], Regex("""1\.\s*Introduction""").containsMatchIn(texts[0]))
        assertTrue(texts[1], Regex("""2\.\s*Methods""").containsMatchIn(texts[1]))
        assertTrue(texts[1], Regex("""3\.\s*Results""").containsMatchIn(texts[1]))
    }

    @Test fun wordListsThroughAListStyleKeepTheirNumbers() {
        // Word 365's "Current List": the list points to a style, whose own definition has the levels.
        val numbering = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:numbering xmlns:w="$W">
            <w:abstractNum w:abstractNumId="0"><w:styleLink w:val="CurrentList1"/>
              <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>
            <w:abstractNum w:abstractNumId="1"><w:numStyleLink w:val="CurrentList1"/></w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num><w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
            </w:numbering>"""
        val pdf = convert(
            wordDocument(listItem("First clause", 2, 0) + listItem("Second clause", 2, 0), "liststyle.docx", numbering = numbering),
            OfficeKind.WORD, "word-list-style",
        )
        val text = pageTexts(pdf)[0]
        assertTrue(text, Regex("""1\.\s*First clause""").containsMatchIn(text))
        assertTrue(text, Regex("""2\.\s*Second clause""").containsMatchIn(text))
        // The text at the level's indent: half an inch in from the margin.
        near(72f + 36f, word(pdf, 1, "First").xDirAdj)
    }

    @Test fun wordAutomaticTextIsWhiteOnDarkShading() {
        val navy = """<w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="1F3864"/></w:tcPr>"""
        val body = """<w:tbl><w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid><w:tr>
            <w:tc>$navy${para("Header")}</w:tc><w:tc>$navy${para("Control", run = """<w:color w:val="000000"/>""")}</w:tc></w:tr></w:tbl>"""
        val pdf = convert(wordDocument(body, "shaded.docx"), OfficeKind.WORD, "word-shaded")
        val scale = 3f
        val page = render(pdf, 0, scale)
        fun lightPixels(word: String): Int {
            val glyph = word(pdf, 1, word)
            var count = 0
            // Across the word and up from its baseline, within the shaded cell.
            val left = (glyph.xDirAdj * scale).toInt()
            val right = ((glyph.xDirAdj + 30f) * scale).toInt()
            val top = ((glyph.yDirAdj - 6f) * scale).toInt()
            val bottom = ((glyph.yDirAdj - 1f) * scale).toInt()
            for (x in left until right) for (y in top until bottom) {
                val c = page.getPixel(x, y)
                if (Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) > 200) count++
            }
            return count
        }
        assertTrue("Automatic text drawn white", lightPixels("Header") > 20)
        assertEquals("Black text stays black", 0, lightPixels("Control"))
    }

    @Test fun wordRightToLeftListsAndTablesAreMirrored() {
        val list = """<w:p><w:pPr><w:bidi/><w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr><w:ind w:left="720" w:hanging="360"/></w:pPr>
            <w:r><w:rPr><w:rtl/></w:rPr><w:t>שלום עולם</w:t></w:r></w:p>"""
        val table = """<w:tbl><w:tblPr><w:bidiVisual/></w:tblPr><w:tblGrid><w:gridCol w:w="3000"/><w:gridCol w:w="3000"/></w:tblGrid>
            <w:tr><w:tc>${para("Alpha")}</w:tc><w:tc>${para("Beta")}</w:tc></w:tr></w:tbl>"""
        val pdf = convert(wordDocument(list + table, "rtl.docx"), OfficeKind.WORD, "word-rtl")
        val glyphs = glyphs(pdf, 1)
        // The number at the right margin, less the hanging indent: 18 points in from it.
        val number = glyphs.first { it.unicode == "1" }
        near(612f - 72f - 18f, number.xDirAdj + number.widthDirAdj)
        // The text from the indent on the right, half an inch in.
        val hebrew = glyphs.filter { g -> g.unicode.any { it in 'א'..'ת' } }
        assertTrue(hebrew.isNotEmpty())
        near(612f - 72f - 36f, hebrew.maxOf { it.xDirAdj + it.widthDirAdj })
        // The table's first column on the right.
        assertTrue(word(pdf, 1, "Alpha").xDirAdj > word(pdf, 1, "Beta").xDirAdj + 100f)
    }

    @Test fun deeplyNestedXmlDoesNotOverflowTheStack() {
        // Only a crafted file nests this deep: what's too deep is left out, and the rest converts.
        val depth = 100_000
        val body = "<w:customXml>".repeat(depth) + para("Deep") + "</w:customXml>".repeat(depth) + para("Shallow")
        val pdf = convert(wordDocument(body, "nested.docx"), OfficeKind.WORD, "word-nested")
        assertTrue(pageTexts(pdf).joinToString(" ").contains("Shallow"))
    }

    // ---- PowerPoint ----

    @Test fun powerPointSlidesComeInOrderWithoutHiddenOnes() {
        val progress = ArrayList<OfficeProgress>()
        val pdf = convert(presentation(), OfficeKind.POWERPOINT, "slides") { progress += it }
        val texts = pageTexts(pdf)
        assertEquals(3, texts.size)
        assertTrue(texts[0].contains("First slide title") && texts[0].contains("Body point two"))
        assertTrue(texts[1].contains("Second slide title"))
        assertTrue(texts[2].contains("Third slide title"))
        assertFalse(texts.any { it.contains("Secret draft") })
        assertEquals(listOf(1, 2, 3), progress.filter { !it.compressing }.map { it.done })
        assertTrue(progress.filter { !it.compressing }.all { it.total == 3 })
        PDDocument.load(pdf).use { doc ->
            for (page in doc.pages) {
                near(960f, page.mediaBox.width); near(540f, page.mediaBox.height)
            }
            assertEquals(0, imagesOn(doc.getPage(0)).size)
            // The picture where the slide puts it: 72 points in, 144 down, 300 × 200.
            val picture = imagesOn(doc.getPage(1)).single()
            val bottomLeft = picture.corner(0f, 0f)
            val topRight = picture.corner(1f, 1f)
            near(72f, bottomLeft.x); near(540f - 144f - 200f, bottomLeft.y)
            near(372f, topRight.x); near(540f - 144f, topRight.y)
        }
        val title = word(pdf, 1, "First")
        val body = word(pdf, 1, "Body")
        near(40f, size(title), 0.5f)
        near(18f, size(body), 0.5f)
    }

    @Test fun powerPointDeckFixture() {
        val pdf = convert(asset("deck.pptx"), OfficeKind.POWERPOINT, "deck")
        val texts = pageTexts(pdf)
        val titles = listOf("Riverside Community Garden", "What we grew", "The beds in June", "Harvest", "How the garden works", "Harvest by month")
        assertEquals(titles.size, texts.size)
        titles.forEachIndexed { i, title -> assertTrue("Slide ${i + 1}: $title", texts[i].contains(title)) }
        assertFalse("The hidden slide is left out", texts.any { it.contains("Draft notes") })
        assertTrue("Bullets from the layout's body placeholder", texts[1].contains("Squash and pumpkins"))
        assertTrue("Table text", texts[3].contains("Potatoes") && texts[3].contains("240"))
        assertTrue("Text in shapes", texts[4].contains("Harvest") && texts[4].contains("Share"))
        assertTrue("The chart's title in its stand-in box", texts[5].contains("Harvest by month (kg)"))
        PDDocument.load(pdf).use { doc ->
            for (page in doc.pages) {
                near(720f, page.mediaBox.width); near(540f, page.mediaBox.height)
            }
            assertEquals("The picture slide", 1, imagesOn(doc.getPage(2)).size)
        }
        // The title placeholder takes its larger text from the master: bigger than the bullets.
        assertTrue(size(word(pdf, 2, "What")) > size(word(pdf, 2, "Vegetables")) + 4f)
    }

    @Test fun presentationWithOnlyHiddenSlidesIsRefused() {
        val input = import(presentation("hidden.pptx", onlyHidden = true), OfficeKind.POWERPOINT)
        try {
            converter.convert(input, "hidden", {}, {})
            fail("Converted a presentation with nothing to show")
        } catch (_: OfficeNothingToConvert) {
        }
        assertFalse(File(context.cacheDir, "output/tools/hidden.pdf").exists())
    }

    // ---- Files that aren't converted ----

    private fun refused(file: File, kind: OfficeKind): Exception {
        val id = nextId
        val problem = try {
            import(file, kind)
            null
        } catch (e: Exception) {
            e
        }
        assertNotNull("${file.name} was accepted as a ${kind.name} file", problem)
        // The copy of a refused file doesn't stay in the cache.
        assertFalse(File(context.cacheDir, "office-inputs/$id.${kind.extension}").exists())
        return problem!!
    }

    @Test fun oldBinaryFilesAreRefusedWithTheirOwnMessage() {
        val compound = File(work, "old.doc").apply { writeBytes(COMPOUND_HEADER + ByteArray(2048)) }
        assertTrue(refused(compound, OfficeKind.WORD) is OfficeLegacyFormat)
        assertTrue(refused(compound.copyTo(File(work, "old.ppt"), overwrite = true), OfficeKind.POWERPOINT) is OfficeLegacyFormat)
    }

    @Test fun passwordProtectedFilesAreRecognized() {
        // Word and PowerPoint put an encrypted package in a compound file, in a stream named so.
        val name = "EncryptedPackage".flatMap { listOf(it.code.toByte(), 0) }.toByteArray()
        val locked = File(work, "locked.docx").apply { writeBytes(COMPOUND_HEADER + ByteArray(1000) + name + ByteArray(1000)) }
        assertTrue(refused(locked, OfficeKind.WORD) is OfficeEncrypted)
    }

    @Test fun otherFilesAreNotTakenForOfficeFiles() {
        val text = File(work, "notes.docx").apply { writeText("Just some text, not a Word document at all.") }
        assertTrue(refused(text, OfficeKind.WORD) is OfficeNotRecognized)
        val otherZip = zip("archive.docx", mapOf("readme.txt" to "hello"))
        assertTrue(refused(otherZip, OfficeKind.WORD) is OfficeNotRecognized)
        // A presentation picked in Word to PDF, and the other way round.
        assertTrue(refused(presentation("deck-as-word.pptx"), OfficeKind.WORD) is OfficeNotRecognized)
        assertTrue(refused(wordDocument(name = "doc-as-slides.docx"), OfficeKind.POWERPOINT) is OfficeNotRecognized)
        val tiny = File(work, "empty.docx").apply { writeBytes(ByteArray(3)) }
        assertTrue(refused(tiny, OfficeKind.WORD) is OfficeNotRecognized)
    }

    @Test fun xmlDeclaringEntitiesIsRefused() {
        // Office never writes a DOCTYPE; one could make a few bytes expand into gigabytes.
        val file = wordDocument(name = "entities.docx").let { plain ->
            val parts = linkedMapOf<String, Any>()
            java.util.zip.ZipFile(plain).use { zip -> zip.entries().asSequence().forEach { parts[it.name] = zip.getInputStream(it).readBytes() } }
            val document = String(parts["word/document.xml"] as ByteArray)
            parts["word/document.xml"] = document.replaceFirst("<w:document", """<!DOCTYPE w:document [<!ENTITY lol "lol">]><w:document""")
            zip("entities2.docx", parts)
        }
        assertTrue(refused(file, OfficeKind.WORD) is OfficeNotRecognized)
    }

    // ---- Large files and cancelling ----

    @Test fun largePicturesAreDecodedSmallerAndCompressed() {
        // A 4000 × 3000 photo-like picture shown 4 inches wide.
        val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.RGB_565)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(90, 140, 60))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            for (i in 0 until 400) {
                paint.color = Color.rgb((i * 37) % 256, (i * 91) % 256, (i * 53) % 256)
                drawCircle((i * 997 % 4000).toFloat(), (i * 613 % 3000).toFloat(), 40f + i % 200, paint)
            }
        }
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        bitmap.recycle()
        val body = """<w:p><w:r><w:drawing><wp:inline><wp:extent cx="${4 * 914400}" cy="${3 * 914400}"/><wp:docPr id="1" name="Photo"/>
            <a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:pic><pic:nvPicPr><pic:cNvPr id="1" name="p"/><pic:cNvPicPr/></pic:nvPicPr>
            <pic:blipFill><a:blip r:embed="rId2"/></pic:blipFill><pic:spPr/></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
        val docx = wordDocument(body, "photo.docx").let { plain ->
            val parts = linkedMapOf<String, Any>()
            java.util.zip.ZipFile(plain).use { zip -> zip.entries().asSequence().forEach { parts[it.name] = zip.getInputStream(it).readBytes() } }
            parts["word/media/picture.png"] = jpeg // The name doesn't matter to the decoder.
            zip("photo2.docx", parts)
        }
        val pdf = convert(docx, OfficeKind.WORD, "photo")
        PDDocument.load(pdf).use { doc ->
            val image = imagesOn(doc.getPage(0)).single().image
            // No more than 200 dpi at 4 inches wide, stored as JPEG.
            assertTrue("${image.width} px wide", image.width <= 800 * 1.25)
            assertEquals("jpg", image.suffix)
        }
        assertTrue("${pdf.length()} bytes", pdf.length() < 400_000)
    }

    @Test fun cancellingLeavesNothingBehind() {
        val input = import(asset("report.docx"), OfficeKind.WORD)
        // Cancelled once the third page has started.
        var cancelled = false
        try {
            converter.convert(input, "cancelled", { if (cancelled) throw CancellationException("Cancelled") }, { if (it.done >= 3) cancelled = true })
            fail("Not cancelled")
        } catch (_: CancellationException) {
        }
        assertFalse(File(context.cacheDir, "output/tools/cancelled.pdf").exists())
        assertFalse("Working files removed", File(context.cacheDir, "office-work").exists())
        assertTrue("The picked file stays, for another try", input.file.isFile)
        // It converts fine afterwards.
        assertEquals(5, converter.convert(input, "after-cancel", {}, {}).pageCount)
        converter.delete(input)
    }

    private companion object {
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
        const val PKG_RELS = "http://schemas.openxmlformats.org/package/2006/relationships"
        const val OFFICE_RELS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val EMU = 12_700
        const val PICTURE_URI = "http://schemas.openxmlformats.org/drawingml/2006/picture"
        const val GROUP_URI = "http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"
        const val SHAPE_URI = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
        const val WORD_NAMESPACES = """xmlns:w="$W" xmlns:r="$OFFICE_RELS" xmlns:a="$A"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:pic="$PICTURE_URI"
            xmlns:wps="$SHAPE_URI" xmlns:wpg="$GROUP_URI" xmlns:v="urn:schemas-microsoft-com:vml"
            xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:w10="urn:schemas-microsoft-com:office:word""""
        val COMPOUND_HEADER = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    }
}
