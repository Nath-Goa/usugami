package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.nath_goa.usugami.core.CropShare
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.cropPages
import io.github.nath_goa.usugami.data.pagesPerSheet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Page layout's engine: Crop's /CropBox and Pages per sheet's sheets, read back and rendered. */
class PageLayoutDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val engine = PdfToolsEngine(app)
    private val results = File(app.getExternalFilesDir(null), "test-results").apply { mkdirs() }
    private val output = File(app.cacheDir, "output/tools")
    private val imported = ArrayList<PdfInput>()
    private var nextId = 8801L

    @After fun tearDown() {
        imported.forEach(engine::delete)
        engine.clearOutput()
    }

    private fun import(file: File, password: String = ""): PdfInput = engine.import(Uri.fromFile(file), nextId++, password).also { imported += it }

    private fun made(name: String) = File(app.cacheDir, name)

    private fun save(file: File, name: String): File = file.copyTo(File(results, name), overwrite = true)

    @Test fun croppingSetsTheCropBoxOfTheChosenPagesAndKeepsTheRest() {
        val source = import(chapters(made("layout_chapters.pdf")))
        // Page 2 named twice is cropped once, not again inside its new box.
        val result = engine.cropPages(source, CropShare(0.1f, 0.2f, 0.9f, 0.8f), listOf(1, 3, 1), "cropped")
        save(result.file, "layout_cropped.pdf")
        assertEquals(5, result.pageCount)
        PDDocument.load(result.file).use { doc ->
            val a4 = PDRectangle.A4
            for ((index, page) in doc.pages.withIndex()) {
                val box = page.cropBox
                val media = page.mediaBox
                assertEquals(a4.width, media.width, 0.01f)
                assertEquals(a4.height, media.height, 0.01f)
                if (index == 1 || index == 3) {
                    // A share from the top is measured down the page, whose y runs up.
                    assertEquals(0.1f * a4.width, box.lowerLeftX, 0.1f)
                    assertEquals(0.2f * a4.height, box.lowerLeftY, 0.1f)
                    assertEquals(0.9f * a4.width, box.upperRightX, 0.1f)
                    assertEquals(0.8f * a4.height, box.upperRightY, 0.1f)
                } else {
                    assertFalse("Page ${index + 1} is untouched", page.cosObject.containsKey(COSName.CROP_BOX))
                    assertEquals(listOf(media.lowerLeftX, media.lowerLeftY, media.upperRightX, media.upperRightY), listOf(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY))
                }
                // The text stays, also where the box hides it.
                val text = PDFTextStripper().apply {
                    startPage = index + 1
                    endPage = index + 1
                }.getText(doc)
                assertTrue(text, "PAGE ${PAGE_WORDS[index]}" in text)
            }
            var bookmarks = 0
            var item = doc.documentCatalog.documentOutline?.firstChild
            while (item != null) {
                bookmarks++
                item = item.nextSibling
            }
            assertEquals(5, bookmarks)
            assertEquals(listOf("keep", "secret"), doc.documentCatalog.acroForm.fieldTree.map { it.partialName })
            assertEquals(2, doc.getPage(0).annotations.count { it is PDAnnotationLink })
        }
    }

    @Test fun aTurnedPageAlreadyCroppedIsCroppedWhereItShows() {
        // A 600 × 800 page cropped to (100, 100)-(500, 700) and turned a quarter clockwise: it shows
        // 600 wide and 400 high, the crop box's lower-left corner at its top-left. Blue all over, but
        // red where the top-left quarter of what shows lies on the page: x 100 to 300, y 100 to 400.
        // Its page tree lists it twice, as a damaged PDF can: it is cropped once all the same.
        val file = made("layout_turned.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(600f, 800f))
            doc.addPage(page)
            doc.addPage(page)
            page.cropBox = PDRectangle(100f, 100f, 400f, 600f)
            page.rotation = 90
            PDPageContentStream(doc, page).use { content ->
                content.setNonStrokingColor(0f, 0f, 1f)
                content.addRect(0f, 0f, 600f, 800f)
                content.fill()
                content.setNonStrokingColor(1f, 0f, 0f)
                content.addRect(100f, 100f, 200f, 300f)
                content.fill()
            }
            doc.save(file)
        }
        val result = engine.cropPages(import(file), CropShare(0f, 0f, 0.5f, 0.5f), null, "turned")
        save(result.file, "layout_turned_cropped.pdf")
        assertEquals(2, result.pageCount)
        PDDocument.load(result.file).use { doc ->
            val page = doc.getPage(0)
            assertTrue(page.cosObject === doc.getPage(1).cosObject)
            assertEquals(90, page.rotation)
            assertEquals(800f, page.mediaBox.height, 0.01f)
            val box = page.cropBox
            assertEquals(listOf(100f, 100f, 300f, 400f), listOf(box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY).map { Math.round(it * 10) / 10f })
        }
        val shown = render(result.file, 0)
        // A quarter of what showed: 300 wide, 200 high.
        assertEquals(300, shown.width)
        assertEquals(200, shown.height)
        val all = shown.width * shown.height
        assertTrue("Mostly red", count(shown, ::red) > all * 0.97)
        assertEquals("No blue", 0, count(shown, ::blue))
        shown.recycle()
    }

    @Test fun sheetsHoldThePagesInReadingOrderAsText() {
        val locked = made("layout_locked.pdf")
        PDDocument.load(chapters(made("layout_plain.pdf"))).use { doc ->
            doc.protect(StandardProtectionPolicy("owner", "user", AccessPermission()).apply { encryptionKeyLength = 128 })
            doc.save(locked)
        }
        val result = engine.pagesPerSheet(import(locked, "user"), 4, PageSize.A4, border = true, fileName = "sheets")
        save(result.file, "layout_sheets.pdf")
        assertEquals(2, result.pageCount)
        // Opens without a password.
        PDDocument.load(result.file).use { doc ->
            assertFalse(doc.isEncrypted)
            assertEquals(2, doc.numberOfPages)
            for (sheet in doc.pages) {
                assertEquals(PDRectangle.A4.width, sheet.mediaBox.width, 0.01f)
                assertEquals(PDRectangle.A4.height, sheet.mediaBox.height, 0.01f)
                assertEquals(0, sheet.rotation)
                assertTrue("No links or comments", sheet.annotations.isEmpty())
                // Nothing was turned into a picture: the sheet draws forms only.
                val resources = sheet.resources
                assertTrue(resources.xObjectNames.all { resources.getXObject(it) is PDFormXObject })
            }
            assertEquals(listOf(4, 1), doc.pages.map { it.resources.xObjectNames.count() })
            val texts = (1..2).map { number ->
                PDFTextStripper().apply {
                    sortByPosition = true
                    startPage = number
                    endPage = number
                }.getText(doc)
            }
            // Across, then down. The fields' values lie between the pages' headings.
            val at = PAGE_WORDS.take(4).map { texts[0].indexOf("PAGE $it") }
            assertTrue(texts[0], at.all { it >= 0 } && at == at.sorted())
            assertTrue("The filled-in field is drawn", "KEEP VALUE" in texts[0])
            assertFalse("PAGE FIVE" in texts[0])
            assertEquals("PAGE FIVE", texts[1].trim())
            // A border round each page: one rectangle stroked for each.
            val content = doc.getPage(0).contents.use { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertEquals(4, Regex("""\bre\s+S\b""").findAll(content).count())
            val catalog = doc.documentCatalog.cosObject
            assertFalse("No form", catalog.containsKey(COSName.ACRO_FORM))
            assertFalse("No bookmarks", catalog.containsKey(COSName.OUTLINES))
            assertFalse("No named destinations", catalog.containsKey(COSName.DESTS) || catalog.containsKey(COSName.NAMES))
        }
    }

    @Test fun turnedAndCroppedPagesLandUprightInTheirCells() {
        // Page 1: 600 × 800 turned a quarter clockwise, so it shows 800 wide and 600 high with the
        // page's lower-left corner at its top-left: red there, 120 by 160 on the page, so a fifth
        // of what shows each way. Page 2: 600 × 800 cropped to its lower 600 points, hiding a blue
        // block above them; a green square inside.
        val file = made("layout_two.pdf")
        PDDocument().use { doc ->
            val turned = PDPage(PDRectangle(600f, 800f)).also(doc::addPage)
            turned.rotation = 90
            PDPageContentStream(doc, turned).use { content ->
                content.setNonStrokingColor(1f, 0f, 0f)
                content.addRect(0f, 0f, 120f, 160f)
                content.fill()
            }
            val cropped = PDPage(PDRectangle(600f, 800f)).also(doc::addPage)
            cropped.cropBox = PDRectangle(0f, 0f, 600f, 600f)
            PDPageContentStream(doc, cropped).use { content ->
                content.setNonStrokingColor(0f, 0f, 1f)
                content.addRect(100f, 650f, 400f, 100f)
                content.fill()
                content.setNonStrokingColor(0f, 1f, 0f)
                content.addRect(100f, 100f, 100f, 100f)
                content.fill()
            }
            doc.save(file)
        }
        val result = engine.pagesPerSheet(import(file), 2, PageSize.A4, border = false, fileName = "two")
        save(result.file, "layout_two_sheets.pdf")
        assertEquals(1, result.pageCount)
        val sheet = render(result.file, 0)
        // A portrait A4 sheet: 595.3 × 841.9 points.
        assertTrue("${sheet.width} × ${sheet.height}", sheet.width in 595..596 && sheet.height in 841..842)
        // Worked by hand: on a portrait A4 sheet, one page above the other (it draws them bigger than
        // side by side), each cell 559.3 × 398.4 points; the first page, 800 × 600 as shown, is scaled
        // by 0.664 to 531.3 × 398.4, 32.0 from the sheet's left and 18.0 from its top. Its red fifth
        // lies from (32, 18) to (138.3, 97.7).
        val redBox = bounds(sheet, ::red)
        assertTrue("Red shows", redBox != null)
        val (left, top, right, bottom) = redBox!!
        assertEquals(32f, left.toFloat(), 2f)
        assertEquals(18f, top.toFloat(), 2f)
        assertEquals(138f, right.toFloat(), 2f)
        assertEquals(98f, bottom.toFloat(), 2f)
        assertEquals("What the crop box hides doesn't show", 0, count(sheet, ::blue))
        val greenBox = bounds(sheet, ::green)
        assertTrue("The second page is in the lower cell", greenBox != null && greenBox[1] > sheet.height / 2)
        sheet.recycle()
    }

    @Test fun cancellingLeavesNoOutput() {
        class Stop : RuntimeException()
        fun stopsOnSecondCall(): () -> Unit {
            var calls = 0
            return { if (++calls == 2) throw Stop() }
        }
        val source = import(chapters(made("layout_cancel.pdf")))
        try {
            engine.cropPages(source, CropShare(0.1f, 0.1f, 0.9f, 0.9f), null, "cropped", stopsOnSecondCall())
            fail("Cropping should have been stopped")
        } catch (_: Stop) {
        }
        assertTrue(output.listFiles().isNullOrEmpty())
        // A PDF made before goes too: the result it was is replaced.
        engine.pagesPerSheet(source, 2, null, border = false, fileName = "before")
        try {
            engine.pagesPerSheet(source, 2, null, border = false, fileName = "sheets", check = stopsOnSecondCall())
            fail("Making the sheets should have been stopped")
        } catch (_: Stop) {
        }
        assertTrue(output.listFiles().isNullOrEmpty())
    }

    @Test fun pagesOnASheetListOnlyTheirOwnPictures() {
        val source = import(sharedResources(made("layout_shared.pdf"), inherited = true))
        val result = engine.pagesPerSheet(source, 4, PageSize.A4, border = false, fileName = "shared")
        save(result.file, "layout_shared_sheets.pdf")
        PDDocument.load(result.file).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val sheet = doc.getPage(0).resources
            val forms = sheet.xObjectNames.map { sheet.getXObject(it) as PDFormXObject }
            assertEquals(3, forms.size)
            val pictures = forms.map { form ->
                val names = form.resources.xObjectNames.toList()
                assertEquals("One picture in each page's list", 1, names.size)
                val picture = form.resources.getXObject(names.single()) as PDImageXObject
                picture.cosObject.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
            }
            assertEquals(IMAGE_MARKERS.map { it + it }.toSet(), pictures.toSet())
        }
    }

    /** Page [index] of [file] as shown, one pixel per point, on white. */
    private fun render(file: File, index: Int): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(index).use { page ->
                    Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }

    private fun count(bitmap: Bitmap, match: (Int) -> Boolean): Int = pixels(bitmap).count(match)

    /** Left, top, right and bottom (exclusive) of the pixels of [bitmap] that [match], y down; null if none. */
    private fun bounds(bitmap: Bitmap, match: (Int) -> Boolean): IntArray? {
        val all = pixels(bitmap)
        var box: IntArray? = null
        for (i in all.indices) {
            if (!match(all[i])) continue
            val x = i % bitmap.width
            val y = i / bitmap.width
            box = box?.apply {
                this[0] = minOf(this[0], x)
                this[1] = minOf(this[1], y)
                this[2] = maxOf(this[2], x + 1)
                this[3] = maxOf(this[3], y + 1)
            } ?: intArrayOf(x, y, x + 1, y + 1)
        }
        return box
    }

    private fun red(pixel: Int) = Color.red(pixel) > 200 && Color.green(pixel) < 60 && Color.blue(pixel) < 60
    private fun blue(pixel: Int) = Color.blue(pixel) > 200 && Color.red(pixel) < 60 && Color.green(pixel) < 60
    private fun green(pixel: Int) = Color.green(pixel) > 200 && Color.red(pixel) < 60 && Color.blue(pixel) < 60
}
