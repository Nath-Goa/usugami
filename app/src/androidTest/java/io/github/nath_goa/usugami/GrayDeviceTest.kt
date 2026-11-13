package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.nath_goa.usugami.core.GrayImage
import io.github.nath_goa.usugami.core.GrayJpeg
import io.github.nath_goa.usugami.core.GrayMath
import io.github.nath_goa.usugami.data.CompressJob
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfPasswordRequired
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.ui.renderPdfPage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
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
     * of the page's own /ColorSpace dictionary, a square annotation with a red appearance, a link
     * with a blue border, an axial shading from red to blue drawn by `sh`, a shading pattern of two
     * hard stops (a stitching function) and an axial shading in the spot colour. Page 2: a rectangle
     * in a named ICC RGB space of its own dictionary, an RGB JPEG, an RGB picture with see-through
     * parts (a soft mask), a 2-bit palette over RGB, a CMYK picture, an RGB picture with a colour key
     * and an 8-bit picture in the spot colour with /Decode [1 0].
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
                // The shadings are gray, the second's function still stitched (its hard stops stay sharp).
                val axial = (resources.getDictionaryObject(COSName.SHADING) as COSDictionary).getDictionaryObject("Ax") as COSDictionary
                val stops = ((resources.getDictionaryObject(COSName.PATTERN) as COSDictionary).getDictionaryObject("Stops") as COSDictionary)
                    .getDictionaryObject(COSName.SHADING) as COSDictionary
                val spot = (resources.getDictionaryObject(COSName.SHADING) as COSDictionary).getDictionaryObject("Sp") as COSDictionary
                for (shading in listOf(axial, stops, spot)) assertEquals(COSName.DEVICEGRAY, shading.getDictionaryObject(COSName.COLORSPACE))
                assertEquals(3, (stops.getDictionaryObject(COSName.FUNCTION) as COSDictionary).getInt(COSName.FUNCTION_TYPE))
                // The spot colour's shading through a sampled gray function.
                assertEquals(0, (spot.getDictionaryObject(COSName.FUNCTION) as COSDictionary).getInt(COSName.FUNCTION_TYPE))

                // Page 2's pictures: each gray, the soft mask still attached, the colour key now a stencil.
                val pictures = after.getPage(1).resources.cosObject.getDictionaryObject(COSName.XOBJECT) as COSDictionary
                assertEquals(6, pictures.size())
                for (name in pictures.keySet()) {
                    val space = (pictures.getDictionaryObject(name) as COSStream).getDictionaryObject(COSName.COLORSPACE)
                    val palette = space is COSArray && space.getObject(0) == COSName.INDEXED && space.getObject(1) == COSName.DEVICEGRAY
                    assertTrue("${name.name} is gray: $space", space == COSName.DEVICEGRAY || palette)
                }
                assertTrue("The soft mask is still attached", (pictures.getDictionaryObject("Clear") as COSStream).getDictionaryObject(COSName.SMASK) is COSStream)
                val stencil = (pictures.getDictionaryObject("Key") as COSStream).getDictionaryObject(COSName.MASK) as COSStream
                assertTrue("The colour key is a stencil", stencil.getBoolean(COSName.IMAGE_MASK, false))
                // The spot colour's picture: a palette of a gray for each sample, its /Decode [1 0] worked in
                // (sample 0 is full tint, dark green; 255 is none, white), so read the right way round.
                val tint = pictures.getDictionaryObject("Tint") as COSStream
                val grays = tint.getDictionaryObject(COSName.COLORSPACE) as COSArray
                assertEquals(255, (grays.getObject(2) as COSNumber).intValue())
                assertNull("/Decode is worked in", tint.getDictionaryObject(COSName.DECODE))
                val table = (grays.getObject(3) as COSString).bytes
                assertEquals(256, table.size)
                assertEquals("Sample 0 at full tint", GrayMath.level(GrayMath.rgb(0f, 0.4f, 0f)).toFloat(), (table[0].toInt() and 0xFF).toFloat(), 1f)
                assertEquals("Sample 255 white", 255, table[255].toInt() and 0xFF)

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
            // So is the CMYK picture on page 2.
            val byFormula = if (page == 0) listOf(Rect(36, 136, 204, 244), Rect(236, 236, 384, 324)) else listOf(Rect(36, 256, 164, 384))
            assertSameLightness(before, after, 10, byFormula)
            if (page == 1) assertEquals("The CMYK picture by the formula", 77f, Color.red(after.getPixel(100, 320)).toFloat(), 4f)
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
     * is 0 wide (as LaTeX's hidden links are), and a coloured form and a colour photo the page lists but
     * never draws (the photo Compress's own pass re-encodes as a gray JPEG).
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
            val random = java.util.Random(7)
            val noise = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until 64) for (x in 0 until 64) setPixel(x, y, Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)))
                setHasAlpha(false) // Else JPEGFactory gives it a soft mask, which Compress's own pass leaves alone.
            }
            val photo = JPEGFactory.createFromImage(doc, noise, 0.9f).cosObject
            assertNull("No soft mask", photo.getDictionaryObject(COSName.SMASK))
            noise.recycle()
            assertTrue("Big enough for Compress's own pass: ${photo.length}", photo.length >= 2048)
            val page = PDPage(PDRectangle(300f, 400f)).also(doc::addPage)
            page.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply {
                setItem(COSName.XOBJECT, COSDictionary().apply {
                    setItem("Unused", unused)
                    setItem("Photo", photo)
                })
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

    /**
     * Three pages: text in red; a mesh whose numbers aren't whole bytes ([addUnconvertibleMeshPage]);
     * a rectangle in a colour space that can't be read (an ICC space of five components, no
     * /Alternate) beside red text. The last two pages are named, everything else still turns gray, and
     * the PDF has its three pages. Cancelled, it leaves no PDF behind.
     */
    @Test fun whatCantBeTurnedGrayIsSaidAndTheRestIsDone() {
        val original = File(context.cacheDir, "partly.pdf")
        PDDocument().use { doc ->
            fun stream(content: String) = doc.document.createCOSStream().apply {
                createOutputStream(COSName.FLATE_DECODE).use { it.write(content.toByteArray()) }
            }
            val font = COSDictionary().apply { setItem("F1", PDType1Font.HELVETICA.cosObject) }
            PDPage(PDRectangle(300f, 400f)).also(doc::addPage).cosObject.apply {
                setItem(COSName.RESOURCES, COSDictionary().apply { setItem(COSName.FONT, font) })
                setItem(COSName.CONTENTS, stream("BT /F1 24 Tf 1 0 0 rg 20 300 Td (Red text) Tj ET\n"))
            }
            addUnconvertibleMeshPage(doc)
            val bad = COSArray().apply {
                add(COSName.ICCBASED)
                add(doc.document.createCOSStream().apply { setInt(COSName.N, 5) })
            }
            PDPage(PDRectangle(300f, 400f)).also(doc::addPage).cosObject.apply {
                setItem(COSName.RESOURCES, COSDictionary().apply {
                    setItem(COSName.FONT, font)
                    setItem(COSName.COLORSPACE, COSDictionary().apply { setItem("Bad", bad) })
                })
                setItem(COSName.CONTENTS, stream("/Bad cs 1 0 0 0 0 sc 20 200 100 80 re f\nBT /F1 18 Tf 1 0 0 rg 140 230 Td (Beside) Tj ET\n"))
            }
            doc.save(original)
        }
        val input = engine.import(Uri.fromFile(original), 1204)
        val result = engine.compress(input, PdfCompression.RECOMMENDED, "partly", gray = true)
        val outcome = checkNotNull(result.gray)
        assertTrue(outcome.changed)
        assertEquals("The mesh's page and the unreadable colour's page", listOf(1, 2), outcome.colorPages)
        PDDocument.load(result.file).use { doc ->
            assertEquals(3, doc.numberOfPages)
            for (page in listOf(0, 2)) {
                val names = operators(doc.getPage(page).contents.use { it.readBytes() }).map { it.first }
                assertFalse("Page $page's text is gray: $names", "rg" in names)
                assertTrue("Page $page's text is gray: $names", "g" in names)
            }
        }
        val first = render(result.file, 0)
        assertNoColor(first)
        first.recycle()

        // Cancelled once Black and white has started: nothing is left, in the output or the scratch folder.
        var calls = 0
        try {
            engine.compress(input, PdfCompression.RECOMMENDED, "cancelled", check = { if (++calls > 4) throw CancellationException() }, gray = true)
            fail("Cancelling should stop it")
        } catch (_: CancellationException) {
        }
        assertTrue("Black and white was under way", calls > 4)
        val output = File(context.cacheDir, "output/tools")
        assertTrue("No PDF left: ${output.list()?.toList()}", output.listFiles().orEmpty().none { it.name.endsWith(".pdf") || it.name.endsWith(".tmp") })
        assertTrue("No temporary PDF", File(context.cacheDir, "pdfbox").walk().none { it.isFile && it.name.endsWith(".pdf") })
        engine.delete(input)
    }

    /**
     * Which file comes out: with the switch, the gray one once anything was turned gray, also when it
     * isn't smaller; a PDF with only gray is kept byte for byte as without it; a scan's photo comes out
     * a gray JPEG at the level's size, smaller than in colour; a PDF with a password comes out gray
     * without it; and with several PDFs, only the ones asked for are gray.
     */
    @Test fun theSwitchDecidesWhichFileComesOut() {
        // Red text beside a picture that neither pass changes (gray noise with a soft mask), which keeps
        // the PDF from getting 1% smaller.
        val red = compactPdf(File(context.cacheDir, "red.pdf"), "1 0 0 rg")
        val redInput = engine.import(Uri.fromFile(red), 1205)
        val plain = engine.compress(redInput, PdfCompression.RECOMMENDED, "plain")
        assertTrue("Without the switch it can't get smaller", plain.keptOriginal)
        assertArrayEquals(red.readBytes(), plain.file.readBytes())
        assertNull(plain.gray)
        val grayed = engine.compress(redInput, PdfCompression.RECOMMENDED, "grayed", gray = true)
        assertFalse("With the switch the gray PDF comes out", grayed.keptOriginal)
        assertTrue(checkNotNull(grayed.gray).changed)
        assertTrue("Not smaller: ${grayed.sizeBytes} from ${red.length()}", grayed.sizeBytes * 100 > red.length() * 99)
        assertFalse(PDDocument.load(grayed.file).use { doc -> "rg" in operators(doc.getPage(0).contents.use { it.readBytes() }).map { it.first } })

        // Only gray: 0 g text, a gray picture, a palette of grays, and an RGB transparency group.
        val gray = compactPdf(File(context.cacheDir, "gray.pdf"), "0 g") { doc, resources ->
            fun picture(bits: Int, space: com.tom_roush.pdfbox.cos.COSBase, samples: ByteArray) = doc.document.createCOSStream().apply {
                setItem(COSName.TYPE, COSName.XOBJECT)
                setItem(COSName.SUBTYPE, COSName.IMAGE)
                setInt(COSName.WIDTH, 8)
                setInt(COSName.HEIGHT, 8)
                setInt(COSName.BITS_PER_COMPONENT, bits)
                setItem(COSName.COLORSPACE, space)
                createOutputStream(COSName.FLATE_DECODE).use { it.write(samples) }
            }
            val grays = COSArray().apply {
                add(COSName.INDEXED)
                add(COSName.DEVICEGRAY)
                add(COSInteger.get(15))
                add(COSString(ByteArray(16) { (it * 17).toByte() }))
            }
            (resources.getDictionaryObject(COSName.XOBJECT) as COSDictionary).apply {
                setItem("Gray", picture(8, COSName.DEVICEGRAY, ByteArray(64) { (it * 4).toByte() }))
                setItem("Grays", picture(4, grays, ByteArray(32) { (it * 7).toByte() }))
            }
            "q 40 0 0 40 20 20 cm /Gray Do Q q 40 0 0 40 80 20 cm /Grays Do Q\n"
        }
        val grayOutcome = engine.compress(engine.import(Uri.fromFile(gray), 1206), PdfCompression.RECOMMENDED, "only_gray", gray = true)
        assertFalse("Nothing was in colour", checkNotNull(grayOutcome.gray).changed)
        assertTrue("Kept as it was", grayOutcome.keptOriginal)
        assertArrayEquals(gray.readBytes(), grayOutcome.file.readBytes())

        // A scan's photo: one gray JPEG, at most A4's long side at 150 dpi, smaller than in colour.
        val scan = engine.import(Uri.fromFile(photoScan(File(context.cacheDir, "gray_scan.pdf"))), 1207)
        val colour = engine.compress(scan, PdfCompression.RECOMMENDED, "colour").sizeBytes
        val grayScan = engine.compress(scan, PdfCompression.RECOMMENDED, "gray_scan", gray = true)
        assertTrue("Its photo, turned gray by Compress's own pass, is a change", checkNotNull(grayScan.gray).changed)
        assertTrue("Gray is smaller: ${grayScan.sizeBytes} against $colour", grayScan.sizeBytes < colour)
        PDDocument.load(grayScan.file).use { doc ->
            val image = imagesOn(doc.getPage(0)).single().image
            assertEquals(COSName.DEVICEGRAY, image.cosObject.getDictionaryObject(COSName.COLORSPACE))
            assertEquals(COSName.DCT_DECODE, image.cosObject.getDictionaryObject(COSName.FILTER))
            assertTrue("${image.width} x ${image.height}", maxOf(image.width, image.height) <= 1754)
            assertTrue(PDFTextStripper().getText(doc).contains("Keep this selectable caption"))
        }

        // With a password: opened with it, out gray and without it.
        val unlocked = try {
            engine.import(Uri.fromFile(asset("protected.pdf")), 1208)
            error("Password request expected")
        } catch (e: PdfPasswordRequired) {
            engine.unlock(e.input, "test123")
        }
        val open = engine.compress(unlocked, PdfCompression.RECOMMENDED, "open", gray = true)
        assertEquals(emptyList<Int>(), checkNotNull(open.gray).colorPages)
        PDDocument.load(open.file).use { doc ->
            assertFalse(doc.isEncrypted)
            assertTrue(PDFTextStripper().getText(doc).contains("ALPHA ONE"))
            for (page in doc.pages) {
                val names = operators(page.contents.use { it.readBytes() }).map { it.first }
                assertTrue("Gray only: $names", names.none { it in listOf("rg", "RG", "k", "K", "sc", "scn", "SC", "SCN") })
            }
        }

        // Several PDFs: only the one asked for is gray.
        val each = engine.compressEach(listOf(
            CompressJob(redInput, PdfCompression.RECOMMENDED, "first", gray = true),
            CompressJob(redInput, PdfCompression.RECOMMENDED, "second"),
        )).map { it.getOrThrow() }
        assertTrue(checkNotNull(each[0].gray).changed)
        assertNull(each[1].gray)
        assertTrue("The second as it was", each[1].keptOriginal)
        listOf(redInput, scan, unlocked).forEach(engine::delete)
    }

    /**
     * Pictures Compress alone leaves as they are turn gray too, each read as the PDF means it, one on
     * each page. Through BitmapFactory (route E): a one-component JPEG in a spot colour whose tint 0
     * is white (read as plain gray it would be its negative) and an RGB JPEG with /Decode
     * [1 0 1 0 1 0] (drawn inverted). Through Android's renderer (route F): a CMYK JPEG with /Decode
     * [1 0 1 0 1 0 1 0], the same without, and with a soft mask; a JPEG 2000 in DeviceRGB and the
     * same without /ColorSpace; an RGB JPEG whose /DecodeParms say /ColorTransform, with a soft mask,
     * so drawn at its own 1200 by 1000 pixels, in two bands. Each comes out a gray JPEG drawn as light
     * as the original was, the soft mask still attached and as it was (read again after the
     * renderer's copy of its picture). A JPEG 2000 the renderer can't decode leaves its page in
     * colour, its bytes as they were, and PDF pages still draw after it. So does one with
     * /SMaskInData, never drawn: a renderer that ignores it would show it opaque. (An RGB JPEG with a
     * soft mask, route E's other way, is test 1's.)
     */
    @Test fun picturesCompressAloneLeavesAsTheyAreTurnGrayToo() {
        val original = File(context.cacheDir, "pictures.pdf")
        // A JP2 file's signature, then nothing a decoder can read.
        val random = java.util.Random(7)
        val damaged = byteArrayOf(0, 0, 0, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A) + ByteArray(200).also(random::nextBytes)
        // Opaque but for the right quarter, which is mostly see-through.
        val maskSamples = ByteArray(32 * 32) { if (it % 32 < 24) -1 else 0x60 }
        picturesPdf(original, damaged, maskSamples)
        val input = engine.import(Uri.fromFile(original), 1209)
        val result = engine.compress(input, PdfCompression.RECOMMENDED, "pictures_bw", gray = true)
        val outcome = checkNotNull(result.gray)
        assertTrue(outcome.changed)
        assertEquals("Only the damaged JPEG 2000's page and the one with /SMaskInData are left in colour", listOf(8, 9), outcome.colorPages)
        assertTrue("No temporary PDF", File(context.cacheDir, "pdfbox").walk().none { it.isFile && it.name.endsWith(".pdf") })
        PDDocument.load(result.file).use { doc ->
            assertEquals(10, doc.numberOfPages)
            for (page in 0 until 8) {
                val image = imagesOn(doc.getPage(page)).single().image.cosObject
                assertEquals("Page $page", COSName.DEVICEGRAY, image.getDictionaryObject(COSName.COLORSPACE))
                assertEquals("Page $page", COSName.DCT_DECODE, image.getDictionaryObject(COSName.FILTER))
                assertNull("Page $page", image.getDictionaryObject(COSName.DECODE))
            }
            val masked = imagesOn(doc.getPage(4)).single().image.cosObject
            val mask = masked.getDictionaryObject(COSName.SMASK) as COSStream
            assertArrayEquals("The soft mask as it was", maskSamples, mask.createInputStream().use { it.readBytes() })
            val banded = imagesOn(doc.getPage(7)).single().image
            assertEquals("A picture with a soft mask keeps its size", 1200 to 1000, banded.width to banded.height)
            val left = imagesOn(doc.getPage(8)).single().image.cosObject
            assertEquals(COSName.getPDFName("JPXDecode"), left.getDictionaryObject(COSName.FILTER))
            assertEquals(COSName.DEVICERGB, left.getDictionaryObject(COSName.COLORSPACE))
            assertArrayEquals("The damaged picture as it was", damaged, left.createRawInputStream().use { it.readBytes() })
            // Read from the page's resources: PDFBox decodes a JPEG 2000 without /ColorSpace to make a PDImageXObject.
            val xObjects = doc.getPage(9).resources.cosObject.getDictionaryObject(COSName.XOBJECT) as COSDictionary
            val alpha = xObjects.getDictionaryObject(xObjects.keySet().single()) as COSStream
            assertNull(alpha.getDictionaryObject(COSName.COLORSPACE))
            assertEquals(1, alpha.getInt(COSName.getPDFName("SMaskInData")))
            assertArrayEquals("The picture with /SMaskInData as it was", asset("gray_color.jp2").readBytes(), alpha.createRawInputStream().use { it.readBytes() })
        }
        val originals = (0 until 8).map { page ->
            val before = render(original, page)
            val after = render(result.file, page)
            if (page > 0) assertTrue("The original page $page has colour", hasColor(before))
            assertNoColor(after)
            assertSameLightness(before, after, if (page < 2) 12 else 16)
            after.recycle()
            before
        }
        // The CMYK JPEG's inks inverted by /Decode and as they are draw differently, so the two gray
        // pictures (each as light as its original) differ as the originals do.
        val inverted = lightness(originals[2])
        val asItIs = lightness(originals[3])
        assertTrue("The CMYK pages differ: $inverted and $asItIs", abs(inverted - asItIs) > 20)
        originals.forEach(Bitmap::recycle)
        // The renderer met a picture it couldn't decode. Android 8's stops drawing every PDF after one
        // it refused, until Usugami is closed: a page's thumbnail, as the tool screens draw it, still
        // draws, here the JPEG 2000 in colour.
        val thumbnail = checkNotNull(renderPdfPage(input, 5, 150)) { "The thumbnail is drawn" }
        assertTrue("The thumbnail has its colours", hasColor(thumbnail.asAndroidBitmap()))
        engine.delete(input)
    }

    /**
     * Test 2's PDF: on each of 10 pages of 200 points square, a picture 160 points square. The spot
     * colour JPEG, the RGB JPEG drawn inverted, the CMYK JPEG ([asset] gray_cmyk.jpg) inverted, as it
     * is and with [maskSamples] as its soft mask, the JPEG 2000 (gray_color.jp2) in DeviceRGB and
     * without /ColorSpace, the RGB JPEG with /ColorTransform from yellow at the top to blue,
     * [damaged] as a JPEG 2000 in DeviceRGB, and the JPEG 2000 without /ColorSpace with /SMaskInData 1.
     */
    private fun picturesPdf(file: File, damaged: ByteArray, maskSamples: ByteArray): File = file.also {
        val cmyk = asset("gray_cmyk.jpg").readBytes()
        val jp2 = asset("gray_color.jp2").readBytes()
        PDDocument().use { doc ->
            fun numbers(vararg values: Float) = COSArray().apply { values.forEach { add(COSFloat(it)) } }
            val jpx = COSName.getPDFName("JPXDecode")
            fun picture(bytes: ByteArray, filter: COSName, space: com.tom_roush.pdfbox.cos.COSBase?, size: Int = 32, entries: COSDictionary.() -> Unit = {}) =
                doc.document.createCOSStream().apply {
                    setItem(COSName.TYPE, COSName.XOBJECT)
                    setItem(COSName.SUBTYPE, COSName.IMAGE)
                    setInt(COSName.WIDTH, size)
                    setInt(COSName.HEIGHT, size)
                    setInt(COSName.BITS_PER_COMPONENT, 8)
                    if (space != null) setItem(COSName.COLORSPACE, space)
                    entries()
                    createRawOutputStream().use { it.write(bytes) }
                    setItem(COSName.FILTER, filter)
                }
            // Samples rising from 0 to 254 across, in a spot colour whose tint 0 is white.
            val ramp = GrayJpeg.encode(GrayImage(128, 128, ByteArray(128 * 128) { ((it % 128) * 2).toByte() }), 95)
            val spot = picture(ramp, COSName.DCT_DECODE, COSArray().apply {
                add(COSName.SEPARATION)
                add(COSName.getPDFName("Spot"))
                add(COSName.DEVICEGRAY)
                add(COSDictionary().apply {
                    setInt(COSName.FUNCTION_TYPE, 2)
                    setItem(COSName.DOMAIN, numbers(0f, 1f))
                    setItem(COSName.C0, numbers(1f))
                    setItem(COSName.C1, numbers(0f))
                    setInt(COSName.N, 1)
                })
            }, size = 128)
            val photo = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 128f, 128f, Color.RED, Color.CYAN, Shader.TileMode.CLAMP) })
                setHasAlpha(false)
            }
            val inverted = JPEGFactory.createFromImage(doc, photo, 0.9f).cosObject.apply { setItem(COSName.DECODE, numbers(1f, 0f, 1f, 0f, 1f, 0f)) }
            photo.recycle()
            val mask = doc.document.createCOSStream().apply {
                setItem(COSName.TYPE, COSName.XOBJECT)
                setItem(COSName.SUBTYPE, COSName.IMAGE)
                setInt(COSName.WIDTH, 32)
                setInt(COSName.HEIGHT, 32)
                setInt(COSName.BITS_PER_COMPONENT, 8)
                setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
                createOutputStream(COSName.FLATE_DECODE).use { it.write(maskSamples) }
            }
            // Yellow at the top to blue at the bottom: a band drawn from the wrong place is the wrong lightness.
            val tall = Bitmap.createBitmap(1200, 1000, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 1000f, Color.YELLOW, Color.BLUE, Shader.TileMode.CLAMP) })
                setHasAlpha(false)
            }
            val transformed = JPEGFactory.createFromImage(doc, tall, 0.9f).cosObject.apply {
                setItem(COSName.DECODE_PARMS, COSDictionary().apply { setInt(COSName.getPDFName("ColorTransform"), 1) })
                setItem(COSName.SMASK, doc.document.createCOSStream().apply {
                    setItem(COSName.TYPE, COSName.XOBJECT)
                    setItem(COSName.SUBTYPE, COSName.IMAGE)
                    setInt(COSName.WIDTH, 8)
                    setInt(COSName.HEIGHT, 8)
                    setInt(COSName.BITS_PER_COMPONENT, 8)
                    setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
                    createOutputStream(COSName.FLATE_DECODE).use { it.write(ByteArray(64) { -1 }) }
                })
            }
            tall.recycle()
            val pictures = listOf(
                spot,
                inverted,
                picture(cmyk, COSName.DCT_DECODE, COSName.DEVICECMYK) { setItem(COSName.DECODE, numbers(1f, 0f, 1f, 0f, 1f, 0f, 1f, 0f)) },
                picture(cmyk, COSName.DCT_DECODE, COSName.DEVICECMYK),
                picture(cmyk, COSName.DCT_DECODE, COSName.DEVICECMYK) { setItem(COSName.SMASK, mask) },
                picture(jp2, jpx, COSName.DEVICERGB),
                picture(jp2, jpx, null),
                transformed,
                picture(damaged, jpx, COSName.DEVICERGB),
                picture(jp2, jpx, null) { setInt(COSName.getPDFName("SMaskInData"), 1) },
            )
            for (picture in pictures) {
                val page = PDPage(PDRectangle(200f, 200f)).also(doc::addPage)
                page.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply {
                    setItem(COSName.XOBJECT, COSDictionary().apply { setItem("Im", picture) })
                })
                page.cosObject.setItem(COSName.CONTENTS, doc.document.createCOSStream().apply {
                    createOutputStream(COSName.FLATE_DECODE).use { it.write("q 160 0 0 160 20 20 cm /Im Do Q\n".toByteArray()) }
                })
            }
            doc.save(file)
        }
    }

    /**
     * A PDF of one page: [contentColour] (such as `1 0 0 rg`) sets the colour of a line of text, beside
     * a picture of gray noise with a soft mask, which neither of Compress's passes changes and which
     * is most of the file, so the PDF can't get 1% smaller. [more] may add to the page's resources
     * and return content drawn after.
     */
    private fun compactPdf(file: File, contentColour: String, more: (PDDocument, COSDictionary) -> String = { _, _ -> "" }): File = file.also {
        PDDocument().use { doc ->
            val random = java.util.Random(5)
            fun picture(samples: ByteArray, entries: COSDictionary.() -> Unit = {}) = doc.document.createCOSStream().apply {
                setItem(COSName.TYPE, COSName.XOBJECT)
                setItem(COSName.SUBTYPE, COSName.IMAGE)
                setInt(COSName.WIDTH, 200)
                setInt(COSName.HEIGHT, 200)
                setInt(COSName.BITS_PER_COMPONENT, 8)
                setItem(COSName.COLORSPACE, COSName.DEVICEGRAY)
                entries()
                createOutputStream(COSName.FLATE_DECODE).use { it.write(samples) }
            }
            val mask = picture(ByteArray(200 * 200) { (128 + random.nextInt(128)).toByte() })
            val noise = picture(ByteArray(200 * 200) { random.nextInt(256).toByte() }) { setItem(COSName.SMASK, mask) }
            val resources = COSDictionary().apply {
                setItem(COSName.FONT, COSDictionary().apply { setItem("F1", PDType1Font.HELVETICA.cosObject) })
                setItem(COSName.XOBJECT, COSDictionary().apply { setItem("Noise", noise) })
            }
            val page = PDPage(PDRectangle(300f, 400f)).also(doc::addPage)
            page.cosObject.setItem(COSName.RESOURCES, resources)
            page.cosObject.setItem(COSName.GROUP, COSDictionary().apply {
                setItem(COSName.S, COSName.TRANSPARENCY)
                setItem(COSName.CS, COSName.DEVICERGB)
            })
            val extra = more(doc, resources)
            page.cosObject.setItem(COSName.CONTENTS, doc.document.createCOSStream().apply {
                createOutputStream(COSName.FLATE_DECODE).use {
                    it.write("BT /F1 18 Tf $contentColour 20 360 Td (Colour or not) Tj ET\nq 200 0 0 200 50 120 cm /Noise Do Q\n$extra".toByteArray())
                }
            })
            doc.save(file)
        }
    }

    /** A scan as `PdfToolsDeviceTest`'s: a 1600 by 2000 photo of random colour noise on an A4 page, with a caption. */
    private fun photoScan(file: File): File = file.also {
        val random = java.util.Random(1L)
        val pixels = IntArray(1600 * 2000) { Color.BLACK or random.nextInt(0x1000000) }
        val bitmap = Bitmap.createBitmap(pixels, 1600, 2000, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            val image = JPEGFactory.createFromImage(doc, bitmap, 0.95f)
            PDPageContentStream(doc, page).use { content ->
                content.drawImage(image, 50f, 70f, 495f, 650f)
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(60f, 40f)
                content.showText("Keep this selectable caption")
                content.endText()
            }
            doc.save(file)
        }
        bitmap.recycle()
    }

    private fun asset(name: String): File = File(context.cacheDir, name).also { target ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
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

            // A spot colour from white (no tint) to dark green (full tint).
            fun separation() = COSArray().apply {
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
            }
            val spaces = dict(
                "Sep" to separation(),
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
            fun exponential(c0: COSArray, c1: COSArray) = dict(
                "FunctionType" to COSInteger.get(2), "Domain" to numbers(0f, 1f), "C0" to c0, "C1" to c1, "N" to COSInteger.ONE,
            )
            fun axial(x0: Float, x1: Float, function: COSDictionary, space: com.tom_roush.pdfbox.cos.COSBase = COSName.DEVICERGB) = dict(
                "ShadingType" to COSInteger.get(2), "ColorSpace" to space, "Coords" to numbers(x0, 0f, x1, 0f),
                "Function" to function, "Extend" to COSArray().apply {
                    add(com.tom_roush.pdfbox.cos.COSBoolean.TRUE)
                    add(com.tom_roush.pdfbox.cos.COSBoolean.TRUE)
                },
            )
            val red = numbers(1f, 0f, 0f)
            val blue = numbers(0f, 0f, 1f)
            val ax = axial(180f, 290f, exponential(red, blue))
            // From no tint to full tint, in the spot colour: its gray is read through the tint transform.
            val sp = axial(20f, 280f, exponential(numbers(0f), numbers(1f)), separation())
            val stops = dict(
                "Type" to COSName.PATTERN, "PatternType" to COSInteger.get(2),
                "Shading" to axial(20f, 170f, dict(
                    "FunctionType" to COSInteger.get(3), "Domain" to numbers(0f, 1f),
                    "Functions" to COSArray().apply {
                        add(exponential(red, red))
                        add(exponential(blue, blue))
                    },
                    "Bounds" to numbers(0.5f), "Encode" to numbers(0f, 1f, 0f, 1f),
                )),
            )
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
            write("q 180 128 110 30 re W n /Ax sh Q\n")
            write("/Pattern cs /Stops scn 20 142 150 16 re f\n")
            write("q 20 8 260 24 re W n /Sp sh Q\n")

            val page1 = PDPage(PDRectangle(300f, 400f)).also(doc::addPage)
            page1.cosObject.setItem(COSName.RESOURCES, dict(
                "Font" to dict("F1" to PDType1Font.HELVETICA.cosObject),
                "ColorSpace" to spaces,
                "Pattern" to dict("Dots" to dots, "Stops" to stops),
                "Shading" to dict("Ax" to ax, "Sp" to sp),
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
            fun picture(width: Int, height: Int, bits: Int, space: com.tom_roush.pdfbox.cos.COSBase, samples: ByteArray, entries: COSDictionary.() -> Unit = {}) =
                stream(samples) {
                    setItem(COSName.TYPE, COSName.XOBJECT)
                    setItem(COSName.SUBTYPE, COSName.IMAGE)
                    setInt(COSName.WIDTH, width)
                    setInt(COSName.HEIGHT, height)
                    setInt(COSName.BITS_PER_COMPONENT, bits)
                    setItem(COSName.COLORSPACE, space)
                    entries()
                }
            val photo = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
                Canvas(this).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 400f, 300f, Color.RED, Color.BLUE, Shader.TileMode.CLAMP) })
            }
            val clear = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.TRANSPARENT)
                Canvas(this).apply {
                    drawCircle(20f, 20f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN })
                    drawRect(0f, 0f, 12f, 12f, Paint().apply { color = 0x80FF8000.toInt() })
                }
            }
            // A palette of red, green, blue and yellow, two bits a pixel.
            val palette = picture(4, 4, 2, COSArray().apply {
                add(COSName.INDEXED)
                add(COSName.DEVICERGB)
                add(COSInteger.get(3))
                add(COSString(byteArrayOf(-1, 0, 0, 0, -1, 0, 0, 0, -1, -1, -1, 0)))
            }, byteArrayOf(0x1B, 0x6C, 0xB1.toByte(), 0xC6.toByte()))
            val ink = picture(16, 16, 8, COSName.DEVICECMYK, ByteArray(16 * 16 * 4) { if (it % 4 == 1 || it % 4 == 2) -1 else 0 })
            // Left half red, right half white; the colour key leaves the white unpainted.
            val keyed = picture(8, 8, 8, COSName.DEVICERGB, ByteArray(8 * 8 * 3) { if (it % 3 == 0 || (it / 3) % 8 >= 4) -1 else 0 }) {
                setItem(COSName.MASK, numbers(250f, 255f, 250f, 255f, 250f, 255f))
            }
            // 64 by 4 samples rising from 0 to 252 across, read through /Decode [1 0]: full tint on the left.
            val tinted = picture(64, 4, 8, separation(), ByteArray(64 * 4) { ((it % 64) * 4).toByte() }) {
                setItem(COSName.DECODE, numbers(1f, 0f))
            }
            page2.cosObject.setItem(COSName.RESOURCES, dict(
                "ColorSpace" to dict("Icc" to icc()),
                "XObject" to dict(
                    "Photo" to JPEGFactory.createFromImage(doc, photo, 0.9f).cosObject,
                    "Clear" to LosslessFactory.createFromImage(doc, clear).cosObject,
                    "Pal" to palette, "Ink" to ink, "Key" to keyed, "Tint" to tinted,
                ),
            ))
            photo.recycle()
            clear.recycle()
            page2.cosObject.setItem(COSName.CONTENTS, stream((
                "/Icc cs 1 0 0 sc 40 40 200 150 re f\n" +
                    "q 120 0 0 90 20 300 cm /Photo Do Q\nq 60 0 0 60 160 300 cm /Clear Do Q\nq 60 0 0 60 230 300 cm /Pal Do Q\n" +
                    "q 60 0 0 60 20 210 cm /Ink Do Q\nq 60 0 0 60 100 210 cm /Key Do Q\nq 60 0 0 60 180 210 cm /Tint Do Q\n"
                ).toByteArray()))
            doc.save(file)
        }
    }
}

