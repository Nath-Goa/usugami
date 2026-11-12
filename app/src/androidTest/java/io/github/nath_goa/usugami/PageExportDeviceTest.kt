package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import io.github.nath_goa.usugami.core.ExportImageFormat
import io.github.nath_goa.usugami.core.ExportImageSize
import io.github.nath_goa.usugami.core.ExportImages
import io.github.nath_goa.usugami.data.ExportProgress
import io.github.nath_goa.usugami.data.ImagesOutcome
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.pagesToImages
import java.io.File
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** PDF to images or text in the engine: pages drawn as pictures (tests 1-3; the text's come with it). */
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
}
