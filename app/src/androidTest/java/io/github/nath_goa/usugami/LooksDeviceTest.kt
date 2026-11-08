package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.Compressor
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.Margin
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.core.PageOrientation
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.PageLooks
import io.github.nath_goa.usugami.data.PdfExporter
import io.github.nath_goa.usugami.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Page looks: a photographed page in shadow made into a PDF with each look, stored as the look needs
 * (black and white a bit a pixel with CCITT G4, grey as a one-component JPEG) at the compression
 * levels' resolutions, far smaller in black and white, and drawn right by Android's PDF renderer
 * and PDFBox's, with brightness and contrast reaching the page, and the colour looks made in the
 * picture itself; and through the view model, a look chosen for a page or for every page, the next
 * photo starting with it (not with a look a page only kept), and the looks kept through the saved
 * state.
 */
class LooksDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val dir = File(app.cacheDir, "test-looks")
    private val a4 = LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.NONE)

    @After fun tearDown() {
        dir.deleteRecursively()
        PdfExporter(app).clearOutput()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!onMain(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    /** [photo] with [look] and [adjustments], as a PDF of one A4 page at [compression], copied out of the exporter's folder. */
    private fun export(
        photo: File,
        width: Int,
        height: Int,
        look: PageLook,
        compression: Compression,
        adjustments: LookAdjustments = LookAdjustments.NONE,
    ): File {
        val item = ImageItem(1, photo.path, photo.name, ImageFormat.JPEG, width, height, 1, look = look, adjustments = adjustments)
        val made = runBlocking { PdfExporter(app).export(listOf(item), a4, compression, true, "Scan", "Usugami") {} }.single().file
        val adjusted = if (adjustments.isNone) "" else "-${adjustments.brightness}-${adjustments.contrast}"
        return made.copyTo(File(dir, "${look.name}$adjusted-${compression.name}.pdf"), overwrite = true)
    }

    /** Page 1 of [file], [width] pixels wide, by Android's renderer, on white. */
    private fun androidRender(file: File, width: Int): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    Bitmap.createBitmap(width, width * page.height / page.width, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    /** Page 1 of [file], [width] pixels wide, by PDFBox. */
    private fun pdfBoxRender(file: File, width: Int): Bitmap = PDDocument.load(file).use { doc ->
        PDFRenderer(doc).renderImage(0, width / doc.getPage(0).mediaBox.width)
    }

    private fun luma(c: Int) = ShadedPages.luma(c)

    @Test fun eachLookIsStoredAsItNeedsAndDrawnRightByBothRenderers() {
        val photo = ShadedPages.photo(File(dir, "page.jpg"), 1240)
        val height = 1240 * 297 / 210
        for (look in PageLook.entries) {
            val pdf = export(photo, 1240, height, look, Compression.RECOMMENDED)
            PDDocument.load(pdf).use { doc ->
                val image = imagesOn(doc.getPage(0)).single().image
                val filter = image.cosObject.getDictionaryObject(COSName.FILTER)
                val expected = when {
                    look.isBilevel -> Triple(COSName.CCITTFAX_DECODE, "DeviceGray", 1)
                    look == PageLook.GRAYSCALE -> Triple(COSName.DCT_DECODE, "DeviceGray", 8)
                    else -> Triple(COSName.DCT_DECODE, "DeviceRGB", 8)
                }
                assertEquals("$look", expected, Triple(filter, image.colorSpace.name, image.bitsPerComponent))
                // A4 at 150 dpi.
                assertEquals(1240, image.width)
            }
            for ((renderer, page) in listOf("Android" to androidRender(pdf, 1240), "PDFBox" to pdfBoxRender(pdf, 1240))) {
                val what = "$look by $renderer"
                val shadedPaper = ShadedPages.share(page, ShadedPages.SHADED_PAPER) { luma(it) >= 225 }
                val litPaper = ShadedPages.share(page, ShadedPages.LIT_PAPER) { luma(it) >= 225 }
                val bar = ShadedPages.share(page, ShadedPages.BAR) { luma(it) < 70 }
                Log.i("UsugamiTest", "$what: paper white ${"%.4f".format(shadedPaper)} in the shadow, ${"%.4f".format(litPaper)} in the light; bar black ${"%.4f".format(bar)}")
                if (look == PageLook.INK_SAVER) {
                    // Ink saver leaves the bar hollow: white inside its outline.
                    val inside = ShadedPages.share(page, ShadedPages.BAR, 0.01f) { luma(it) >= 225 }
                    assertTrue("$what: inside the bar $inside", inside > 0.98)
                } else {
                    assertTrue("$what: bar $bar", bar > 0.98)
                }
                if (look == PageLook.ORIGINAL) {
                    // The photo as taken: the paper in the shadow is grey.
                    assertTrue("$what: $shadedPaper", shadedPaper < 0.01)
                    continue
                }
                if (look == PageLook.LIGHTEN) {
                    // Lighter than the photo's (about 110 in the shadow, 175 in the light), the shadow softened, not taken out.
                    val shaded = ShadedPages.share(page, ShadedPages.SHADED_PAPER) { luma(it) in 170..224 }
                    val lit = ShadedPages.share(page, ShadedPages.LIT_PAPER) { luma(it) >= 210 }
                    assertTrue("$what: paper in the shadow $shaded, in the light $lit", shaded > 0.99 && lit > 0.995)
                    continue
                }
                assertTrue("$what: paper in the shadow $shadedPaper", shadedPaper > 0.995)
                assertTrue("$what: paper in the light $litPaper", litPaper > 0.995)
                if (!look.isColour) {
                    // Grey: every pixel drawn has red, green and blue alike.
                    val gray = ShadedPages.share(page, android.graphics.RectF(0f, 0f, 1f, 1f), 0f) {
                        max(Color.red(it), max(Color.green(it), Color.blue(it))) - min(Color.red(it), min(Color.green(it), Color.blue(it))) <= 2
                    }
                    assertTrue("$what: grey $gray", gray > 0.999)
                }
            }
        }
        // Contrast all the way down fades the ink: the bar grey, the paper still white.
        val faded = androidRender(export(photo, 1240, height, PageLook.ENHANCED, Compression.RECOMMENDED, LookAdjustments(contrast = -LookAdjustments.MAX)), 1240)
        val bar = ShadedPages.share(faded, ShadedPages.BAR) { luma(it) < 70 }
        val paper = ShadedPages.share(faded, ShadedPages.LIT_PAPER) { luma(it) >= 225 }
        assertTrue("Faded: bar black $bar, paper white $paper", bar < 0.05 && paper > 0.995)
    }

    @Test fun blackAndWhitePagesAreFarSmallerAtEveryLevel() {
        // A page photographed at 300 dpi, made into PDFs at each level: the dpi rules hold for every
        // look, and black and white takes a fraction of the photo's size.
        val width = 2480
        val height = 3508
        val photo = ShadedPages.photo(File(dir, "large.jpg"), width)
        val report = StringBuilder("KB per A4 page:")
        val all = LinkedHashMap<Compression, Map<PageLook, Long>>()
        for (compression in Compression.entries) {
            val sizes = PageLook.entries.associateWith { look ->
                val pdf = export(photo, width, height, look, compression)
                PDDocument.load(pdf).use { doc ->
                    val image = imagesOn(doc.getPage(0)).single().image
                    val (w, h) = Compressor.scaledSize(width, height, Compressor.scaleFor(width, height, a4, compression))
                    assertEquals("$look at $compression", w to h, image.width to image.height)
                }
                pdf.length()
            }
            report.append("\n  ${compression.name} (${compression.dpi} dpi): ")
                .append(sizes.entries.joinToString { (look, bytes) -> "${look.name} ${bytes / 1024}" })
            all[compression] = sizes
        }
        Log.i("UsugamiTest", report.toString())
        for ((compression, sizes) in all) {
            val photoSize = sizes.getValue(PageLook.ORIGINAL)
            val bilevel = sizes.getValue(PageLook.BLACK_WHITE)
            assertTrue("$compression: black and white $bilevel, photo $photoSize", bilevel * 4 < photoSize)
            // One grey component takes less than three of colour.
            assertTrue("$compression: $sizes", sizes.getValue(PageLook.GRAYSCALE) < sizes.getValue(PageLook.ENHANCED))
        }
    }

    @Test fun aLookIsChosenPerPageOrForEveryPageAndKept() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        val photos = listOf("one.jpg", "two.jpg").map { PagePhotos.jpeg(PagePhotos.photo(), File(dir, it)) }
        onMain { vm.addImages(photos.map { Uri.fromFile(it) }) }
        waitUntil("the photos") { vm.items.value.size == 2 && vm.pendingImports.value == 0 }
        val (first, second) = onMain { vm.items.value }

        // The first page cropped, in black and white.
        onMain { vm.openCrop(first.id) }
        waitUntil("the crop screen") { vm.crop.value != null }
        val session = onMain { vm.crop.value!! }
        assertEquals(PageLook.ORIGINAL, session.look)
        assertTrue(onMain { vm.finishCrop(session.shown(PagePhotos.QUAD), PageLook.BLACK_WHITE) })
        waitUntil("the page to be cropped") { vm.cropping.value.isEmpty() && vm.items.value.first().original != null }
        assertEquals(listOf(PageLook.BLACK_WHITE, PageLook.ORIGINAL), onMain { vm.items.value.map { it.look } })

        // The second opens on its own look, and is left whole, in grey, with every page.
        onMain { vm.openCrop(second.id) }
        waitUntil("the second crop screen") { vm.crop.value != null }
        assertEquals(PageLook.ORIGINAL, onMain { vm.crop.value!!.look })
        assertTrue(onMain { vm.finishCrop(CropQuad.WHOLE, PageLook.GRAYSCALE, allPages = true) })
        val items = onMain { vm.items.value }
        assertEquals(listOf(PageLook.GRAYSCALE, PageLook.GRAYSCALE), items.map { it.look })
        assertEquals(second.copy(look = PageLook.GRAYSCALE), items[1])
        assertTrue("The first page stays cropped", items[0].original != null)

        // Opened again, a page starts on its look; the next photo taken starts on the look chosen last.
        onMain { vm.openCrop(first.id) }
        waitUntil("the crop screen again") { vm.crop.value != null }
        assertEquals(PageLook.GRAYSCALE, onMain { vm.crop.value!!.look })
        // Leaving with the look changed only: the corners stay, the look is the new one.
        assertTrue(onMain { vm.finishCrop(vm.crop.value!!.shown(items[0].original!!.corners), PageLook.ENHANCED) })
        assertEquals(items[0].copy(look = PageLook.ENHANCED), onMain { vm.items.value[0] })
        val uri = onMain { vm.newCapture() }
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
        onMain { vm.photoTaken(true) }
        waitUntil("the photo's crop screen") { vm.crop.value?.fromCamera == true }
        assertEquals(PageLook.ENHANCED, onMain { vm.crop.value!!.look })
        onMain { vm.closeCrop() }

        // Android stops Usugami: the pages come back with their looks.
        val kept = onMain { vm.items.value }
        val restored = onMain { MainViewModel(app, SavedStateHandle(mapOf("items" to MainViewModel.packItems(kept)))) }
        runBlocking { restored.awaitFileWork() }
        assertEquals(kept, onMain { restored.items.value })
        assertEquals(listOf(PageLook.ENHANCED, PageLook.GRAYSCALE), kept.map { it.look })

        onMain { restored.clearAll() }
        runBlocking { restored.awaitFileWork() }
    }

    @Test fun aLookOnlyKeptLeavesTheNextPhotosLook() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        onMain { vm.addImages(listOf(Uri.fromFile(PagePhotos.jpeg(PagePhotos.photo(), File(dir, "gallery.jpg"))))) }
        waitUntil("the photo") { vm.items.value.size == 1 && vm.pendingImports.value == 0 }
        val gallery = onMain { vm.items.value.single() }
        fun takePhoto() {
            val uri = onMain { vm.newCapture() }
            app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
            onMain { vm.photoTaken(true) }
            waitUntil("the photo's crop screen") { vm.crop.value?.fromCamera == true }
        }

        // A page scanned in black and white.
        takePhoto()
        assertTrue(onMain { vm.finishCrop(vm.crop.value!!.shown(PagePhotos.QUAD), PageLook.BLACK_WHITE) })
        // The photo from the gallery has its corners put right, its look left as it was.
        onMain { vm.openCrop(gallery.id) }
        waitUntil("its crop screen") { vm.crop.value?.itemId == gallery.id }
        val session = onMain { vm.crop.value!! }
        assertEquals(PageLook.ORIGINAL, session.look)
        assertTrue(onMain { vm.finishCrop(session.shown(PagePhotos.QUAD), session.look) })
        // The next page scanned starts in black and white, as the one before.
        takePhoto()
        assertEquals(PageLook.BLACK_WHITE, onMain { vm.crop.value!!.look })
        onMain { vm.closeCrop() }

        waitUntil("the pages to be cropped") { vm.cropping.value.isEmpty() && vm.pendingImports.value == 0 }
        assertEquals(listOf(PageLook.ORIGINAL, PageLook.BLACK_WHITE), onMain { vm.items.value.map { it.look } })
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    @Test fun eachColourLookIsMadeInThePictureAsBesideIt() {
        // As a PDF's page is made, without a second picture as large.
        val photo = PagePhotos.photo()
        val adjustments = LookAdjustments(brightness = 20, contrast = 30)
        for (look in PageLook.entries.filter { it.isColour }) {
            val beside = PageLooks.applied(photo, look, adjustments)
            val inPlace = photo.copy(Bitmap.Config.ARGB_8888, true)
            PageLooks.colourInPlace(inPlace, look, adjustments)
            assertFalse(inPlace.hasAlpha())
            val expected = IntArray(photo.width * photo.height).also { beside.getPixels(it, 0, photo.width, 0, 0, photo.width, photo.height) }
            val actual = IntArray(photo.width * photo.height).also { inPlace.getPixels(it, 0, photo.width, 0, 0, photo.width, photo.height) }
            assertTrue("$look", expected.contentEquals(actual))
        }
    }
}
