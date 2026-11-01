package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDJavascriptNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.Redaction
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPasswordRequired
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.IdentityHashMap
import java.util.zip.Inflater

/**
 * Redact PDF: what the boxes cover is gone from the whole file, not just hidden; the pages without
 * boxes stay as they were; sizes and turns are kept; metadata, bookmarks, tags, attachments,
 * scripts and form fields that could repeat the hidden text are removed; Find text boxes the right
 * words on upright, turned and cropped pages; and cancelling leaves nothing behind. The PDFs are
 * made up here.
 */
class RedactionDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)
    private val results = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }
    private var nextId = 3001L

    private fun import(file: File): PdfInput = engine.import(Uri.fromFile(file), nextId++)

    private fun save(file: File, name: String): File = file.copyTo(File(results, name), overwrite = true)

    private fun write(content: PDPageContentStream, text: String, x: Float, y: Float, size: Float = 14f) {
        content.beginText()
        content.setFont(PDType1Font.HELVETICA, size)
        content.newLineAtOffset(x, y)
        // The word SECRET is red and the rest black, so a picture of the page shows whether every
        // bit of it is covered.
        for (part in text.split(Regex("(?=SECRET)|(?<=SECRET)"))) {
            if (part.isEmpty()) continue
            if (part == "SECRET") content.setNonStrokingColor(1f, 0f, 0f) else content.setNonStrokingColor(0f, 0f, 0f)
            content.showText(part)
        }
        content.endText()
    }

    /** Whether [pixel] is the red of the word SECRET, smoothed edges included. */
    private fun red(pixel: Int) = Color.red(pixel) > 150 && Color.green(pixel) < 110 && Color.blue(pixel) < 110

    /** A plain image [width] × [height] pixels big, in [color]. */
    private fun image(doc: PDDocument, width: Int, height: Int, color: Int) =
        LosslessFactory.createFromImage(doc, createBitmap(width, height).apply { eraseColor(color) })

    /**
     * Four pages, the word SECRET (in red) on all but page 3, and the same word hidden in every
     * place a PDF can keep text besides its pages:
     * 1. A4, upright: text, a form drawing FORMSECRET and an image, both listed in resources it
     *    shares with page 3; a sticky note, a green square comment, a filled-in form field, private
     *    application data and a thumbnail
     * 2. A4, turned clockwise (/Rotate 90), so its text runs down the page as shown
     * 3. A4, upright, without the word: shares page 1's resources and draws its own image, and has a
     *    form field of its own; it is never redacted
     * 4. A4 with a crop box that leaves a 100-point border off on every side
     * Also: the document information, XMP metadata, a bookmark, named destinations, an attached file,
     * JavaScript (also run on opening) and tags with alternate text and ActualText.
     */
    private fun secrets(name: String): File = File(context.cacheDir, name).also { file ->
        PDDocument().use { doc ->
            val shared = PDResources()
            val pages = (0 until 4).map { index ->
                PDPage(PDRectangle.A4).also { page ->
                    when (index) {
                        0, 2 -> page.resources = shared
                        1 -> page.rotation = 90
                        3 -> page.cropBox = PDRectangle(100f, 100f, PDRectangle.A4.width - 200f, PDRectangle.A4.height - 200f)
                    }
                    doc.addPage(page)
                }
            }
            val (first, turned, plain, cropped) = pages
            val form = PDFormXObject(doc).apply {
                bBox = PDRectangle(0f, 0f, 300f, 40f)
                resources = PDResources().apply { put(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
                stream.createOutputStream().use { it.write("BT /F1 12 Tf 5 15 Td (FORM) Tj 1 0 0 rg (SECRET) Tj 0 g ( drawn in a form) Tj ET".toByteArray()) }
            }
            PDPageContentStream(doc, first).use { content ->
                write(content, "Public heading", 72f, 760f)
                write(content, "The agent SECRET met them.", 72f, 700f)
                write(content, "Lunch at Café José with Jane", 72f, 660f)
                write(content, "Doe and nobody else.", 72f, 640f)
                content.saveGraphicsState()
                content.transform(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(72f, 560f))
                content.drawForm(form)
                content.restoreGraphicsState()
                content.drawImage(image(doc, 37, 23, Color.rgb(200, 120, 40)), 72f, 450f, 74f, 46f)
            }
            PDPageContentStream(doc, turned).use { content -> write(content, "TOP SECRET on a turned page", 150f, 420f) }
            PDPageContentStream(doc, plain).use { content ->
                write(content, "Unredacted page text stays.", 72f, 700f)
                content.drawImage(image(doc, 41, 29, Color.rgb(40, 120, 200)), 72f, 500f, 82f, 58f)
            }
            PDPageContentStream(doc, cropped).use { content -> write(content, "A SECRET inside the crop box", 150f, 420f) }

            // Comments: a sticky note and a green square, on page 1.
            first.annotations.add(PDAnnotationText().apply {
                rectangle = PDRectangle(500f, 700f, 20f, 20f)
                contents = "SECRET note"
            })
            first.annotations.add(PDAnnotationSquareCircle(PDAnnotationSquareCircle.SUB_TYPE_SQUARE).apply {
                rectangle = PDRectangle(400f, 100f, 80f, 60f)
                color = PDColor(floatArrayOf(0f, 0.6f, 0f), PDDeviceRGB.INSTANCE)
                interiorColor = PDColor(floatArrayOf(0f, 0.6f, 0f), PDDeviceRGB.INSTANCE)
                constructAppearances()
            })
            // Form fields: one on page 1, one on page 3.
            val acroForm = PDAcroForm(doc)
            doc.documentCatalog.acroForm = acroForm
            acroForm.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            acroForm.defaultAppearance = "/Helv 12 Tf 0 0 1 rg"
            for ((page, fieldName, value) in listOf(Triple(first, "name", "SECRET field value"), Triple(plain, "keepme", "public value"))) {
                val field = PDTextField(acroForm)
                field.partialName = fieldName
                val widget = field.widgets[0]
                widget.rectangle = PDRectangle(72f, 300f, 250f, 24f)
                widget.page = page
                page.annotations.add(widget)
                acroForm.fields.add(field)
                field.value = value
            }
            // Private application data and a thumbnail.
            val private = COSDictionary().apply { setItem(COSName.getPDFName("Usugami"), COSDictionary().apply { setString(COSName.getPDFName("Private"), "SECRET private") }) }
            first.cosObject.setItem(COSName.PIECE_INFO, private)
            doc.documentCatalog.cosObject.setItem(COSName.PIECE_INFO, private)
            first.cosObject.setItem(COSName.getPDFName("Thumb"), image(doc, 30, 42, Color.GRAY).cosObject)

            doc.documentInformation.apply {
                title = "SECRET title"
                author = "SECRET author"
                keywords = "SECRET"
            }
            doc.documentCatalog.metadata = PDMetadata(doc, "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><dc>SECRET xmp</dc></x:xmpmeta>".byteInputStream())
            val outline = PDDocumentOutline()
            outline.addLast(PDOutlineItem().apply {
                title = "SECRET chapter"
                destination = PDPageFitDestination().apply { page = first }
            })
            doc.documentCatalog.documentOutline = outline

            val names = PDDocumentNameDictionary(doc.documentCatalog)
            names.dests = PDDestinationNameTreeNode().apply {
                setNames(mapOf<String, PDPageDestination>(
                    "SECRETDEST" to PDPageFitDestination().apply { page = first },
                    "keep" to PDPageFitDestination().apply { page = plain },
                ))
            }
            names.embeddedFiles = PDEmbeddedFilesNameTreeNode().apply {
                val attached = PDEmbeddedFile(doc, "SECRET attachment".byteInputStream())
                setNames(mapOf("notes.txt" to PDComplexFileSpecification().apply {
                    setFile("notes.txt")
                    embeddedFile = attached
                }))
            }
            names.setJavascript(PDJavascriptNameTreeNode().apply { setNames(mapOf("hello" to PDActionJavaScript("app.alert('SECRET script');"))) })
            doc.documentCatalog.names = names
            doc.documentCatalog.openAction = PDActionJavaScript("app.alert('SECRET on opening');")

            val tags = PDStructureTreeRoot()
            tags.appendKid(PDStructureElement("P", tags).apply {
                page = first
                actualText = "SECRET actual text"
                alternateDescription = "SECRET alternate text"
            })
            doc.documentCatalog.structureTreeRoot = tags
            doc.documentCatalog.markInfo = PDMarkInfo().apply { isMarked = true }
            doc.save(file)
        }
    }

    /** Each page's characters, with their place on the page as shown (y down). */
    private fun glyphsByPage(file: File): List<List<TextPosition>> = PDDocument.load(file).use { doc ->
        (1..doc.numberOfPages).map { number ->
            ArrayList<TextPosition>().also { glyphs ->
                object : PDFTextStripper() {
                    override fun processTextPosition(text: TextPosition) {
                        glyphs += text
                    }
                }.apply {
                    startPage = number
                    endPage = number
                }.getText(doc)
            }
        }
    }

    /** The glyphs of each place [word] is written on [page], found by PDFBox's own text extraction. */
    private fun occurrences(page: List<TextPosition>, word: String): List<List<TextPosition>> {
        val text = page.joinToString("") { it.unicode }
        val found = ArrayList<List<TextPosition>>()
        var at = text.indexOf(word)
        while (at >= 0) {
            found += page.subList(at, at + word.length)
            at = text.indexOf(word, at + word.length)
        }
        return found
    }

    /**
     * Whether [glyph]'s origin, where its baseline starts, is inside [box] on a page [width] ×
     * [height] points as shown: PDFBox's own reading of where the glyph is, whichever way it runs.
     */
    private fun inside(glyph: TextPosition, box: RedactBox, width: Float, height: Float): Boolean {
        val x = glyph.x / width
        val y = glyph.y / height
        return x in box.left..box.right && y in box.top..box.bottom
    }

    /** Page [index] of [file] as shown, [scale] pixels per point, on white. */
    private fun render(file: File, index: Int, scale: Float = 1f): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(index).use { page ->
                    createBitmap((page.width * scale).toInt(), (page.height * scale).toInt()).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    /** Every pixel of [box] in [bitmap] is black (but for its rounded edges). */
    private fun allBlack(bitmap: Bitmap, box: RedactBox): Boolean {
        val left = (box.left * bitmap.width).toInt() + 1
        val right = (box.right * bitmap.width).toInt() - 1
        val top = (box.top * bitmap.height).toInt() + 1
        val bottom = (box.bottom * bitmap.height).toInt() - 1
        for (y in top until bottom) for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            if (Color.red(pixel) > 40 || Color.green(pixel) > 40 || Color.blue(pixel) > 40) return false
        }
        return true
    }

    // ---- Find text ----

    @Test fun findTextBoxesEveryMatchAndNothingElse() {
        val file = secrets("find.pdf")
        val source = import(file)
        val matches = engine.findText(source, "secret")
        val boxes = matches.flatten()
        val glyphs = glyphsByPage(file)
        PDDocument.load(file).use { doc ->
            for (pageIndex in 0 until 4) {
                val page = doc.getPage(pageIndex)
                val turned = page.rotation % 180 != 0
                val width = if (turned) page.cropBox.height else page.cropBox.width
                val height = if (turned) page.cropBox.width else page.cropBox.height
                val onPage = boxes.filter { it.page == pageIndex }
                val secrets = occurrences(glyphs[pageIndex], "SECRET")
                if (pageIndex == 2) {
                    assertTrue("Page 3 has nothing to find", onPage.isEmpty())
                    continue
                }
                assertTrue("Page ${pageIndex + 1} has the word", secrets.isNotEmpty())
                // Every letter of every match is boxed…
                for (glyph in secrets.flatten()) {
                    assertTrue(
                        "'${glyph.unicode}' at ${glyph.x}, ${glyph.y} on page ${pageIndex + 1} ($width × $height) is boxed: $onPage",
                        onPage.any { inside(glyph, it, width, height) },
                    )
                }
                // …and the words around them aren't.
                val neighbours = glyphs[pageIndex].filter { glyph -> secrets.none { glyph in it } && glyph.unicode.isNotBlank() }
                val nearby = neighbours.filter { glyph -> onPage.any { inside(glyph, it, width, height) } }
                assertTrue("Only the matches on page ${pageIndex + 1} are boxed, not ${nearby.joinToString("") { it.unicode }}", nearby.isEmpty())
            }
        }
        // Page 1: SECRET, FORMSECRET (drawn inside a form) and the filled-in form field, which the
        // preview shows too; page 2 and page 4 once each. One line each.
        assertEquals(listOf(0, 0, 0, 1, 3), matches.map { it.single().page })

        // Redacted with those boxes, not a speck of the red word is left on any page.
        val redacted = save(engine.redact(source, boxes, "find_redacted").file, "find_redacted.pdf")
        for (index in listOf(0, 1, 3)) {
            assertTrue("Page ${index + 1} shows the word to begin with", pixels(render(file, index, 2f)).any(::red))
            val left = pixels(render(redacted, index, 2f)).count(::red)
            assertEquals("Red pixels left on page ${index + 1}", 0, left)
        }
    }

    @Test fun findTextMatchesAcrossLinesIgnoringCaseAndAccents() {
        val source = import(secrets("phrases.pdf"))
        // "Jane" ends a line and "Doe" starts the next: one box for each line.
        val jane = engine.findText(source, "jane   DOE").single()
        assertEquals(2, jane.size)
        assertTrue(jane.all { it.page == 0 })
        assertTrue("The second box is lower", jane[1].top > jane[0].bottom - 0.005f)
        assertEquals(1, engine.findText(source, "cafe jose").size)
        assertTrue(engine.findText(source, "nowhere to be found").isEmpty())
        assertTrue(engine.findText(source, "   ").isEmpty())
    }

    // ---- Redacting ----

    /** Every string and every decoded stream reachable in [doc], as text, for searching. */
    private fun everything(doc: PDDocument): List<String> {
        val texts = ArrayList<String>()
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val pending = ArrayDeque<COSBase>()
        pending += doc.document.trailer
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            val item = (if (next is COSObject) next.`object` else next) ?: continue
            if (!seen.add(item)) continue
            when (item) {
                is COSString -> texts += item.string
                is COSName -> texts += item.name
                is COSArray -> item.forEach { pending += it }
                is COSDictionary -> {
                    for ((key, value) in item.entrySet()) {
                        texts += key.name
                        pending += value
                    }
                    if (item is COSStream) {
                        val bytes = try {
                            item.createInputStream().use { it.readBytes() }
                        } catch (_: Exception) {
                            item.createRawInputStream().use { it.readBytes() }
                        }
                        texts += String(bytes, Charsets.ISO_8859_1)
                        texts += String(bytes, Charsets.UTF_16BE)
                    }
                }
                else -> Unit
            }
        }
        return texts
    }

    /** The file's own bytes, and every stream in it inflated, as text: a check that doesn't go through PDFBox. */
    private fun rawTexts(file: File): List<String> {
        val bytes = file.readBytes()
        val raw = String(bytes, Charsets.ISO_8859_1)
        val texts = arrayListOf(raw)
        var at = raw.indexOf("stream")
        while (at >= 0) {
            var start = at + "stream".length
            if (raw.startsWith("\r\n", start)) start += 2 else if (raw.startsWith("\n", start)) start += 1
            val end = raw.indexOf("endstream", start)
            if (end < 0) break
            val inflater = Inflater()
            try {
                inflater.setInput(bytes, start, end - start)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, count)
                }
                texts += String(out.toByteArray(), Charsets.ISO_8859_1)
            } catch (_: Exception) {
                // Not deflated: its raw bytes are already searched.
            } finally {
                inflater.end()
            }
            at = raw.indexOf("stream", end + "endstream".length)
        }
        // Hex strings, as PDF writers use for text that isn't plain ASCII.
        Regex("<([0-9A-Fa-f\\s]{8,})>").findAll(raw).forEach { match ->
            val hex = match.groupValues[1].filterNot(Char::isWhitespace)
            val decoded = ByteArray(hex.length / 2) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
            texts += String(decoded, Charsets.ISO_8859_1)
            texts += String(decoded, Charsets.UTF_16BE)
        }
        return texts
    }

    /** A redaction of page 1 by Find text and a box over its image, of page 2 and page 4 by Find text; page 3 untouched. */
    private fun redactSecrets(name: String): Triple<File, File, List<RedactBox>> {
        val file = secrets("$name-source.pdf")
        val source = import(file)
        // Find text, then a box drawn over the image on page 1 (72, 450 to 146, 496 in points, y up).
        val found = engine.findText(source, "SECRET").flatten()
        val overImage = Redaction.between(0, 60f / 595f, 1 - 506f / 842f, 160f / 595f, 1 - 440f / 842f)
        val boxes = found + overImage
        val result = engine.redact(source, boxes, name)
        assertEquals("$name.pdf", result.file.name)
        return Triple(file, save(result.file, "$name.pdf"), boxes)
    }

    @Test fun redactedTextIsGoneFromTheWholeFile() {
        val (source, redacted, _) = redactSecrets("secrets_redacted")
        // The source has the word everywhere: the checks below would find it.
        PDDocument.load(source).use { doc -> assertTrue(everything(doc).any { it.contains("SECRET", ignoreCase = true) }) }
        assertTrue(rawTexts(source).any { it.contains("SECRET", ignoreCase = true) })

        PDDocument.load(redacted).use { doc ->
            assertEquals(4, doc.numberOfPages)
            for (number in 1..4) {
                val text = PDFTextStripper().apply {
                    startPage = number
                    endPage = number
                }.getText(doc)
                assertFalse("Page $number reads \"$text\"", text.contains("secret", ignoreCase = true))
                // Redacted pages are pictures: they have no text at all.
                if (number != 3) assertTrue("Page $number has text: $text", text.isBlank())
            }
            val leaks = everything(doc).filter { it.contains("SECRET", ignoreCase = true) }
            assertTrue("Still in the file: $leaks", leaks.isEmpty())
        }
        val rawLeaks = rawTexts(redacted).filter { it.contains("SECRET", ignoreCase = true) }
        assertTrue("Still in the file's bytes: ${rawLeaks.map { it.take(200) }}", rawLeaks.isEmpty())
    }

    @Test fun pagesKeepTheirSizeAndTurnAndTheBoxesAreBlackWherePlaced() {
        val (source, redacted, boxes) = redactSecrets("secrets_layout")
        PDDocument.load(source).use { before ->
            PDDocument.load(redacted).use { after ->
                for (index in 0 until 4) {
                    val a = before.getPage(index)
                    val b = after.getPage(index)
                    assertEquals("Turn of page ${index + 1}", a.rotation, b.rotation)
                    assertArrayEquals("Media box of page ${index + 1}", a.mediaBox.cosArray.toFloatArray(), b.mediaBox.cosArray.toFloatArray(), 0.01f)
                    assertArrayEquals("Crop box of page ${index + 1}", a.cropBox.cosArray.toFloatArray(), b.cropBox.cosArray.toFloatArray(), 0.01f)
                }
                // A redacted page is one picture, as big as the page shown, at 200 dpi, and nothing else.
                for (index in listOf(0, 1, 3)) {
                    val page = after.getPage(index)
                    val images = imagesOn(page)
                    assertEquals(1, images.size)
                    val shown = page.cropBox.let { if (page.rotation % 180 != 0) it.height to it.width else it.width to it.height }
                    assertEquals(Redaction.pixelSize(shown.first, shown.second).first, images[0].image.width)
                    assertNull("No annotations are left on page ${index + 1}", page.cosObject.getDictionaryObject(COSName.ANNOTS))
                    assertNull(page.cosObject.getDictionaryObject(COSName.getPDFName("Thumb")))
                    assertNull(page.cosObject.getDictionaryObject(COSName.PIECE_INFO))
                }
            }
        }
        // Each box is solid black where the preview showed it, on upright, turned and cropped pages.
        for (index in listOf(0, 1, 3)) {
            val bitmap = render(redacted, index, 1.5f)
            for (box in boxes.filter { it.page == index }) assertTrue("Box $box on page ${index + 1} is black", allBlack(bitmap, box))
        }
        // What isn't boxed still shows, comments included: the green square on page 1 became part
        // of the picture, and the heading is still there.
        val page1 = render(redacted, 0, 1f)
        val green = page1.getPixel(440, 842 - 130)
        assertTrue("The green square is drawn: ${Integer.toHexString(green)}", Color.green(green) > 120 && Color.red(green) < 60)
        val heading = (72 until 180).flatMap { x -> (842 - 775 until 842 - 755).map { y -> page1.getPixel(x, y) } }
        assertTrue("The heading still shows", heading.any { Color.red(it) < 100 })
    }

    @Test fun pagesWithoutBoxesStayAsTheyWere() {
        val (source, redacted, _) = redactSecrets("secrets_untouched")
        PDDocument.load(source).use { before ->
            PDDocument.load(redacted).use { after ->
                val a = before.getPage(2)
                val b = after.getPage(2)
                assertArrayEquals(a.contents.use { it.readBytes() }, b.contents.use { it.readBytes() })
                assertEquals(
                    PDFTextStripper().apply { startPage = 3; endPage = 3 }.getText(before),
                    PDFTextStripper().apply { startPage = 3; endPage = 3 }.getText(after),
                )
                // Its own image is untouched; the image and form only page 1 drew are emptied, though
                // page 3 still lists them in the resources it shares with page 1.
                val drawn = imagesOn(b).single().image
                assertEquals(41, drawn.width)
                val resources = b.resources
                val sizes = resources.xObjectNames.mapNotNull { (resources.getXObject(it) as? com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject)?.width }
                assertFalse("Page 1's image is gone", 37 in sizes)
                // Its form field is still there, with its value.
                assertEquals("public value", after.documentCatalog.acroForm.getField("keepme").valueAsString)
                assertNull("Page 1's field is gone", after.documentCatalog.acroForm.getField("name"))
            }
        }
        // And it looks the same.
        val a = pixels(render(source, 2))
        val b = pixels(render(redacted, 2))
        assertArrayEquals(a, b)
    }

    @Test fun metadataBookmarksTagsAttachmentsAndScriptsAreRemoved() {
        val (_, redacted, _) = redactSecrets("secrets_metadata")
        PDDocument.load(redacted).use { doc ->
            val trailer = doc.document.trailer
            assertNull("Document information", trailer.getDictionaryObject(COSName.INFO))
            val catalog = doc.documentCatalog.cosObject
            for (key in listOf(COSName.METADATA, COSName.OUTLINES, COSName.STRUCT_TREE_ROOT, COSName.MARK_INFO, COSName.OPEN_ACTION, COSName.PIECE_INFO)) {
                assertNull("$key", catalog.getDictionaryObject(key))
            }
            val names = catalog.getDictionaryObject(COSName.NAMES) as COSDictionary
            assertEquals(setOf(COSName.DESTS), names.keySet())
            // The named destination to the redacted page goes; the one to page 3 stays.
            val dests = doc.documentCatalog.names.dests
            assertNull(dests.getValue("SECRETDEST"))
            assertNotNull(dests.getValue("keep"))
        }
    }

    @Test fun protectedPdfsAreRedactedWithoutTheirPassword() {
        val asset = File(context.cacheDir, "protected-redact.pdf").also { target ->
            InstrumentationRegistry.getInstrumentation().context.assets.open("protected.pdf").use { input -> target.outputStream().use(input::copyTo) }
        }
        val pending = try {
            import(asset)
            fail("Password request expected")
            return
        } catch (e: PdfPasswordRequired) {
            e.input
        }
        val unlocked = engine.unlock(pending, "test123")
        // The preview can draw it: the view has no password.
        val view = engine.redactionView(unlocked)
        assertEquals("", view.password)
        assertEquals(unlocked.pageCount, ParcelFileDescriptor.open(view.file, ParcelFileDescriptor.MODE_READ_ONLY).use { PdfRenderer(it).use { renderer -> renderer.pageCount } })
        val box = RedactBox(0, 0f, 0f, 1f, 0.5f)
        val redacted = save(engine.redact(unlocked, listOf(box), "protected_redacted").file, "protected_redacted.pdf")
        PDDocument.load(redacted).use { doc -> assertFalse(doc.isEncrypted) }
        assertTrue(allBlack(render(redacted, 0), box))
    }

    @Test fun textPagesStayLosslessAndPhotosBecomeJpeg() {
        val file = File(context.cacheDir, "photo.pdf")
        PDDocument().use { doc ->
            val text = PDPage(PDRectangle.A4).also(doc::addPage)
            PDPageContentStream(doc, text).use { content -> for (line in 0 until 40) write(content, "Line $line of plain text, the kind most pages have.", 72f, 780f - 18f * line, 11f) }
            val photo = PDPage(PDRectangle.A4).also(doc::addPage)
            // Like a photo: smooth colours with fine grain.
            val noise = createBitmap(600, 850).apply {
                val random = java.util.Random(7)
                val values = IntArray(width * height) { i ->
                    val x = i % width
                    val y = i / width
                    fun grain(base: Int) = (base + random.nextInt(21) - 10).coerceIn(0, 255)
                    Color.rgb(grain(x * 255 / width), grain(y * 255 / height), grain(128))
                }
                setPixels(values, 0, width, 0, 0, width, height)
            }
            PDPageContentStream(doc, photo).use { content -> content.drawImage(LosslessFactory.createFromImage(doc, noise), 0f, 0f, 595f, 842f) }
            doc.save(file)
        }
        val source = import(file)
        val redacted = save(engine.redact(source, listOf(RedactBox(0, 0.1f, 0.1f, 0.3f, 0.12f), RedactBox(1, 0.1f, 0.1f, 0.3f, 0.2f)), "photo_redacted").file, "photo_redacted.pdf")
        PDDocument.load(redacted).use { doc ->
            val text = imagesOn(doc.getPage(0)).single().image
            assertEquals(COSName.FLATE_DECODE, text.cosObject.getDictionaryObject(COSName.FILTER))
            assertEquals("A black-and-white page is stored in grey", 1, text.colorSpace.numberOfComponents)
            val photo = imagesOn(doc.getPage(1)).single().image
            assertEquals(COSName.DCT_DECODE, photo.cosObject.getDictionaryObject(COSName.FILTER))
            // A page of text stays small.
            assertTrue("The text page takes ${text.cosObject.length} bytes", text.cosObject.length < 400_000)
        }
    }

    @Test fun cancellingLeavesNoOutput() {
        class Stop : RuntimeException()
        val source = import(secrets("cancel-redact.pdf"))
        // The preview's copy is made first, as on the screen; cancelling stops the redaction itself:
        // while it looks at the pages, while it draws one, and while it stores the picture.
        engine.redactionView(source)
        val boxes = listOf(RedactBox(0, 0.1f, 0.1f, 0.5f, 0.2f), RedactBox(1, 0.1f, 0.1f, 0.5f, 0.2f))
        for (stopAt in listOf(2, 5, 60)) {
            var calls = 0
            try {
                engine.redact(source, boxes, "cancelled") { if (++calls == stopAt) throw Stop() }
                fail("The redaction should have been stopped")
            } catch (_: Stop) {
            }
            assertTrue(File(context.cacheDir, "output/tools").listFiles().isNullOrEmpty())
        }
        // It still works afterwards.
        assertEquals(4, engine.redact(source, boxes, "after-cancel").pageCount)
    }

    /** The boxes, the page showing and the chosen box come back after Android has stopped Usugami. */
    @Test fun boxesSurviveProcessDeath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = context.applicationContext as Application
        val input = import(secrets("restored.pdf"))
        val boxes = listOf(RedactBox(0, 0.1f, 0.2f, 0.3f, 0.25f), RedactBox(3, 0.5f, 0.5f, 0.9f, 0.6f))
        // PdfToolsViewModel's saved-state keys, as Android hands them back.
        val handle = SavedStateHandle(
            mapOf("pdf_inputs" to arrayListOf(input), "redact_boxes" to Redaction.pack(boxes), "redact_page" to 3, "redact_selected" to 1),
        )
        var vm: PdfToolsViewModel? = null
        instrumentation.runOnMainSync { vm = PdfToolsViewModel(app, handle) }
        val restored = vm!!
        try {
            assertEquals(boxes, restored.redactBoxes.value)
            assertEquals(3, restored.redactPage.value)
            assertEquals(1, restored.redactSelected.value)
            // The preview's copy is made again, and redacting works from what came back.
            instrumentation.runOnMainSync { restored.prepareRedaction(input) }
            val deadline = System.currentTimeMillis() + 10_000
            while (restored.redactView.value == null) {
                assertTrue("The preview's copy is ready in time", System.currentTimeMillis() < deadline)
                Thread.sleep(50)
            }
            assertEquals(input.pageCount, restored.redactView.value!!.pageCount)
        } finally {
            instrumentation.runOnMainSync { restored.reset() }
        }
    }

    @Test fun viewCopiesGoWithTheirPdf() {
        val source = import(secrets("view.pdf"))
        val view = engine.redactionView(source)
        // The comments make a copy with them drawn in; deleting the PDF deletes it too.
        assertTrue(view.path != source.path && view.file.isFile)
        assertEquals(source.pageCount, view.pageCount)
        PDDocument.load(view.file).use { doc -> assertTrue(doc.pages.none { it.cosObject.containsKey(COSName.ANNOTS) }) }
        engine.delete(source)
        assertFalse(view.file.exists())
        // A PDF without comments or a password is shown as it is.
        val plain = File(context.cacheDir, "plain-view.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            PDPageContentStream(doc, page).use { content -> write(content, "Nothing to hide", 72f, 700f) }
            doc.save(plain)
        }
        val input = import(plain)
        assertEquals(input.path, engine.redactionView(input).path)
    }
}