/**
 * Adds to [doc] a page whose only content is a mesh shading Black and white can't turn gray: a
 * triangle (type 4) in RGB whose flags are 2 bits, so its records aren't whole bytes. With the switch
 * the page is named as still partly in colour. Each vertex is padded to a byte.
 */
fun addUnconvertibleMeshPage(doc: PDDocument): PDPage {
    fun numbers(vararg values: Float) = COSArray().apply { values.forEach { add(COSFloat(it)) } }
    // flag, x, y, r, g, b for each vertex: 2 + 8 + 8 + 24 bits, then padding to the byte.
    val vertices = listOf(intArrayOf(0, 25, 19, 255, 0, 0), intArrayOf(0, 230, 19, 0, 255, 0), intArrayOf(0, 128, 236, 0, 0, 255))
    val data = ByteArrayOutputStream()
    for (v in vertices) {
        var bits = v[0].toLong()
        for (i in 1 until 6) bits = (bits shl 8) or v[i].toLong()
        bits = bits shl 6
        for (shift in 40 downTo 0 step 8) data.write(((bits shr shift) and 0xFF).toInt())
    }
    val mesh = doc.document.createCOSStream().apply {
        setInt(COSName.SHADING_TYPE, 4)
        setItem(COSName.COLORSPACE, COSName.DEVICERGB)
        setInt(COSName.BITS_PER_COORDINATE, 8)
        setInt(COSName.BITS_PER_COMPONENT, 8)
        setInt(COSName.BITS_PER_FLAG, 2)
        setItem(COSName.DECODE, numbers(0f, 300f, 0f, 400f, 0f, 1f, 0f, 1f, 0f, 1f))
        createOutputStream().use { it.write(data.toByteArray()) }
    }
    val page = PDPage(PDRectangle(300f, 400f)).also(doc::addPage)
    page.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply {
        setItem(COSName.SHADING, COSDictionary().apply { setItem("Mesh", mesh) })
    })
    page.cosObject.setItem(COSName.CONTENTS, doc.document.createCOSStream().apply {
        createOutputStream(COSName.FLATE_DECODE).use { it.write("q 0 0 300 400 re W n /Mesh sh Q\n".toByteArray()) }
    })
    return page
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

/** The average gray ([GrayMath.rgb8]) of [bitmap]'s pixels. */
private fun lightness(bitmap: Bitmap): Int {
    val all = pixels(bitmap)
    return (all.sumOf { GrayMath.rgb8(Color.red(it), Color.green(it), Color.blue(it)).toLong() } / all.size).toInt()
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
