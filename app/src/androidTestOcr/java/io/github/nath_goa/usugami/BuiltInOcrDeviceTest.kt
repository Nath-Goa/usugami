package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfOcr
import io.github.nath_goa.usugami.data.ocrEngine
import io.github.nath_goa.usugami.ocr.NcnnNet
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Usugami OCR's built-in text recognition (PaddleOCR models on ncnn), end to end: the native library
 * loads (also on 16 KB-page devices), the models read the test PDFs, and the text layer holds what
 * they read. Logs the time per page.
 */
class BuiltInOcrDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = ocrEngine(context)!!
    private val dir = File(context.filesDir, "builtin_ocr").apply { deleteRecursively(); mkdirs() }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun fixture(name: String): File = File(dir, name).also { target ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            target.outputStream().use(input::copyTo)
        }
    }

    /** The text layer's characters, with where and how each is drawn. */
    private fun glyphs(file: File): List<TextPosition> = ArrayList<TextPosition>().also { glyphs ->
        PDDocument.load(file).use { doc ->
            object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    glyphs += text
                }
            }.getText(doc)
        }
    }

    private fun text(file: File): String = PDDocument.load(file).use { PDFTextStripper().getText(it) }

    /** Runs the whole pipeline on [file] and returns the PDF with its text layer. */
    private suspend fun ocr(file: File, language: OcrLanguage): File {
        engine.ensureReady(language)
        val start = SystemClock.elapsedRealtime()
        val output = PdfOcr(context).apply(listOf(PdfFile(file, 1, file.length())), engine, language)
        Log.i(TAG, "${file.name} ($language): ${output.lineCount} lines in ${SystemClock.elapsedRealtime() - start} ms for 1 page")
        assertTrue("Nothing read in ${file.name}", output.lineCount > 0)
        return output.files.single().file
    }

    @Test fun readsEnglishAndHindiPdfs() = runBlocking {
        for ((name, language, words) in listOf(
            Triple("english_image.pdf", OcrLanguage.ENGLISH, listOf("THE", "QUICK", "BROWN", "FOX", "SCHOOL", "NOTES", "12345")),
            Triple("hindi_image.pdf", OcrLanguage.HINDI, listOf("नमस्ते", "दुनिया", "भारत", "मेरा", "देश", "है")),
        )) {
            val found = text(ocr(fixture(name), language))
            Log.i(TAG, "$name read as: ${found.trim().replace('\n', '|')}")
            val read = found.split(Regex("\\s+")).toSet()
            for (word in words) assertTrue("Missing $word in $found", word in read)
        }
    }

    /** A page photographed upside down is read the right way up, and its text layer is turned to match. */
    @Test fun readsAnUpsideDownPage() = runBlocking {
        val upright = fixture("english_image.pdf")
        val upsideDown = File(dir, "upside_down.pdf")
        ParcelFileDescriptor.open(upright, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val document = PdfDocument()
                    val out = document.startPage(PdfDocument.PageInfo.Builder(page.width, page.height, 1).create())
                    out.canvas.rotate(180f, page.width / 2f, page.height / 2f)
                    out.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), null)
                    document.finishPage(out)
                    upsideDown.outputStream().use(document::writeTo)
                    document.close()
                    bitmap.recycle()
                }
            }
        }
        val glyphs = glyphs(ocr(upsideDown, OcrLanguage.ENGLISH))
        val found = glyphs.joinToString("") { it.unicode }
        assertTrue("Missing SCHOOL NOTES 12345 in $found", found.contains("SCHOOL NOTES 12345"))
        // The first line read is the page's first line (now at the bottom), drawn upside down.
        assertTrue("Read in the wrong order: $found", found.indexOf("FOX") < found.indexOf("SCHOOL"))
        for (glyph in glyphs) {
            val angle = Math.toDegrees(atan2(glyph.textMatrix.shearY, glyph.textMatrix.scaleX).toDouble())
            assertEquals("Angle of \"${glyph.unicode}\"", 180.0, abs(angle), 3.0)
        }
    }

    /** The native bridge turns bad input into exceptions instead of crashing the app. */
    @Test fun nativeBridgeRejectsBadInput() {
        val net = NcnnNet.load(context.assets, "ocr/PP-OCRv5_mobile_det.ncnn.param", "ocr/PP-OCRv5_mobile_det.ncnn.bin", reuseMemory = true)
        val mean = floatArrayOf(0f, 0f, 0f)
        val norm = floatArrayOf(1f, 1f, 1f)
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            val output = net.runToBytes(NcnnNet.Input(bitmap, 64, 64, mean, norm))
            assertEquals(64 * 64, output.values.size)
            net.trim() // the next run still works
            assertEquals(64 * 64, net.runToBytes(NcnnNet.Input(bitmap, 64, 64, mean, norm)).values.size)
            assertThrows(IllegalArgumentException::class.java) { net.run(NcnnNet.Input(bitmap, 0, 64, mean, norm)) }
            assertThrows(IllegalArgumentException::class.java) { net.run(NcnnNet.Input(bitmap, 64, 100_000, mean, norm)) }
            assertThrows(IllegalArgumentException::class.java) { net.run(NcnnNet.Input(bitmap, 64, 64, floatArrayOf(0f), norm)) }
            val small = bitmap.copy(Bitmap.Config.RGB_565, false)
            assertThrows(IllegalArgumentException::class.java) { net.run(NcnnNet.Input(small, 64, 64, mean, norm)) }
            small.recycle()
            val recycled = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { recycle() }
            assertThrows(IllegalArgumentException::class.java) { net.run(NcnnNet.Input(recycled, 8, 8, mean, norm)) }
        } finally {
            net.close()
        }
        assertThrows(IllegalStateException::class.java) { net.run(NcnnNet.Input(bitmap, 64, 64, mean, norm)) }
        net.close() // twice is fine
        net.trim()
        bitmap.recycle()
        assertThrows(java.io.IOException::class.java) { NcnnNet.load(context.assets, "ocr/missing.param", "ocr/missing.bin", reuseMemory = false) }
    }

    private companion object {
        const val TAG = "UsugamiTest"
    }
}
