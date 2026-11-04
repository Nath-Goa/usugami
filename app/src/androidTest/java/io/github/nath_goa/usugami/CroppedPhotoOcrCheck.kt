package io.github.nath_goa.usugami

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.core.Margin
import io.github.nath_goa.usugami.core.PageLayout
import io.github.nath_goa.usugami.core.PageOrientation
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.OcrEngine
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.OriginalImage
import io.github.nath_goa.usugami.data.PageCropper
import io.github.nath_goa.usugami.data.PdfExporter
import io.github.nath_goa.usugami.data.PdfOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * A photo of a page lying at a slant on a table, beside a sticky note, cropped to the page found on
 * it and made into a PDF: text recognition reads the straightened page, not the photo, so the note
 * is left out, and each line's text lies over the line on the page. The photo is stored sideways
 * with an EXIF orientation. Run by the device tests of each edition with text recognition.
 */
internal object CroppedPhotoOcrCheck {
    private const val PAGE_WIDTH = 1400
    private const val PAGE_HEIGHT = 1980
    private const val TITLE = "QUARTZ"
    private const val NOTE = "STICKY"
    private val BODY = listOf(
        "The quick brown fox jumps over the lazy dog.",
        "Invoice number 48213 is due on Friday.",
    )
    private val EXPECTED = listOf(TITLE, "quick", "brown", "Invoice", "48213", "Friday")
    private val SETTINGS = LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.NONE)

    /**
     * How far the title's text may be from the title's ink on the page, as a part of the ink's
     * height: text recognition boxes large print with room around it (PaddleOCR's more than ML
     * Kit's), and the page is found within a few pixels.
     */
    private const val TOLERANCE = 0.3

    suspend fun run(context: Context, engine: OcrEngine, dir: File) {
        engine.ensureReady(OcrLanguage.ENGLISH)
        dir.mkdirs()
        val title = Rect()
        val page = page(title)
        val corners = floatArrayOf(700f, 900f, 2150f, 1000f, 2250f, 3000f, 500f, 2950f)
        val upright = PagePhotos.photo(page, 2400, 3200, corners) { canvas ->
            // A sticky note on the table, well away from the page.
            canvas.drawRect(150f, 150f, 1100f, 600f, Paint().apply { color = Color.rgb(250, 238, 150) })
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textSize = 130f
            }
            canvas.drawText(NOTE, 260f, 440f, ink)
        }
        // Stored a quarter turn anticlockwise, with EXIF saying to turn it back, as a phone does.
        val stored = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, Matrix().apply { postRotate(-90f) }, true)
        val photo = PagePhotos.jpeg(stored, File(dir, "photo.jpg"))
        ExifInterface(photo.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val exporter = PdfExporter(context)
        val ocr = PdfOcr(context)
        try {
            // The photo as taken: the note is read too, so leaving it out means the page was read.
            val whole = ImageItem(1, photo.path, photo.name, ImageFormat.JPEG, stored.width, stored.height, ExifInterface.ORIENTATION_ROTATE_90)
            val all = text(ocr.apply(exporter.export(listOf(whole), SETTINGS, Compression.EXTREME, true, "Photo", "Usugami") {}, engine, OcrLanguage.ENGLISH).files.single().file)
            Log.i("UsugamiTest", "Photo read as: $all")
            assertTrue("Missing $NOTE in $all", all.contains(NOTE))

            val original = OriginalImage(photo.path, ImageFormat.JPEG, stored.width, stored.height, ExifInterface.ORIENTATION_ROTATE_90, CropQuad.WHOLE)
            val outline = checkNotNull(PageCropper.findPage(original))
            assertTrue("Confidence ${outline.confidence}", outline.confident)
            val cropped = PageCropper.crop(original, outline.quad, File(dir, "page.jpg"))
            val item = ImageItem(2, cropped.file.path, "page.jpg", ImageFormat.JPEG, cropped.width, cropped.height, 1, original = original.copy(corners = outline.quad))
            val pdf = exporter.export(listOf(item), SETTINGS, Compression.EXTREME, true, "Page", "Usugami") {}
            assertEquals(item, pdf.single().images.single().item)
            val output = ocr.apply(pdf, engine, OcrLanguage.ENGLISH)
            val glyphs = glyphs(output.files.single().file)
            val text = glyphs.joinToString("") { it.unicode }
            Log.i("UsugamiTest", "Cropped page read as: $text")
            for (word in EXPECTED) assertTrue("Missing $word in $text", text.contains(word))
            assertFalse("The note, off the page, was read: $text", text.contains(NOTE))

            // The straightened page fills the PDF's page; the title lies where the page has it.
            val layout = PageLayout.compute(cropped.width, cropped.height, SETTINGS)
            val left = layout.imageX + title.left * layout.imageWidth / PAGE_WIDTH
            val right = layout.imageX + title.right * layout.imageWidth / PAGE_WIDTH
            val bottom = layout.imageTop + title.bottom * layout.imageHeight / PAGE_HEIGHT
            val tolerance = TOLERANCE * title.height() * layout.imageHeight / PAGE_HEIGHT
            val start = text.indexOf(TITLE)
            val first = glyphs[start]
            val last = glyphs[start + TITLE.length - 1]
            Log.i("UsugamiTest", "$TITLE at ${listOf(first.xDirAdj, last.xDirAdj + last.widthDirAdj, first.yDirAdj)}, its ink at ${listOf(left, right, bottom)}")
            assertEquals("Left of $TITLE", left, first.xDirAdj.toDouble(), tolerance)
            assertEquals("Right of $TITLE", right, (last.xDirAdj + last.widthDirAdj).toDouble(), tolerance)
            assertEquals("Baseline of $TITLE", bottom, first.yDirAdj.toDouble(), tolerance)
        } finally {
            exporter.clearOutput()
            dir.deleteRecursively()
        }
    }

    /** The page: a title and two lines of print. Returns it, with where the title's ink is in [title]. */
    private fun page(title: Rect): Bitmap {
        val page = Bitmap.createBitmap(PAGE_WIDTH, PAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(page)
        canvas.drawColor(Color.rgb(244, 242, 236))
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 36)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 110f
        }
        ink.getTextBounds(TITLE, 0, TITLE.length, title)
        title.offset(160, 360)
        canvas.drawText(TITLE, 160f, 360f, ink)
        ink.typeface = Typeface.SERIF
        ink.textSize = 52f
        BODY.forEachIndexed { i, line -> canvas.drawText(line, 160f, 620f + i * 110f, ink) }
        return page
    }

    private fun text(file: File): String = PDDocument.load(file).use { PDFTextStripper().getText(it) }

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
}
