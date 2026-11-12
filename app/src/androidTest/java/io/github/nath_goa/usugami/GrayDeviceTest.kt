package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.nath_goa.usugami.core.GrayMath
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfToolsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Compress PDF's Black and white on PDFs made here by hand, colour of every kind in them: what comes
 * out has no colour when drawn, the same lightness, the same text, and is still made of text and
 * drawings (no operator sets a colour but a gray).
 */
class GrayDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)

    /**
     * Page 1: Helvetica text in RGB red, a CMYK rectangle, a blue line, a spot colour at full tint and
     * at half, an uncoloured tiling pattern painted blue, a form that fills green, an inline 2 by 2
     * RGB picture, a rectangle through a luminosity soft mask whose group paints in a named ICC space
     * of the page's own /ColorSpace dictionary, a square annotation with a red appearance and a link
     * with a blue border. Page 2: a rectangle in a named ICC RGB space of its own dictionary.
     */
    @Test fun colorsOfEveryKindTurnGrayAndTextStaysText() {
        val original = colorsPdf(File(context.cacheDir, "colors.pdf"))
        val input = engine.import(Uri.fromFile(original), 1201)
        val result = engine.compress(input, PdfCompression.RECOMMENDED, "bw", gray = true)
        val outcome = checkNotNull(result.gray) { "Black and white says what it did" }
        assertTrue("Something was turned gray", outcome.changed)
        assertEquals("No page is left in colour", emptyList<Int>(), outcome.colorPages)
        assertFalse(result.keptOriginal)

        val groupBefore = PDDocument.load(original).use { doc -> maskGroup(doc).createRawInputStream().use { it.readBytes() } }
        PDDocument.load(original).use { before ->
            PDDocument.load(result.file).use { after ->
                assertEquals("The same text", PDFTextStripper().getText(before), PDFTextStripper().getText(after))
                for (p in 0 until 2) assertEquals(imagesOn(before.getPage(p)).size, imagesOn(after.getPage(p)).size)

                val page1 = after.getPage(0)
                val resources = page1.resources.cosObject
                fun listed(kind: COSName, name: String) = (resources.getDictionaryObject(kind) as COSDictionary).getDictionaryObject(name) as COSStream
                val appearance = ((page1.cosObject.getDictionaryObject(COSName.ANNOTS) as COSArray).getObject(0) as COSDictionary)
                    .let { ((it.getDictionaryObject(COSName.AP) as COSDictionary).getDictionaryObject(COSName.N) as COSStream) }
                val streams = mapOf(
                    "page 1" to page1.contents.use { it.readBytes() },
                    "page 2" to after.getPage(1).contents.use { it.readBytes() },
                    "the form" to listed(COSName.XOBJECT, "Fm").createInputStream().use { it.readBytes() },
                    "the pattern" to listed(COSName.PATTERN, "Dots").createInputStream().use { it.readBytes() },
                    "the appearance" to appearance.createInputStream().use { it.readBytes() },
                )
                for ((what, bytes) in streams) {
                    for ((name, numbers) in operators(bytes)) {
                        assertFalse("$what sets a colour with $name", name in listOf("rg", "RG", "k", "K"))
                        if (name in listOf("sc", "scn", "SC", "SCN")) assertTrue("$what: $name with $numbers numbers", numbers <= 1)
                    }
                }
                val inline = PDFStreamParser(streams.getValue("page 1")).apply { parse() }.tokens
                    .filterIsInstance<Operator>().single { it.name == "BI" }.imageParameters
                assertEquals("The inline picture is gray", COSName.getPDFName("G"), inline.getDictionaryObject(COSName.CS))
                assertEquals(8, inline.getInt(COSName.BPC))

                assertEquals("The soft mask's group as it was", groupBefore.toList(), maskGroup(after).createRawInputStream().use { it.readBytes() }.toList())
                val spaces1 = resources.getDictionaryObject(COSName.COLORSPACE) as COSDictionary
                assertTrue("Page 1's colour spaces kept for the soft mask: ${spaces1.keySet()}", listOf("Sep", "P", "Icc").all { spaces1.containsKey(it) })
                val spaces2 = after.getPage(1).resources.cosObject.getDictionaryObject(COSName.COLORSPACE) as COSDictionary
                assertFalse("Page 2's /Icc is no longer used", spaces2.containsKey("Icc"))

                val annots = page1.cosObject.getDictionaryObject(COSName.ANNOTS) as COSArray
                for ((i, expected) in listOf(GrayMath.rgb(1f, 0f, 0f), GrayMath.rgb(0f, 0f, 1f)).withIndex()) {
                    val c = (annots.getObject(i) as COSDictionary).getDictionaryObject(COSName.C) as COSArray
                    assertEquals(3, c.size())
                    for (j in 0 until 3) assertEquals(expected, (c.getObject(j) as COSNumber).floatValue(), 0.001f)
                }
            }
        }

        for (page in 0 until 2) {
            val before = render(original, page)
            val after = render(result.file, page)
            assertTrue("The original page $page has colour", hasColor(before))
            assertNoColor(after)
            // The CMYK rectangle is drawn by the formula, which renderers' own CMYK needn't match. So is
            // the spot colour set by `cs` alone: Android's renderer keeps the colour before it there
            // (red, from the CMYK rectangle) where the standard says full tint.
            val byFormula = if (page == 0) listOf(Rect(36, 136, 204, 244), Rect(236, 236, 384, 324)) else emptyList()
            assertSameLightness(before, after, 10, byFormula)
            if (page == 0) {
                assertEquals("The CMYK rectangle by the formula", 77f, Color.red(after.getPixel(120, 190)).toFloat(), 4f)
                val fullTint = GrayMath.level(GrayMath.rgb(0f, 0.4f, 0f)).toFloat()
                assertEquals("The spot colour at full tint by the formula", fullTint, Color.red(after.getPixel(310, 280)).toFloat(), 4f)
                // The red text (24 points at 20, 360) is drawn dark, not gone.
                var darkest = 255
                for (y in 40 until 90) for (x in 40 until 400) darkest = minOf(darkest, Color.red(after.getPixel(x, y)))
                assertTrue("The text is drawn: $darkest", darkest < 110)
            }
            before.recycle()
            after.recycle()
        }
    }

    /**
     * A spot colour whose tint transform PDFBox can't read (a type 4 function that is no stream) and a
     * palette whose table can't be read (a filter that doesn't exist) keep the colours set in them, as
     * their `cs` is kept, where reading them by their count made both white; the page is named, and
     * its RGB rectangle still turns gray.
     */
    @Test fun aSpotColourOrPaletteThatCantBeReadKeepsItsColours() {
        val original = File(context.cacheDir, "unreadable.pdf")
        PDDocument().use { doc ->
            fun numbers(vararg values: Float) = COSArray().apply { values.forEach { add(COSFloat(it)) } }
            val table = doc.document.createCOSStream().apply {
                setItem(COSName.FILTER, COSName.getPDFName("NoSuchFilter"))
                createRawOutputStream().use { it.write(byteArrayOf(0, 0, 0, 0, 0, 0)) }
            }
            val spaces = COSDictionary().apply {
                setItem("Spot", COSArray().apply {
                    add(COSName.SEPARATION)
                    add(COSName.getPDFName("Black"))
                    add(COSName.DEVICECMYK)
                    add(COSDictionary().apply {
                        setInt(COSName.FUNCTION_TYPE, 4)
                        setItem(COSName.DOMAIN, numbers(0f, 1f))
                        setItem(COSName.RANGE, numbers(0f, 1f, 0f, 1f, 0f, 1f, 0f, 1f))
                    })
                })
                setItem("Pal", COSArray().apply {
                    add(COSName.INDEXED)
                    add(COSName.DEVICERGB)
                    add(COSInteger.ONE)
                    add(table)
                })
            }
            val page = PDPage(PDRectangle(300f, 400f)).also(doc::addPage)
            page.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.COLORSPACE, spaces) })
            page.cosObject.setItem(COSName.CONTENTS, doc.document.createCOSStream().apply {
                createOutputStream(COSName.FLATE_DECODE).use {
                    it.write("/Spot cs 1 scn 20 300 100 50 re f\n/Pal cs 1 sc 20 200 100 50 re f\n1 0 0 rg 20 100 100 50 re f\n".toByteArray())
                }
            })
            doc.save(original)
        }
        val result = engine.compress(engine.import(Uri.fromFile(original), 1202), PdfCompression.RECOMMENDED, "bw", gray = true)
        val outcome = checkNotNull(result.gray)
        assertTrue("The RGB rectangle turned gray", outcome.changed)
        assertEquals("The page is still partly in colour", listOf(0), outcome.colorPages)
        PDDocument.load(result.file).use { doc ->
            val page = doc.getPage(0)
            assertEquals(
                "The spot colour and the palette entry are set as they were",
                listOf("cs" to 0, "scn" to 1, "re" to 4, "f" to 0, "cs" to 0, "sc" to 1, "re" to 4, "f" to 0, "g" to 1, "re" to 4, "f" to 0),
                operators(page.contents.use { it.readBytes() }),
            )
            val spaces = page.resources.cosObject.getDictionaryObject(COSName.COLORSPACE) as COSDictionary
            assertTrue("Both spaces kept: ${spaces.keySet()}", spaces.containsKey("Spot") && spaces.containsKey("Pal"))
        }
    }

    /**
     * Colour nothing shows is no change: on a page in black, a link without an appearance whose border
     * is 0 wide (as LaTeX's hidden links are) and a coloured form the page lists but never draws.
     */
    @Test fun colourNothingShowsIsNoChange() {
        val original = File(context.cacheDir, "hidden.pdf")
        PDDocument().use { doc ->
            fun numbers(vararg values: Float) = COSArray().apply { values.forEach { add(COSFloat(it)) } }
            fun stream(content: String) = doc.document.createCOSStream().apply {
                createOutputStream(COSName.FLATE_DECODE).use { it.write(content.toByteArray()) }
            }
            val unused = stream("1 0 0 rg 0 0 60 40 re f").apply {
                setItem(COSName.TYPE, COSName.XOBJECT)
                setItem(COSName.SUBTYPE, COSName.FORM)
                setItem(COSName.BBOX, numbers(0f, 0f, 60f, 40f))
                setItem(COSName.RESOURCES, COSDictionary())
            }
            val page = PDPage(PDRectangle(300f, 400f)).also(doc::addPage)
            page.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply {
                setItem(COSName.XOBJECT, COSDictionary().apply { setItem("Unused", unused) })
            })
            page.cosObject.setItem(COSName.CONTENTS, stream("0 g 20 300 100 50 re f\n"))
            val link = COSDictionary().apply {
                setItem(COSName.TYPE, COSName.ANNOT)
                setItem(COSName.SUBTYPE, COSName.getPDFName("Link"))
                setItem(COSName.RECT, numbers(20f, 300f, 120f, 350f))
                setItem(COSName.BORDER, numbers(0f, 0f, 0f))
                setItem(COSName.C, numbers(1f, 0f, 0f))
            }
            page.cosObject.setItem(COSName.ANNOTS, COSArray().apply { add(link) })
            doc.save(original)
        }
        val result = engine.compress(engine.import(Uri.fromFile(original), 1203), PdfCompression.RECOMMENDED, "bw", gray = true)
        val outcome = checkNotNull(result.gray)
        assertFalse("Nothing that shows was in colour", outcome.changed)
        assertEquals(emptyList<Int>(), outcome.colorPages)
    }

    private fun maskGroup(doc: PDDocument): COSStream {
        val states = doc.getPage(0).resources.cosObject.getDictionaryObject(COSName.EXT_G_STATE) as COSDictionary
        val mask = (states.getDictionaryObject("GS1") as COSDictionary).getDictionaryObject(COSName.SMASK) as COSDictionary
        return mask.getDictionaryObject(COSName.G) as COSStream
    }

    private fun colorsPdf(file: File): File = file.also {
        PDDocument().use { doc ->
            fun numbers(vararg values: Float) = COSArray().apply { values.forEach { add(COSFloat(it)) } }
            fun stream(content: ByteArray, entries: COSDictionary.() -> Unit = {}): COSStream = doc.document.createCOSStream().apply {
                entries()
                createOutputStream(COSName.FLATE_DECODE).use { it.write(content) }
            }
            fun form(content: String, box: COSArray, entries: COSDictionary.() -> Unit = {}) = stream(content.toByteArray()) {
                setItem(COSName.TYPE, COSName.XOBJECT)
                setItem(COSName.SUBTYPE, COSName.FORM)
                setItem(COSName.BBOX, box)
                entries()
            }
            fun icc() = COSArray().apply {
                add(COSName.ICCBASED)
                add(stream(ByteArray(0)) {
                    setInt(COSName.N, 3)
                    setItem(COSName.ALTERNATE, COSName.DEVICERGB)
                })
            }
            fun dict(vararg entries: Pair<String, com.tom_roush.pdfbox.cos.COSBase>) = COSDictionary().apply { entries.forEach { (k, v) -> setItem(k, v) } }

            val spaces = dict(
                "Sep" to COSArray().apply {
                    add(COSName.SEPARATION)
                    add(COSName.getPDFName("Spot"))
                    add(COSName.DEVICERGB)
                    add(COSDictionary().apply {
                        setInt(COSName.FUNCTION_TYPE, 2)
                        setItem(COSName.DOMAIN, numbers(0f, 1f))
                        setItem(COSName.C0, numbers(1f, 1f, 1f))
                        setItem(COSName.C1, numbers(0f, 0.4f, 0f))
                        setInt(COSName.N, 1)
                    })
                },
                "P" to COSArray().apply {
                    add(COSName.PATTERN)
                    add(COSName.DEVICERGB)
                },
                "Icc" to icc(),
            )
            val dots = stream("1 1 4 4 re f".toByteArray()) {
                setItem(COSName.TYPE, COSName.PATTERN)
                setInt(COSName.PATTERN_TYPE, 1)
                setInt(COSName.PAINT_TYPE, 2)
                setInt(COSName.TILING_TYPE, 1)
                setItem(COSName.BBOX, numbers(0f, 0f, 8f, 8f))
                setInt(COSName.X_STEP, 8)
                setInt(COSName.Y_STEP, 8)
                setItem(COSName.RESOURCES, COSDictionary())
            }
            val green = form("0 1 0 rg 0 0 60 40 re f", numbers(0f, 0f, 60f, 40f)) { setItem(COSName.RESOURCES, COSDictionary()) }
            // The soft mask's group paints with /Icc of the page's own dictionary: it must keep every name.
            val group = form("/Icc cs 1 1 1 sc 0 0 50 100 re f 0.6 0.6 0.6 sc 50 0 50 100 re f 0.25 0.25 0.25 sc 100 0 50 100 re f", numbers(0f, 0f, 150f, 100f)) {
                setItem(COSName.GROUP, dict("S" to COSName.TRANSPARENCY, "CS" to COSName.DEVICERGB))
                setItem(COSName.RESOURCES, dict("ColorSpace" to spaces))
            }
            val state = dict("SMask" to dict("Type" to COSName.MASK, "S" to COSName.LUMINOSITY, "G" to group))

            val content = ByteArrayOutputStream()
            fun write(text: String) = content.write(text.toByteArray(Charsets.US_ASCII))
            write("BT /F1 24 Tf 1 0 0 rg 20 360 Td (Black and white) Tj ET\n")
            write("0 1 1 0 k 20 280 80 50 re f\n")
            write("0 0 1 RG 4 w 120 310 m 280 310 l S\n")
            write("/Sep cs 120 240 70 40 re f\n")
            write("/Sep cs 0.5 scn 200 240 70 40 re f\n")
            write("/P cs 0 0 1 /Dots scn 20 160 100 60 re f\n")
            write("q 1 0 0 1 140 170 cm /Fm Do Q\n")
            write("q 40 0 0 40 220 170 cm BI /W 2 /H 2 /CS /RGB /BPC 8 ID ")
            content.write(byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, 0))
            write("\nEI Q\n")
            write("q 1 0 0 1 20 40 cm /GS1 gs 0.2 0.6 0.2 rg 0 0 150 100 re f Q\n")

            val page1 = PDPage(PDRectangle(300f, 400f)).also(doc::addPage)
            page1.cosObject.setItem(COSName.RESOURCES, dict(
                "Font" to dict("F1" to PDType1Font.HELVETICA.cosObject),
                "ColorSpace" to spaces,
                "Pattern" to dict("Dots" to dots),
                "XObject" to dict("Fm" to green),
                "ExtGState" to dict("GS1" to state),
            ))
            page1.cosObject.setItem(COSName.CONTENTS, stream(content.toByteArray()))
            val square = dict(
                "Type" to COSName.ANNOT, "Subtype" to COSName.getPDFName("Square"),
                "Rect" to numbers(200f, 40f, 280f, 120f), "C" to numbers(1f, 0f, 0f),
                "AP" to dict("N" to form("1 0 0 RG 4 w 2 2 76 76 re S", numbers(0f, 0f, 80f, 80f))),
            )
            val link = dict(
                "Type" to COSName.ANNOT, "Subtype" to COSName.getPDFName("Link"),
                "Rect" to numbers(20f, 350f, 200f, 385f), "C" to numbers(0f, 0f, 1f),
            )
            page1.cosObject.setItem(COSName.ANNOTS, COSArray().apply {
                add(square)
                add(link)
            })

            val page2 = PDPage(PDRectangle(300f, 400f)).also(doc::addPage)
            page2.cosObject.setItem(COSName.RESOURCES, dict("ColorSpace" to dict("Icc" to icc())))
            page2.cosObject.setItem(COSName.CONTENTS, stream("/Icc cs 1 0 0 sc 40 40 200 150 re f\n".toByteArray()))
            doc.save(file)
        }
    }
}

