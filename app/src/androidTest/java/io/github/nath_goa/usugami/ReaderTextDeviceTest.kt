package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import io.github.nath_goa.usugami.core.TextSelection
import io.github.nath_goa.usugami.data.ReaderDocument
import io.github.nath_goa.usugami.data.shownFrame
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The reader's text for selecting (ReaderDocument.glyphs, read with PDFBox as Find text reads it, and
 * TextSelection): on a page turned a quarter and cropped, the text reads as it is written, and a word
 * long-pressed and its box are where the page's picture shows it; a page that is only a picture has
 * no text to select.
 */
class ReaderTextDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val dir = File(app.cacheDir, "output/test-reader-text")

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    /** The bounds of the dark pixels of [bitmap] within [area]; null when there are none. */
    private fun darkIn(bitmap: Bitmap, area: Rect): Rect? {
        val row = IntArray(area.width())
        var found: Rect? = null
        for (y in area.top until area.bottom) {
            bitmap.getPixels(row, 0, row.size, area.left, y, row.size, 1)
            for (i in row.indices) {
                if (Color.red(row[i]) >= 128) continue
                val x = area.left + i
                found = found?.apply { union(x, y, x + 1, y + 1) } ?: Rect(x, y, x + 1, y + 1)
            }
        }
        return found
    }

    @Test fun textIsSelectedWhereItShowsOnATurnedPage() {
        // A page turned a quarter and cropped, with "Hello world" written across it: as shown, the
        // words run down the page, "Hello" above "world". A second page with only a picture.
        PDFBoxResourceLoader.init(app)
        val file = File(dir, "turned.pdf").apply { parentFile!!.mkdirs() }
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(400f, 500f))
            page.cropBox = PDRectangle(50f, 100f, 300f, 250f)
            page.rotation = 90
            doc.addPage(page)
            writeText(doc, page, "Hello world", x = 100f, y = 250f)
            val picture = PDPage(PDRectangle(200f, 300f))
            doc.addPage(picture)
            val gray = createBitmap(40, 40).apply { eraseColor(Color.DKGRAY) }
            PDPageContentStream(doc, picture).use { it.drawImage(LosslessFactory.createFromImage(doc, gray), 50f, 50f, 100f, 100f) }
            doc.save(file)
        }
        val sizes = PDDocument.load(file).use { doc -> doc.pages.map { it.shownFrame().let { frame -> frame.shownWidth to frame.shownHeight } } }
        assertEquals(250f to 300f, sizes[0])
        val document = ReaderDocument.open(file, sizes) { PDDocument.load(file) }
        try {
            val glyphs = runBlocking { document.glyphs(0) }
            // All of it, read as written: one line, the two words a space apart.
            assertEquals("Hello world", TextSelection.text(glyphs, glyphs.glyphs.indices))

            // The page's picture at 4 pixels a point: the second word's dark pixels are those below the
            // widest gap between rows of dark pixels (the space between the words).
            val scale = 4f
            val picture = runBlocking { document.page(0, (250 * scale).toInt(), (300 * scale).toInt()) }!!
            val text = checkNotNull(darkIn(picture, Rect(0, 0, picture.width, picture.height))) { "The text isn't drawn" }
            assertTrue("The words run down the page: $text", text.height() > 2 * text.width())
            val inked = (text.top until text.bottom).map { y -> darkIn(picture, Rect(text.left, y, text.right, y + 1)) != null }
            var gapStart = -1
            var gapLength = 0
            var run = 0
            for ((i, dark) in inked.withIndex()) {
                if (dark) {
                    if (run > gapLength) {
                        gapLength = run
                        gapStart = i - run
                    }
                    run = 0
                } else {
                    run++
                }
            }
            assertTrue("A gap between the words", gapStart > 0)
            val world = checkNotNull(darkIn(picture, Rect(text.left, text.top + gapStart + gapLength, text.right, text.bottom))) { "No second word" }

            // A long-press in the middle of it takes "world".
            val word = checkNotNull(TextSelection.wordAt(glyphs, world.exactCenterX() / scale, world.exactCenterY() / scale, reach = 2f)) { "No word at $world" }
            assertEquals("world", TextSelection.text(glyphs, word))
            // Its line's box covers the word's dark pixels (but for a pixel at its edges).
            val box = TextSelection.lineBoxes(glyphs, word).single()
            val boxed = Rect(floor(box.left * scale).toInt(), floor(box.top * scale).toInt(), ceil(box.right * scale).toInt(), ceil(box.bottom * scale).toInt())
            assertTrue("The word $world in its box $boxed", boxed.left <= world.left + 1 && boxed.top <= world.top + 1 && boxed.right >= world.right - 1 && boxed.bottom >= world.bottom - 1)
            picture.recycle()

            // A page that is only a picture has no text to select.
            assertTrue(runBlocking { document.glyphs(1) }.isEmpty)
        } finally {
            document.close()
        }
    }
}
