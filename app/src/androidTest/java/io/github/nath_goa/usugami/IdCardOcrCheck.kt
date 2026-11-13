package io.github.nath_goa.usugami

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.IdCardLayout
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.core.Margin
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

/**
 * An ID card's front and back, each with one large word, made at Extreme into one page: text
 * recognition reads both sides as picked, reports the page once, and each word's text lies over its
 * own side on the page. Run by the device tests of each edition with text recognition.
 */
internal object IdCardOcrCheck {
    private const val WIDTH = 1712
    private const val HEIGHT = 1080
    private val WORDS = listOf("QUARTZ", "MARBLE")
    private val SETTINGS = LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.SMALL)

    /** How far (in points) a word's text may lie outside its side on the page. */
    private const val TOLERANCE = 4.0

    suspend fun run(context: Context, engine: OcrEngine, dir: File) {
        engine.ensureReady(OcrLanguage.ENGLISH)
        dir.mkdirs()
        val sides = WORDS.mapIndexed { i, word ->
            val file = PagePhotos.jpeg(side(word), File(dir, "side$i.jpg"))
            ImageItem(i + 1L, file.path, "ID_card_side$i", ImageFormat.JPEG, WIDTH, HEIGHT, ExifInterface.ORIENTATION_NORMAL, card = 5)
        }
        val exporter = PdfExporter(context)
        try {
            val pdf = exporter.export(sides, SETTINGS, Compression.EXTREME, true, "Card", "Usugami") {}
            assertEquals(1, pdf.single().pageCount)
            val pages = ArrayList<Int>()
            val output = PdfOcr(context).apply(pdf, engine, OcrLanguage.ENGLISH) { pages += it }
            assertEquals("One page, reported once", listOf(1), pages)
            val glyphs = glyphs(output.files.single().file)
            val text = glyphs.joinToString("") { it.unicode }
            Log.i("UsugamiTest", "Card read as: $text")
            val places = IdCardLayout.compute(sides.map { it.shownWidth to it.shownHeight }, SETTINGS)
            WORDS.forEachIndexed { i, word ->
                val start = text.indexOf(word)
                assertTrue("Missing $word in $text", start >= 0)
                val place = places[i]
                for (glyph in glyphs.subList(start, start + word.length)) {
                    // yDirAdj is the baseline, measured down from the top of the page.
                    val where = listOf(glyph.xDirAdj, glyph.xDirAdj + glyph.widthDirAdj, glyph.yDirAdj)
                    val side = listOf(place.imageX, place.imageX + place.imageWidth, place.imageTop, place.imageTop + place.imageHeight)
                    assertTrue("\"${glyph.unicode}\" of $word at $where, its side at $side", glyph.xDirAdj >= place.imageX - TOLERANCE)
                    assertTrue("\"${glyph.unicode}\" of $word at $where, its side at $side", glyph.xDirAdj + glyph.widthDirAdj <= place.imageX + place.imageWidth + TOLERANCE)
                    assertTrue("\"${glyph.unicode}\" of $word at $where, its side at $side", glyph.yDirAdj >= place.imageTop - TOLERANCE)
                    assertTrue("\"${glyph.unicode}\" of $word at $where, its side at $side", glyph.yDirAdj <= place.imageTop + place.imageHeight + TOLERANCE)
                }
            }
        } finally {
            exporter.clearOutput()
            dir.deleteRecursively()
        }
    }

    /** A card's side with [word] in large print across its middle. */
    private fun side(word: String): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(232, 236, 242))
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 30, 40)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 220f
        }
        canvas.drawText(word, (WIDTH - ink.measureText(word)) / 2, HEIGHT / 2f + 80f, ink)
        return bitmap
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
