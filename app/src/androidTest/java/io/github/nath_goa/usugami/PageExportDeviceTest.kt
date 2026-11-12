package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Point
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.ExportImageFormat
import io.github.nath_goa.usugami.core.ExportImageSize
import io.github.nath_goa.usugami.core.ExportImages
import io.github.nath_goa.usugami.data.ExportProgress
import io.github.nath_goa.usugami.data.ImagesOutcome
import io.github.nath_goa.usugami.data.NoTextFound
import io.github.nath_goa.usugami.data.OcrEngine
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.OcrLine
import io.github.nath_goa.usugami.data.OcrProblem
import io.github.nath_goa.usugami.data.OcrRecognizer
import io.github.nath_goa.usugami.data.OcrUnavailableException
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.TextOutcome
import io.github.nath_goa.usugami.data.pagesToImages
import io.github.nath_goa.usugami.data.pagesToText
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * PDF to images or text in the engine: pages drawn as pictures (tests 1-3), and the text of pages
 * as a file, with pages without text read by a made-up text recognition (tests 4-8).
 */
class PageExportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)
    private val output = File(context.cacheDir, "output/tools")
    private val dir = File(context.cacheDir, "page-export-test").apply { mkdirs() }
    private val imported = ArrayList<PdfInput>()
    private var nextId = 5801L

    private fun import(file: File, password: String = ""): PdfInput = engine.import(Uri.fromFile(file), nextId++, password).also { imported += it }

    private fun images(
        input: PdfInput,
        pages: List<Int>,
        format: ExportImageFormat,
        size: ExportImageSize,
        name: String,
        check: () -> Unit = {},
        progress: MutableList<ExportProgress> = ArrayList(),
    ): ImagesOutcome = engine.pagesToImages(input, pages, format, size, name, check) { progress += it }

    @After fun tearDown() {
        engine.clearOutput()
        imported.forEach(engine::delete)
        dir.deleteRecursively()
    }

    /** An A4 page with a black square 100 points wide in its top-left corner. */
    private fun squarePage(doc: PDDocument): PDPage = PDPage(PDRectangle.A4).also { page ->
        doc.addPage(page)
        PDPageContentStream(doc, page).use { content ->
            content.setNonStrokingColor(0f, 0f, 0f)
            content.addRect(0f, PDRectangle.A4.height - 100f, 100f, 100f)
            content.fill()
        }
    }

    private fun dark(bitmap: Bitmap, x: Int, y: Int): Boolean = bitmap.getPixel(x, y).let { Color.red(it) + Color.green(it) + Color.blue(it) < 3 * 64 }

    private fun light(bitmap: Bitmap, x: Int, y: Int): Boolean = bitmap.getPixel(x, y).let { Color.red(it) + Color.green(it) + Color.blue(it) > 3 * 200 }

    private fun decode(file: File): Bitmap = BitmapFactory.decodeFile(file.path) ?: throw AssertionError("${file.name} doesn't decode")

    @Test fun imagesShowPagesAsViewersDo() {
        val file = File(dir, "squares.pdf")
        PDDocument().use { doc ->
            squarePage(doc)
            squarePage(doc).rotation = 90
            squarePage(doc).cropBox = PDRectangle(0f, PDRectangle.A4.height / 2, PDRectangle.A4.width, PDRectangle.A4.height / 2)
            doc.save(file)
        }
        val input = import(file)
        val progress = ArrayList<ExportProgress>()
        val outcome = images(input, listOf(0, 1, 2), ExportImageFormat.JPG, ExportImageSize.SMALL, "X", progress = progress)

        assertEquals(listOf("X_p1.jpg", "X_p2.jpg", "X_p3.jpg"), outcome.files.map { it.name })
        assertTrue(outcome.files.all { it.file.parentFile == output && it.pageCount == 1 })
        assertEquals("No page is too big at 100 dpi", emptyList<Int>(), outcome.reduced)
        assertEquals((0 until 3).map { ExportProgress(ExportProgress.Stage.IMAGES, it, 3) }, progress)

        // The shown sizes × 100 / 72: A4 upright, A4 turned (landscape), and the top half of A4.
        val sizes = outcome.files.map { pdf ->
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(pdf.file.path, it) }.let {
                assertEquals("image/jpeg", it.outMimeType)
                it.outWidth to it.outHeight
            }
        }
        assertEquals(listOf(827 to 1169, 1169 to 827, 827 to 585), sizes)

        val (upright, turned, cropped) = outcome.files.map { decode(it.file) }
        assertTrue("The square is at the top left", dark(upright, 30, 30))
        assertTrue(light(upright, upright.width - 30, 30) && light(upright, 30, upright.height - 30))
        // Turned a quarter clockwise, the page's top-left corner is at the top right.
        assertTrue("The turned page's square is at the top right", dark(turned, turned.width - 30, 30))
        assertTrue(light(turned, 30, 30) && light(turned, 30, turned.height - 30))
        assertTrue("The crop box's top half keeps the square", dark(cropped, 30, 30))
        assertTrue(light(cropped, cropped.width - 30, 30) && light(cropped, 30, cropped.height - 30))
        listOf(upright, turned, cropped).forEach(Bitmap::recycle)
    }

    @Test fun pngOfAProtectedPdfWithAFilledForm() {
        val file = File(dir, "protected-form.pdf")
        formPdf(File(dir, "form.pdf"))
        PDDocument.load(File(dir, "form.pdf")).use { doc ->
            doc.addPage(PDPage(PDRectangle(5000f, 5000f)))
            doc.protect(StandardProtectionPolicy("owner", "user", AccessPermission()).apply { encryptionKeyLength = 128 })
            doc.save(file)
        }
        val input = import(file, "user")
        val outcome = images(input, listOf(0, 1), ExportImageFormat.PNG, ExportImageSize.LARGE, "Form.pdf")
        assertEquals(listOf("Form_p1.png", "Form_p2.png"), outcome.files.map { it.name })

        val form = outcome.files[0].file
        // The colour type in the PNG's header: 2 is red, green and blue, without alpha.
        assertEquals(2, form.readBytes()[25].toInt())
        val page = decode(form)
        assertFalse(page.hasAlpha())
        assertEquals(2480 to 3508, page.width to page.height)
        // The "name" field, at 72, 600 to 322, 624 points, shows its value, drawn by the view.
        val perPoint = 300 / 72f
        val left = (72 * perPoint).toInt()
        val right = (322 * perPoint).toInt()
        val top = ((PDRectangle.A4.height - 624) * perPoint).toInt()
        val bottom = ((PDRectangle.A4.height - 600) * perPoint).toInt()
        val drawn = (top until bottom).any { y -> (left until right).any { x -> dark(page, x, y) } }
        page.recycle()
        assertTrue("The field's value is drawn", drawn)

        // 5000 points square at 300 dpi would be 20,833 pixels a side.
        assertEquals(listOf(1), outcome.reduced)
        val huge = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(outcome.files[1].file.path, it) }
        assertEquals(huge.outWidth, huge.outHeight)
        assertTrue(huge.outWidth <= ExportImages.MAX_SIDE)
        val pixels = huge.outWidth.toLong() * huge.outHeight
        assertTrue("$pixels pixels", pixels <= ExportImages.MAX_PIXELS && pixels > ExportImages.MAX_PIXELS * 0.99)
    }

    @Test fun cancellingLeavesNoImages() {
        val file = File(dir, "four.pdf")
        PDDocument().use { doc ->
            for (word in PAGE_WORDS.take(4)) writeText(doc, PDPage(PDRectangle.A4).also(doc::addPage), "PAGE $word")
            doc.save(file)
        }
        val input = import(file)
        var written = 0
        // Stopped as the third page is about to be drawn, with two pictures made.
        val stop = {
            written = output.listFiles { f: File -> f.name.endsWith(".jpg") }.orEmpty().size
            if (written >= 2) throw CancellationException("Cancelled by the test")
        }
        assertThrows(CancellationException::class.java) { images(input, listOf(0, 1, 2, 3), ExportImageFormat.JPG, ExportImageSize.SMALL, "Stopped", stop) }
        assertEquals(2, written)
        assertTrue("Nothing is left: ${output.list()?.toList()}", output.list().isNullOrEmpty())
    }

    // ---- Text ----

    private fun text(
        input: PdfInput,
        pages: List<Int>,
        ocr: OcrEngine? = null,
        name: String = "Report",
        progress: MutableList<ExportProgress> = ArrayList(),
    ): TextOutcome = runBlocking { engine.pagesToText(input, pages, ocr, OcrLanguage.ENGLISH, name, {}) { progress += it } }

    private fun leftInOutput(): List<String> = output.list().orEmpty().toList()

    /** A page with a filled square on it: contents, but no text. */
    private fun drawnPage(doc: PDDocument): PDPage = PDPage(PDRectangle.A4).also { page ->
        doc.addPage(page)
        PDPageContentStream(doc, page).use { content ->
            content.addRect(100f, 100f, 200f, 200f)
            content.fill()
        }
    }

    /** A page with no contents of its own, only a text field showing [value]. */
    private fun fieldOnlyPage(doc: PDDocument, value: String) {
        val page = PDPage(PDRectangle.A4).also(doc::addPage)
        val form = PDAcroForm(doc).also { doc.documentCatalog.acroForm = it }
        form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
        form.defaultAppearance = "/Helv 12 Tf 0 g"
        val field = PDTextField(form).apply {
            partialName = "name"
            defaultAppearance = "/Helv 12 Tf 0 g"
        }
        val widget = field.widgets.single().apply {
            rectangle = PDRectangle(72f, 600f, 250f, 24f)
            this.page = page
            isPrinted = true
        }
        form.fields.add(field)
        page.annotations = listOf(widget)
        field.value = value
    }

    /**
     * A made-up text recognition: [read] gives the lines of the nth picture read (from 0), or
     * throws. It recycles the pictures, as an engine must.
     */
    private class FakeOcr(private val ready: () -> Unit = {}, private val read: suspend (Int) -> List<OcrLine>) : OcrEngine {
        var readied = 0
        var pictures = 0

        override suspend fun ensureReady(language: OcrLanguage, onDownload: (Float?) -> Unit) {
            readied++
            ready()
        }

        override fun open(language: OcrLanguage): OcrRecognizer = object : OcrRecognizer {
            override suspend fun recognize(page: Bitmap): List<OcrLine> = try {
                assertTrue("A page drawn for reading: ${page.width} x ${page.height}", page.width > 1000 && page.height > 1000)
                read(pictures++)
            } finally {
                page.recycle()
            }

            override fun close() = Unit
        }
    }

    /** A line of [text] read with its top at [top] pixels. */
    private fun ocrLine(text: String, top: Int) = OcrLine(text, listOf(Point(100, top), Point(900, top), Point(900, top + 40), Point(100, top + 40)))

    /**
     * Two lines on page 1, one a word broken after its hyphen, and an invisible signature's widget; a
     * turned page; a page drawn without text; one with only a form field; one cropped to its top
     * half, with a line in each half; one with no contents at all; an upright page with a label
     * turned a quarter after its line. The file has each page's lines as the page breaks them, an
     * empty line between pages, and nothing of pages without text, of the field or below the crop
     * box. With text recognition, the field's page is read with its field, and the page with nothing
     * on it isn't read.
     */
    @Test fun textComesPageAfterPageInReadingOrder() {
        val file = File(dir, "pages.pdf")
        PDDocument().use { doc ->
            PDPage(PDRectangle.A4).also { page ->
                doc.addPage(page)
                writeText(doc, page, "Hello world")
                writeText(doc, page, "second-", y = 680f)
                // As PDFBox signs a PDF invisibly: a widget with no room on the page, which shows nothing.
                page.annotations = listOf(PDAnnotationWidget().apply { rectangle = PDRectangle(0f, 0f, 0f, 0f); isPrinted = true })
            }
            PDPage(PDRectangle.A4).also { page ->
                doc.addPage(page)
                page.rotation = 90
                writeText(doc, page, "Turned page")
            }
            drawnPage(doc)
            fieldOnlyPage(doc, "Field value")
            PDPage(PDRectangle.A4).also { page ->
                doc.addPage(page)
                page.cropBox = PDRectangle(0f, PDRectangle.A4.height / 2, PDRectangle.A4.width, PDRectangle.A4.height / 2)
                writeText(doc, page, "Upper line", y = 700f)
                writeText(doc, page, "Lower line", y = 200f)
            }
            doc.addPage(PDPage(PDRectangle.A4))
            PDPage(PDRectangle.A4).also { page ->
                doc.addPage(page)
                writeText(doc, page, "Upright line")
                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 14f)
                    // Turned a quarter: it runs up the page from (300, 300).
                    content.setTextMatrix(Matrix(0f, 1f, -1f, 0f, 300f, 300f))
                    content.showText("Sideways label")
                    content.endText()
                }
            }
            doc.save(file)
        }
        val input = import(file)
        val progress = ArrayList<ExportProgress>()
        val outcome = text(input, (0 until 6).toList(), name = "Report.pdf", progress = progress)

        assertEquals("Report.txt", outcome.file.name)
        assertEquals(output, outcome.file.file.parentFile)
        val bytes = outcome.file.file.readBytes()
        assertFalse("No byte order mark", bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
        assertEquals("Hello world\nsecond-\n\nTurned page\n\nUpper line\n", String(bytes, Charsets.UTF_8))
        assertEquals(3, outcome.file.pageCount)
        assertEquals(listOf(2, 3, 5), outcome.withoutText)
        assertEquals(emptyList<Int>(), outcome.read)
        assertEquals(emptyList<Int>(), outcome.failed)
        assertTrue("Page 4 has a form field", outcome.formsOrComments)
        // Told of each page with contents, by its place among the pages chosen.
        assertEquals(listOf(0, 1, 2, 4).map { ExportProgress(ExportProgress.Stage.TEXT, it, 6) }, progress)
        assertEquals("Only the text file is left", listOf("Report.txt"), leftInOutput())

        // Without page 4, no page chosen has a form field or a comment (page 1's widget shows nothing).
        assertFalse(text(input, listOf(0, 4)).formsOrComments)

        // The label turned a quarter is a line of its own, whole, after the line drawn before it.
        assertEquals("Upright line\nSideways label\n", text(input, listOf(6)).file.file.readText())

        // With text recognition: the page drawn without text and the field's page are read, the
        // field's value with its page, so no note says it is left out; the page with nothing on it
        // isn't drawn for reading, and with only a page of text beside it, recognition isn't readied.
        val reading = FakeOcr { listOf(ocrLine("Read line", 100)) }
        val recognised = text(input, (0 until 6).toList(), reading)
        assertEquals(listOf(2, 3), recognised.read)
        assertEquals(listOf(5), recognised.withoutText)
        assertEquals(2, reading.pictures)
        assertFalse("The field is read with its page", recognised.formsOrComments)
        val unready = FakeOcr(ready = { throw OcrUnavailableException(OcrProblem.DOWNLOAD_FAILED) }) { emptyList() }
        assertEquals(listOf(5), text(input, listOf(0, 5), unready).withoutText)
        assertEquals(0, unready.readied)
    }

    /** A line of Hebrew drawn as most PDFs draw it, word by word from the left: its words come out in the order they are read. */
    @Test fun rightToLeftLinesReadInOrder() {
        val font = File("/system/fonts").listFiles().orEmpty().filter { "Hebrew" in it.name && it.name.endsWith(".ttf") }.minByOrNull { it.name }
        assumeTrue("A Hebrew font on the phone", font != null)
        val file = File(dir, "hebrew.pdf")
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
                    x += hebrew.getStringWidth(word.reversed()) / 1000f * 20f + 12f
                }
            }
            doc.save(file)
        }
        val outcome = text(import(file), listOf(0))
        assertEquals("שלום עולם\n", outcome.file.file.readText())
    }

    /**
     * Pages without text are read with text recognition, only when there are such pages, and the
     * lines it reads come out top first. What it can't read is named as failed, and the rest kept;
     * when it can't get ready, or reads nothing anywhere, nothing is made; and a run cancelled while
     * a page is read leaves nothing behind.
     */
    @Test fun pagesWithoutTextAreReadWhereThereIsRecognition() {
        val file = File(dir, "scanned.pdf")
        PDDocument().use { doc ->
            writeText(doc, PDPage(PDRectangle.A4).also(doc::addPage), "Typed page")
            drawnPage(doc)
            writeText(doc, PDPage(PDRectangle.A4).also(doc::addPage), "Last page")
            drawnPage(doc)
            doc.save(file)
        }
        val input = import(file)

        // Read bottom line first, as an engine may give them.
        val reading = FakeOcr { listOf(ocrLine("second scanned line", 300), ocrLine("First scanned line", 100)) }
        val progress = ArrayList<ExportProgress>()
        val outcome = text(input, listOf(0, 1, 2), reading, progress = progress)
        assertEquals("Typed page\n\nFirst scanned line\nsecond scanned line\n\nLast page\n", outcome.file.file.readText())
        assertEquals(listOf(1), outcome.read)
        assertEquals(emptyList<Int>(), outcome.withoutText)
        assertEquals(emptyList<Int>(), outcome.failed)
        assertEquals(3, outcome.file.pageCount)
        assertEquals(1, reading.readied)
        assertTrue(progress.contains(ExportProgress(ExportProgress.Stage.READ_SCANS, 0, 1)))
        // Pages that all have text never ask for text recognition.
        text(input, listOf(0, 2), reading)
        assertEquals(1, reading.readied)

        // It can't get ready: that goes up, and nothing is made.
        val unavailable = FakeOcr(ready = { throw OcrUnavailableException(OcrProblem.DOWNLOAD_FAILED) }) { emptyList() }
        val problem = assertThrows(OcrUnavailableException::class.java) { text(input, listOf(0, 1, 2), unavailable) }
        assertEquals(OcrProblem.DOWNLOAD_FAILED, problem.problem)
        assertTrue("Nothing is left: ${leftInOutput()}", leftInOutput().isEmpty())

        // It fails on page 2 and reads page 4: page 2 is named as failed, the text of the others kept.
        val failing = FakeOcr { picture -> if (picture == 0) throw IllegalStateException("Can't read this one") else listOf(ocrLine("Read at last", 100)) }
        val partly = text(input, listOf(0, 1, 2, 3), failing)
        assertEquals("Typed page\n\nLast page\n\nRead at last\n", partly.file.file.readText())
        assertEquals(listOf(1), partly.failed)
        assertEquals(listOf(3), partly.read)
        assertEquals(emptyList<Int>(), partly.withoutText)

        // It fails on every page of a PDF without text: no text found, as recognition failed.
        val never = FakeOcr { throw IllegalStateException("Can't read any") }
        val none = assertThrows(NoTextFound::class.java) { text(input, listOf(1, 3), never) }
        assertTrue(none.recognitionFailed)
        assertTrue("Nothing is left: ${leftInOutput()}", leftInOutput().isEmpty())

        // Cancelled while a page is being read: neither the text file nor the spool is left.
        runBlocking {
            val reached = CompletableDeferred<Unit>()
            val waiting = FakeOcr {
                reached.complete(Unit)
                awaitCancellation()
            }
            var thrown: Throwable? = null
            val run = launch {
                try {
                    engine.pagesToText(input, listOf(0, 1, 2), waiting, OcrLanguage.ENGLISH, "Report", {}) {}
                } catch (t: Throwable) {
                    thrown = t
                    throw t
                }
            }
            reached.await()
            assertTrue("The spool holds the pages read so far: ${leftInOutput()}", "pages.tmp" in leftInOutput())
            run.cancelAndJoin()
            assertTrue("Cancelled: $thrown", thrown is CancellationException)
        }
        assertTrue("Nothing is left: ${leftInOutput()}", leftInOutput().isEmpty())
    }

    /** No page chosen has text, and there is no text recognition: nothing is made. */
    @Test fun noTextAtAllIsRefused() {
        val file = File(dir, "blank.pdf")
        PDDocument().use { doc ->
            drawnPage(doc)
            doc.addPage(PDPage(PDRectangle.A4))
            doc.save(file)
        }
        val refused = assertThrows(NoTextFound::class.java) { text(import(file), listOf(0, 1)) }
        assertFalse(refused.recognitionFailed)
        assertTrue("Nothing is left: ${leftInOutput()}", leftInOutput().isEmpty())
    }

    /**
     * Japanese written down the page in a vertical font without a ToUnicode map, two columns, and a
     * column of two Korean words with room between them and no space glyph, on an upright page and on
     * one turned a quarter: each column is a line, in the order the PDF draws them, the Korean words
     * a space apart. Not an exact string for the Japanese, as whether PDFBox puts a word gap before
     * the digits isn't known.
     */
    @Test fun verticalWritingComesOutAColumnToALine() {
        val file = File(dir, "vertical.pdf")
        PDDocument().use { doc ->
            // 東京都2024年 down from y 400 at x 490 to 510, then 港区 down from y 400 at x 460 to 480.
            val columns = "BT /F1 20 Tf 500 400 Td <67714EAC90FD00320030003200345E74> Tj ET\n" +
                "BT /F1 20 Tf 470 400 Td <6E2F533A> Tj ET\n" +
                // 서울 down from y 400 at x 430 to 450, then 6 points of room (0.3 of a letter), then 특별시.
                "BT /F2 20 Tf 440 400 Td <C11CC6B8> Tj ET\nBT /F2 20 Tf 440 354 Td <D2B9BCC4C2DC> Tj ET\n"
            for (rotation in listOf(0, 90)) {
                val page = PDPage(PDRectangle.A4).also(doc::addPage)
                page.rotation = rotation
                page.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply {
                    setItem(COSName.FONT, COSDictionary().apply {
                        setItem("F1", cjkFont("UniJIS-UCS2-V", "Japan1", 6))
                        setItem("F2", cjkFont("UniKS-UCS2-V", "Korea1", 1))
                    })
                })
                page.setContents(PDStream(doc, columns.byteInputStream(Charsets.ISO_8859_1)))
            }
            doc.save(file)
        }
        val read = text(import(file), listOf(0, 1)).file.file.readText()
        assertTrue("Ends with a line break: $read", read.endsWith("\n"))
        val pages = read.removeSuffix("\n").split("\n\n")
        assertEquals("Two pages: $read", 2, pages.size)
        for ((index, page) in pages.withIndex()) {
            val lines = page.split("\n")
            assertEquals("Page ${index + 1}, a column to a line: $read", 3, lines.size)
            assertEquals("Page ${index + 1}: $read", listOf("東京都2024年", "港区"), lines.take(2).map { it.replace(" ", "") })
            assertEquals("Page ${index + 1}, a space between the Korean words: $read", "서울 특별시", lines[2])
        }
    }
}