/** The operators of a content stream with how many numbers each was given, in order. */
private fun operators(content: ByteArray): List<Pair<String, Int>> {
    val found = ArrayList<Pair<String, Int>>()
    var numbers = 0
    for (token in PDFStreamParser(content).apply { parse() }.tokens) {
        if (token is Operator) {
            found += token.name to numbers
            numbers = 0
        } else if (token is COSNumber) {
            numbers++
        }
    }
    return found
}

/**
 * Page [page] of [file] drawn by Android's renderer at twice its size on white, for printing: drawn
 * for the screen, text gets coloured fringes that would look like colour.
 */
private fun render(file: File, page: Int): Bitmap = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
    PdfRenderer(fd).use { renderer ->
        renderer.openPage(page).use {
            Bitmap.createBitmap(it.width * 2, it.height * 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                it.render(this, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
            }
        }
    }
}

private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
    bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
}

private fun spread(pixel: Int): Int {
    val r = Color.red(pixel)
    val g = Color.green(pixel)
    val b = Color.blue(pixel)
    return maxOf(r, g, b) - minOf(r, g, b)
}

/** Some pixel's channels differ by more than 40. */
private fun hasColor(bitmap: Bitmap): Boolean = pixels(bitmap).any { spread(it) > 40 }

/** No pixel's channels differ by more than 3. */
private fun assertNoColor(bitmap: Bitmap) {
    val all = pixels(bitmap)
    val worst = all.indices.maxByOrNull { spread(all[it]) } ?: return
    assertTrue("A pixel in colour at (${worst % bitmap.width}, ${worst / bitmap.width}): ${Integer.toHexString(all[worst])}", spread(all[worst]) <= 3)
}

