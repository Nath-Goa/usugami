package io.github.nath_goa.pdfer

import android.graphics.Rect
import android.net.Uri
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.pdfer.data.OcrLanguage
import io.github.nath_goa.pdfer.data.PdfCertificateEncrypted
import io.github.nath_goa.pdfer.data.PdfCompression
import io.github.nath_goa.pdfer.data.PdfFile
import io.github.nath_goa.pdfer.data.PdfOcr
import io.github.nath_goa.pdfer.data.PdfPageRef
import io.github.nath_goa.pdfer.data.PdfPasswordRequired
import io.github.nath_goa.pdfer.data.PdfToolsEngine
import io.github.nath_goa.pdfer.data.ocrModels
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

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
            val unlocked = engine.inspect(e.input, "test123")
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

    /** Hindi mode recognizes Latin text too; the text layer must keep both scripts. */
    @Test fun textLayerKeepsDevanagariAndLatin() = runBlocking {
        val blank = File(context.filesDir, "blank.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.save(blank)
        }
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

    private companion object {
        const val TAG = "PdferTest"
    }
}
