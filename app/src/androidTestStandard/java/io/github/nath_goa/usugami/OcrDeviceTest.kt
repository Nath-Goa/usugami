package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfOcr
import io.github.nath_goa.usugami.data.ocrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.atan2

/**
 * Text recognition with Google ML Kit through Google Play services (standard edition only). Needs
 * Google Play services, and an internet connection the first time each model is downloaded. The
 * text layer itself is tested in PdfToolsDeviceTest, for every edition with text recognition.
 */
class OcrDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = ocrEngine(context)!!
    private val results = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }

    private fun fixture(name: String): File = File(context.cacheDir, name).also { target ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            target.outputStream().use(input::copyTo)
        }
    }

    private fun save(file: File, name: String): File = file.copyTo(File(results, name), overwrite = true)

    private fun text(file: File): String = PDDocument.load(file).use { PDFTextStripper().getText(it) }

    /** Every character of the text layer, with where and how it is drawn. */
    private fun glyphs(file: File): List<TextPosition> = ArrayList<TextPosition>().also { glyphs ->
        PDDocument.load(file).use { doc ->
            object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    glyphs += text
                }
            }.getText(doc)
        }
    }

    /** The angle of a character's baseline, in degrees anticlockwise. */
    private fun TextPosition.angle(): Double = Math.toDegrees(atan2(textMatrix.shearY, textMatrix.scaleX).toDouble())

    /** Play services downloads the model the first time (needs internet). */
    private suspend fun ensureOcrReady(language: OcrLanguage) {
        engine.ensureReady(language) { fraction -> Log.i(TAG, "Downloading $language text recognition: $fraction") }
    }

    @Test fun ocrEnglishAndHindi() = runBlocking {
        val ocr = PdfOcr(context)
        for ((language, fixtureName, expected) in listOf(
            Triple(OcrLanguage.ENGLISH, "english_image.pdf", "SCHOOL NOTES"),
            Triple(OcrLanguage.HINDI, "hindi_image.pdf", "भारत"),
        )) {
            ensureOcrReady(language)
            val original = fixture(fixtureName)
            val working = original.copyTo(File(context.filesDir, "ocr_${language.name}.pdf"), overwrite = true)
            assertTrue(text(working).isBlank())
            val pages = ArrayList<Int>()
            val output = ocr.apply(listOf(PdfFile(working, 1, original.length())), engine, language) { pages += it }
            assertTrue("No OCR lines for $language", output.lineCount > 0)
            assertEquals(listOf(1), pages)
            assertFalse("The original should be replaced", working.exists())
            val searchable = save(output.files.single().file, "ocr_${language.name.lowercase()}.pdf")
            assertEquals(1, PDDocument.load(searchable).use { it.numberOfPages })
            assertTrue("Missing $expected in ${text(searchable)}", text(searchable).contains(expected))
        }
    }

    /**
     * French, Spanish, German and Portuguese keep their accents, umlauts and ß: ML Kit's Latin model
     * reads them and the OCR font has them.
     */
    @Test fun ocrKeepsAccentsAndUmlauts() = runBlocking {
        val ocr = PdfOcr(context)
        for ((language, lines, expected) in listOf(
            Triple(OcrLanguage.FRENCH, listOf("Le garçon et l'élève", "fêtent Noël à Paris"), listOf("garçon", "élève", "Noël")),
            Triple(OcrLanguage.SPANISH, listOf("El niño pequeño", "cumple años mañana"), listOf("niño", "pequeño", "mañana")),
            Triple(OcrLanguage.GERMAN, listOf("Die Größe der Tür", "Übung für Mädchen"), listOf("Größe", "Tür", "Übung", "Mädchen")),
            // ML Kit reads "maçã" as "maçā" (a macron, U+0101); ç and ã are still checked in the other words.
            Triple(OcrLanguage.PORTUGUESE, listOf("A ação do coração", "não é só pão e maçã"), listOf("ação", "coração", "não", "pão")),
        )) {
            ensureOcrReady(language)
            val page = scannedText(lines, File(context.filesDir, "ocr_${language.name}.pdf"))
            assertTrue(text(page).isBlank())
            val output = ocr.apply(listOf(PdfFile(page, 1, page.length())), engine, language)
            val searchable = save(output.files.single().file, "ocr_${language.name.lowercase()}.pdf")
            val found = text(searchable)
            for (word in expected) assertTrue("Missing $word in $found", found.contains(word))
        }
    }

    /**
     * An image under 32 pixels on a side, which ML Kit refuses, is read padded rather than failing
     * (which would leave every PDF made with it without text).
     */
    @Test fun aTinyImageIsReadRatherThanRefused() = runBlocking {
        ensureOcrReady(OcrLanguage.ENGLISH)
        engine.open(OcrLanguage.ENGLISH).use { recognizer ->
            for ((width, height) in listOf(12 to 12, 800 to 10, 31 to 300)) {
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                assertTrue("A blank ${width}x$height image has no text", recognizer.recognize(image).isEmpty())
                assertTrue("Recycled once read", image.isRecycled)
            }
        }
    }

    /** A photo made into a PDF at Extreme is read as picked, and its text goes over its lines. */
    @Test fun ocrOfPhotoReadsTheOriginal() = runBlocking {
        val dir = File(context.filesDir, "ocr_photo").apply { deleteRecursively(); mkdirs() }
        try {
            PhotoOcrCheck.run(context, engine, dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** A photo cropped to the page on it is read as cropped: what lies beside the page is left out. */
    @Test fun ocrOfACroppedPhotoReadsThePage() = runBlocking {
        CroppedPhotoOcrCheck.run(context, engine, File(context.filesDir, "ocr_cropped"))
    }

    /** A page in black and white, a bit a pixel, still gets its text layer, and reads as the PDF draws it. */
    @Test fun ocrOfABlackAndWhitePageReadsIt() = runBlocking {
        BlackAndWhiteOcrCheck.run(context, engine, File(context.filesDir, "ocr_black_and_white"))
    }

    /** An ID card's two sides on one page are both read, the page reported once, each side's text over it. */
    @Test fun ocrOfAnIdCardReadsBothSides() = runBlocking {
        IdCardOcrCheck.run(context, engine, File(context.filesDir, "ocr_id_card"))
    }

    /** A one-page PDF that holds [lines] only as a picture, like a scan. */
    private fun scannedText(lines: List<String>, file: File): File {
        val bitmap = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 64f
            typeface = Typeface.SANS_SERIF
        }
        val canvas = Canvas(bitmap)
        lines.forEachIndexed { index, line -> canvas.drawText(line, 120f, 300f + index * 110f, paint) }
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        page.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, 595f, 842f), null)
        document.finishPage(page)
        file.outputStream().use(document::writeTo)
        document.close()
        bitmap.recycle()
        return file
    }

    @Test fun ocrFailureLeavesPdfsUntouched() = runBlocking {
        ensureOcrReady(OcrLanguage.ENGLISH)
        val dir = File(context.filesDir, "ocr_failure").apply { deleteRecursively(); mkdirs() }
        val good = fixture("english_image.pdf").copyTo(File(dir, "good.pdf"))
        val broken = File(dir, "broken.pdf").apply { writeText("not a PDF") }
        try {
            PdfOcr(context).apply(listOf(PdfFile(good, 1, good.length()), PdfFile(broken, 1, broken.length())), engine, OcrLanguage.ENGLISH)
            fail("The broken file should make OCR fail")
        } catch (_: Exception) {
        }
        assertEquals(setOf("good.pdf", "broken.pdf"), dir.list()!!.toSet())
        assertTrue(text(good).isBlank())
    }

    /** Cancelling while a page is being read stops OCR and leaves the PDFs as they were. */
    @Test fun ocrCancelLeavesPdfsUntouched() = runBlocking {
        ensureOcrReady(OcrLanguage.ENGLISH)
        val dir = File(context.filesDir, "ocr_cancel").apply { deleteRecursively(); mkdirs() }
        val image = fixture("english_image.pdf")
        val files = (1..3).map { image.copyTo(File(dir, "page$it.pdf")) }
        val job = launch(Dispatchers.IO) {
            PdfOcr(context).apply(files.map { PdfFile(it, 1, it.length()) }, engine, OcrLanguage.ENGLISH)
        }
        delay(300) // well within reading the first of the three pages
        job.cancelAndJoin()
        assertTrue("OCR should have been cancelled, not finished", job.isCancelled)
        assertEquals(setOf("page1.pdf", "page2.pdf", "page3.pdf"), dir.list()!!.toSet())
        files.forEach { assertTrue(text(it).isBlank()) }
        // ML Kit may still be reading the cancelled page's bitmap; that must not crash the app.
        delay(2000)
    }

    /** The same, end to end: ML Kit's corner points on a page photographed at an angle. */
    @Test fun ocrOfTiltedPageFollowsItsLines() = runBlocking {
        ensureOcrReady(OcrLanguage.ENGLISH)
        val dir = File(context.filesDir, "ocr_tilted").apply { deleteRecursively(); mkdirs() }
        val upright = fixture("english_image.pdf").copyTo(File(dir, "upright.pdf"))
        val tilted = File(dir, "tilted.pdf")
        // The fixture's page turned 8° anticlockwise.
        ParcelFileDescriptor.open(upright, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val document = PdfDocument()
                    val out = document.startPage(PdfDocument.PageInfo.Builder(page.width, page.height, 1).create())
                    out.canvas.drawColor(Color.WHITE)
                    out.canvas.rotate(-8f, page.width / 2f, page.height / 2f)
                    out.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), null)
                    document.finishPage(out)
                    tilted.outputStream().use(document::writeTo)
                    document.close()
                    bitmap.recycle()
                }
            }
        }
        val ocr = PdfOcr(context)
        val uprightGlyphs = glyphs(ocr.apply(listOf(PdfFile(upright, 1, upright.length())), engine, OcrLanguage.ENGLISH).files.single().file)
        val tiltedFile = save(ocr.apply(listOf(PdfFile(tilted, 1, tilted.length())), engine, OcrLanguage.ENGLISH).files.single().file, "ocr_tilted_page.pdf")
        val tiltedGlyphs = glyphs(tiltedFile)
        val tiltedText = tiltedGlyphs.joinToString("") { it.unicode }
        assertTrue("Missing THE QUICK BROWN FOX in $tiltedText", tiltedText.contains("THE QUICK BROWN FOX"))
        for (glyph in tiltedGlyphs) assertEquals("Angle of \"${glyph.unicode}\"", 8.0, glyph.angle(), 3.0)
        // The tilt doesn't change the size of the text (upright bounds made it 3 times as big).
        fun firstLineSize(glyphs: List<TextPosition>) = glyphs.first { it.unicode == "T" }.fontSize
        assertEquals(1f, firstLineSize(tiltedGlyphs) / firstLineSize(uprightGlyphs), 0.2f)
    }

    private companion object {
        const val TAG = "UsugamiTest"
    }
}
