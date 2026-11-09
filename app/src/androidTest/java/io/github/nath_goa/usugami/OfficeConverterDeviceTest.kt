package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.text.Layout
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.office.Align
import io.github.nath_goa.usugami.data.office.ConvertJob
import io.github.nath_goa.usugami.data.office.OfficeConverter
import io.github.nath_goa.usugami.data.office.OfficeEmbeddedContent
import io.github.nath_goa.usugami.data.office.OfficeEncrypted
import io.github.nath_goa.usugami.data.office.OfficeFonts
import io.github.nath_goa.usugami.data.office.OfficeInput
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.data.office.OfficeLegacyFormat
import io.github.nath_goa.usugami.data.office.OfficeNotRecognized
import io.github.nath_goa.usugami.data.office.OfficeNothingToConvert
import io.github.nath_goa.usugami.data.office.OfficeProgress
import io.github.nath_goa.usugami.data.office.PagedPdf
import io.github.nath_goa.usugami.data.office.ParagraphBlock
import io.github.nath_goa.usugami.data.office.ParagraphSpec
import io.github.nath_goa.usugami.data.office.RichText
import io.github.nath_goa.usugami.data.office.TextStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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

    /**
     * A 720 × 540 point presentation of [slides] (each slide's XML), with [attributes] on
     * p:presentation, the relationships of slide n+1 at [slideRels][n], the presentation's other
     * relationships [more] (type to target), and [parts] besides.
     */
    private fun deck(
        name: String, slides: List<String>, attributes: String = "", slideRels: Map<Int, String> = emptyMap(),
        more: List<Pair<String, String>> = emptyList(), parts: Map<String, Any> = emptyMap(),
    ): File {
        val slideType = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
        val all = linkedMapOf<String, Any>(
            "[Content_Types].xml" to contentTypes(
                "/ppt/presentation.xml" to "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml",
                *slides.indices.map { "/ppt/slides/slide${it + 1}.xml" to slideType }.toTypedArray(),
            ),
            "_rels/.rels" to relationships("officeDocument" to "ppt/presentation.xml"),
            "ppt/presentation.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:presentation xmlns:p="$P" xmlns:r="$OFFICE_RELS" xmlns:a="$A" $attributes>
                <p:sldIdLst>${slides.indices.joinToString("") { """<p:sldId id="${256 + it}" r:id="rId${it + 1}"/>""" }}</p:sldIdLst>
                <p:sldSz cx="${720 * EMU}" cy="${540 * EMU}"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to relationships(*(slides.indices.map { "slide" to "slides/slide${it + 1}.xml" } + more).toTypedArray()),
        )
        slides.forEachIndexed { i, xml -> all["ppt/slides/slide${i + 1}.xml"] = xml }
        slideRels.forEach { (i, rels) -> all["ppt/slides/_rels/slide${i + 1}.xml.rels"] = rels }
        all.putAll(parts)
        return zip(name, all)
    }

    private fun xfrm(x: Int, y: Int, w: Int, h: Int, attributes: String = "") =
        """<a:xfrm $attributes><a:off x="${x * EMU}" y="${y * EMU}"/><a:ext cx="${w * EMU}" cy="${h * EMU}"/></a:xfrm>"""

    private fun solid(hex: String) = """<a:solidFill><a:srgbClr val="$hex"/></a:solidFill>"""

    /** A p:sp with [spPr] and, when given, a text body of [paragraphs] with [bodyPr] and the placeholder [ph]. */
    private fun sp(id: Int, spPr: String, paragraphs: String? = null, bodyPr: String = "<a:bodyPr/>", ph: String = "") =
        """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="Shape $id"/><p:cNvSpPr/><p:nvPr>$ph</p:nvPr></p:nvSpPr><p:spPr>$spPr</p:spPr>""" +
            (paragraphs?.let { "<p:txBody>$bodyPr<a:lstStyle/>$it</p:txBody>" } ?: "") + "</p:sp>"

    private fun pic(id: Int, rel: String, spPr: String, crop: String = "") = """<p:pic><p:nvPicPr><p:cNvPr id="$id" name="Picture $id"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
        <p:blipFill><a:blip r:embed="$rel"/>$crop<a:stretch><a:fillRect/></a:stretch></p:blipFill><p:spPr>$spPr</p:spPr></p:pic>"""

    /** A small PNG all in [color]. */
    private fun solidPng(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
    }

    /** Whether the pixel at ([x], [y]) points of [page], drawn [scale] pixels a point, is about [color]. */
    private fun isColor(page: Bitmap, scale: Float, x: Float, y: Float, color: Int): Boolean {
        val c = page.getPixel((x * scale).toInt(), (y * scale).toInt())
        return abs(Color.red(c) - Color.red(color)) < 60 && abs(Color.green(c) - Color.green(color)) < 60 && abs(Color.blue(c) - Color.blue(color)) < 60
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

    @Test fun wordOldDrawingCanvasesStayInTheText() {
        // A Word 2003 drawing canvas in the text, 300 × 150 points, holding a screenshot placed in the
        // canvas's own coordinates, which say nothing of where the canvas goes on the page.
        val canvas = """<w:r><w:pict><v:group id="Canvas" editas="canvas" coordorigin="1800,1440" coordsize="6000,3000"
              style="width:300pt;height:150pt;mso-position-horizontal-relative:char;mso-position-vertical-relative:line">
            <v:shape id="Screenshot" type="#_x0000_t75" style="position:absolute;left:3600;top:3000;width:2000;height:1500">
            <v:imagedata r:id="rId2" o:title="screenshot"/></v:shape></v:group></w:pict></w:r>"""
        val body = para("Before the canvas") + "<w:p>$canvas</w:p>" + para("After the canvas")
        val pdf = convert(wordDocument(body, "canvas.docx"), OfficeKind.WORD, "word-canvas")
        PDDocument.load(pdf).use { doc ->
            val image = bounds(imagesOn(doc.getPage(0)).single())
            near(300f, image.width()); near(150f, image.height())
            near(72f, image.left)
            // In the text, taking its room: the next paragraph goes below it.
            val after = word(pdf, 1, "After")
            assertTrue("${after.yDirAdj} below ${image.bottom}", after.yDirAdj > image.bottom)
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

    @Test fun wordEvenPagesHaveTheirOwnHeaders() {
        fun header(text: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:hdr $WORD_NAMESPACES>${para(text)}</w:hdr>"""
        val pageBreak = """<w:p><w:r><w:br w:type="page"/></w:r></w:p>"""
        val body = para("One") + pageBreak + para("Two") + pageBreak + para("Three") +
            """<w:sectPr><w:headerReference w:type="default" r:id="rId4"/><w:headerReference w:type="even" r:id="rId5"/></w:sectPr>"""
        val pdf = convert(
            wordDocument(
                body, "even-odd.docx", rels = listOf("header" to "header1.xml", "header" to "header2.xml", "settings" to "settings.xml"),
                parts = mapOf(
                    "word/header1.xml" to header("Right-hand header"),
                    "word/header2.xml" to header("Left-hand header"),
                    "word/settings.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="$W"><w:evenAndOddHeaders/></w:settings>""",
                ),
            ),
            OfficeKind.WORD, "word-even-odd-headers",
        )
        assertEquals(listOf("Right", "Left", "Right"), pageTexts(pdf).map { Regex("""(\S+)-hand header""").find(it)?.groupValues?.get(1) })
    }

    @Test fun wordRightAndCentreTabStopsPlaceTheTextAfterThem() {
        // Word's Header style: a centre stop in the middle of the text and a right stop at its right
        // margin. Stops count from the margin, so a first line indented as in many styles changes nothing.
        val tabs = """<w:tabs><w:tab w:val="center" w:pos="4680"/><w:tab w:val="right" w:pos="9360"/></w:tabs>"""
        for (indent in listOf("", """<w:ind w:firstLine="720"/>""")) {
            val header = """<w:p><w:pPr>$tabs$indent</w:pPr><w:r><w:t>Report</w:t><w:tab/><w:t>Middle</w:t><w:tab/><w:t xml:space="preserve">Page 1</w:t></w:r></w:p>"""
            val pdf = convert(wordDocument(header, "tabs.docx"), OfficeKind.WORD, "word-tab-stops")
            val all = glyphs(pdf, 1)
            assertEquals("All on one line", 1, all.map { it.yDirAdj.toInt() }.distinct().size)
            val at = all.joinToString("") { it.unicode }.indexOf("Middle")
            val middle = all.subList(at, at + 6)
            near(72f + 234f, (middle.first().xDirAdj + middle.last().xDirAdj + middle.last().widthDirAdj) / 2, 2f)
            near(72f + 468f, all.last().xDirAdj + all.last().widthDirAdj, 2f)
        }
    }

    /** A run referring to footnote (or endnote) [id], in Word's reference style. */
    private fun noteRef(id: Int, endnote: Boolean = false) = if (endnote) {
        """<w:r><w:rPr><w:rStyle w:val="EndnoteReference"/></w:rPr><w:endnoteReference w:id="$id"/></w:r>"""
    } else {
        """<w:r><w:rPr><w:rStyle w:val="FootnoteReference"/></w:rPr><w:footnoteReference w:id="$id"/></w:r>"""
    }

    /**
     * A document of [body] with footnotes and endnotes as Word writes them: the separators, a
     * continuation notice, and [footnotes] and [endnotes] (each a note's text, numbered by id from 2),
     * in the note styles; [settings] goes in settings.xml.
     */
    private fun notesDocument(body: String, name: String, footnotes: List<String>, endnotes: List<String> = emptyList(), settings: String = ""): File {
        fun notes(kind: String, texts: List<String>): String {
            val style = if (kind == "footnote") "Footnote" else "Endnote"
            val single = """<w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr>"""
            return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:${kind}s xmlns:w="$W">
                <w:$kind w:type="separator" w:id="-1"><w:p>$single<w:r><w:separator/></w:r></w:p></w:$kind>
                <w:$kind w:type="continuationSeparator" w:id="0"><w:p>$single<w:r><w:continuationSeparator/></w:r></w:p></w:$kind>
                <w:$kind w:type="continuationNotice" w:id="1"><w:p><w:r><w:t>Notice of more</w:t></w:r></w:p></w:$kind>""" +
                texts.withIndex().joinToString("") { (i, text) ->
                    """<w:$kind w:id="${i + 2}"><w:p><w:pPr><w:pStyle w:val="${style}Text"/></w:pPr><w:r><w:rPr><w:rStyle w:val="${style}Reference"/></w:rPr><w:${kind}Ref/></w:r>""" +
                        """<w:r><w:t xml:space="preserve"> $text</w:t></w:r></w:p></w:$kind>"""
                } + "</w:${kind}s>"
        }
        val noteStyles = listOf("Footnote", "Endnote").joinToString("") { kind ->
            """<w:style w:type="paragraph" w:styleId="${kind}Text"><w:name w:val="${kind.lowercase()} text"/><w:basedOn w:val="Normal"/>
                <w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr><w:rPr><w:sz w:val="20"/></w:rPr></w:style>
                <w:style w:type="character" w:styleId="${kind}Reference"><w:name w:val="${kind.lowercase()} reference"/><w:rPr><w:vertAlign w:val="superscript"/></w:rPr></w:style>"""
        }
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$W">
            <w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="Times New Roman" w:hAnsi="Times New Roman"/><w:sz w:val="22"/></w:rPr></w:rPrDefault>
            <w:pPrDefault><w:pPr><w:spacing w:after="120"/></w:pPr></w:pPrDefault></w:docDefaults>
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>$noteStyles</w:styles>"""
        return wordDocument(
            body, name,
            rels = listOf("footnotes" to "footnotes.xml", "endnotes" to "endnotes.xml", "settings" to "settings.xml"),
            parts = mapOf(
                "word/styles.xml" to styles,
                "word/footnotes.xml" to notes("footnote", footnotes),
                "word/endnotes.xml" to notes("endnote", endnotes),
                "word/settings.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="$W">$settings</w:settings>""",
            ),
        )
    }

    /** The character just after the first place [word] is written on page [number] of [file]. */
    private fun after(file: File, number: Int, word: String): TextPosition {
        val all = glyphs(file, number)
        val starts = IntArray(all.size)
        val text = StringBuilder()
        all.forEachIndexed { i, glyph ->
            starts[i] = text.length
            text.append(glyph.unicode)
        }
        val at = text.indexOf(word)
        assertTrue("\"$word\" is on page $number", at >= 0)
        return all[starts.indexOfFirst { it >= at + word.length }]
    }

    /** Where [word] first is on page [number] of [file]: its top, in points from the page's top. */
    private fun top(file: File, number: Int, word: String): Float = word(file, number, word).let { it.yDirAdj - it.heightDir }

    @Test fun wordFootnotesGoAtTheFootOfTheirPageAndEndnotesAtTheEnd() {
        val filler = (1..30).joinToString("") { para("Filler line $it of the body.") }
        val longNote = "A long note that goes on and on. ".repeat(60)
        val body = """<w:p><w:r><w:t>Alpha</w:t></w:r>${noteRef(2)}<w:r><w:t xml:space="preserve"> and Charlie</w:t></w:r>${noteRef(2, endnote = true)}</w:p>""" +
            filler + """<w:p><w:r><w:t>Bravo</w:t></w:r>${noteRef(3)}</w:p>""" + filler +
            """<w:tbl><w:tblGrid><w:gridCol w:w="4000"/></w:tblGrid><w:tr><w:tc><w:p><w:r><w:t>Delta</w:t></w:r>${noteRef(4)}${noteRef(3, endnote = true)}</w:p></w:tc></w:tr></w:tbl>"""
        val pdf = convert(
            notesDocument(body, "notes.docx", listOf("First note.", longNote, "Table note."), listOf("First endnote.", "Second endnote.")),
            OfficeKind.WORD, "word-notes",
        )
        val texts = pageTexts(pdf)
        val all = texts.joinToString(" ")
        // The special notes aren't notes; each note once, with its mark, at the foot of its reference's page.
        assertFalse(all, "Notice of more" in all)
        assertTrue(texts[0], Regex("""Alpha1 and Charlie""").containsMatchIn(texts[0]))
        assertTrue(texts[0], Regex("""1 First note\.""").containsMatchIn(texts[0]))
        assertTrue("A superscript mark", size(after(pdf, 1, "Alpha")) < size(word(pdf, 1, "Alpha")))
        assertTrue("Under the text", top(pdf, 1, "First note") > top(pdf, 1, "Filler line 30"))
        assertTrue("Above the bottom margin", word(pdf, 1, "First note").yDirAdj < 720f)
        // The long note starts on its reference's page and goes on at the foot of the next.
        assertTrue(texts[0], "Bravo2" in texts[0] && "2 A long note" in texts[0])
        val goesOn = texts.indexOfFirst { "on and on" in it && "Bravo" !in it }
        assertEquals(1, goesOn)
        assertTrue("At the foot of the page", top(pdf, 2, "on and on") > top(pdf, 2, "Filler line"))
        // Endnotes at the end of the document, numbered i, ii.
        assertEquals(1, Regex("""First endnote""").findAll(all).count())
        val end = texts.last()
        assertTrue(all, "Delta3ii" in all && "3 Table note." in all)
        assertTrue(end, Regex("""i First endnote\.\s*ii Second endnote\.""").containsMatchIn(end))
        // A long note keeps two lines at least on its reference's page, as its paragraph's widow and orphan
        // control says: with the reference lower and lower on the page, its line goes on to the next page
        // rather than leave one line of its note.
        val pageBreaks = """<w:p><w:r><w:br w:type="page"/></w:r></w:p>""".repeat(2)
        val lower = (0..15).joinToString(pageBreaks) { i ->
            filler + """<w:p><w:pPr><w:spacing w:before="${i * 80}"/></w:pPr><w:r><w:t>Bravo</w:t></w:r>${noteRef(2 + i)}</w:p>"""
        }
        val pdf2 = convert(notesDocument(lower, "lower.docx", List(16) { "A long note that goes on and on. ".repeat(30) }), OfficeKind.WORD, "word-long-notes")
        val raw = PDDocument.load(pdf2).use { doc -> (1..doc.numberOfPages).map { n -> PDFTextStripper().apply { startPage = n; endPage = n }.getText(doc) } }
        for (page in raw) {
            val start = Regex("""(\d+) A long note""").find(page) ?: continue
            assertTrue(page, Regex("""Bravo${start.groupValues[1]}\b""").containsMatchIn(page))
            assertTrue(page, page.substring(start.range.first).trim().lines().size >= 2)
        }
    }

    @Test fun wordNotesAreNumberedAndPlacedAsTheDocumentSays() {
        // Footnotes a, b... again on each page, just under the text; endnotes *, †... at the end of each section.
        val settings = """<w:footnotePr><w:pos w:val="beneathText"/><w:numFmt w:val="lowerLetter"/><w:numRestart w:val="eachPage"/></w:footnotePr>
            <w:endnotePr><w:pos w:val="sectEnd"/><w:numFmt w:val="chicago"/></w:endnotePr>"""
        fun line(text: String, vararg refs: String) = """<w:p><w:r><w:t>$text</w:t></w:r>${refs.joinToString("")}</w:p>"""
        // A custom mark: the text after the reference, which takes no number.
        val custom = """<w:r><w:rPr><w:rStyle w:val="FootnoteReference"/></w:rPr><w:footnoteReference w:customMarkFollows="1" w:id="5"/><w:t>#</w:t></w:r>"""
        val body = line("One", noteRef(2), noteRef(2, endnote = true)) + line("Two", noteRef(3)) + """<w:p><w:r><w:br w:type="page"/></w:r></w:p>""" +
            line("Three", noteRef(4)) + line("Custom", custom) + line("Four", noteRef(6)) + """<w:p><w:pPr><w:sectPr/></w:pPr></w:p>""" +
            line("Five", noteRef(3, endnote = true)) + "<w:sectPr/>"
        val pdf = convert(
            notesDocument(body, "numbered.docx", listOf("Note A.", "Note B.", "Note C.", "Note D.", "Note E."), listOf("End one.", "End two."), settings),
            OfficeKind.WORD, "word-numbered-notes",
        )
        val texts = pageTexts(pdf)
        assertTrue(texts[0], "Onea*" in texts[0] && "Twob" in texts[0] && "a Note A." in texts[0] && "b Note B." in texts[0])
        assertTrue("Just under the text", top(pdf, 1, "Note A") < 200f)
        assertTrue(texts[1], "Threea" in texts[1] && "Custom#" in texts[1] && "Fourb" in texts[1])
        assertTrue(texts[1], Regex("""a Note C\.\s*# Note D\.\s*b Note E\.""").containsMatchIn(texts[1]))
        // The first section's endnote at its end, the second's at the end of the document.
        assertTrue(texts[1], "* End one." in texts[1])
        assertTrue(texts.last(), "Five†" in texts.last() && "† End two." in texts.last())
        // Numbered again on each page, a reference whose line its note takes on to the next page is numbered
        // there, and its note with it: the reference lower and lower on the page.
        val filler = (1..30).joinToString("") { para("Filler line $it of the body.") }
        val lower = (0..15).joinToString("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""") { i ->
            line("Alpha", noteRef(2 + 2 * i)) + filler +
                """<w:p><w:pPr><w:spacing w:before="${i * 80}"/></w:pPr><w:r><w:t>Bravo</w:t></w:r>${noteRef(3 + 2 * i)}</w:p>"""
        }
        val eachPage = """<w:footnotePr><w:numRestart w:val="eachPage"/></w:footnotePr>"""
        val pages = pageTexts(convert(notesDocument(lower, "moved.docx", List(32) { if (it % 2 == 0) "Alpha note." else "Bravo note." }, settings = eachPage), OfficeKind.WORD, "word-moved-notes"))
        assertTrue(pages.any { "Bravo1" in it })
        for (page in pages) Regex("""Bravo(\d)""").findAll(page).forEach { assertTrue(page, Regex("""\b${it.groupValues[1]} Bravo note""").containsMatchIn(page)) }
    }

    @Test fun wordPageNumbersInTheTextAreTheirOwnPages() {
        val field = """<w:fldSimple w:instr=" PAGE "><w:r><w:t>9</w:t></w:r></w:fldSimple>"""
        val body = """<w:p><w:r><w:t xml:space="preserve">Opening page </w:t></w:r>$field</w:p><w:p><w:pPr><w:sectPr/></w:pPr></w:p>""" +
            """<w:p><w:r><w:t xml:space="preserve">Restarted page </w:t></w:r>$field</w:p><w:sectPr><w:pgNumType w:start="5"/></w:sectPr>"""
        val texts = pageTexts(convert(wordDocument(body, "body-page.docx"), OfficeKind.WORD, "word-body-page"))
        assertTrue(texts[0], "Opening page 1" in texts[0])
        assertTrue(texts[1], "Restarted page 5" in texts[1])
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

    @Test fun wordLegalNumberingWritesEveryLevelInArabic() {
        // Contracts number articles in roman and their sections in legal style: Section 1.1, not I.1.
        val numbering = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:numbering xmlns:w="$W">
            <w:abstractNum w:abstractNumId="0">
              <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="upperRoman"/><w:lvlText w:val="Article %1"/><w:suff w:val="space"/></w:lvl>
              <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:isLgl/><w:lvlText w:val="Section %1.%2"/><w:suff w:val="space"/></w:lvl></w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
            </w:numbering>"""
        val body = listItem("Terms", 1, 0) + listItem("Scope", 1, 1) + listItem("Payment", 1, 0) + listItem("Fees", 1, 1)
        val text = pageTexts(convert(wordDocument(body, "legal.docx", numbering = numbering), OfficeKind.WORD, "word-legal-numbering"))[0]
        assertTrue(text, text.contains("Article I Terms Section 1.1 Scope Article II Payment Section 2.1 Fees"))
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

    @Test fun wordParagraphsReadLeftToRightUnlessSetOtherwise() {
        // No w:bidi: Hebrew first, but left to right and at the left margin, as in Word.
        val pdf = convert(wordDocument(para("שלום world"), "ltr-hebrew.docx"), OfficeKind.WORD, "word-ltr-hebrew")
        val hebrew = glyphs(pdf, 1).filter { g -> g.unicode.any { it in 'א'..'ת' } }
        near(72f, hebrew.minOf { it.xDirAdj })
    }

    @Test fun wordComplexScriptTextHasItsOwnSize() {
        // Hebrew takes w:szCs, the Latin letters beside it w:sz, as in Word.
        val run = """<w:sz w:val="22"/><w:szCs w:val="32"/>"""
        val pdf = convert(wordDocument(para("שלום world", run = run), "complex-script.docx"), OfficeKind.WORD, "word-complex-script")
        val hebrew = glyphs(pdf, 1).filter { g -> g.unicode.any { it in 'א'..'ת' } }
        assertTrue(hebrew.isNotEmpty())
        hebrew.forEach { near(16f, size(it), 0.3f) }
        near(11f, size(word(pdf, 1, "world")), 0.3f)
    }

    @Test fun wordTablesSpanningCountlessOrNegativeColumnsStillConvert() {
        val grid = """<w:tblGrid><w:gridCol w:w="3000"/></w:tblGrid>"""
        val body = """<w:tbl>$grid<w:tr><w:tc><w:tcPr><w:gridSpan w:val="20000000"/></w:tcPr>${para("Wide cell")}</w:tc></w:tr></w:tbl>""" + para("Between") +
            """<w:tbl>$grid<w:tr><w:trPr><w:gridBefore w:val="-3"/></w:trPr><w:tc>${para("Shifted cell")}</w:tc></w:tr></w:tbl>"""
        val text = pageTexts(convert(wordDocument(body, "spans.docx"), OfficeKind.WORD, "word-spans"))[0]
        assertTrue(text, "Wide cell" in text && "Shifted cell" in text)
    }

    @Test fun wordSpaceBeforeTallerThanThePageLeavesTheTextOnIt() {
        // A cover title pushed down by 700 points, more than a Letter page has for text: it ends up at the foot of the page, not below it.
        val pdf = convert(wordDocument(para("Cover title", """<w:spacing w:before="14000"/>"""), "space-before.docx"), OfficeKind.WORD, "word-space-before")
        val title = word(pdf, 1, "Cover")
        assertTrue("${title.yDirAdj}", title.yDirAdj in 600f..721f)
    }

    // ---- The text engine Word, PowerPoint and EPUB share ----

    @Test fun aSuperscriptParagraphMarkLeavesItsTextWhereItIs() {
        // Word keeps the look of a paragraph's mark (superscript, spaced out after "m²" and Enter, say).
        fun drawn(mark: TextStyle): Bitmap {
            val text = RichText().apply {
                append("H", TextStyle())
                append("2", TextStyle(baseline = -1))
                append("O", TextStyle())
            }.text
            val block = ParagraphBlock(ParagraphSpec(text, mark, 2000))
            return Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888).also { Canvas(it).apply { drawColor(Color.WHITE); block.draw(this, 10f, 60f) } }
        }
        assertTrue(drawn(TextStyle()).sameAs(drawn(TextStyle(baseline = 1, spacing = 3f))))
    }

    @Test fun aParagraphSplitAfterALineBreakFitsItsSpace() {
        val text = RichText().apply { append("one\ntwo\nthree\nfour", TextStyle()) }.text
        val block = ParagraphBlock(ParagraphSpec(text, TextStyle(), 2000, avoidSingleLines = false))
        val space = block.layout.getLineBottom(1)
        val (head, tail) = block.split(space, false)!!
        assertTrue("${head.height} in $space", head.height <= space)
        assertNotNull(tail)
    }

    @Test fun aWordWiderThanTheLineBreaksAtEachLinesEndInLittleMemory() {
        // Pasted blocks 100,000 characters long. Android measured each of their lines along with all of the
        // block, keeping a copy for each.
        val paint = TextStyle().paint()
        fun lines(word: String): List<String> {
            val text = RichText().apply { append("Key: $word end", TextStyle()) }.text
            val before = Debug.getNativeHeapAllocatedSize()
            val layout = ParagraphBlock(ParagraphSpec(text, TextStyle(), 4680)).layout
            val grown = Debug.getNativeHeapAllocatedSize() - before
            assertTrue("Native memory grew by $grown bytes", grown < 32L shl 20)
            val lines = (0 until layout.lineCount).map { layout.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trimEnd('\n') }
            assertEquals("Key: $word end", lines.joinToString(""))
            return lines
        }
        // Base64 has nowhere Word breaks: lines of its own, full to the end, not broken after its slashes.
        val base64 = lines("QWxhZGRpbjpvcGVuIHNlc2FtZQ+/".repeat(3572))
        assertEquals("Key: ", base64.first())
        for (line in base64.subList(1, base64.lastIndex)) assertTrue(line, paint.measureText(line) > 4680 - paint.measureText("W"))
        // Base64url, as UUIDs and JSON, has hyphens: each line ends after one, the first after "Key:".
        val dashed = lines("QWxhZGRpbjpvcGVuIHNlc2FtZQ_-".repeat(3572))
        assertTrue(dashed.first(), dashed.first().startsWith("Key: QWxh"))
        for (line in dashed.dropLast(1)) assertTrue(line, line.endsWith("-") && paint.measureText(line) <= 4681)
        // Justified, the line keeps the start of such a run and stays justified.
        val key = RichText().apply { append("Key: " + "QWxhZGRpbjpvcGVuIHNlc2FtZQ_-".repeat(40) + " end", TextStyle()) }.text
        val justified = ParagraphBlock(ParagraphSpec(key, TextStyle(), 4680, Align.JUSTIFY)).layout
        assertTrue(justified.text.substring(0, justified.getLineEnd(0)).startsWith("Key: QWxh"))
        assertTrue(justified.getLineWidth(0) > 4670)
    }

    @Test fun aParagraphThatReadsRightToLeftByItsTextDoesOnAllItsLines() {
        // A line break, and those put in a run wider than the line, start no paragraph of their own direction.
        val hebrew = RichText().apply { append("שלום עולם\nhttps://example.com/" + "abcdefghij".repeat(12) + " סוף הפסקה כאן", TextStyle()) }.text
        val layout = ParagraphBlock(ParagraphSpec(hebrew, TextStyle(), 3320)).layout
        assertTrue(layout.lineCount > 3)
        for (line in 0 until layout.lineCount) assertEquals(Layout.DIR_RIGHT_TO_LEFT, layout.getParagraphDirection(line))
    }

    @Test fun aVeryLongParagraphGoesInPiecesLaidOutAsItIs() {
        val text = RichText().apply { append("Many words in one long paragraph, with a line break now and then.\n".repeat(1000), TextStyle()) }.text
        val block = ParagraphBlock(ParagraphSpec(text, TextStyle(), 2000))
        val pieces = block.pieces()
        assertTrue(pieces.size > 1)
        assertEquals(text.toString(), pieces.joinToString("") { it.layout.text })
        assertEquals(block.height.toFloat(), pieces.sumOf { it.height }.toFloat(), pieces.size.toFloat())
    }

    @Test fun officeFontsBecomeThePhonesFontOfTheSameKind() {
        assertEquals("sans-serif", OfficeFonts.family("Century Gothic"))
        assertEquals("cursive", OfficeFonts.family("Monotype Corsiva"))
        assertEquals("serif", OfficeFonts.family("Century Schoolbook"))
        assertEquals("monospace", OfficeFonts.family("Courier New"))
        assertEquals("monospace", OfficeFonts.family("Roboto Mono"))
        assertEquals("monospace", OfficeFonts.family("Noto Sans Mono"))
        // Names written as PostScript writes them.
        for (name in listOf("DejaVuSansMono", "RobotoMono-Regular", "SFMono-Regular", "SourceCodePro-Regular")) assertEquals(name, "monospace", OfficeFonts.family(name))
    }

    @Test fun closingWithAPageOpenFreesThePages() {
        // As when a conversion is cancelled while a page is being drawn.
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val pdf = PagedPdf(work)
        pdf.keep(bitmap)
        pdf.startPage(100f, 100f)
        pdf.close()
        assertTrue(bitmap.isRecycled)
    }

    @Test fun deeplyNestedXmlDoesNotOverflowTheStack() {
        // Only a crafted file nests this deep: what's too deep is left out, and the rest converts.
        fun nested(depth: Int) = wordDocument("<w:customXml>".repeat(depth) + para("Deep") + "</w:customXml>".repeat(depth) + para("Shallow"), "nested-$depth.docx")
        val pdf = convert(nested(10_000), OfficeKind.WORD, "word-nested")
        assertTrue(pageTexts(pdf).joinToString(" ").contains("Shallow"))
        // Far deeper, which the XML parser would take minutes over, the file is refused as damaged.
        try {
            convert(nested(100_000), OfficeKind.WORD, "word-too-nested")
            fail("Converted a document nested 100,000 levels deep")
        } catch (_: IOException) {
        }
    }

    @Test fun everyWayOfNestingHoldsInTheStack() {
        // Links in a paragraph, tables in cells, content controls and PowerPoint's groups are each read
        // a level at a time, one call inside another. In a debuggable app on Android 15, a worker
        // thread's stack ran out before the levels read stop; what's deeper is left out, and the rest converts.
        val depth = 1_000
        val group = """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="5" name="Group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"""
        val files = listOf(
            wordDocument("<w:p>" + "<w:hyperlink>".repeat(depth) + "<w:r><w:t>Deep</w:t></w:r>" + "</w:hyperlink>".repeat(depth) + "</w:p>" + para("Shallow"), "links.docx") to OfficeKind.WORD,
            wordDocument("<w:tbl><w:tr><w:tc>".repeat(depth) + para("Deep") + "</w:tc></w:tr></w:tbl>".repeat(depth) + para("Shallow"), "tables.docx") to OfficeKind.WORD,
            wordDocument("<w:sdt><w:sdtContent>".repeat(depth) + para("Deep") + "</w:sdtContent></w:sdt>".repeat(depth) + para("Shallow"), "controls.docx") to OfficeKind.WORD,
            deck(
                "groups.pptx",
                listOf(
                    slide(
                        group.repeat(depth) + textBox(2, 36, 36, 300, 72, "<a:p>${run("Deep", 18)}</a:p>") + "</p:grpSp>".repeat(depth) +
                            textBox(3, 36, 136, 300, 72, "<a:p>${run("Shallow", 18)}</a:p>"),
                    ),
                ),
            ) to OfficeKind.POWERPOINT,
        )
        for ((file, kind) in files) {
            val text = pageTexts(convert(file, kind, "nested-${file.nameWithoutExtension}")).joinToString(" ")
            assertTrue("${file.name}: $text", text.contains("Shallow") && !text.contains("Deep"))
        }
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

    @Test fun powerPointSlideNumbersCountHiddenSlides() {
        val numbered = { hidden: Boolean ->
            slide(textBox(2, 36, 36, 400, 72, """<a:p>${run("Slide ", 24)}<a:fld id="{B6F15528-21DE-4FAA-801E-634DDDAF4B2B}" type="slidenum"><a:rPr lang="en-US" sz="2400"/><a:t>‹#›</a:t></a:fld></a:p>"""), hidden)
        }
        val slides = listOf(numbered(false), numbered(true), numbered(false), numbered(false))
        fun numbers(pdf: File) = pageTexts(pdf).map { Regex("""Slide (\d+)""").find(it)?.groupValues?.get(1) }
        // A hidden slide keeps its number, as in PowerPoint.
        assertEquals(listOf("1", "3", "4"), numbers(convert(deck("numbers.pptx", slides), OfficeKind.POWERPOINT, "slide-numbers")))
        assertEquals(listOf("0", "2", "3"), numbers(convert(deck("numbers-0.pptx", slides, """firstSlideNum="0""""), OfficeKind.POWERPOINT, "slide-numbers-0")))
    }

    @Test fun powerPointConnectorsFlippedGroupsAndSavedPictures() {
        val rect = """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>"""
        val elbow = """<p:cxnSp><p:nvCxnSpPr><p:cNvPr id="2" name="Elbow"/><p:cNvCxnSpPr/><p:nvPr/></p:nvCxnSpPr><p:spPr>${xfrm(40, 40, 200, 100)}
            <a:prstGeom prst="bentConnector3"><a:avLst/></a:prstGeom><a:ln w="${4 * EMU}">${solid("000000")}<a:tailEnd type="triangle"/></a:ln></p:spPr></p:cxnSp>"""
        val circle = sp(3, xfrm(300, 40, 100, 100) + """<a:prstGeom prst="flowChartConnector"><a:avLst/></a:prstGeom>""" + solid("FF0000"))
        // Flipped left to right: its blue square, in the left half of the group's own coordinates, on the right.
        val group = """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="4" name="Group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm flipH="1">
            <a:off x="${40 * EMU}" y="${200 * EMU}"/><a:ext cx="${200 * EMU}" cy="${100 * EMU}"/><a:chOff x="0" y="0"/><a:chExt cx="${200 * EMU}" cy="${100 * EMU}"/></a:xfrm></p:grpSpPr>
            ${sp(5, xfrm(0, 0, 100, 100) + rect + solid("0000FF"))}</p:grpSp>"""
        // Freeforms: one with a point given by a formula, which is left out; one in the shape's EMUs, 72 points square.
        val formula = sp(6, xfrm(300, 200, 100, 100) + """<a:custGeom><a:pathLst><a:path w="100" h="100"><a:moveTo><a:pt x="gd1" y="0"/></a:moveTo>
            <a:lnTo><a:pt x="100" y="100"/></a:lnTo><a:lnTo><a:pt x="0" y="100"/></a:lnTo><a:close/></a:path></a:pathLst></a:custGeom>""" + solid("FF0000"))
        val emus = sp(7, xfrm(450, 200, 200, 200) + """<a:custGeom><a:pathLst><a:path><a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="914400" y="0"/></a:lnTo>
            <a:lnTo><a:pt x="914400" y="914400"/></a:lnTo><a:lnTo><a:pt x="0" y="914400"/></a:lnTo><a:close/></a:path></a:pathLst></a:custGeom>""" + solid("00B050"))
        val mc = """xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006""""
        // Ink, and an equation, which PowerPoint also saves as pictures.
        val ink = """<mc:AlternateContent $mc><mc:Choice Requires="p14"><p:contentPart r:id="rId4"/></mc:Choice>
            <mc:Fallback>${pic(10, "rId1", xfrm(40, 40, 100, 100) + rect)}</mc:Fallback></mc:AlternateContent>"""
        val equation = """<mc:AlternateContent $mc xmlns:a14="http://schemas.microsoft.com/office/drawing/2010/main"><mc:Choice Requires="a14">
            ${sp(11, xfrm(200, 40, 100, 100) + rect, """<a:p><a14:m><m:oMathPara xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"><m:oMath><m:r><m:t>x</m:t></m:r></m:oMath></m:oMathPara></a14:m></a:p>""")}
            </mc:Choice><mc:Fallback>${sp(11, xfrm(200, 40, 100, 100) + rect + """<a:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></a:blipFill>""")}</mc:Fallback></mc:AlternateContent>"""
        val text = """<mc:AlternateContent $mc><mc:Choice Requires="p14">${textBox(12, 40, 200, 300, 50, "<a:p>${run("Choice text", 18)}</a:p>")}</mc:Choice>
            <mc:Fallback>${textBox(12, 40, 200, 300, 50, "<a:p>${run("Fallback text", 18)}</a:p>")}</mc:Fallback></mc:AlternateContent>"""
        // An embedded spreadsheet, shown by the picture saved with it.
        val ole = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="13" name="Object"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
            <p:xfrm><a:off x="${400 * EMU}" y="${40 * EMU}"/><a:ext cx="${100 * EMU}" cy="${100 * EMU}"/></p:xfrm><a:graphic>
            <a:graphicData uri="http://schemas.openxmlformats.org/presentationml/2006/ole"><mc:AlternateContent $mc><mc:Choice Requires="v">
            <p:oleObj name="Worksheet" r:id="rId5" progId="Excel.Sheet.12"><p:embed/></p:oleObj></mc:Choice><mc:Fallback>
            <p:oleObj name="Worksheet" r:id="rId5" progId="Excel.Sheet.12"><p:embed/>${pic(14, "rId3", xfrm(400, 40, 100, 100) + rect)}</p:oleObj>
            </mc:Fallback></mc:AlternateContent></a:graphicData></a:graphic></p:graphicFrame>"""
        val rels = relationships(
            "image" to "../media/red.png", "image" to "../media/green.png", "image" to "../media/blue.png",
            "customXml" to "../ink/ink1.xml", "oleObject" to "../embeddings/oleObject1.bin",
        )
        val media = mapOf("ppt/media/red.png" to solidPng(Color.RED), "ppt/media/green.png" to solidPng(Color.GREEN), "ppt/media/blue.png" to solidPng(Color.BLUE))
        // Cropped by minus half its width on each side (Crop > Fit): the middle half of its frame.
        val fitted = pic(15, "rId1", xfrm(40, 300, 200, 100) + rect, """<a:srcRect l="-50000" r="-50000"/>""")
        // A group drawn half the size of its own coordinates, holding a freeform in its shape's EMUs, 200 points square.
        val half = """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="8" name="Half"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm>
            <a:off x="${40 * EMU}" y="${420 * EMU}"/><a:ext cx="${100 * EMU}" cy="${100 * EMU}"/><a:chOff x="0" y="0"/><a:chExt cx="${200 * EMU}" cy="${200 * EMU}"/></a:xfrm></p:grpSpPr>
            ${sp(9, xfrm(0, 0, 200, 200) + """<a:custGeom><a:pathLst><a:path><a:moveTo><a:pt x="0" y="0"/></a:moveTo><a:lnTo><a:pt x="${200 * EMU}" y="0"/></a:lnTo>
            <a:lnTo><a:pt x="${200 * EMU}" y="${200 * EMU}"/></a:lnTo><a:lnTo><a:pt x="0" y="${200 * EMU}"/></a:lnTo><a:close/></a:path></a:pathLst></a:custGeom>""" + solid("7030A0"))}</p:grpSp>"""
        // Flipped upside down: its text too.
        val upsideDown = sp(16, xfrm(450, 250, 200, 50, """flipV="1"""") + rect, "<a:p>${run("Upside down", 18)}</a:p>")
        val slides = listOf(slide(elbow + circle + group + formula + emus + half), slide(ink + equation + text + ole + fitted + upsideDown))
        val pdf = convert(deck("shapes.pptx", slides, slideRels = mapOf(1 to rels), parts = media), OfficeKind.POWERPOINT, "slide-shapes")
        val scale = 2f
        val shapes = render(pdf, 0, scale)
        assertTrue("The elbow's middle", isColor(shapes, scale, 140f, 90f, Color.BLACK))
        assertTrue("No box around the elbow", isColor(shapes, scale, 240f, 70f, Color.WHITE))
        assertTrue("Its arrowhead", isColor(shapes, scale, 230f, 136f, Color.BLACK))
        assertTrue("A flowchart connector is a filled circle", isColor(shapes, scale, 350f, 90f, Color.RED))
        assertTrue("The flipped group's square, on the right", isColor(shapes, scale, 190f, 250f, Color.BLUE))
        assertTrue(isColor(shapes, scale, 90f, 250f, Color.WHITE))
        assertTrue("A freeform that can't be drawn isn't a box", isColor(shapes, scale, 350f, 250f, Color.WHITE))
        assertTrue("A freeform in EMUs", isColor(shapes, scale, 486f, 236f, 0xFF00B050.toInt()))
        assertTrue(isColor(shapes, scale, 600f, 350f, Color.WHITE))
        assertTrue("A freeform in EMUs in a group half its size", isColor(shapes, scale, 90f, 470f, 0xFF7030A0.toInt()))
        assertTrue(isColor(shapes, scale, 180f, 470f, Color.WHITE))
        val saved = render(pdf, 1, scale)
        assertTrue("Ink", isColor(saved, scale, 90f, 90f, Color.RED))
        assertTrue("An equation", isColor(saved, scale, 250f, 90f, Color.GREEN))
        assertTrue("An embedded object", isColor(saved, scale, 450f, 90f, Color.BLUE))
        assertTrue("A picture fitted in its frame", isColor(saved, scale, 140f, 350f, Color.RED))
        assertTrue(isColor(saved, scale, 60f, 350f, Color.WHITE))
        val texts = pageTexts(pdf)
        assertTrue(texts[1], texts[1].contains("Choice text") && !texts[1].contains("Fallback text"))
        assertEquals(180f, word(pdf, 2, "Upside").dir)
    }

    @Test fun powerPointPlaceholdersAndColourMapsComeFromTheLayout() {
        val tree = """<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"""
        val rect = """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>"""
        val mapping = """bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink""""
        val master = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldMaster xmlns:p="$P" xmlns:a="$A" xmlns:r="$OFFICE_RELS"><p:cSld><p:spTree>$tree
            ${sp(2, xfrm(40, 40, 640, 80) + rect, "<a:p/>", ph = """<p:ph type="title"/>""")}</p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" $mapping/>
            <p:txStyles><p:titleStyle><a:lvl1pPr><a:defRPr sz="4000"><a:solidFill><a:schemeClr val="tx1"/></a:solidFill></a:defRPr></a:lvl1pPr></p:titleStyle></p:txStyles></p:sldMaster>"""
        // A grey picture placeholder, and an oval for the body.
        val layout = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sldLayout xmlns:p="$P" xmlns:a="$A" xmlns:r="$OFFICE_RELS"><p:cSld><p:spTree>$tree
            ${sp(2, xfrm(500, 300, 100, 100) + rect + solid("808080"), "<a:p/>", ph = """<p:ph type="pic" idx="13"/>""")}
            ${sp(3, xfrm(40, 300, 300, 200) + """<a:prstGeom prst="ellipse"><a:avLst/></a:prstGeom>""" + solid("00B050"), "<a:p/>", ph = """<p:ph idx="1"/>""")}
            </p:spTree></p:cSld></p:sldLayout>"""
        // The colours swapped, as a dark background style does: a black background, a white title.
        val dark = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><p:sld xmlns:p="$P" xmlns:a="$A" xmlns:r="$OFFICE_RELS"><p:cSld>
            <p:bg><p:bgRef idx="1001"><a:schemeClr val="bg1"/></p:bgRef></p:bg><p:spTree>$tree
            ${sp(2, "", """<a:p><a:r><a:rPr lang="en-US"/><a:t>Dark title</a:t></a:r></a:p>""", ph = """<p:ph type="title"/>""")}</p:spTree></p:cSld>
            <p:clrMapOvr><a:overrideClrMapping bg1="dk1" tx1="lt1" $mapping/></p:clrMapOvr></p:sld>"""
        // The picture placeholder left empty, and text typed in the body.
        val plain = slide(sp(2, "", ph = """<p:ph type="pic" idx="13"/>""") + sp(3, "", """<a:p><a:r><a:rPr lang="en-US"/><a:t>Hi</a:t></a:r></a:p>""", ph = """<p:ph idx="1"/>"""))
        val toLayout = relationships("slideLayout" to "../slideLayouts/slideLayout1.xml")
        val parts = mapOf(
            "ppt/slideLayouts/slideLayout1.xml" to layout,
            "ppt/slideLayouts/_rels/slideLayout1.xml.rels" to relationships("slideMaster" to "../slideMasters/slideMaster1.xml"),
            "ppt/slideMasters/slideMaster1.xml" to master,
        )
        val pdf = convert(deck("layouts.pptx", listOf(dark, plain), slideRels = mapOf(0 to toLayout, 1 to toLayout), parts = parts), OfficeKind.POWERPOINT, "slide-layouts")
        val scale = 2f
        val first = render(pdf, 0, scale)
        assertTrue("A black background", isColor(first, scale, 700f, 520f, Color.BLACK))
        val title = word(pdf, 1, "Dark")
        var white = 0
        for (x in (title.xDirAdj * scale).toInt() until ((title.xDirAdj + 80f) * scale).toInt()) {
            for (y in ((title.yDirAdj - 25f) * scale).toInt() until (title.yDirAdj * scale).toInt()) if (isColor(first, 1f, x.toFloat(), y.toFloat(), Color.WHITE)) white++
        }
        assertTrue("A white title", white > 50)
        val second = render(pdf, 1, scale)
        assertTrue("An empty placeholder isn't drawn", isColor(second, scale, 550f, 350f, Color.WHITE))
        assertTrue("The layout's oval, not a box", isColor(second, scale, 46f, 306f, Color.WHITE))
        assertTrue(isColor(second, scale, 190f, 420f, 0xFF00B050.toInt()))
    }

    @Test fun powerPointTextDirectionSymbolsAndAnchoring() {
        fun box(id: Int, x: Int, y: Int, bodyPr: String, paragraph: String) = sp(id, xfrm(x, y, 300, 300) + """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>""", paragraph, bodyPr)
        val hebrew = { text: String -> """<a:r><a:rPr lang="he-IL" sz="2000"/><a:t>$text</a:t></a:r>""" }
        // Aligned right in a right-to-left paragraph; Hebrew aligned left in a left-to-right one.
        val directions = slide(
            box(2, 40, 40, "<a:bodyPr/>", """<a:p><a:pPr algn="r" rtl="1"/>${hebrew("שלום עולם")}</a:p>""") +
                box(3, 380, 40, "<a:bodyPr/>", """<a:p><a:pPr algn="l"/>${hebrew("עולם שלום")}</a:p>"""),
        )
        // Wingdings' check mark from Insert > Symbol (a:sym), and typed in Wingdings; and a link, in the theme's link colour.
        val symbols = slide(
            box(2, 40, 40, "<a:bodyPr/>", """<a:p><a:r><a:rPr lang="en-US" sz="2000"><a:sym typeface="Wingdings"/></a:rPr><a:t>&#xF0FC;</a:t></a:r>""" +
                """<a:r><a:rPr lang="en-US" sz="2000"><a:latin typeface="Wingdings"/></a:rPr><a:t>ü</a:t></a:r></a:p>""" +
                """<a:p><a:r><a:rPr lang="en-US" sz="4000"><a:solidFill><a:srgbClr val="000000"/></a:solidFill><a:hlinkClick r:id="rId9"/></a:rPr><a:t>Link</a:t></a:r></a:p>"""),
        )
        // At the bottom of their boxes, with and without space before: a first paragraph's doesn't count.
        val bottom = """<a:bodyPr anchor="b" tIns="0" bIns="0"/>"""
        // As PowerPoint writes most text bodies: with what to do when the text overflows inside.
        val bottomWithAutofit = """<a:bodyPr anchor="b" tIns="0" bIns="0"><a:normAutofit/></a:bodyPr>"""
        val anchored = slide(
            box(2, 40, 100, bottom, """<a:p><a:pPr><a:spcBef><a:spcPts val="2000"/></a:spcBef></a:pPr>${run("Alpha", 20)}</a:p>""") +
                box(3, 380, 100, bottomWithAutofit, "<a:p>${run("Beta", 20)}</a:p>"),
        )
        val pdf = convert(deck("text.pptx", listOf(directions, symbols, anchored)), OfficeKind.POWERPOINT, "slide-text")
        val (rtl, ltr) = glyphs(pdf, 1).filter { g -> g.unicode.any { it in 'א'..'ת' } }.partition { it.xDirAdj < 360f }
        // 7.2 points in from the right of the first box, and from the left of the second.
        near(340f - 7.2f, rtl.maxOf { it.xDirAdj + it.widthDirAdj }, 3f)
        near(380f + 7.2f, ltr.minOf { it.xDirAdj }, 3f)
        val texts = pageTexts(pdf)
        assertEquals(texts[1], 2, texts[1].count { it == '✓' })
        val scale = 2f
        val links = render(pdf, 1, scale)
        val link = word(pdf, 2, "Link")
        var blue = 0
        for (x in 0 until 60) for (y in 0 until 25) if (isColor(links, scale, link.xDirAdj + x, link.yDirAdj - y, 0xFF0563C1.toInt())) blue++
        assertTrue("The link in the theme's link colour", blue > 20)
        near(word(pdf, 3, "Beta").yDirAdj, word(pdf, 3, "Alpha").yDirAdj)
    }

    @Test fun powerPointVerticalTextKeepsJapaneseUprightAndTurnsLatinLetters() {
        // Insert > Text Box > Vertical, as PowerPoint writes it: a narrow box that grows with its text.
        val vertical = """<a:bodyPr vert="eaVert" wrap="none"><a:spAutoFit/></a:bodyPr>"""
        val rect = """<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>"""
        val japanese = """<a:p><a:r><a:rPr lang="ja-JP" sz="2000"/><a:t>縦書き</a:t></a:r></a:p>"""
        val pdf = convert(
            deck("vertical.pptx", listOf(slide(sp(2, xfrm(40, 40, 30, 200) + rect, japanese, vertical) + sp(3, xfrm(200, 40, 30, 200) + rect, "<a:p>${run("Sideways", 20)}</a:p>", vertical)))),
            OfficeKind.POWERPOINT, "slide-vertical",
        )
        val (upright, turned) = glyphs(pdf, 1).partition { it.dir == 0f }
        assertEquals("縦書き", upright.joinToString("") { it.unicode })
        assertEquals("One under the other", 3, upright.map { it.yDirAdj.toInt() }.distinct().size)
        // Read down the page: turned a quarter clockwise.
        assertEquals("Sideways", turned.joinToString("") { it.unicode })
        assertTrue(turned.map { it.dir }.toString(), turned.all { it.dir == 270f })
    }

    @Test fun powerPointTableStylesAndMergedCells() {
        val id = "{5C22544A-0000-0000-0000-000000000001}"
        fun ln(hex: String) = """<a:ln w="${3 * EMU}">${solid(hex)}</a:ln>"""
        // Red at the table's top and bottom, green between rows; the last column blue.
        val styles = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><a:tblStyleLst xmlns:a="$A" def="$id"><a:tblStyle styleId="$id" styleName="Test">
            <a:wholeTbl><a:tcStyle><a:tcBdr><a:top>${ln("FF0000")}</a:top><a:bottom>${ln("FF0000")}</a:bottom><a:insideH>${ln("00B050")}</a:insideH></a:tcBdr>
            <a:fill><a:noFill/></a:fill></a:tcStyle></a:wholeTbl><a:lastCol><a:tcStyle><a:tcBdr/><a:fill>${solid("0000FF")}</a:fill></a:tcStyle></a:lastCol>
            </a:tblStyle></a:tblStyleLst>"""
        fun cell(text: String?, attributes: String = "", tcPr: String = "<a:tcPr/>") =
            """<a:tc $attributes><a:txBody><a:bodyPr/><a:lstStyle/><a:p>${text?.let { run(it, 18) }.orEmpty()}</a:p></a:txBody>$tcPr</a:tc>"""
        // A header merged across two columns, and a cell merged down two rows.
        val rows = listOf(
            cell("Head", """gridSpan="2"""") + cell(null, """hMerge="1"""") + cell("Last"),
            cell("Merged", """rowSpan="2"""", """<a:tcPr anchor="ctr"/>""") + cell("b1") + cell("c1"),
            cell(null, """vMerge="1"""") + cell("b2") + cell("c2"),
            cell("a3") + cell("b3") + cell("c3"),
        ).joinToString("") { """<a:tr h="${40 * EMU}">$it</a:tr>""" }
        val table = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="2" name="Table"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
            <p:xfrm><a:off x="${40 * EMU}" y="${40 * EMU}"/><a:ext cx="${300 * EMU}" cy="${160 * EMU}"/></p:xfrm><a:graphic>
            <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl><a:tblPr lastCol="1"><a:tableStyleId>$id</a:tableStyleId></a:tblPr>
            <a:tblGrid>${"""<a:gridCol w="${100 * EMU}"/>""".repeat(3)}</a:tblGrid>$rows</a:tbl></a:graphicData></a:graphic></p:graphicFrame>"""
        // The same table right to left, its first column on the right.
        val rtl = table.replace("""<a:tblPr lastCol="1">""", """<a:tblPr lastCol="1" rtl="1">""")
        val file = deck("table.pptx", listOf(slide(table), slide(rtl)), more = listOf("tableStyles" to "tableStyles.xml"), parts = mapOf("ppt/tableStyles.xml" to styles))
        val pdf = convert(file, OfficeKind.POWERPOINT, "slide-table")
        val scale = 2f
        val page = render(pdf, 0, scale)
        val green = 0xFF00B050.toInt()
        assertTrue("The table's top", isColor(page, scale, 190f, 40f, Color.RED))
        assertTrue("Between rows, the inside line", isColor(page, scale, 190f, 80f, green))
        assertTrue(isColor(page, scale, 190f, 120f, green))
        assertTrue("No line across the merged cell", isColor(page, scale, 130f, 120f, Color.WHITE))
        assertTrue("The table's bottom", isColor(page, scale, 190f, 200f, Color.RED))
        assertTrue("The last column, the merged header counted once", isColor(page, scale, 290f, 100f, Color.BLUE))
        // The merged cell's text in the middle of its two rows.
        assertTrue(word(pdf, 1, "Merged").yDirAdj > 115f)
        val mirrored = render(pdf, 1, scale)
        assertTrue("The last column on the left", isColor(mirrored, scale, 90f, 100f, Color.BLUE))
        assertTrue(word(pdf, 2, "Merged").xDirAdj > 240f)
    }

    @Test fun powerPointShapesCanTakeTheirGroupsFill() {
        // The square is filled as its red group is (a:grpFill), not in its style's accent colour.
        val style = """<p:style><a:lnRef idx="0"><a:schemeClr val="accent1"/></a:lnRef><a:fillRef idx="1"><a:schemeClr val="accent1"/></a:fillRef>
            <a:effectRef idx="0"><a:schemeClr val="accent1"/></a:effectRef><a:fontRef idx="minor"><a:schemeClr val="lt1"/></a:fontRef></p:style>"""
        val square = """<p:sp><p:nvSpPr><p:cNvPr id="3" name="Square"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
            <p:spPr>${xfrm(100, 100, 100, 100)}<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:grpFill/></p:spPr>$style</p:sp>"""
        val group = """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="2" name="Group"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
            <p:grpSpPr>${xfrm(100, 100, 100, 100)}${solid("FF0000")}</p:grpSpPr>$square</p:grpSp>"""
        val pdf = convert(deck("group-fill.pptx", listOf(slide(group))), OfficeKind.POWERPOINT, "slide-group-fill")
        assertTrue(isColor(render(pdf, 0, 2f), 2f, 150f, 150f, Color.RED))
    }

    @Test fun powerPointNumberingGoesOnAfterAnEmptyParagraph() {
        fun item(text: String) = """<a:p><a:pPr marL="342900" indent="-342900"><a:buAutoNum type="arabicPeriod"/></a:pPr>${if (text.isEmpty()) "" else run(text, 18)}</a:p>"""
        val file = deck("numbered.pptx", listOf(slide(textBox(2, 40, 40, 400, 300, item("Apples"), item(""), item("Pears")))))
        val text = pageTexts(convert(file, OfficeKind.POWERPOINT, "slide-numbered"))[0]
        assertTrue(text, Regex("""1\.\s*Apples""").containsMatchIn(text) && Regex("""2\.\s*Pears""").containsMatchIn(text))
    }

    @Test fun powerPointDamagedPartsLeaveOutOnlyWhatIsInThem() {
        // A chart whose part is empty, and a table whose styles' part is: the rest of the slide still converts.
        val chart = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="3" name="Chart"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
            <p:xfrm><a:off x="${40 * EMU}" y="${300 * EMU}"/><a:ext cx="${200 * EMU}" cy="${100 * EMU}"/></p:xfrm><a:graphic>
            <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" r:id="rId1"/>
            </a:graphicData></a:graphic></p:graphicFrame>"""
        val table = """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="4" name="Table"/><p:cNvGraphicFramePr/><p:nvPr/></p:nvGraphicFramePr>
            <p:xfrm><a:off x="${40 * EMU}" y="${120 * EMU}"/><a:ext cx="${200 * EMU}" cy="${40 * EMU}"/></p:xfrm><a:graphic>
            <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/table"><a:tbl><a:tblPr><a:tableStyleId>{5C22544A-0000-0000-0000-000000000002}</a:tableStyleId></a:tblPr>
            <a:tblGrid><a:gridCol w="${200 * EMU}"/></a:tblGrid><a:tr h="${40 * EMU}"><a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p>${run("Cell words", 18)}</a:p></a:txBody><a:tcPr/></a:tc></a:tr>
            </a:tbl></a:graphicData></a:graphic></p:graphicFrame>"""
        val file = deck(
            "damaged.pptx", listOf(slide(textBox(2, 40, 40, 300, 40, "<a:p>${run("Still here", 18)}</a:p>") + chart + table)),
            slideRels = mapOf(0 to relationships("chart" to "../charts/chart1.xml")), more = listOf("tableStyles" to "tableStyles.xml"),
            parts = mapOf("ppt/charts/chart1.xml" to "", "ppt/tableStyles.xml" to ""),
        )
        val text = pageTexts(convert(file, OfficeKind.POWERPOINT, "slide-damaged"))[0]
        assertTrue(text, "Still here" in text && "Cell words" in text)
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

    /**
     * Several files at once: each into a PDF of its own, named as asked (told apart), with progress
     * for each; one that can't be converted fails on its own, and cancelling leaves nothing behind.
     */
    @Test fun convertEachMakesAPdfOfEachFile() {
        val first = import(asset("report.docx"), OfficeKind.WORD)
        val damaged = import(asset("report.docx"), OfficeKind.WORD).also { it.file.writeText("not a document any more") }
        val second = import(asset("report.docx"), OfficeKind.WORD)
        val output = File(context.cacheDir, "output/tools")
        val started = ArrayList<Int>()
        val drawn = ArrayList<Pair<Int, Int>>()
        val jobs = listOf(ConvertJob(first, "Garden report"), ConvertJob(damaged, "Broken"), ConvertJob(second, "garden report"))
        val outcomes = converter.convertEach(jobs, PageSize.A5, {}, { started += it }) { if (!it.compressing) drawn += started.last() to it.done }
        assertEquals("Each file in turn", listOf(0, 1, 2), started)
        assertTrue("The damaged file failed", outcomes[1].isFailure)
        val (a, b) = listOf(0, 2).map { outcomes[it].getOrThrow() }
        assertEquals(listOf("Garden report.pdf", "garden report (2).pdf"), listOf(a.name, b.name))
        assertEquals("Only those made", setOf(a.name, b.name), output.list()!!.toSet())
        for (pdf in listOf(a, b)) {
            assertEquals(5, pdf.pageCount)
            assertTrue(pageTexts(pdf.file).joinToString(" ").contains("Riverside Community Garden"))
        }
        assertEquals("Each file's pages are heard as it is drawn", (1..5).toList(), drawn.filter { it.first == 2 }.map { it.second }.distinct())
        assertFalse("Working files removed", File(context.cacheDir, "office-work").exists())

        // Cancelled once the second file's third page has started: none is left, the first's neither.
        var cancelled = false
        try {
            converter.convertEach(
                listOf(ConvertJob(first, "one"), ConvertJob(second, "two")),
                PageSize.A5,
                { if (cancelled) throw CancellationException("Cancelled") },
                { started += it },
            ) { if (started.size == 5 && it.done >= 3) cancelled = true }
            fail("Not cancelled")
        } catch (_: CancellationException) {
        }
        assertTrue("Nothing left behind", output.listFiles().isNullOrEmpty())
        assertFalse("Working files removed", File(context.cacheDir, "office-work").exists())
        listOf(first, damaged, second).forEach(converter::delete)
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
