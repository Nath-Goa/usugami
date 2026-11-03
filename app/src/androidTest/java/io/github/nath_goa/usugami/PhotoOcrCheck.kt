package io.github.nath_goa.usugami

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.core.Margin
import io.github.nath_goa.usugami.core.PageLayout
import io.github.nath_goa.usugami.core.PageOrientation
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.OcrEngine
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.PdfExporter
import io.github.nath_goa.usugami.data.PdfOcr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import kotlin.math.atan2
import kotlin.random.Random

/**
 * A PDF made at Extreme from a photo of a printed page gets its text from the photo as picked, not
 * from the 100-dpi copy in the PDF, and each line's text lies over the line on the page. The photo
 * is stored sideways with an EXIF orientation and turned again in the editor. Run by the device
 * tests of each edition with text recognition.
 */
internal object PhotoOcrCheck {
    /** The photo as it shows, upright. */
    private const val WIDTH = 2400
    private const val HEIGHT = 3200
    private const val TITLE = "QUARTZ"

    /** Small print: about 8 pixels high in the PDF at Extreme, too little to read there. */
    private val BODY = listOf(
        "The quick brown fox jumps over the lazy dog.",
        "Pack my box with five dozen liquor jugs.",
        "Invoice number 48213 is due on Friday.",
    )
    private val EXPECTED = listOf(TITLE, "quick", "brown", "jumps", "dozen", "liquor", "Invoice", "48213", "Friday")
    private val SETTINGS = LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.SMALL)

    /** How far (in points) the title's text may be from the title on the page. */
    private const val TOLERANCE = 4f

    suspend fun run(context: Context, engine: OcrEngine, dir: File) {
        engine.ensureReady(OcrLanguage.ENGLISH)
        val photo = File(dir, "photo.jpg")
        val title = writePhoto(photo)
        // Stored a quarter turn clockwise; EXIF says a quarter clockwise, the editor a half turn.
        val item = ImageItem(1, photo.path, photo.name, ImageFormat.JPEG, HEIGHT, WIDTH, exifOrientation = 6, rotation = 180)
        val exporter = PdfExporter(context)
        try {
            val pdf = exporter.export(listOf(item), SETTINGS, Compression.EXTREME, true, "Photo", "Usugami") {}
            val pages = ArrayList<Int>()
            val output = PdfOcr(context).apply(pdf, engine, OcrLanguage.ENGLISH) { pages += it }
            assertEquals(listOf(1), pages)
            val glyphs = glyphs(output.files.single().file)
            val text = glyphs.joinToString("") { it.unicode }
            Log.i("UsugamiTest", "Photo read as: $text")
            for (word in EXPECTED) assertTrue("Missing $word in $text", text.contains(word))

            // Where the page shows the title: the image fills the space between the margins.
            val page = PageLayout.compute(WIDTH, HEIGHT, SETTINGS)
            val left = page.imageX + title.left * page.imageWidth / WIDTH
            val right = page.imageX + title.right * page.imageWidth / WIDTH
            val bottom = page.imageTop + title.bottom * page.imageHeight / HEIGHT
            val start = text.indexOf(TITLE)
            val first = glyphs[start]
            val last = glyphs[start + TITLE.length - 1]
            val read = listOf(first.xDirAdj, last.xDirAdj + last.widthDirAdj, first.yDirAdj)
            Log.i("UsugamiTest", "$TITLE at $read on the page, its ink at ${listOf(left, right, bottom)}")
            assertEquals("Left of $TITLE", left, first.xDirAdj.toDouble(), TOLERANCE.toDouble())
            assertEquals("Right of $TITLE", right, (last.xDirAdj + last.widthDirAdj).toDouble(), TOLERANCE.toDouble())
            assertEquals("Baseline of $TITLE", bottom, first.yDirAdj.toDouble(), TOLERANCE.toDouble())
            for (glyph in glyphs) {
                val angle = Math.toDegrees(atan2(glyph.textMatrix.shearY, glyph.textMatrix.scaleX).toDouble())
                assertEquals("Angle of \"${glyph.unicode}\"", 0.0, angle, 3.0)
            }
        } finally {
            exporter.clearOutput()
            photo.delete()
        }
    }

    /**
     * Writes the photo, stored a quarter turn clockwise from how it shows, as a phone camera does.
     * Returns where the title's ink is in the photo as it shows.
     */
    private fun writePhoto(file: File): Rect {
        val upright = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(upright)
        // Off-white paper, lit unevenly.
        canvas.drawColor(Color.rgb(236, 231, 220))
        val shade = Paint().apply {
            shader = LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), Color.TRANSPARENT, Color.argb(48, 0, 0, 0), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), shade)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 38, 44)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 120f
        }
        val title = Rect()
        ink.getTextBounds(TITLE, 0, TITLE.length, title)
        title.offset(300, 600)
        canvas.drawText(TITLE, 300f, 600f, ink)
        ink.typeface = Typeface.SERIF
        ink.textSize = 26f
        BODY.forEachIndexed { i, line -> canvas.drawText(line, 300f, 900f + i * 70f, ink) }

        // A little out of focus, with sensor noise.
        val soft = Bitmap.createScaledBitmap(upright, WIDTH * 7 / 10, HEIGHT * 7 / 10, true)
        upright.recycle()
        val stored = Bitmap.createBitmap(soft, 0, 0, soft.width, soft.height, Matrix().apply { postRotate(90f); postScale(10f / 7, 10f / 7) }, true)
        soft.recycle()
        assertEquals(HEIGHT to WIDTH, stored.width to stored.height)
        val random = Random(5)
        val row = IntArray(stored.width)
        for (y in 0 until stored.height) {
            stored.getPixels(row, 0, stored.width, 0, y, stored.width, 1)
            for (x in row.indices) {
                val n = random.nextInt(-10, 11)
                val c = row[x]
                row[x] = Color.rgb(
                    (Color.red(c) + n).coerceIn(0, 255),
                    (Color.green(c) + n).coerceIn(0, 255),
                    (Color.blue(c) + n).coerceIn(0, 255),
                )
            }
            stored.setPixels(row, 0, stored.width, 0, y, stored.width, 1)
        }
        file.outputStream().use { stored.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        stored.recycle()
        return title
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
}
