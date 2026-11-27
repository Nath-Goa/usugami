package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.TranslatedPages
import io.github.nath_goa.usugami.data.NothingToTranslate
import io.github.nath_goa.usugami.data.OcrEngine
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.OcrLine
import io.github.nath_goa.usugami.data.OcrRecognizer
import io.github.nath_goa.usugami.data.OcrUnavailableException
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.ScannedLines
import io.github.nath_goa.usugami.data.TextTranslator
import io.github.nath_goa.usugami.data.TranslateOutcome
import io.github.nath_goa.usugami.data.TranslateProgress
import io.github.nath_goa.usugami.data.TranslationEngine
import io.github.nath_goa.usugami.data.TranslationProblem
import io.github.nath_goa.usugami.data.TranslationUnavailableException
import io.github.nath_goa.usugami.data.detectSourceLanguage
import io.github.nath_goa.usugami.data.ocrEngine
import io.github.nath_goa.usugami.data.translatePdf
import io.github.nath_goa.usugami.data.translationEngine
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Translate PDF's engine (standard edition only), with a made-up translator that writes a text's
 * letters backwards between marks, so a translation is easy to tell from its source: what is
 * translated, what stays, how pages, titles and bookmarks come out, what is left out of the new
 * PDF, cancelling, pages that fail, scanned pages (made-up and real text recognition), scripts drawn
 * as real text, and one run with ML Kit's real translator (needs internet the first time).
 */
class PdfTranslationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)
    private val output = File(context.cacheDir, "output/tools")
    private val dir = File(context.cacheDir, "translate-test").apply { mkdirs() }
    private val results = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }
    private val imported = ArrayList<PdfInput>()
    private var nextId = 7301L

    @After fun tearDown() {
        engine.clearOutput()
        imported.forEach(engine::delete)
        dir.deleteRecursively()
    }

    private fun import(file: File, password: String = ""): PdfInput = engine.import(Uri.fromFile(file), nextId++, password).also { imported += it }

    /** A translation engine that knows every language, has every pack, and translates with [translate]. */
    private class FakeEngine(private val answer: (String) -> String = ::marked) : TranslationEngine {
        val asked = ArrayList<String>()
        var detected: String? = null
        override val languages = listOf("ar", "de", "en", "es", "fr", "hi", "zh")
        override suspend fun downloaded(): Set<String> = languages.toSet()
        override suspend fun ensureReady(source: String, target: String, onDownload: (Float?) -> Unit) {
            onDownload(null)
            onDownload(1f)
        }
        override fun open(source: String, target: String): TextTranslator = object : TextTranslator {
            override suspend fun translate(text: String): String {
                synchronized(asked) { asked += text }
                return answer(text)
            }
            override fun close() = Unit
        }
        override suspend fun delete(tag: String) = Unit
        override suspend fun detect(text: String): String? {
            detectedFrom = text
            return detected
        }
        var detectedFrom: String? = null
    }

    /** Text recognition that reads [lines] on every page: each a text and its box as shares of the page (left, top, right, bottom). */
    private class FakeOcr(private val lines: List<Pair<String, List<Float>>>) : OcrEngine {
        var readied = 0
        var read = 0
        override suspend fun ensureReady(language: OcrLanguage, onDownload: (Float?) -> Unit) {
            readied++
        }
        override fun open(language: OcrLanguage): OcrRecognizer = object : OcrRecognizer {
            override suspend fun recognize(page: Bitmap): List<OcrLine> {
                read++
                val w = page.width
                val h = page.height
                page.recycle()
                return lines.map { (text, box) ->
                    val (l, t, r, b) = box
                    OcrLine(text, listOf(Point((l * w).toInt(), (t * h).toInt()), Point((r * w).toInt(), (t * h).toInt()), Point((r * w).toInt(), (b * h).toInt()), Point((l * w).toInt(), (b * h).toInt())))
                }
            }
            override fun close() = Unit
        }
    }

    private fun translate(
        input: PdfInput,
        pages: List<Int> = (0 until input.pageCount).toList(),
        translation: TranslationEngine = FakeEngine(),
        ocr: OcrEngine? = null,
        source: String = "fr",
        target: String = "en",
        check: () -> Unit = {},
        progress: MutableList<TranslateProgress> = ArrayList(),
    ): TranslateOutcome = runBlocking { engine.translatePdf(input, pages, translation, ocr, source, target, "Translated", check) { progress += it } }

    // ---- Fixtures ----

    private fun PDPageContentStream.line(x: Float, y: Float, text: String, size: Float = 11f, font: PDFont = PDType1Font.HELVETICA) {
        beginText()
        setFont(font, size)
        newLineAtOffset(x, y)
        showText(text)
        endText()
    }

    /** One A4 page: a heading, two paragraphs, a table's row of four cells and an amount under it. */
    private fun article(page: PDPage, doc: PDDocument) {
        PDPageContentStream(doc, page).use { c ->
            c.line(72f, 760f, HEADING, 20f, PDType1Font.HELVETICA_BOLD)
            PARAGRAPH_ONE.forEachIndexed { i, text -> c.line(72f, 720f - 14f * i, text) }
            PARAGRAPH_TWO.forEachIndexed { i, text -> c.line(72f, 640f - 14f * i, text) }
            CELLS.forEachIndexed { i, text -> c.line(72f + 130f * i, 560f, text) }
            c.line(462f, 540f, AMOUNT)
        }
    }

    private fun articlePdf(name: String = "article.pdf", edit: (PDDocument) -> Unit = {}): File = File(dir, name).also { file ->
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            article(page, doc)
            edit(doc)
            doc.save(file)
        }
    }

    /** [count] A4 pages, page n saying "Page n" in a heading over a paragraph naming its number. */
    private fun numberedPages(file: File, count: Int, edit: (PDDocument) -> Unit = {}): File = file.also {
        PDDocument().use { doc ->
            for (n in 1..count) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { c ->
                    c.line(72f, 760f, "Chapitre ${WORDS[n % WORDS.size]} $n", 18f, PDType1Font.HELVETICA_BOLD)
                    c.line(72f, 720f, "Ceci est le texte de la page numero $n du document.")
                }
            }
            edit(doc)
            doc.save(file)
        }
    }

    /** A picture of grey noise, [width] × [height] pixels, as a PDF image. */
    private fun noise(doc: PDDocument, width: Int, height: Int): PDImageXObject {
        val bitmap = createBitmap(width, height)
        val random = java.util.Random(7)
        for (y in 0 until height) for (x in 0 until width) {
            val v = 100 + random.nextInt(120)
            bitmap.setPixel(x, y, Color.rgb(v, v - 20, v + 20))
        }
        return LosslessFactory.createFromImage(doc, bitmap).also { bitmap.recycle() }
    }

    // ---- Reading the result ----

    private fun textOf(file: File, page: Int? = null): String = PDDocument.load(file).use { doc ->
        PDFTextStripper().apply {
            if (page != null) {
                startPage = page + 1
                endPage = page + 1
            }
        }.getText(doc)
    }.replace(Regex("\\s+"), " ")

    /** The glyphs of the text on [page] of [file], where they are drawn. */
    private fun glyphs(file: File, page: Int): List<TextPosition> = ArrayList<TextPosition>().also { glyphs ->
        PDDocument.load(file).use { doc ->
            object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    glyphs += text
                }
            }.apply {
                startPage = page + 1
                endPage = page + 1
            }.getText(doc)
        }
    }

    /** [page] of [file] drawn [perPoint] pixels to the point. */
    private fun render(file: File, page: Int, perPoint: Float = 2f): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.openPage(page).use { p ->
                    createBitmap((p.width * perPoint).toInt(), (p.height * perPoint).toInt()).also {
                        it.eraseColor(Color.WHITE)
                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    /** How many dark pixels [bitmap] (2 pixels to the point) has in the box from [left], [top] to [right], [bottom] points, y down. */
    private fun darkIn(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Int {
        var dark = 0
        for (y in (top * 2).toInt() until (bottom * 2).toInt()) for (x in (left * 2).toInt() until (right * 2).toInt()) {
            val c = bitmap.getPixel(x, y)
            if (Color.red(c) + Color.green(c) + Color.blue(c) < 3 * 100) dark++
        }
        return dark
    }

    // ---- Tests ----

    @Test fun paragraphsAreReplacedByTheirTranslationAsRealText() {
        val input = import(articlePdf())
        val progress = ArrayList<TranslateProgress>()
        val outcome = translate(input, progress = progress)

        assertEquals("Translated.pdf", outcome.file.file.name)
        assertEquals(output, outcome.file.file.parentFile)
        assertEquals(1, outcome.file.pageCount)
        assertEquals(emptyList<Int>(), outcome.noText + outcome.unread + outcome.partly)
        PDDocument.load(outcome.file.file).use { doc ->
            val box = doc.getPage(0).mediaBox
            assertEquals(PDRectangle.A4.width, box.width, 0.01f)
            assertEquals(PDRectangle.A4.height, box.height, 0.01f)
        }
        val text = textOf(outcome.file.file)
        // Each block whole: the heading, each paragraph joined into one text, each cell.
        for (source in listOf(HEADING, PARAGRAPH_ONE.joinToString(" "), PARAGRAPH_TWO.joinToString(" ")) + CELLS) {
            assertTrue("The translation of \"$source\" is missing from: $text", text.contains(marked(source)))
            assertFalse("\"$source\" is still text", text.contains(source))
        }
        // The amount isn't translated: it stays on the page's picture, and isn't text.
        assertFalse(text.contains(AMOUNT))
        val page = render(outcome.file.file, 0)
        assertTrue("The amount is still on the page", darkIn(page, 460f, 842f - 550f, 495f, 842f - 538f) > 20)
        page.recycle()
        assertEquals(
            listOf(TranslateProgress(TranslateProgress.Stage.DOWNLOAD, 0, 0), TranslateProgress(TranslateProgress.Stage.DOWNLOAD, 100, 100)) +
                listOf(TranslateProgress(TranslateProgress.Stage.PAGE, 0, 1), TranslateProgress(TranslateProgress.Stage.PAGE, 1, 1)),
            progress,
        )
    }

    @Test fun onlyThePagesChosenWithTheTitleAndBookmarksTranslated() {
        val file = numberedPages(File(dir, "book.pdf"), 4) { doc ->
            doc.documentInformation.title = "Le livre des pages"
            doc.documentInformation.author = "Quelqu'un"
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            fun item(title: String, page: Int, parent: PDOutlineNode): PDOutlineItem = PDOutlineItem().also {
                it.title = title
                it.destination = PDPageXYZDestination().apply {
                    setPage(doc.getPage(page))
                    top = 700
                }
                parent.addLast(it)
            }
            item("Premier chapitre", 1, outline)
            // A bookmark to a page left out, with one under it to a page kept: that one moves up.
            val second = item("Deuxieme chapitre", 2, outline)
            item("Une section", 3, second)
            item("Troisieme chapitre", 0, outline)
        }
        val input = import(file)
        val outcome = translate(input, pages = listOf(1, 3))

        assertEquals(2, outcome.file.pageCount)
        assertTrue(textOf(outcome.file.file, 0).contains(marked("Chapitre deux 2")))
        assertTrue(textOf(outcome.file.file, 1).contains(marked("Chapitre quatre 4")))
        assertFalse(textOf(outcome.file.file).contains(marked("Chapitre un 1")))
        PDDocument.load(outcome.file.file).use { doc ->
            assertEquals(marked("Le livre des pages"), doc.documentInformation.title)
            assertNull("No author is kept", doc.documentInformation.author)
            assertEquals("en", doc.documentCatalog.language)
            val items = generateSequence(doc.documentCatalog.documentOutline.firstChild) { it.nextSibling }.toList()
            assertEquals(listOf(marked("Premier chapitre"), marked("Une section")), items.map { it.title })
            assertEquals(listOf(0, 1), items.map { doc.pages.indexOf((it.destination as PDPageDestination).page) })
            assertTrue("They keep where on the page they lead", items.all { (it.destination as PDPageXYZDestination).top == 700 })
        }
    }

    @Test fun theNewPdfKeepsNoFormsCommentsMetadataOrScripts() {
        val file = File(dir, "busy.pdf")
        // A page with filled form fields, comments and a link (see formPdf), then the article.
        PDDocument.load(formPdf(File(dir, "form.pdf"))).use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            article(page, doc)
            doc.documentCatalog.metadata = PDMetadata(doc).apply { importXMPMetadata("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">SECRET XMP</x:xmpmeta>".toByteArray()) }
            doc.documentCatalog.openAction = PDActionJavaScript("app.alert('OPEN SCRIPT')")
            doc.documentCatalog.cosObject.setItem(COSName.NAMES, COSDictionary().apply {
                setItem(COSName.JAVA_SCRIPT, COSDictionary().apply {
                    setItem(COSName.NAMES, COSArray().apply {
                        add(COSString("hello"))
                        add(PDActionJavaScript("app.alert('NAMED SCRIPT')").cosObject)
                    })
                })
            })
            doc.save(file)
        }
        val input = import(file)
        val outcome = translate(input)

        assertEquals(2, outcome.file.pageCount)
        PDDocument.load(outcome.file.file).use { doc ->
            val catalog = doc.documentCatalog.cosObject
            for (key in listOf(COSName.ACRO_FORM, COSName.METADATA, COSName.OPEN_ACTION, COSName.NAMES, COSName.OUTLINES, COSName.AA)) {
                assertFalse("$key is kept", catalog.containsKey(key))
            }
            for (page in doc.pages) assertFalse("A page keeps its comments", page.cosObject.containsKey(COSName.ANNOTS))
        }
        val everything = everythingIn(outcome.file.file)
        for (leak in listOf("JavaScript", "SCRIPT", "SECRET XMP", "COSName{Annot}", "COSName{Widget}")) assertFalse("\"$leak\" is in the new PDF", everything.contains(leak))
        // The field's value, which the page shows, is translated as the page's text.
        assertTrue(textOf(outcome.file.file, 0).contains(marked("Jane Doe")))
        assertTrue(textOf(outcome.file.file, 1).contains(marked(HEADING)))
    }

    @Test fun cancellingAtAnyPointLeavesNoFile() {
        val input = import(numberedPages(File(dir, "three.pdf"), 3))
        var completed = false
        var stopAt = 1
        while (!completed) {
            var calls = 0
            try {
                translate(input, check = { if (++calls >= stopAt) throw CancellationException("Cancelled by the test") })
                completed = true
            } catch (_: CancellationException) {
                assertTrue("Cancelled at check $stopAt: ${output.list()?.toList()}", output.list().isNullOrEmpty())
            }
            stopAt++
        }
        assertTrue("Stopped at ${stopAt - 1} checks at least", stopAt > 10)
        assertEquals(listOf("Translated.pdf"), output.list()?.toList())
    }

    @Test fun aParagraphTheTranslatorFailsOnIsLeftAsItWas() {
        val input = import(articlePdf())
        val failing = PARAGRAPH_TWO.joinToString(" ")
        val outcome = translate(input, translation = FakeEngine { if (it == failing) throw IllegalStateException("Can't translate this one") else marked(it) })
        assertEquals(listOf(0), outcome.partly)
        val text = textOf(outcome.file.file)
        assertFalse(text.contains(marked(failing)))
        assertTrue(text.contains(marked(HEADING)))
        // It is still on the picture: its lines aren't painted over.
        val page = render(outcome.file.file, 0)
        assertTrue(darkIn(page, 72f, 842f - 652f, 400f, 842f - 638f) > 100)
        page.recycle()
    }

    @Test fun aTranslationThePhoneHasNoFontForIsLeftAsItWas() {
        val input = import(articlePdf())
        // A character of a private use area no font draws.
        val outcome = translate(input, translation = FakeEngine { if (it == HEADING) "Titre 󰀀" else marked(it) })
        assertEquals(listOf(0), outcome.partly)
        assertFalse(textOf(outcome.file.file).contains("Titre"))
        assertTrue(textOf(outcome.file.file).contains(marked(CELLS[0])))
    }

    @Test fun languagePacksGoneMidwayEndTheRun() {
        val input = import(articlePdf())
        try {
            translate(input, translation = FakeEngine { throw TranslationUnavailableException(TranslationProblem.DOWNLOAD_FAILED) })
            fail("It should have stopped")
        } catch (e: TranslationUnavailableException) {
            assertEquals(TranslationProblem.DOWNLOAD_FAILED, e.problem)
        }
        assertTrue(output.list().isNullOrEmpty())
    }

    @Test fun aTurnedPageIsTranslatedAsItIsShown() {
        val file = File(dir, "turned.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).apply { rotation = 90 }
            doc.addPage(page)
            // Written up the page, so that it reads across the page once it is turned a quarter.
            PDPageContentStream(doc, page).use { c ->
                c.beginText()
                c.setFont(PDType1Font.HELVETICA, 14f)
                c.setTextMatrix(com.tom_roush.pdfbox.util.Matrix(0f, 1f, -1f, 0f, 100f, 72f))
                c.showText("Une page tournee pour la lire en largeur")
                c.endText()
            }
            doc.save(file)
        }
        val outcome = translate(import(file))
        PDDocument.load(outcome.file.file).use { doc ->
            val page = doc.getPage(0)
            assertEquals(0, page.rotation)
            assertEquals(PDRectangle.A4.height, page.mediaBox.width, 0.01f)
            assertEquals(PDRectangle.A4.width, page.mediaBox.height, 0.01f)
        }
        assertTrue(textOf(outcome.file.file).contains(marked("Une page tournee pour la lire en largeur")))
        // Across the shown page, where the line was: 72 points from the left, 100 down from the top... of the page as shown.
        val placed = glyphs(outcome.file.file, 0)
        assertTrue(placed.all { abs(it.textMatrix.shearY) < 0.01f })
        assertEquals(72f, placed.minOf { it.xDirAdj }, 3f)
    }

    @Test fun aProtectedPdfIsTranslated() {
        val file = File(dir, "protected.pdf")
        articlePdf("open.pdf").let { open ->
            PDDocument.load(open).use { doc ->
                doc.protect(StandardProtectionPolicy("owner", "user", AccessPermission()).apply { encryptionKeyLength = 128 })
                doc.save(file)
            }
        }
        val outcome = translate(import(file, "user"))
        PDDocument.load(outcome.file.file).use { assertFalse("The new PDF has no password", it.isEncrypted) }
        assertTrue(textOf(outcome.file.file).contains(marked(HEADING)))
    }

    @Test fun aPosterSizedPageFitsInMemory() {
        val file = File(dir, "poster.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(5000f, 5000f))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { c -> c.line(200f, 4600f, "Une affiche immense", 200f) }
            doc.save(file)
        }
        val outcome = translate(import(file))
        PDDocument.load(outcome.file.file).use { doc ->
            val page = doc.getPage(0)
            assertEquals(5000f, page.mediaBox.width, 0.01f)
            val image = page.resources.xObjectNames.map { page.resources.getXObject(it) }.filterIsInstance<PDImageXObject>().single()
            val pixels = image.width.toLong() * image.height
            assertTrue("$pixels pixels", pixels <= TranslatedPages.MAX_PIXELS && pixels > TranslatedPages.MAX_PIXELS * 0.99)
        }
        assertTrue(textOf(outcome.file.file).contains(marked("Une affiche immense")))
    }

    @Test fun picturesAndBlankPagesAreKeptAsTheyAre() {
        val file = File(dir, "pictures.pdf")
        PDDocument().use { doc ->
            // A photo, a blank page, a page with only a number, then text.
            doc.addPage(PDPage(PDRectangle.A4).also { page -> PDPageContentStream(doc, page).use { it.drawImage(noise(doc, 60, 40), 100f, 400f, 300f, 200f) } })
            doc.addPage(PDPage(PDRectangle.A4))
            doc.addPage(PDPage(PDRectangle.A4).also { page -> PDPageContentStream(doc, page).use { it.line(290f, 40f, "3") } })
            doc.addPage(PDPage(PDRectangle.A4).also { page -> PDPageContentStream(doc, page).use { it.line(72f, 700f, "Enfin du texte a traduire") } })
            doc.save(file)
        }
        val input = import(file)
        val ocr = FakeOcr(emptyList())
        val outcome = translate(input, ocr = ocr)
        assertEquals(4, outcome.file.pageCount)
        assertEquals(listOf(0, 1, 2), outcome.noText)
        assertEquals(emptyList<Int>(), outcome.unread + outcome.partly)
        // The photo and the number had text recognition look at them; the blank page didn't.
        assertEquals(1, ocr.readied)
        assertEquals(2, ocr.read)
        val photo = render(outcome.file.file, 0)
        // In the photo's middle, at (250, 342) points from the top-left corner, and beside it.
        assertTrue("The photo is on its page", Color.red(photo.getPixel(500, 684)) < 240 && Color.red(photo.getPixel(1000, 684)) > 250)
        photo.recycle()

        // Without anything to translate, there is no PDF. Pictures that text recognition can't read (Arabic) are unreadable.
        val none = import(File(dir, "none.pdf").also { f -> PDDocument.load(file).use { it.removePage(3); it.save(f) } })
        try {
            translate(none, ocr = ocr, source = "ar")
            fail("Nothing to translate")
        } catch (e: NothingToTranslate) {
            assertTrue(e.unreadable)
        }
        try {
            translate(none, ocr = ocr)
            fail("Nothing to translate")
        } catch (e: NothingToTranslate) {
            assertFalse(e.unreadable)
        }
        assertTrue(output.list().isNullOrEmpty())
    }

    @Test fun aScanIsReadWithTextRecognitionAndItsHiddenTextLayerIsIgnored() {
        val file = File(dir, "scan.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { c ->
                c.drawImage(noise(doc, 40, 60), 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
                // An earlier text recognition's layer: invisible.
                c.beginText()
                c.setFont(PDType1Font.HELVETICA, 12f)
                c.setRenderingMode(RenderingMode.NEITHER)
                c.newLineAtOffset(72f, 700f)
                c.showText("Couche cachee du scan")
                c.endText()
            }
            doc.save(file)
        }
        val input = import(file)
        val ocr = FakeOcr(listOf("Texte lu sur le scan" to listOf(0.12f, 0.1f, 0.6f, 0.13f)))
        val outcome = translate(input, ocr = ocr)
        assertEquals(emptyList<Int>(), outcome.noText + outcome.unread + outcome.partly)
        val text = textOf(outcome.file.file)
        assertTrue(text.contains(marked("Texte lu sur le scan")))
        assertFalse(text.contains(marked("Couche cachee du scan")))
        assertFalse(text.contains("Couche"))

        // Without text recognition for its script, the scan can't be read.
        try {
            translate(input, ocr = ocr, source = "ru")
            fail("Nothing to translate")
        } catch (e: NothingToTranslate) {
            assertTrue(e.unreadable)
        }
    }

    @Test fun aScanReadByTheRealTextRecognition() {
        val ocr = ocrEngine(context)!!
        try {
            runBlocking { ocr.ensureReady(OcrLanguage.ENGLISH) }
        } catch (e: OcrUnavailableException) {
            assumeTrue("No text recognition here (${e.problem})", false)
        }
        val file = File(dir, "real-scan.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val picture = createBitmap(1240, 1754).apply {
                eraseColor(Color.WHITE)
                Canvas(this).drawText("Bonjour tout le monde", 150f, 300f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = 64f
                    typeface = Typeface.SERIF
                    color = Color.BLACK
                })
            }
            val image = LosslessFactory.createFromImage(doc, picture)
            picture.recycle()
            PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height) }
            doc.save(file)
        }
        val outcome = translate(import(file), ocr = ocr)
        val text = textOf(outcome.file.file)
        assertTrue("Read: $text", text.contains("ruojnoB"))
    }

    @Test fun scannedLinesOfOneParagraphGetItsSize() {
        // Three lines of 11-point type as text recognition boxes them, from their tallest letters to
        // their lowest, in tenths of a point: capitals, then descenders too, then small letters alone.
        fun read(text: String, top: Int, height: Int) = OcrLine(text, listOf(Point(500, top), Point(4000, top), Point(4000, top + height), Point(500, top + height)))
        val read = listOf(read("Service des achats", 1000, 75), read("Paris, gâteaux et pains", 1140, 97), read("une rue en mer", 1300, 50))
        val lines = ScannedLines.of(read, 0.1f, 0.1f)
        assertEquals(1, lines.map { it.size }.distinct().size)
        assertEquals(11f, lines[0].size, 1f)
        // The small letters' line is as tall as a line of the font: its box reaches up for capitals.
        assertTrue(lines[2].top < 130f - 2f && lines[2].bottom >= 135f)
    }

    @Test fun manyPagesUseSeveralStampsAndKeepMemoryFlat() {
        val input = import(numberedPages(File(dir, "long.pdf"), 25))
        val heap = ArrayList<Long>()
        val native = ArrayList<Long>()
        val started = SystemClock.elapsedRealtime()
        val outcome = translate(input, progress = object : ArrayList<TranslateProgress>() {
            override fun add(element: TranslateProgress): Boolean {
                if (element.stage == TranslateProgress.Stage.PAGE && element.done in listOf(5, 24)) {
                    System.gc()
                    Runtime.getRuntime().let { heap += it.totalMemory() - it.freeMemory() }
                    native += Debug.getNativeHeapAllocatedSize()
                }
                return super.add(element)
            }
        })
        Log.i(TAG, "25 pages in ${SystemClock.elapsedRealtime() - started} ms; Java heap $heap, native heap $native")
        assertEquals(25, outcome.file.pageCount)
        for (n in 1..25) assertTrue("Page $n", textOf(outcome.file.file, n - 1).contains(marked("Ceci est le texte de la page numero $n du document.")))
        assertTrue("Java heap grew from ${heap[0]} to ${heap[1]}", heap[1] - heap[0] < 48L * 1024 * 1024)
        assertTrue("Native heap grew from ${native[0]} to ${native[1]}", native[1] - native[0] < 32L * 1024 * 1024)
    }

    @Test fun otherScriptsAreDrawnAsRealTextInTheirDirection() {
        val input = import(articlePdf())
        for ((tag, sentence) in listOf("hi" to "भारत एक देश है", "ar" to "مرحبا بالعالم", "zh" to "你好世界")) {
            val outcome = translate(input, target = tag, translation = FakeEngine { sentence })
            assertEquals(tag, emptyList<Int>(), outcome.partly)
            val text = textOf(outcome.file.file)
            for (word in sentence.split(' ')) assertTrue("$tag: \"$word\" isn't in $text", text.contains(word))
            if (tag == "ar") {
                // Right to left: the heading's translation, shorter than the heading, ends at the heading's right end.
                val heading = glyphs(outcome.file.file, 0).filter { it.yDirAdj < 842f - 740f }
                val right = PDType1Font.HELVETICA_BOLD.getStringWidth(HEADING) / 1000 * 20 + 72
                assertEquals(right, heading.maxOf { it.xDirAdj + it.widthDirAdj }, 4f)
                assertTrue(heading.minOf { it.xDirAdj } > 100f)
            }
        }
    }

    @Test fun theLanguageIsDetectedFromTheFirstPagesWithText() {
        val translation = FakeEngine().apply { detected = "fr" }
        val input = import(articlePdf())
        assertEquals("fr", runBlocking { engine.detectSourceLanguage(input, translation) {} })
        assertNotNull(translation.detectedFrom)
        assertTrue(translation.detectedFrom!!.contains(HEADING) && translation.detectedFrom!!.contains(PARAGRAPH_ONE[0]))

        val blank = import(File(dir, "blank.pdf").also { f -> PDDocument().use { it.addPage(PDPage()); it.save(f) } })
        val unasked = FakeEngine().apply { detected = "fr" }
        assertNull(runBlocking { engine.detectSourceLanguage(blank, unasked) {} })
        assertNull("Nothing to detect from", unasked.detectedFrom)
    }

    /** ML Kit's own translator, French to English (the first run downloads French's pack). The new PDF is kept in test-results. */
    @Test fun frenchToEnglishWithTheRealTranslator() {
        val translation = translationEngine(context)!!
        try {
            runBlocking { translation.ensureReady("fr", "en") }
        } catch (e: TranslationUnavailableException) {
            assumeTrue("No language pack without internet (${e.problem})", e.problem != TranslationProblem.NO_INTERNET && e.problem != TranslationProblem.DOWNLOAD_FAILED)
            throw e
        }
        val input = import(articlePdf("rapport.pdf"))
        val outcome = translate(input, translation = translation)
        outcome.file.file.copyTo(File(results, "translated-fr-en.pdf"), overwrite = true)
        val text = textOf(outcome.file.file)
        Log.i(TAG, "Translated: $text")
        assertTrue("No English in: $text", text.contains("annual report", ignoreCase = true))
        assertEquals(emptyList<Int>(), outcome.partly)
    }

    private companion object {
        const val TAG = "UsugamiTest"

        /** The made-up translation: the letters backwards, between marks. */
        fun marked(text: String) = "«" + text.reversed() + "»"

        const val HEADING = "Le rapport annuel"
        val PARAGRAPH_ONE = listOf(
            "Cette annee, notre entreprise a beaucoup grandi et nous avons ouvert",
            "trois nouveaux magasins dans le pays. Les ventes ont augmente de",
            "vingt pour cent et nos clients sont plus nombreux que jamais avant.",
            "Merci a toute notre equipe.",
        )
        val PARAGRAPH_TWO = listOf(
            "L'annee prochaine, nous voulons ouvrir deux autres magasins et",
            "proposer nos produits en ligne a tous nos clients du pays entier.",
        )
        val CELLS = listOf("Produit", "Quantite", "Prix", "Total")
        const val AMOUNT = "12.50"
        val WORDS = listOf("zero", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf")
    }
}
