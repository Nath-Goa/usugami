package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfOcr
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.ocrEngine
import io.github.nath_goa.usugami.data.pagesToText
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

    /**
     * A word as tall as a poster's, which detection at full size finds only in pieces or not at all,
     * is read whole, without the pieces. Also on a postcard (4 x 6 inches), which is read at only
     * 1080 x 720 pixels: its word is still taller than detection at full size finds.
     */
    @Test fun readsAPostersLargeWord() = runBlocking {
        val poster = picturePage("poster.pdf", 1600, 1200, 800, 600, listOf(Triple("SALE", 560f, 680f), Triple("all week", 100f, 1000f)))
        assertEquals(listOf("SALE", "all week"), text(ocr(poster, OcrLanguage.ENGLISH)).lines().map(String::trim).filter(String::isNotEmpty))
        val postcard = picturePage("postcard.pdf", 1080, 720, 432, 288, listOf(Triple("Hello", 400f, 400f), Triple("from the seaside", 50f, 600f)))
        assertEquals(listOf("Hello", "from the seaside"), text(ocr(postcard, OcrLanguage.ENGLISH)).lines().map(String::trim).filter(String::isNotEmpty))
    }

    /**
     * German and Portuguese are read by the latin model with their umlauts, ß, tildes and cedillas,
     * which the text layer keeps (each language finds its model's files).
     */
    @Test fun readsGermanAndPortugueseLetters() = runBlocking {
        val lines = listOf("Die Größe der Tür", "Übung für Mädchen", "A ação do coração", "não é só pão e maçã")
            .mapIndexed { index, line -> Triple(line, 72f, 240f + index * 240f) }
        val words = listOf("Größe", "Tür", "Übung", "Mädchen", "ação", "coração", "não", "maçã")
        for (language in listOf(OcrLanguage.GERMAN, OcrLanguage.PORTUGUESE)) {
            // Made for each language: the PDF read is replaced by the one with the text layer.
            val page = picturePage("letters_${language.name}.pdf", 1600, 1200, 800, 600, lines)
            val found = text(ocr(page, language))
            Log.i(TAG, "letters_${language.name}.pdf read as: ${found.trim().replace('\n', '|')}")
            val read = found.split(Regex("\\s+")).toSet()
            for (word in words) assertTrue("$language: missing $word in $found", word in read)
        }
    }

    /**
     * A PDF of one [pageWidth] x [pageHeight] page showing a [width] x [height] picture of [lines]
     * (each its text, size and baseline, centred in bold), so that the PDF has no text of its own.
     */
    private fun picturePage(name: String, width: Int, height: Int, pageWidth: Int, pageHeight: Int, lines: List<Triple<String, Float, Float>>): File {
        val picture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(picture)
        canvas.drawColor(Color.WHITE)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        for ((text, size, baseline) in lines) {
            ink.textSize = size
            canvas.drawText(text, width / 2f, baseline, ink)
        }
        val file = File(dir, name)
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
        page.canvas.drawBitmap(picture, null, RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()), null)
        document.finishPage(page)
        file.outputStream().use(document::writeTo)
        document.close()
        picture.recycle()
        return file
    }

    /**
     * PDF to images or text: a page that is only a picture of large printed words has no text of
     * its own, so it is read with text recognition, and the text file has its words.
     */
    @Test fun aScannedPageIsReadForItsText() = runBlocking {
        val scan = picturePage("scanned_text.pdf", 1600, 1200, 800, 600, listOf(Triple("INVOICE", 140f, 300f), Triple("Total due today", 100f, 700f)))
        val tools = PdfToolsEngine(context)
        val input = tools.import(Uri.fromFile(scan), 7901L)
        try {
            val outcome = tools.pagesToText(input, listOf(0), engine, OcrLanguage.ENGLISH, "Scan", {}) {}
            assertEquals(listOf(0), outcome.read)
            val found = outcome.file.file.readText()
            Log.i(TAG, "scanned_text.pdf exported as: ${found.trim().replace('\n', '|')}")
            val read = found.split(Regex("\\s+")).toSet()
            for (word in listOf("INVOICE", "Total", "due", "today")) assertTrue("Missing $word in $found", word in read)
        } finally {
            tools.clearOutput()
            tools.delete(input)
        }
    }

    /** A photo made into a PDF at Extreme is read as picked, and its text goes over its lines. */
    @Test fun readsAPhotoAsPicked() = runBlocking {
        PhotoOcrCheck.run(context, engine, dir)
    }

    /** A photo cropped to the page on it is read as cropped: what lies beside the page is left out. */
    @Test fun readsACroppedPhotosPage() = runBlocking {
        CroppedPhotoOcrCheck.run(context, engine, File(dir, "cropped"))
    }

    /** A page in black and white, a bit a pixel, still gets its text layer, and reads as the PDF draws it. */
    @Test fun readsABlackAndWhitePage() = runBlocking {
        BlackAndWhiteOcrCheck.run(context, engine, File(dir, "black-and-white"))
    }

    /** An ID card's two sides on one page are both read, the page reported once, each side's text over it. */
    @Test fun readsBothSidesOfAnIdCard() = runBlocking {
        IdCardOcrCheck.run(context, engine, File(dir, "id-card"))
    }

    /** The native bridge turns bad input into exceptions instead of crashing the app. */
    /**
     * Every character the models' dictionaries can give has a glyph in the text layer's font, rather
     * than becoming a space, and those beyond U+FFFF, which it can't have, are written in their
     * compatibility form. The text layer is then saved with them, and searchable.
     */
    @Test fun everyCharacterTheModelsCanReadIsWritten() = runBlocking {
        // Made first: it readies PDFBox's resources, which loading the font needs.
        val ocr = PdfOcr(context)
        val blank = File(dir, "blank.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.save(blank)
        }
        PDDocument.load(blank).use { doc ->
            val font = context.assets.open("fonts/UsugamiOcr-Regular.ttf").use { PDType0Font.load(doc, it) }
            for (dictionary in listOf("latin", "devanagari")) {
                val characters = context.assets.open("ocr/${dictionary}_PP-OCRv5_mobile_rec.dict.txt").bufferedReader().use { it.readLines() }
                    .flatMap { line -> line.codePoints().toArray().map { String(Character.toChars(it)) } }
                    .filter { it.isNotBlank() }
                val spaces = characters.filter { ocr.printable(font, it).isBlank() }
                assertEquals("Written as spaces from the $dictionary dictionary", emptyList<String>(), spaces)
            }
        }
        val text = "\u2460 Install \u2461 Open \u2300 12 mm \uD835\uDC62"
        val output = ocr.embed(PdfFile(blank, 1, blank.length()), listOf(PdfOcr.Line(0, text, Rect(100, 100, 1000, 150), 1190, 1684)))
        val extracted = PDDocument.load(output.file).use { PDFTextStripper().getText(it) }
        assertTrue(extracted, extracted.contains("\u2460 Install \u2461 Open \u2300 12 mm u"))
    }

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
