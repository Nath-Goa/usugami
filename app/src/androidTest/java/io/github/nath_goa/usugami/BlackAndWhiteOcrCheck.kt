package io.github.nath_goa.usugami

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.core.Margin
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.core.PageOrientation
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.OcrEngine
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.PdfExporter
import io.github.nath_goa.usugami.data.PdfOcr
import org.junit.Assert.assertTrue
import java.io.File

/**
 * A photographed page in shadow made into a PDF in black and white, a bit a pixel: text recognition
 * still makes its text layer, and the page as the PDF draws it (Android's renderer, at 150 dpi) is
 * clear enough to read word for word. Run by the device tests of each edition with text recognition.
 */
internal object BlackAndWhiteOcrCheck {
    suspend fun run(context: Context, engine: OcrEngine, dir: File) {
        engine.ensureReady(OcrLanguage.ENGLISH)
        val width = 1240
        val photo = ShadedPages.photo(File(dir, "page.jpg"), width)
        val item = ImageItem(1, photo.path, photo.name, ImageFormat.JPEG, width, width * 297 / 210, 1, look = PageLook.BLACK_WHITE)
        val exporter = PdfExporter(context)
        try {
            val settings = LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.NONE)
            val pdf = exporter.export(listOf(item), settings, Compression.EXTREME, true, "Scan", "Usugami") {}
            val output = PdfOcr(context).apply(pdf, engine, OcrLanguage.ENGLISH).files.single().file
            val layer = PDDocument.load(output).use { PDFTextStripper().getText(it) }
            Log.i("UsugamiTest", "Black and white page's text layer: $layer")
            for (word in ShadedPages.WORDS) assertTrue("Missing $word in $layer", layer.contains(word))

            // The page as drawn, read.
            val page = ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    renderer.openPage(0).use { page ->
                        Bitmap.createBitmap(width, width * page.height / page.width, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
            val read = engine.open(OcrLanguage.ENGLISH).use { it.recognize(page) }.joinToString("\n") { it.text }
            Log.i("UsugamiTest", "Black and white page, as drawn, read as: $read")
            for (word in ShadedPages.WORDS) assertTrue("Missing $word in $read", read.contains(word))
        } finally {
            exporter.clearOutput()
            dir.deleteRecursively()
        }
    }
}
