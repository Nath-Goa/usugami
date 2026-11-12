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
import com.tom_roush.pdfbox.cos.COSInteger
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
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationRubberStamp
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationPopup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationSquareCircle
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.pdmodel.PDDestinationNameTreeNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.Redaction
import io.github.nath_goa.usugami.data.PageRendering
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPasswordRequired
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
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
     *    form field of its own; it is never redacted. The word is only in what Usugami removes from
     *    every page: an attached file (with its pop-up and a reply to it) and the details of a
     *    digital signature
     * 4. A4 with a crop box that leaves a 100-point border off on every side
     * Also: the document information, XMP metadata, a bookmark, named destinations (in a tree whose
     * leaves list their first and last names), an attached file, JavaScript (also run on opening),
     * tags with alternate text and ActualText, and the certificates of signatures (/DSS).
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
            // On page 3: a file attached as a comment, with its pop-up and a reply…
            val attachment = PDAnnotationFileAttachment().apply {
                rectangle = PDRectangle(500f, 600f, 20f, 20f)
                contents = "payroll.txt"
                this.file = PDComplexFileSpecification().apply {
                    setFile("payroll.txt")
                    embeddedFile = PDEmbeddedFile(doc, "SECRET payroll".byteInputStream())
                }
            }
            val popup = PDAnnotationPopup().apply {
                rectangle = PDRectangle(400f, 500f, 150f, 80f)
                cosObject.setItem(COSName.PARENT, attachment)
            }
            attachment.popup = popup
            val reply = PDAnnotationText().apply {
                rectangle = PDRectangle(530f, 600f, 20f, 20f)
                contents = "Is this the latest?"
                inReplyTo = attachment
            }
            plain.annotations.addAll(listOf(attachment, popup, reply))
            // …and an invisible digital signature, with its signer's certificates kept for the catalog.
            val signature = PDSignatureField(acroForm)
            signature.widgets[0].apply {
                rectangle = PDRectangle(0f, 0f, 0f, 0f)
                page = plain
                plain.annotations.add(this)
            }
            signature.value = PDSignature().apply {
                this.name = "SECRET signer"
                reason = "SECRET reason"
                location = "SECRET place"
                contents = "SECRET cert".toByteArray()
            }
            acroForm.fields.add(signature)
            acroForm.setSignaturesExist(true)
            doc.documentCatalog.cosObject.setItem(COSName.getPDFName("DSS"), COSDictionary().apply {
                setItem(COSName.getPDFName("Certs"), COSArray().apply { add(PDStream(doc, "SECRET cert".byteInputStream()).cosObject) })
            })
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
                setKids(listOf(
                    PDDestinationNameTreeNode().apply {
                        setNames(mapOf<String, PDPageDestination>(
                            "SECRETDEST" to PDPageFitDestination().apply { page = first },
                            "keep" to PDPageFitDestination().apply { page = plain },
                        ))
                    },
                    PDDestinationNameTreeNode().apply { setNames(mapOf<String, PDPageDestination>("zz" to PDPageFitDestination().apply { page = plain })) },
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

    /**
     * Page [index] of [file] as shown, [shown] points big (from the PDF itself), on white, drawn
     * exactly that big into [pixels] (see [PageRendering]): Android's own sizing, in whole points,
     * stretches it from Android 17 by up to a point.
     */
    private fun renderExactly(file: File, index: Int, shown: Pair<Float, Float>, pixels: Pair<Int, Int>): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(index).use { page ->
                    createBitmap(pixels.first, pixels.second).also {
                        it.eraseColor(Color.WHITE)
                        PageRendering.render(page, it, shown.first, shown.second)
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
        // Each redacted page's size as shown, in points, by page.
        val shownSizes = HashMap<Int, Pair<Float, Float>>()
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
                    shownSizes[index] = shown
                    assertEquals(Redaction.pixelSize(shown.first, shown.second).first, images[0].image.width)
                    assertNull("No annotations are left on page ${index + 1}", page.cosObject.getDictionaryObject(COSName.ANNOTS))
                    assertNull(page.cosObject.getDictionaryObject(COSName.getPDFName("Thumb")))
                    assertNull(page.cosObject.getDictionaryObject(COSName.PIECE_INFO))
                }
            }
        }
        // Each box is solid black where the preview showed it, on upright, turned and cropped pages:
        // each page drawn exactly, as big as its picture, a pixel of it to a pixel.
        for (index in listOf(0, 1, 3)) {
            val shown = shownSizes.getValue(index)
            val bitmap = renderExactly(redacted, index, shown, Redaction.pixelSize(shown.first, shown.second))
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
                val sizes = resources.xObjectNames.mapNotNull { (resources.getXObject(it) as? PDImageXObject)?.width }
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

    /**
     * Pages cut from one scanned spread, as some tools split them: each draws the whole scan (red
     * on the left, blue on the right) and its crop shows one half. Pages 1 and 2 share one content
     * stream, which also writes SECRET on the left; pages 3 and 4 each have their own. Boxing all
     * of pages 1 and 3 makes 2 and 4 pictures too, so neither the red half nor the word stays in
     * the file. Page 5 draws a logo page 1 also drew, where it shows: it stays as it was.
     */
    @Test fun pagesKeepingWhatABoxHidesOutOfSightBecomePicturesToo() {
        val file = File(context.cacheDir, "spread.pdf")
        PDDocument().use { doc ->
            val scan = LosslessFactory.createFromImage(doc, createBitmap(2, 1).apply {
                setPixel(0, 0, Color.RED)
                setPixel(1, 0, Color.BLUE)
            })
            val resources = PDResources().apply {
                put(COSName.getPDFName("F1"), PDType1Font.HELVETICA)
                put(COSName.getPDFName("Scan"), scan)
                put(COSName.getPDFName("Logo"), image(doc, 4, 4, Color.GREEN))
            }
            fun page(left: Float, content: PDStream) = PDPage(PDRectangle(left, 0f, 200f, 200f)).also {
                it.resources = resources
                it.setContents(content)
                doc.addPage(it)
            }
            fun stream(text: String) = PDStream(doc, text.byteInputStream())
            val scanned = "q 400 0 0 200 0 0 cm /Scan Do Q"
            val logo = "q 20 0 0 20 150 150 cm /Logo Do Q"
            val shared = stream("$scanned $logo BT /F1 14 Tf 20 100 Td (SECRET) Tj ET")
            page(0f, shared)
            page(200f, shared)
            page(0f, stream(scanned))
            page(200f, stream(scanned))
            page(0f, stream(logo))
            doc.save(file)
        }
        val source = import(file)
        val boxes = listOf(RedactBox(0, 0f, 0f, 1f, 1f), RedactBox(2, 0f, 0f, 1f, 1f))
        val redacted = save(engine.redact(source, boxes, "spread_redacted").file, "spread_redacted.pdf")
        PDDocument.load(redacted).use { doc ->
            assertTrue("The word is gone", everything(doc).none { it.contains("SECRET") })
            // No image left in the file, the pages' pictures included, has any of the red half.
            assertFalse("An image keeps the red half", anImageIsRed(doc))
            // Pages 2 and 4 are pictures of their blue half; page 5 keeps its own content.
            for (index in listOf(1, 3)) assertEquals(1, imagesOn(doc.getPage(index)).size)
            assertEquals("q 20 0 0 20 150 150 cm /Logo Do Q", doc.getPage(4).contents.use { String(it.readBytes()) })
        }
        for (index in listOf(1, 3)) {
            val center = render(redacted, index).getPixel(100, 100)
            assertTrue("Page ${index + 1} still shows blue: ${Integer.toHexString(center)}", Color.blue(center) > 200 && Color.red(center) < 60)
        }
    }

    /** Whether any image in [doc], wherever it is kept, has a pixel of red. */
    private fun anImageIsRed(doc: PDDocument): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val pending = ArrayDeque<COSBase>().apply { add(doc.document.trailer) }
        while (pending.isNotEmpty()) {
            val item = pending.removeLast().let { if (it is COSObject) it.`object` else it } ?: continue
            if (!seen.add(item)) continue
            if (item is COSStream && item.getCOSName(COSName.SUBTYPE) == COSName.IMAGE) {
                if (pixels(PDImageXObject(PDStream(item), null).image).any(::red)) return true
            }
            if (item is COSDictionary) item.entrySet().forEach { pending += it.value } else if (item is COSArray) item.forEach { pending += it }
        }
        return false
    }

    /**
     * A photo all under a box on page 1 that a hidden stamp on page 2 also draws, in its appearance:
     * it would stay in the file for the stamp, out of sight, so page 2 becomes a picture too and
     * the photo goes.
     */
    @Test fun anImageUnderABoxDoesNotStayForAHiddenStamp() {
        val file = File(context.cacheDir, "stamped.pdf")
        PDDocument().use { doc ->
            val photo = image(doc, 4, 4, Color.RED)
            fun withPhoto() = PDResources().apply { put(COSName.getPDFName("Photo"), photo) }
            doc.addPage(PDPage(PDRectangle(200f, 200f)).apply {
                resources = withPhoto()
                setContents(PDStream(doc, "q 200 0 0 200 0 0 cm /Photo Do Q".byteInputStream()))
            })
            val look = PDAppearanceStream(doc).apply {
                bBox = PDRectangle(50f, 50f)
                resources = withPhoto()
                cosObject.createOutputStream().use { it.write("q 50 0 0 50 0 0 cm /Photo Do Q".toByteArray()) }
            }
            doc.addPage(PDPage(PDRectangle(200f, 200f)).apply {
                resources = PDResources()
                annotations = listOf(PDAnnotationRubberStamp().apply {
                    rectangle = PDRectangle(20f, 20f, 50f, 50f)
                    isHidden = true
                    appearance = PDAppearanceDictionary().apply { setNormalAppearance(look) }
                })
            })
            doc.save(file)
        }
        val redacted = save(engine.redact(import(file), listOf(RedactBox(0, 0f, 0f, 1f, 1f)), "stamped_redacted").file, "stamped_redacted.pdf")
        PDDocument.load(redacted).use { doc -> assertFalse("The photo stays in the file", anImageIsRed(doc)) }
    }

    /**
     * Two pages share the resources of their page tree. Only the redacted page paints with a pattern
     * and sets a soft mask, both of which write the word SECRET: they go, though the page that stays
     * lists them too.
     */
    @Test fun sharedPatternsAndSoftMasksOfARedactedPageGo() {
        val file = File(context.cacheDir, "shared_patterns.pdf")
        PDDocument().use { doc ->
            val fonts = COSDictionary().apply { setItem(COSName.getPDFName("F1"), PDType1Font.HELVETICA) }
            fun stream(content: String, vararg entries: Pair<COSName, COSBase>) = doc.document.createCOSStream().apply {
                for ((key, value) in entries) setItem(key, value)
                setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.FONT, fonts) })
                createOutputStream().use { it.write(content.toByteArray()) }
            }
            val pattern = stream(
                "BT /F1 12 Tf 2 10 Td (SECRET) Tj ET",
                COSName.TYPE to COSName.PATTERN, COSName.PATTERN_TYPE to COSInteger.ONE, COSName.PAINT_TYPE to COSInteger.ONE,
                COSName.TILING_TYPE to COSInteger.ONE, COSName.BBOX to PDRectangle(120f, 30f).cosArray,
                COSName.X_STEP to COSInteger.get(120), COSName.Y_STEP to COSInteger.get(30),
            )
            val mask = stream(
                "BT /F1 30 Tf 60 300 Td (SECRET) Tj ET",
                COSName.TYPE to COSName.XOBJECT, COSName.SUBTYPE to COSName.FORM, COSName.BBOX to PDRectangle.A4.cosArray,
                COSName.GROUP to COSDictionary().apply { setItem(COSName.S, COSName.TRANSPARENCY) },
            )
            val state = COSDictionary().apply {
                setItem(COSName.SMASK, COSDictionary().apply {
                    setItem(COSName.S, COSName.LUMINOSITY)
                    setItem(COSName.G, mask)
                })
            }
            doc.pages.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply {
                setItem(COSName.FONT, fonts)
                setItem(COSName.PATTERN, COSDictionary().apply { setItem(COSName.getPDFName("P1"), pattern) })
                setItem(COSName.EXT_G_STATE, COSDictionary().apply { setItem(COSName.getPDFName("G1"), state) })
            })
            for (content in listOf("q /Pattern cs /P1 scn 50 600 360 120 re f Q q /G1 gs 0 g 50 200 400 200 re f Q", "BT /F1 14 Tf 50 700 Td (Public) Tj ET")) {
                doc.addPage(PDPage(PDRectangle.A4).apply { setContents(PDStream(doc, content.byteInputStream())) })
            }
            doc.save(file)
        }
        PDDocument.load(file).use { doc -> assertEquals(2, everything(doc).count { it.contains("SECRET") }) }
        val redacted = save(engine.redact(import(file), listOf(RedactBox(0, 0.05f, 0.05f, 0.95f, 0.95f)), "shared_patterns_redacted").file, "shared_patterns_redacted.pdf")
        PDDocument.load(redacted).use { doc ->
            assertTrue("Still in the file", everything(doc).none { it.contains("SECRET") })
            assertTrue(PDFTextStripper().getText(doc).contains("Public"))
        }
    }

    /** A name found only in a comment's text gets a box on that comment's page, so the comment goes. */
    @Test fun findTextFindsWhatCommentsSay() {
        val file = File(context.cacheDir, "commented.pdf")
        PDDocument().use { doc ->
            val first = PDPage(PDRectangle.A4).also(doc::addPage)
            PDPageContentStream(doc, first).use { content -> write(content, "Meeting with John Smith", 72f, 700f) }
            val second = PDPage(PDRectangle.A4).also(doc::addPage)
            second.annotations.add(PDAnnotationText().apply {
                rectangle = PDRectangle(500f, 700f, 20f, 20f)
                contents = "Call John\nSmith about it"
            })
            // A highlight by John Smith: its page goes too, but what it marks isn't blacked out.
            val third = PDPage(PDRectangle.A4).also(doc::addPage)
            third.annotations.add(PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                rectangle = PDRectangle(72f, 400f, 400f, 200f)
                titlePopup = "John Smith"
            })
            doc.save(file)
        }
        val source = import(file)
        val found = engine.findText(source, "john smith")
        assertEquals(listOf(0, 1, 2), found.map { it.single().page })
        val mark = found.last().single()
        assertEquals(Redaction.MIN_SIZE, mark.width, 1e-6f)
        assertEquals(Redaction.MIN_SIZE, mark.height, 1e-6f)
        val redacted = save(engine.redact(source, found.flatten(), "commented_redacted").file, "commented_redacted.pdf")
        PDDocument.load(redacted).use { doc -> assertTrue(everything(doc).none { it.contains("Smith") }) }
    }

    /**
     * Hebrew, drawn from left to right as PDF makers draw it (a word's last letter first, and the
     * second word left of the first), is found as it is typed, a phrase of two words too, with
     * one box over both words.
     */
    @Test fun findTextFindsRightToLeftText() {
        val font = File("/system/fonts").listFiles().orEmpty().filter { "Hebrew" in it.name && it.name.endsWith(".ttf") }.minByOrNull { it.name }
        assumeTrue("A Hebrew font on the phone", font != null)
        val file = File(context.cacheDir, "hebrew.pdf")
        var end = 0f
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            val hebrew = PDType0Font.load(doc, font!!)
            PDPageContentStream(doc, page).use { content ->
                var x = 72f
                for (word in listOf("עולם", "שלום")) {
                    content.beginText()
                    content.setFont(hebrew, 20f)
                    content.newLineAtOffset(x, 700f)
                    content.showText(word.reversed())
                    content.endText()
                    end = x + hebrew.getStringWidth(word.reversed()) / 1000f * 20f
                    x = end + 12f
                }
            }
            doc.save(file)
        }
        val box = engine.findText(import(file), "שלום עולם").single().single()
        assertEquals(0, box.page)
        assertTrue("From the left word: $box", box.left * 595f < 73f)
        assertTrue("To the right one: $box", box.right * 595f > end - 1f)
    }

    /**
     * What the file holds but no page shows: where a link leads (a name in it too, though only as
     * escapes), the value of a hidden form field, the choices of a hidden list, and a field on no
     * page at all. Find text finds them, without blacking out what is under them, and they are gone
     * from the redacted file.
     */
    @Test fun findTextFindsLinkAddressesAndHiddenFieldValues() {
        val file = File(context.cacheDir, "addresses.pdf")
        PDDocument().use { doc ->
            val first = PDPage(PDRectangle.A4).also(doc::addPage)
            PDPageContentStream(doc, first).use { content -> write(content, "Email me", 72f, 700f) }
            first.annotations.add(PDAnnotationLink().apply {
                rectangle = PDRectangle(72f, 695f, 80f, 20f)
                action = PDActionURI().apply { uri = "mailto:john.smith@example.org" }
            })
            // A name with a space is in a web address only as an escape.
            first.annotations.add(PDAnnotationLink().apply {
                rectangle = PDRectangle(72f, 650f, 80f, 20f)
                action = PDActionURI().apply { uri = "https://example.org/team/Ana%20Silva" }
            })
            val second = PDPage(PDRectangle.A4).also(doc::addPage)
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            val field = PDTextField(form)
            field.partialName = "contact"
            field.cosObject.setString(COSName.V, "John Smith")
            field.widgets[0].apply {
                rectangle = PDRectangle(72f, 600f, 200f, 20f)
                isHidden = true
                page = second
                second.annotations.add(this)
            }
            form.fields.add(field)
            val office = PDComboBox(form).apply {
                partialName = "office"
                setOptions(listOf("a1", "b2"), listOf("Ana Silva", "Bo Chen"))
                cosObject.setString(COSName.V, "b2")
            }
            office.widgets[0].apply {
                rectangle = PDRectangle(72f, 500f, 200f, 20f)
                isHidden = true
                page = second
                second.annotations.add(this)
            }
            form.fields.add(office)
            form.fields.add(PDTextField(form).apply {
                partialName = "former"
                cosObject.setString(COSName.V, "Ana Silva")
            })
            doc.save(file)
        }
        val source = import(file)
        val found = engine.findText(source, "smith")
        assertEquals(listOf(0, 1), found.map { it.single().page })
        for (box in found.flatten()) assertEquals(Redaction.MIN_SIZE, box.width, 1e-6f)
        val escaped = engine.findText(source, "ana silva")
        // The field on no page gets no box: Redact takes it out, told what was looked for.
        assertEquals(listOf(0, 1), escaped.map { it.single().page })
        val boxes = (found + escaped).flatten()
        val redacted = save(engine.redact(source, boxes, "addresses_redacted", searched = listOf("smith", "ana silva")).file, "addresses_redacted.pdf")
        PDDocument.load(redacted).use { doc ->
            assertTrue(everything(doc).none { it.contains("smith", ignoreCase = true) || it.contains("silva", ignoreCase = true) })
        }
    }

    /**
     * Form fields filled without a look of their own, whose values PDFBox can't draw (a letter its
     * font lacks, a font the form doesn't have): Find text finds them, and a redacted page keeps
     * them in its picture, as the preview shows them.
     */
    @Test fun fieldValuesPdfboxCannotDrawAreFoundAndKept() {
        val file = File(context.cacheDir, "undrawn-fields.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.defaultAppearance = "/Helv 12 Tf 0 g"
            form.needAppearances = true
            for ((name, y, look) in listOf(Triple("polish", 700f, null), Triple("broken", 600f, "/Nope 12 Tf 0 g"))) {
                val field = PDTextField(form)
                field.partialName = name
                if (look != null) field.defaultAppearance = look
                field.widgets[0].apply {
                    rectangle = PDRectangle(72f, y, 300f, 24f)
                    this.page = page
                    page.annotations.add(this)
                }
                form.fields.add(field)
            }
            // As some apps fill forms: values set, no look made for them.
            form.getField("polish").cosObject.setString(COSName.V, "Łukasz Żółć")
            form.getField("broken").cosObject.setString(COSName.V, "Visible value")
            doc.save(file)
        }
        val source = import(file)
        assertEquals(listOf(0), engine.findText(source, "łukasz").map { it.single().page })
        assertEquals(listOf(0), engine.findText(source, "visible value").map { it.single().page })
        val redacted = save(engine.redact(source, listOf(RedactBox(0, 0.1f, 0.9f, 0.2f, 0.95f)), "undrawn_redacted").file, "undrawn_redacted.pdf")
        val picture = render(redacted, 0)
        for (y in listOf(700, 600)) {
            // Dark letters inside the field, which is 24 points tall from y up.
            val dark = (75 until 370).count { x -> (842 - y - 22 until 842 - y - 2).any { row -> Color.red(picture.getPixel(x, row)) < 100 } }
            assertTrue("The value at $y is in the picture ($dark columns)", dark > 20)
        }
    }

    /**
     * A box placed exactly over a drawing hides all of it, also on a page whose size isn't whole
     * points: Android gives a page's size in whole points, and from Android 17 a page drawn by that
     * size is stretched, here by most of a point at its foot, out from under the box.
     */
    @Test fun aBoxHidesAllOfWhatItIsPlacedOver() {
        val width = 300f
        val height = 400.9f
        val file = File(context.cacheDir, "fractional.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(width, height)).also(doc::addPage)
            PDPageContentStream(doc, page).use { content ->
                content.setNonStrokingColor(1f, 0f, 0f)
                content.addRect(100f, 10f, 100f, 20f)
                content.fill()
            }
            doc.save(file)
        }
        val box = Redaction.between(0, 100f / width, 1 - 30f / height, 200f / width, 1 - 10f / height)
        val redacted = save(engine.redact(import(file), listOf(box), "fractional_redacted").file, "fractional_redacted.pdf")
        val picture = PDDocument.load(redacted).use { doc -> imagesOn(doc.getPage(0)).single().image.image }
        assertEquals("Red pixels left", 0, pixels(picture).count(::red))
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
        val view = engine.previewView(unlocked)
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
        engine.previewView(source)
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
            mapOf(
                "pdf_inputs" to arrayListOf(input), "redact_boxes" to Redaction.pack(boxes), "redact_page" to 3, "redact_selected" to 1,
                "redact_unread" to intArrayOf(1, 3),
            ),
        )
        var vm: PdfToolsViewModel? = null
        instrumentation.runOnMainSync { vm = PdfToolsViewModel(app, handle) }
        val restored = vm!!
        try {
            assertEquals(boxes, restored.redactBoxes.value)
            assertEquals(3, restored.redactPage.value)
            assertEquals(1, restored.redactSelected.value)
            assertEquals("The pages Find text couldn't read", listOf(1, 3), restored.redactUnread.value.toList())
            // The preview's copy is made again, and redacting works from what came back.
            instrumentation.runOnMainSync { restored.preparePreview(input) }
            val deadline = System.currentTimeMillis() + 10_000
            while (restored.previewView.value == null) {
                assertTrue("The preview's copy is ready in time", System.currentTimeMillis() < deadline)
                Thread.sleep(50)
            }
            assertEquals(input.pageCount, restored.previewView.value!!.pageCount)
        } finally {
            instrumentation.runOnMainSync { restored.reset() }
            // Its cleanup would delete the copies the next test makes.
            runBlocking { restored.awaitFileWork() }
        }
    }

    @Test fun viewCopiesGoWithTheirPdf() {
        val source = import(secrets("view.pdf"))
        val view = engine.previewView(source)
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
        assertEquals(input.path, engine.previewView(input).path)
    }
}