/**
 * Over cells of 8 by 8 pixels, [after]'s average is within [tolerance] levels of the average gray
 * ([GrayMath.rgb8]) of [before]; cells touching [skip] aren't compared. Without it, a page that came
 * out blank would pass "no colour".
 */
private fun assertSameLightness(before: Bitmap, after: Bitmap, tolerance: Int, skip: List<Rect> = emptyList()) {
    assertEquals(before.width, after.width)
    val a = pixels(before)
    val b = pixels(after)
    val width = before.width
    for (top in 0 until before.height - 7 step 8) for (left in 0 until width - 7 step 8) {
        if (skip.any { Rect.intersects(it, Rect(left, top, left + 8, top + 8)) }) continue
        var sumBefore = 0
        var sumAfter = 0
        for (y in top until top + 8) for (x in left until left + 8) {
            val p = a[y * width + x]
            sumBefore += GrayMath.rgb8(Color.red(p), Color.green(p), Color.blue(p))
            val q = b[y * width + x]
            sumAfter += GrayMath.rgb8(Color.red(q), Color.green(q), Color.blue(q))
        }
        assertTrue("Lightness at ($left, $top): ${sumBefore / 64} before, ${sumAfter / 64} after", abs(sumBefore - sumAfter) <= tolerance * 64)
    }
}
