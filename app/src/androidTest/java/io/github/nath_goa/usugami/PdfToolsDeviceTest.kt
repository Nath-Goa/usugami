package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.PdfCertificateEncrypted
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfOcr
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.PdfPasswordRequired
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.PdfWrongPassword
import io.github.nath_goa.usugami.data.ocrModels
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

class PdfToolsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)
    private val results = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }

    private fun fixture(name: String): File = File(context.cacheDir, name).also { target ->
        javaClass.classLoader!!.getResourceAsStream("assets/$name")?.use { input ->
            target.outputStream().use(input::copyTo)
        } ?: InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            target.outputStream().use(input::copyTo)
        }
    }

    private fun save(file: File, name: String): File = file.copyTo(File(results, name), overwrite = true)

    private fun text(file: File): String = PDDocument.load(file).use { PDFTextStripper().getText(it) }

    private fun blankA4(name: String): File = File(context.filesDir, name).also { file ->
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.save(file)
        }
    }

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

    @Test fun splitMergeCompressAndPasswords() {
        val alpha = engine.import(Uri.fromFile(fixture("alpha.pdf")), 1001)
        val beta = engine.import(Uri.fromFile(fixture("beta.pdf")), 1002)
        assertEquals(3, alpha.pageCount)
        assertEquals(2, beta.pageCount)

        val selected = save(engine.split(alpha, listOf(listOf(0, 2)), "selected").single().file, "split_selected.pdf")
        assertEquals(2, PDDocument.load(selected).use { it.numberOfPages })
        assertTrue(text(selected).contains("ALPHA ONE"))
        assertTrue(text(selected).contains("ALPHA THREE"))
        assertFalse(text(selected).contains("ALPHA TWO"))

        val separate = engine.split(alpha, listOf(listOf(0, 1), listOf(2)), "chunks")
        assertEquals(listOf(2, 1), separate.map { it.pageCount })
        separate.forEachIndexed { index, file -> save(file.file, "split_chunk_${index + 1}.pdf") }

        val merged = save(engine.merge(listOf(beta, alpha), null, "merged").file, "merged.pdf")
        assertEquals(5, PDDocument.load(merged).use { it.numberOfPages })
        assertTrue(text(merged).indexOf("BETA ONE") < text(merged).indexOf("ALPHA ONE"))

        val pages = listOf(PdfPageRef(alpha.id, 2, 90), PdfPageRef(beta.id, 0), PdfPageRef(alpha.id, 0))
        val edited = save(engine.merge(listOf(alpha, beta), pages, "edited").file, "merge_pages.pdf")
        assertEquals(3, PDDocument.load(edited).use { it.numberOfPages })
        assertEquals(90, PDDocument.load(edited).use { it.getPage(0).rotation })
        val order = text(edited)
        assertTrue(order.indexOf("ALPHA THREE") < order.indexOf("BETA ONE"))
        assertTrue(order.indexOf("BETA ONE") < order.indexOf("ALPHA ONE"))

        val textCompressed = save(engine.compress(alpha, PdfCompression.EXTREME, "text").file, "compress_text.pdf")
        assertTrue(textCompressed.length() <= alpha.file.length())
        assertTrue(text(textCompressed).contains("ALPHA THREE"))

        val scanned = engine.import(Uri.fromFile(fixture("scanned.pdf")), 1003)
        for (level in PdfCompression.entries) {
            val compressed = save(engine.compress(scanned, level, "scan").file, "compress_${level.name.lowercase()}.pdf")
            assertEquals(1, PDDocument.load(compressed).use { it.numberOfPages })
            assertTrue(text(compressed).contains("Keep this selectable caption"))
            assertTrue(compressed.length() <= scanned.file.length())
        }
        val pngScan = engine.import(Uri.fromFile(fixture("png_scanned.pdf")), 1005)
        val pngCompressed = save(engine.compress(pngScan, PdfCompression.RECOMMENDED, "png").file, "compress_png.pdf")
        assertTrue(pngCompressed.length() < pngScan.file.length())
        assertEquals(1, PDDocument.load(pngCompressed).use { it.numberOfPages })

        val encrypted = fixture("protected.pdf")
        try {
            engine.import(Uri.fromFile(encrypted), 1004)
            fail("Password request expected")
        } catch (e: PdfPasswordRequired) {
            assertTrue("The copy is kept for the password dialog", e.input.file.isFile)
            try {
                engine.unlock(e.input, "wrong")
                fail("A wrong password should be refused")
            } catch (_: PdfWrongPassword) {
            }
            val unlocked = engine.unlock(e.input, "test123")
            assertEquals(3, unlocked.pageCount)
            val split = save(engine.split(unlocked, listOf(listOf(1)), "protected_split").single().file, "protected_split.pdf")
            assertTrue(text(split).contains("ALPHA TWO"))
            val unlockedCompressed = save(engine.compress(unlocked, PdfCompression.LESS, "protected_compressed").file, "protected_compressed.pdf")
            assertTrue(text(unlockedCompressed).contains("ALPHA ONE"))
        }
    }

    /**
     * certificate.pdf is alpha.pdf encrypted for a certificate (Adobe.PubSec) instead of a password,
     * made by app/src/androidTest/fixtures/make_certificate_pdf.py. PDFBox needs BouncyCastle for
     * those, which isn't bundled, so the PDF must be refused with a clear error, not a crash.
     */
    @Test fun certificateEncryptedPdfIsRefused() {
        try {
            engine.import(Uri.fromFile(fixture("certificate.pdf")), 1006)
            fail("Certificate-encrypted PDFs should be refused")
        } catch (_: PdfCertificateEncrypted) {
        }
        assertFalse("The copy should be removed", File(context.cacheDir, "pdf-inputs/1006.pdf").exists())
    }

    /** Leaving a tool stops a slow import part way, and its partial copy goes. */
    @Test fun importCanBeStoppedPartWay() {
        class Stop : RuntimeException()
        val source = fixture("scanned.pdf") // several 64 KB chunks
        var chunks = 0
        try {
            engine.import(Uri.fromFile(source), 1007) { if (++chunks == 3) throw Stop() }
            fail("The import should have been stopped")
        } catch (_: Stop) {
        }
        assertEquals(3, chunks)
        assertFalse("The partial copy should be removed", File(context.cacheDir, "pdf-inputs/1007.pdf").exists())
    }

    /** Standard edition: Play services downloads the model the first time (needs internet). */
    private suspend fun ensureOcrReady(language: OcrLanguage) {
        ocrModels(context).ensureReady(language) { fraction -> Log.i(TAG, "Downloading $language text recognition: $fraction") }
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
            val output = ocr.apply(listOf(PdfFile(working, 1, original.length())), language) { pages += it }
            assertTrue("No OCR lines for $language", output.lineCount > 0)
            assertEquals(listOf(1), pages)
            assertFalse("The original should be replaced", working.exists())
            val searchable = save(output.files.single().file, "ocr_${language.name.lowercase()}.pdf")
            assertEquals(1, PDDocument.load(searchable).use { it.numberOfPages })
            assertTrue("Missing $expected in ${text(searchable)}", text(searchable).contains(expected))
        }
    }

    @Test fun ocrFailureLeavesPdfsUntouched() = runBlocking {
        ensureOcrReady(OcrLanguage.ENGLISH)
        val dir = File(context.filesDir, "ocr_failure").apply { deleteRecursively(); mkdirs() }
        val good = fixture("english_image.pdf").copyTo(File(dir, "good.pdf"))
        val broken = File(dir, "broken.pdf").apply { writeText("not a PDF") }
        try {
            PdfOcr(context).apply(listOf(PdfFile(good, 1, good.length()), PdfFile(broken, 1, broken.length())), OcrLanguage.ENGLISH)
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
            PdfOcr(context).apply(files.map { PdfFile(it, 1, it.length()) }, OcrLanguage.ENGLISH)
        }
        delay(300) // well within reading the first of the three pages
        job.cancelAndJoin()
        assertTrue("OCR should have been cancelled, not finished", job.isCancelled)
        assertEquals(setOf("page1.pdf", "page2.pdf", "page3.pdf"), dir.list()!!.toSet())
        files.forEach { assertTrue(text(it).isBlank()) }
        // ML Kit may still be reading the cancelled page's bitmap; that must not crash the app.
        delay(2000)
    }

    /** Hindi mode recognizes Latin text too; the text layer must keep both scripts. */
    @Test fun textLayerKeepsDevanagariAndLatin() = runBlocking {
        val blank = blankA4("blank.pdf")
        // A4 page rendered at 2.0 scale: 1190 × 1684 pixels.
        val lines = listOf(
            "भारत India 2024 test@example.com",
            "क्षत्रिय श्री ₹500 Hello, World!",
        ).mapIndexed { index, line -> PdfOcr.Line(0, line, Rect(100, 100 + index * 80, 1000, 150 + index * 80), 1190, 1684) }
        val output = PdfOcr(context).embed(PdfFile(blank, 1, blank.length()), lines)
        assertTrue("The source should be left alone", blank.exists())
        val extracted = text(save(output.file, "ocr_mixed_scripts.pdf"))
        for (expected in listOf("भारत India 2024 test@example.com", "क्षत्रिय श्री ₹500 Hello, World!")) {
            assertTrue("Missing \"$expected\" in \"$extracted\"", extracted.contains(expected))
        }
    }

    /** A line on a tilted photo: the text follows the line's angle and height, not its upright bounds. */
    @Test fun textLayerFollowsTiltedLines() = runBlocking {
        val blank = blankA4("tilted.pdf")
        // A 600 × 40 pixel line rising at 30° on the A4 page rendered at 1190 × 1684 pixels. Image y
        // points down, so "along" and "up" have negative y.
        val angle = Math.toRadians(30.0)
        fun at(along: Double, up: Double) = Point(
            (200 + along * cos(angle) - up * sin(angle)).roundToInt(),
            (900 - along * sin(angle) - up * cos(angle)).roundToInt(),
        )
        val corners = listOf(at(0.0, 40.0), at(600.0, 40.0), at(600.0, 0.0), at(0.0, 0.0))
        val bounds = Rect(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
        val words = "This line was photographed at an angle"
        val line = PdfOcr.Line(0, words, bounds, 1190, 1684, corners)
        val output = PdfOcr(context).embed(PdfFile(blank, 1, blank.length()), listOf(line))

        val glyphs = glyphs(save(output.file, "ocr_tilted_line.pdf"))
        assertEquals(words, glyphs.joinToString("") { it.unicode })
        val scale = PDRectangle.A4.height / 1684 // points per pixel
        val first = glyphs.first().textMatrix
        val last = glyphs.last().textMatrix
        // Starts at the bottom-left corner...
        assertEquals(200 * scale, first.translateX, 1f)
        assertEquals(PDRectangle.A4.height - 900 * scale, first.translateY, 1f)
        // ...runs at the line's angle, along most of its 600 pixels...
        val dx = last.translateX - first.translateX
        val dy = last.translateY - first.translateY
        assertEquals(30.0, Math.toDegrees(atan2(dy, dx).toDouble()), 1.0)
        assertEquals(30.0, glyphs.first().angle(), 1.0)
        assertTrue("Text should span the line", hypot(dx, dy) in 500 * scale..600 * scale)
        // ...and is as tall as the line (upright bounds would make it about 8 times taller).
        assertEquals(40 * scale * 0.9f, glyphs.first().fontSize, 0.5f)
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
        val uprightGlyphs = glyphs(ocr.apply(listOf(PdfFile(upright, 1, upright.length())), OcrLanguage.ENGLISH).files.single().file)
        val tiltedFile = save(ocr.apply(listOf(PdfFile(tilted, 1, tilted.length())), OcrLanguage.ENGLISH).files.single().file, "ocr_tilted_page.pdf")
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
