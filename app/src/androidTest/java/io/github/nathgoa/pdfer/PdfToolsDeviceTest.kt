package io.github.nathgoa.pdfer

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nathgoa.pdfer.data.OcrLanguage
import io.github.nathgoa.pdfer.data.PdfCompression
import io.github.nathgoa.pdfer.data.PdfFile
import io.github.nathgoa.pdfer.data.PdfOcr
import io.github.nathgoa.pdfer.data.PdfPageRef
import io.github.nathgoa.pdfer.data.PdfPasswordRequired
import io.github.nathgoa.pdfer.data.PdfToolsEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
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

    @Test fun offlineOcrEnglishAndHindi() = runBlocking {
        val ocr = PdfOcr(context)
        for ((language, fixtureName, expected) in listOf(
            Triple(OcrLanguage.ENGLISH, "english_image.pdf", "SCHOOL NOTES"),
            Triple(OcrLanguage.HINDI, "hindi_image.pdf", "भारत"),
        )) {
            val original = fixture(fixtureName)
            val working = original.copyTo(File(context.filesDir, "ocr_${language.name}.pdf"), overwrite = true)
            assertTrue(text(working).isBlank())
            val output = ocr.apply(listOf(PdfFile(working, 1, original.length())), language)
            assertTrue("No OCR lines for $language", output.lineCount > 0)
            val searchable = save(output.files.single().file, "ocr_${language.name.lowercase()}.pdf")
            assertEquals(1, PDDocument.load(searchable).use { it.numberOfPages })
            assertTrue("Missing $expected in ${text(searchable)}", text(searchable).contains(expected))
        }
    }
}
