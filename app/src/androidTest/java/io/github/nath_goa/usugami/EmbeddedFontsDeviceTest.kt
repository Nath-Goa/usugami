package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.office.EmbeddedFonts
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The fonts Android's PDF writer embeds whole, shrunk to the glyphs drawn: the pages look exactly
 * the same (rendered before and after, at three sizes), their text reads the same, the file is much
 * smaller, and a font for a script the shrinking doesn't know is left whole.
 */
class EmbeddedFontsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.cacheDir, "fonts-test").apply { mkdirs() }

    init {
        PdfToolsEngine(context) // Sets PDFBox up.
    }

    @After fun tearDown() {
        work.deleteRecursively()
    }

    /** Two pages drawn by Android's PDF writer, in the serif font's styles, monospace fonts and several scripts. */
    private fun androidPdf(file: File) {
        val doc = PdfDocument()
        val serif = Typeface.SERIF
        val lines = listOf(
            serif to "The office's fluffy final file: café, naïve, 1234567890.",
            Typeface.create(serif, Typeface.BOLD) to "A Bold Heading (with ligatures: fi fl ffi)",
            Typeface.create(serif, Typeface.ITALIC) to "Some italic words, “quoted” and – dashed.",
            Typeface.MONOSPACE to "val answer = 42 // monospace",
            // A font with hinting instructions of its own.
            Typeface.create("serif-monospace", Typeface.NORMAL) to "Cutive: 7 apples",
            // Only digits and punctuation from this style: hinted as Latin all the same.
            Typeface.create(serif, Typeface.BOLD_ITALIC) to "2026 — 42.",
            serif to "Ελληνικά γράμματα και русский текст",
            serif to "x² + y³ = z⁴, the 1ˢᵗ, ₹ 250",
            // Accents on capitals, which the font swaps for flatter ones without a character of their own.
            serif to "Q\u0301 X\u0303 W\u0302\u0301",
            serif to "שלום עולם",
            serif to "हिन्दी का पाठ और English together",
        )
        for (n in 1..2) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(420, 595, n).create())
            var y = 40f
            for ((typeface, text) in lines) {
                page.canvas.drawText(text, 24f, y, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.typeface = typeface
                    textSize = 11f + n
                })
                y += 26f
            }
            doc.finishPage(page)
        }
        file.outputStream().use(doc::writeTo)
        doc.close()
    }

    private fun render(file: File, scale: Int): List<Bitmap> = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            (0 until renderer.pageCount).map { i ->
                renderer.openPage(i).use { page ->
                    Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }

    /** The size of each TrueType font file [file] embeds, decoded, by its font's name. */
    private fun fontFiles(file: File): Map<String, Int> = PDDocument.load(file).use { doc ->
        val files = HashMap<String, Int>()
        for (page in doc.pages) {
            for (name in page.resources.fontNames) {
                val font = page.resources.getFont(name) as? PDType0Font ?: continue
                val stream = (font.descendantFont as? PDCIDFontType2)?.fontDescriptor?.fontFile2?.cosObject ?: continue
                files[font.name] = stream.createInputStream().use { it.readBytes().size }
            }
        }
        files
    }

    @Test fun shrunkFontsLookAndReadTheSameAndTakeLittleRoom() {
        val original = File(work, "android.pdf").also(::androidPdf)
        val shrunk = File(work, "shrunk.pdf")
        PDDocument.load(original).use { doc ->
            EmbeddedFonts.shrink(doc) {}
            doc.save(shrunk)
        }
        val before = fontFiles(original)
        val after = fontFiles(shrunk)
        assertEquals(before.keys, after.keys)
        val serif = before.keys.filter { "NotoSerif" in it && "Hebrew" !in it }
        assertTrue("The serif font's styles: $before", serif.size >= 3)
        // Newer Android versions embed only the glyphs drawn already: little is left to shrink then,
        // but the pages must still look and read the same.
        val whole = androidEmbedsWholeFonts()
        if (whole) {
            assertTrue("Android embeds the serif font whole: $before", serif.all { before.getValue(it) > 100_000 })
            assertTrue("Shrunk to what is drawn: $before to $after", serif.all { after.getValue(it) * 3 < before.getValue(it) })
        }
        // Hebrew is hinted by letters the shrinking doesn't know: that font stays whole.
        val hebrew = before.keys.single { "Hebrew" in it }
        assertEquals(before[hebrew], after[hebrew])
        if (whole) assertTrue("${shrunk.length()} bytes, from ${original.length()}", shrunk.length() * 3 < original.length())
        // Exactly the same pixels, page by page, small (where hinting shows most) and large.
        for (scale in 1..3) {
            val a = render(original, scale)
            val b = render(shrunk, scale)
            assertEquals(a.size, b.size)
            a.zip(b).forEachIndexed { i, (x, y) -> assertTrue("Page ${i + 1} at ${scale}x looks the same", x.sameAs(y)) }
        }
        val text = { f: File -> PDDocument.load(f).use { PDFTextStripper().getText(it) } }
        assertEquals(text(original), text(shrunk))
        val shrunkText = text(shrunk)
        for (words in listOf("fluffy final file", "русский текст", "का पाठ और", "₹ 250")) assertTrue(words, shrunkText.contains(words))
    }
}

/** Whether Android's PDF writer embeds a font whole, as it did before it kept only the glyphs drawn. */
internal fun androidEmbedsWholeFonts(): Boolean {
    val doc = PdfDocument()
    val page = doc.startPage(PdfDocument.PageInfo.Builder(100, 100, 1).create())
    page.canvas.drawText("a", 10f, 50f, Paint().apply { typeface = Typeface.SERIF })
    doc.finishPage(page)
    val out = ByteArrayOutputStream()
    doc.writeTo(out)
    doc.close()
    return out.size() > 100_000
}
