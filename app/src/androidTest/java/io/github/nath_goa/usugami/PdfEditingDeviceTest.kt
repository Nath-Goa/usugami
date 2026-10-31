package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.PageFrame
import io.github.nath_goa.usugami.core.PageNumberPosition
import io.github.nath_goa.usugami.core.PageNumberSize
import io.github.nath_goa.usugami.core.PageNumbers
import io.github.nath_goa.usugami.core.SignatureLayout
import io.github.nath_goa.usugami.core.SignaturePlacement
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPasswordRequired
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.Signature
import io.github.nath_goa.usugami.data.SignatureInk
import io.github.nath_goa.usugami.data.WatermarkColor
import io.github.nath_goa.usugami.data.WatermarkFontMissing
import io.github.nath_goa.usugami.data.WatermarkOpacity
import io.github.nath_goa.usugami.data.WatermarkText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Rotate, page numbers, watermarks and signatures, checked by reading the result back and by rendering it. */
class PdfEditingDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = PdfToolsEngine(context)
    private val results = File(context.getExternalFilesDir(null), "test-results").apply { mkdirs() }
    private var nextId = 2001L

    private fun import(file: File): PdfInput = engine.import(Uri.fromFile(file), nextId++)

    private fun asset(name: String): File = File(context.cacheDir, name).also { target ->
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
    }

    private fun save(file: File, name: String): File = file.copyTo(File(results, name), overwrite = true)

    /**
     * The text of [file] in drawing order, without spaces or line breaks: text extraction splits text
     * that isn't upright on the page into short lines.
     */
    private fun text(file: File): String = glyphsByPage(file).flatten().joinToString("") { it.unicode }.filterNot(Char::isWhitespace)

    /**
     * Four pages, each with a caption in the middle only:
     * 1. A4, upright
     * 2. A4, turned clockwise (/Rotate 90)
     * 3. A4 with a crop box that leaves a 100-point border off on every side
     * 4. landscape A4, turned anticlockwise (/Rotate 270)
     */
    private fun mixedPages(name: String): File = File(context.cacheDir, name).also { file ->
        PDDocument().use { doc ->
            val landscape = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)
            for ((index, box) in listOf(PDRectangle.A4, PDRectangle.A4, PDRectangle.A4, landscape).withIndex()) {
                val page = PDPage(box)
                when (index) {
                    1 -> page.rotation = 90
                    2 -> page.cropBox = PDRectangle(100f, 100f, box.width - 200f, box.height - 200f)
                    3 -> page.rotation = 270
                }
                doc.addPage(page)
                PDPageContentStream(doc, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font.HELVETICA, 14f)
                    content.newLineAtOffset(box.width / 2 - 40, box.height / 2)
                    content.showText("CAPTION ${listOf("A", "B", "C", "D")[index]}")
                    content.endText()
                }
            }
            doc.save(file)
        }
    }

    /** Page [index] of [file] as shown, [scale] pixels per point, on white. */
    private fun render(file: File, index: Int, scale: Float = 2f): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(index).use { page ->
                    Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    /** Pixels of [bitmap] that [match], as (x, y) with y down. */
    private fun pixels(bitmap: Bitmap, match: (Int) -> Boolean): List<Pair<Int, Int>> {
        val all = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(all, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return all.indices.filter { match(all[it]) }.map { it % bitmap.width to it / bitmap.width }
    }

    private fun dark(pixel: Int) = Color.red(pixel) < 128 && Color.green(pixel) < 128 && Color.blue(pixel) < 128

    /** Every character on each page, with its position on the page as shown (y down). */
    private fun glyphsByPage(file: File): List<List<TextPosition>> = PDDocument.load(file).use { doc ->
        (1..doc.numberOfPages).map { number ->
            ArrayList<TextPosition>().also { glyphs ->
                object : PDFTextStripper() {
                    override fun processTextPosition(text: TextPosition) {
                        glyphs += text
                    }
                }.apply {
                    startPage = number
                    endPage = number
                }.getText(doc)
            }
        }
    }

    @Test fun rotateTurnsOnlyTheChosenPages() {
        val source = import(mixedPages("mixed.pdf"))
        val rotated = save(engine.rotate(source, mapOf(0 to 90, 1 to 90, 3 to -90), "rotated").file, "rotated.pdf")
        PDDocument.load(rotated).use { doc ->
            assertEquals(listOf(90, 180, 0, 180), doc.pages.map { it.rotation })
            // The content isn't touched: only the page's turn changes.
            assertEquals(PDRectangle.A4.width, doc.getPage(0).mediaBox.width, 0.01f)
            assertEquals(100f, doc.getPage(2).cropBox.lowerLeftX, 0.01f)
        }
        assertEquals("CAPTIONACAPTIONBCAPTIONCCAPTIOND", text(rotated))
        // Shown sideways now: PdfRenderer gives the page its turned size.
        assertTrue(render(rotated, 0, 1f).let { it.width > it.height })
    }

    @Test fun rotatingAProtectedPdfRemovesThePassword() {
        val pending = try {
            import(asset("protected.pdf"))
            fail("Password request expected")
            return
        } catch (e: PdfPasswordRequired) {
            e.input
        }
        val unlocked = engine.unlock(pending, "test123")
        val rotated = save(engine.rotate(unlocked, mapOf(1 to 180), "protected_rotated").file, "protected_rotated.pdf")
        PDDocument.load(rotated).use { doc ->
            assertFalse(doc.isEncrypted)
            assertEquals(listOf(0, 180, 0), doc.pages.map { it.rotation })
        }
    }

    @Test fun pageNumbersFollowThePageAsShown() {
        val source = import(mixedPages("numbers.pdf"))
        val pages = PageNumbers.numbered("1", source.pageCount)
        val numbered = save(engine.addPageNumbers(source, pages, PageNumberPosition.BOTTOM_RIGHT, PageNumberSize.MEDIUM, "numbered").file, "numbered.pdf")

        val glyphs = glyphsByPage(numbered)
        // The skipped first page has no number; the others have their own page number.
        assertEquals(listOf("CAPTIONA", "2CAPTIONB", "3CAPTIONC", "4CAPTIOND"), glyphs.map { page -> page.filterNot { it.unicode == " " }.sortedBy { it.unicode.first().isLetter() }.joinToString("") { it.unicode } })
        PDDocument.load(numbered).use { doc ->
            for (index in 1..3) {
                val page = doc.getPage(index)
                val sideways = page.rotation % 180 != 0
                val shownWidth = if (sideways) page.cropBox.height else page.cropBox.width
                val shownHeight = if (sideways) page.cropBox.width else page.cropBox.height
                val digit = glyphs[index].first { it.unicode == "${index + 1}" }
                val margin = PageNumbers.margin(shownWidth, shownHeight)
                // In the bottom-right corner of the page as shown, the margin away from the edges.
                assertEquals("Page ${index + 1} x", shownWidth - margin, digit.x + digit.width, 1.5f)
                assertEquals("Page ${index + 1} y", shownHeight - margin, digit.y, 1.5f)
                assertEquals("Page ${index + 1} size", 12f, digit.fontSizeInPt, 0.5f)
            }
        }
        // Rendered the same way: ink in the bottom-right corner, none in the skipped page's corners.
        for (index in 0..3) {
            val bitmap = render(numbered, index)
            val corner = pixels(bitmap) { dark(it) }.filter { (x, y) -> x > bitmap.width * 0.8 && y > bitmap.height * 0.85 }
            if (index == 0) assertTrue("Page 1 was skipped", corner.isEmpty()) else assertTrue("No number on page ${index + 1}", corner.isNotEmpty())
        }
    }

    @Test fun pageNumbersGoWhereChosen() {
        val source = import(mixedPages("positions.pdf"))
        fun cell(position: PageNumberPosition, bitmap: Bitmap, x: Int, y: Int): Boolean {
            val column = when (position.column) { 0 -> 0.0..0.2; 1 -> 0.4..0.6; else -> 0.8..1.0 }
            val row = if (position.top) 0.0..0.12 else 0.88..1.0
            return x.toDouble() / bitmap.width in column && y.toDouble() / bitmap.height in row
        }
        for (position in PageNumberPosition.entries) {
            val numbered = engine.addPageNumbers(source, listOf(0), position, PageNumberSize.LARGE, "numbered").file
            val bitmap = render(numbered, 0)
            val ink = pixels(bitmap) { dark(it) }
            assertTrue("No number at $position", ink.any { (x, y) -> cell(position, bitmap, x, y) })
            for (other in PageNumberPosition.entries - position) {
                assertFalse("A number at $other instead of $position", ink.any { (x, y) -> cell(other, bitmap, x, y) })
            }
        }
    }

    /** The share of [pixels] whose colour is a tint of [color] over white (not grey or black). */
    private fun tinted(pixel: Int, color: WatermarkColor): Boolean = when (color) {
        WatermarkColor.RED -> Color.red(pixel) - Color.green(pixel) > 30 && abs(Color.green(pixel) - Color.blue(pixel)) < 20
        WatermarkColor.BLUE -> Color.blue(pixel) - Color.red(pixel) > 30
        else -> error("Not used")
    }

    @Test fun watermarkCrossesEveryPageAlongItsDiagonal() {
        val source = import(mixedPages("watermark.pdf"))
        val watermarked = save(engine.addWatermark(source, "Brouillon é ñ", WatermarkColor.RED, WatermarkOpacity.TRANSLUCENT, "watermarked").file, "watermark_translucent.pdf")
        assertEquals(4, PDDocument.load(watermarked).use { it.numberOfPages })
        // Real text, accents included, after each page's caption.
        assertEquals("CAPTIONABrouillonéñCAPTIONBBrouillonéñCAPTIONCBrouillonéñCAPTIONDBrouillonéñ", text(watermarked))
        for (index in 0..3) {
            val bitmap = render(watermarked, index, 1f)
            val red = pixels(bitmap) { tinted(it, WatermarkColor.RED) }
            assertTrue("No watermark on page ${index + 1}", red.size > 2000)
            // Translucent: the page shows through, so the red is light (except over the black caption).
            assertTrue("Too opaque on page ${index + 1}", red.count { (x, y) -> Color.green(bitmap.getPixel(x, y)) < 140 } < red.size / 50)
            // Centred, and running from the bottom-left corner as shown to the top-right one.
            val cx = red.map { it.first }.average()
            val cy = red.map { it.second }.average()
            assertEquals("Page ${index + 1} centre x", bitmap.width / 2.0, cx, bitmap.width * 0.08)
            assertEquals("Page ${index + 1} centre y", bitmap.height / 2.0, cy, bitmap.height * 0.08)
            val sxx = red.sumOf { (it.first - cx) * (it.first - cx) }
            val syy = red.sumOf { (it.second - cy) * (it.second - cy) }
            val sxy = red.sumOf { (it.first - cx) * (it.second - cy) }
            val angle = Math.toDegrees(0.5 * atan2(2 * sxy, sxx - syy))
            val diagonal = Math.toDegrees(atan2(bitmap.height.toDouble(), bitmap.width.toDouble()))
            // y points down here, so rising to the right is a negative angle.
            assertEquals("Page ${index + 1} angle", -diagonal, angle, 4.0)
        }
    }

    @Test fun opaqueWatermarkHidesThePage() {
        val source = import(asset("alpha.pdf"))
        val opaque = save(engine.addWatermark(source, "COPY", WatermarkColor.BLUE, WatermarkOpacity.OPAQUE, "opaque").file, "watermark_opaque.pdf")
        val bitmap = render(opaque, 0, 1f)
        val blue = pixels(bitmap) { tinted(it, WatermarkColor.BLUE) }
        assertTrue("Opaque blue expected", blue.any { (x, y) -> Color.red(bitmap.getPixel(x, y)) < 60 })
    }

    @Test fun hindiWatermarkIsShaped() {
        val source = import(mixedPages("hindi.pdf"))
        val words = "गोपनीय प्रति"
        val hindi = save(engine.addWatermark(source, words, WatermarkColor.GRAY, WatermarkOpacity.SEMI, "hindi").file, "watermark_hindi.pdf")
        val bitmap = render(hindi, 0, 1f)
        assertTrue(pixels(bitmap) { Color.red(it) in 100..230 && Color.red(it) == Color.green(it) }.size > 2000)
        // The same text drawn by Android, which shapes it: the stamp keeps Android's glyphs.
        val stamp = File(context.cacheDir, "stamp.pdf").apply { writeBytes(WatermarkText.render(words, Color.BLACK)) }
        save(stamp, "watermark_hindi_stamp.pdf")
        assertTrue(text(hindi).startsWith("CAPTIONA"))
    }

    @Test fun textWithoutAFontIsRefused() {
        assertTrue(WatermarkText.canDraw("Confidential é ñ ç ß"))
        assertTrue(WatermarkText.canDraw("गोपनीय क्षत्रिय"))
        assertFalse(WatermarkText.canDraw("Draft ͸"))
        val source = import(asset("alpha.pdf"))
        try {
            engine.addWatermark(source, "͸͹", WatermarkColor.GRAY, WatermarkOpacity.TRANSLUCENT, "none")
            fail("Text without a font should be refused")
        } catch (_: WatermarkFontMissing) {
        }
    }

    @Test fun cancellingLeavesNoOutput() {
        class Stop : RuntimeException()
        val source = import(mixedPages("cancel.pdf"))
        var calls = 0
        try {
            engine.addWatermark(source, "Draft", WatermarkColor.GRAY, WatermarkOpacity.TRANSLUCENT, "cancelled") { if (++calls == 2) throw Stop() }
            fail("The watermark should have been stopped")
        } catch (_: Stop) {
        }
        assertTrue(File(context.cacheDir, "output/tools").listFiles().isNullOrEmpty())
    }

    /** A made-up signature in pad units: a wave above a line, and a dot. */
    private fun signature(ink: SignatureInk = SignatureInk.BLUE) = Signature(
        listOf(
            FloatArray(80) { i -> if (i % 2 == 0) 120f + 9f * (i / 2) else 250f - 80f * sin(0.4f * (i / 2)) },
            floatArrayOf(150f, 360f, 500f, 372f, 820f, 355f),
            floatArrayOf(860f, 140f),
        ),
        ink,
    )

    /** [page] as a viewer shows it. */
    private fun frameOf(page: PDPage) = page.cropBox.let { PageFrame(it.lowerLeftX, it.lowerLeftY, it.width, it.height, page.rotation) }

    /** What [page] draws, decoded. */
    private fun contentOf(page: PDPage): ByteArray = page.contents.use { it.readBytes() }

    /** A pixel of blue ink. */
    private fun blueInk(pixel: Int) = Color.blue(pixel) - Color.red(pixel) > 40

    @Test fun signatureGoesWhereThePreviewPutsIt() {
        val source = import(mixedPages("sign.pdf"))
        val drawn = signature()
        // Up and to the left, away from where a signature starts.
        val placement = SignaturePlacement(centerX = 0.3f, centerY = 0.25f, width = 0.35f)
        val before = PDDocument.load(source.file).use { doc -> doc.pages.map(::contentOf) }
        for (index in 0..3) {
            val signed = save(engine.sign(source, drawn, index, placement, "signed").file, "signed_${index + 1}.pdf")
            val frame = PDDocument.load(signed).use { doc ->
                for ((i, page) in doc.pages.withIndex()) {
                    if (i == index) continue
                    assertTrue("Page ${i + 1} has no signature", imagesOn(page).isEmpty())
                    assertArrayEquals("Page ${i + 1} is unchanged", before[i], contentOf(page))
                }
                val page = doc.getPage(index)
                val frame = frameOf(page)
                val area = SignatureLayout.pdfArea(placement, frame.shownWidth, frame.shownHeight, drawn.aspect)
                val placed = imagesOn(page).single()
                // The image's corners are the area's, the right way up on the page as shown.
                for ((unit, shown) in listOf(
                    (0f to 0f) to (area.x to area.y),
                    (1f to 0f) to (area.x + area.width to area.y),
                    (0f to 1f) to (area.x to area.y + area.height),
                )) {
                    val actual = placed.corner(unit.first, unit.second)
                    val (x, y) = frame.toPage(shown.first, shown.second)
                    assertEquals("Page ${index + 1}, corner $unit, x", x, actual.x, 0.01f)
                    assertEquals("Page ${index + 1}, corner $unit, y", y, actual.y, 0.01f)
                }
                frame
            }
            assertTrue("Page ${index + 1}'s own text is kept", text(signed).contains("CAPTION${listOf("A", "B", "C", "D")[index]}"))

            // Rendered, the blue ink fills just that area of the page as shown (y down here).
            val scale = 2f
            val area = SignatureLayout.pdfArea(placement, frame.shownWidth, frame.shownHeight, drawn.aspect)
            val bitmap = render(signed, index, scale)
            val ink = pixels(bitmap, ::blueInk)
            assertTrue("No signature on page ${index + 1}", ink.size > 500)
            val tolerance = 2 * scale
            assertEquals("Page ${index + 1} left", area.x * scale, ink.minOf { it.first }.toFloat(), tolerance)
            assertEquals("Page ${index + 1} right", (area.x + area.width) * scale, ink.maxOf { it.first } + 1f, tolerance)
            assertEquals("Page ${index + 1} top", (frame.shownHeight - area.y - area.height) * scale, ink.minOf { it.second }.toFloat(), tolerance)
            assertEquals("Page ${index + 1} bottom", (frame.shownHeight - area.y) * scale, ink.maxOf { it.second } + 1f, tolerance)
        }
    }

    @Test fun signatureStartsInTheLowerRight() {
        val source = import(mixedPages("sign_default.pdf"))
        for (index in 0..3) {
            val signed = engine.sign(source, signature(), index, null, "default").file
            val bitmap = render(signed, index, 1f)
            val ink = pixels(bitmap, ::blueInk)
            assertTrue("No signature on page ${index + 1}", ink.isNotEmpty())
            assertTrue("Page ${index + 1}: not on the right", ink.minOf { it.first } > bitmap.width / 2)
            assertTrue("Page ${index + 1}: not at the bottom", ink.minOf { it.second } > bitmap.height * 3 / 4)
        }
    }

    @Test fun signatureImageIsSharpSmallAndTrimmed() {
        val source = import(asset("alpha.pdf"))
        val drawn = signature(SignatureInk.BLUE)
        val signed = save(engine.sign(source, drawn, 1, SignaturePlacement(0.5f, 0.5f, 0.3f), "sharp").file, "signed_sharp.pdf")
        val added = signed.length() - source.file.length()
        assertTrue("The signature added $added bytes", added < 40_000)
        PDDocument.load(signed).use { doc ->
            assertEquals(listOf(0, 1, 0), doc.pages.map { imagesOn(it).size })
            val placed = imagesOn(doc.getPage(1)).single()
            val image = placed.image
            // 300 pixels per inch of the width it is drawn at.
            val origin = placed.corner(0f, 0f)
            val widthPoints = placed.corner(1f, 0f).let { hypot(it.x - origin.x, it.y - origin.y) }
            assertEquals((widthPoints / 72 * 300).roundToInt().toFloat(), image.width.toFloat(), 1f)
            assertEquals(image.width / drawn.aspect, image.height.toFloat(), 1.5f)

            // Every pixel has the ink's colour; the soft mask's alpha alone draws the strokes.
            val colors = (image.cosObject as COSStream).createInputStream().use { it.readBytes() }
            assertEquals(image.width * image.height * 3, colors.size)
            val ink = SignatureInk.BLUE.argb
            assertTrue("Not all the ink's colour", colors.indices.all { colors[it].toInt() and 0xFF == (ink shr (16 - 8 * (it % 3))) and 0xFF })
            val mask = checkNotNull(image.softMask) { "No transparency" }
            assertEquals(image.width, mask.width)
            val alpha = (mask.cosObject as COSStream).createInputStream().use { it.readBytes() }
            assertEquals(mask.width * mask.height, alpha.size)
            fun inked(x: Int, y: Int) = alpha[y * mask.width + x].toInt() and 0xFF > 0
            // Trimmed to the ink: ink close to every edge, none in the corners.
            val near = 0..2
            assertTrue("Top", (0 until mask.width).any { x -> near.any { inked(x, it) } })
            assertTrue("Bottom", (0 until mask.width).any { x -> near.any { inked(x, mask.height - 1 - it) } })
            assertTrue("Left", (0 until mask.height).any { y -> near.any { inked(it, y) } })
            assertTrue("Right", (0 until mask.height).any { y -> near.any { inked(mask.width - 1 - it, y) } })
            assertFalse(inked(0, 0) || inked(mask.width - 1, mask.height - 1))
        }
    }

    @Test fun cancellingASignatureLeavesNoOutput() {
        class Stop : RuntimeException()
        val source = import(mixedPages("sign_cancel.pdf"))
        try {
            engine.sign(source, signature(), 0, null, "cancelled") { throw Stop() }
            fail("Signing should have been stopped")
        } catch (_: Stop) {
        }
        assertTrue(File(context.cacheDir, "output/tools").listFiles().isNullOrEmpty())
    }
}
