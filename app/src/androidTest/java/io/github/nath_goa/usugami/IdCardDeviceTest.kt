package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.core.Margin
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.core.PageOrientation
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.ImageStore
import io.github.nath_goa.usugami.data.PdfExporter
import io.github.nath_goa.usugami.ui.CardScan
import io.github.nath_goa.usugami.ui.CardSide
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.SortMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * An ID card's two sides go on one page of the PDF, at the card's real size, and are one page in the
 * editor's list; the ID card flow takes a card's front and back one after another through the crop
 * screen, and is kept in the saved state.
 */
class IdCardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = File(context.cacheDir, "test-id-card").apply { deleteRecursively(); mkdirs() }
    private val exporter = PdfExporter(context)

    init { PDFBoxResourceLoader.init(context) }

    @After fun cleanUp() {
        exporter.clearOutput()
        dir.deleteRecursively()
    }

    /**
     * An ordinary page, then a card's front and back: two pages, the card's sides on the second, each
     * 85.6 mm wide, centred across the page, the front above the back, and shrunk to twice
     * Recommended's resolution for that width (1011 pixels of 1712). Made one PDF per page, the card
     * is one PDF named after its front.
     */
    @Test fun bothSidesGoOnOnePageAtTheCardsSize() = runBlocking {
        val page = PagePhotos.jpeg(PagePhotos.page(), File(dir, "Page.jpg"))
        val front = PagePhotos.jpeg(cardSide(Color.rgb(205, 222, 240), seed = 1), File(dir, "ID_card_20261112_101500.jpg"))
        val back = PagePhotos.jpeg(cardSide(Color.rgb(236, 228, 210), seed = 2), File(dir, "ID_card_20261112_101530.jpg"))
        val items = listOf(
            ImageItem(1, page.path, "Page.jpg", ImageFormat.JPEG, PagePhotos.PAGE_WIDTH, PagePhotos.PAGE_HEIGHT, ExifInterface.ORIENTATION_NORMAL),
            ImageItem(2, front.path, "ID_card_20261112_101500", ImageFormat.JPEG, CARD_WIDTH, CARD_HEIGHT, ExifInterface.ORIENTATION_NORMAL, card = 7),
            ImageItem(3, back.path, "ID_card_20261112_101530", ImageFormat.JPEG, CARD_WIDTH, CARD_HEIGHT, ExifInterface.ORIENTATION_NORMAL, card = 7),
        )
        val settings = LayoutSettings(PageSize.A4, PageOrientation.PORTRAIT, Margin.SMALL)
        val progress = ArrayList<Int>()

        val merged = exporter.export(items, settings, Compression.RECOMMENDED, true, "Documents", "Usugami") { progress += it }.single()
        assertEquals(2, merged.pageCount)
        assertEquals(listOf(0, 1, 1), merged.images.map { it.page })
        assertEquals(items, merged.images.map { it.item })
        // The progress counts images, as it always has.
        assertEquals(listOf(1, 3), progress)
        PDDocument.load(merged.file).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertEquals(1, imagesOn(doc.getPage(0)).size)
            val card = doc.getPage(1)
            val box = card.mediaBox
            val sides = imagesOn(card)
            assertEquals(2, sides.size)
            val cardWidth = 85.6 * 72 / 25.4
            for (side in sides) {
                val bottomLeft = side.corner(0f, 0f)
                val bottomRight = side.corner(1f, 0f)
                assertEquals("Width on the page", cardWidth, (bottomRight.x - bottomLeft.x).toDouble(), 0.5)
                assertEquals("Centred across", box.width / 2.0, (bottomLeft.x + bottomRight.x) / 2.0, 0.5)
                assertTrue("Shrunk to ${side.image.width} pixels", side.image.width in 1010..1012)
            }
            // The front is drawn first and lies above the back (PDF's y grows upwards).
            assertTrue("Front above back", sides[0].corner(0f, 0f).y > sides[1].corner(0f, 1f).y)
            // The pair is centred down the page.
            val above = box.height - sides[0].corner(0f, 1f).y
            val below = sides[1].corner(0f, 0f).y
            assertEquals("Centred down", above.toDouble(), below.toDouble(), 0.5)
        }

        progress.clear()
        val single = exporter.export(items, settings, Compression.RECOMMENDED, false, "Documents", "Usugami") { progress += it }
        assertEquals(listOf("Page.pdf", "ID_card_20261112_101500.pdf"), single.map { it.name })
        assertEquals(listOf(1, 1), single.map { it.pageCount })
        assertEquals(listOf(0, 0), single[1].images.map { it.page })
        assertEquals(front.length() + back.length(), single[1].originalBytes)
        assertEquals(listOf(1, 3), progress)
        PDDocument.load(single[1].file).use { doc ->
            assertEquals(1, doc.numberOfPages)
            assertEquals(2, imagesOn(doc.getPage(0)).size)
        }
    }

    /**
     * In the view model a card's two sides are one page: their card number comes back from the saved
     * state, and sorting, reversing, moving and removing take both sides together, the front staying
     * above the back. Done on the images instead, each step below would split the card or turn it over.
     */
    @Test fun aCardsSidesAreSortedMovedAndRemovedAsOnePage() {
        val app = context.applicationContext as Application
        val store = ImageStore(app)
        fun item(id: Long, name: String, card: Long): ImageItem {
            val file = PagePhotos.jpeg(Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888), store.fileFor(id, ImageFormat.JPEG))
            return ImageItem(id, file.path, name, ImageFormat.JPEG, 64, 40, ExifInterface.ORIENTATION_NORMAL, card = card)
        }
        val page = item(1, "Page.jpg", 0)
        val front = item(2, "ID_card_20261112_101500", 7)
        val back = item(3, "ID_card_20261112_101530", 7)

        // ("items" is MainViewModel's saved-state key.)
        val vm = onMain { MainViewModel(app, SavedStateHandle(mapOf("items" to MainViewModel.packItems(listOf(page, front, back))))) }
        runBlocking { vm.awaitFileWork() }
        assertEquals(listOf(page, front, back), onMain { vm.items.value })

        fun ids() = onMain { vm.items.value.map { it.id } }
        onMain { vm.sort(SortMode.REVERSE) }
        assertEquals(listOf(2L, 3L, 1L), ids())
        // By the pages' first names: the card ("ID_card_…") after "Page.jpg", still front then back.
        onMain { vm.sort(SortMode.NAME_DESC) }
        assertEquals(listOf(1L, 2L, 3L), ids())
        onMain { vm.moveBy(back.id, -1) }
        assertEquals(listOf(2L, 3L, 1L), ids())
        onMain { vm.move(front.id, page.id) }
        assertEquals(listOf(1L, 2L, 3L), ids())

        onMain { vm.removePage(back.id) }
        assertEquals(listOf(page), onMain { vm.items.value })
        runBlocking { vm.awaitFileWork() }
        assertFalse(File(front.path).exists())
        assertFalse(File(back.path).exists())
        assertTrue(File(page.path).isFile)

        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /**
     * The ID card flow through the view model: the front a photo taken, cropped and given a look,
     * after which the flow asks for the back with that look; the back an image chosen, discarded on
     * its crop screen (the flow asks for the back again) and chosen again. Done there, the flow is
     * over: two images with one card number, next to each other, named after the card, which are one
     * page to remove.
     */
    @Test fun aCardIsScannedSideBySideThroughTheViewModel() {
        val app = context.applicationContext as Application
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        onMain { vm.startCard() }
        val scan = checkNotNull(onMain { vm.card.value })
        assertEquals(CardSide.FRONT, scan.side)
        assertTrue(onMain { vm.scanUnderWay() })

        // The front: a photo taken. Its crop screen is the front's, and starts on the Original look.
        val uri = onMain { vm.newCapture() }
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
        onMain { vm.photoTaken(true) }
        waitUntil("the front's crop screen") { vm.crop.value != null }
        val front = onMain { vm.crop.value!! }
        assertEquals(CardSide.FRONT, front.side)
        assertTrue(front.cardScan)
        assertEquals(PageLook.ORIGINAL, front.look)
        assertTrue(onMain { vm.finishCrop(front.shown(PagePhotos.QUAD), look = PageLook.GRAYSCALE) })
        assertEquals(scan.copy(side = CardSide.BACK, look = PageLook.GRAYSCALE), onMain { vm.card.value })
        assertTrue(onMain { vm.scanUnderWay() })

        // The back: an image chosen, with the front's look; discarded, the flow still wants the back.
        val picked = Uri.fromFile(PagePhotos.jpeg(PagePhotos.photo(), File(dir, "Back.jpg")))
        onMain { vm.cardImage(picked) }
        waitUntil("the back's crop screen") { vm.crop.value != null }
        val back = onMain { vm.crop.value!! }
        assertEquals(CardSide.BACK, back.side)
        assertTrue(back.cardScan)
        assertEquals(PageLook.GRAYSCALE, back.look)
        assertEquals(2, onMain { vm.items.value.size })
        onMain { vm.closeCrop() }
        assertNull(onMain { vm.crop.value })
        assertEquals(1, onMain { vm.items.value.size })
        assertEquals(CardSide.BACK, onMain { vm.card.value?.side })

        onMain { vm.cardImage(picked) }
        waitUntil("the back's crop screen, again") { vm.crop.value != null }
        val again = onMain { vm.crop.value!! }
        assertNotEquals(back.itemId, again.itemId)
        // As the crop screen does, with the look it shows (the front's).
        assertTrue(onMain { vm.finishCrop(again.shown(PagePhotos.QUAD), look = again.look) })
        assertNull(onMain { vm.card.value })
        assertFalse(onMain { vm.scanUnderWay() })
        waitUntil("the sides to be cropped") { vm.cropping.value.isEmpty() && vm.pendingImports.value == 0 }
        val sides = onMain { vm.items.value }
        assertEquals(listOf(front.itemId, again.itemId), sides.map { it.id })
        assertTrue(sides.all { it.card == scan.card && it.name.startsWith("ID_card_") && it.original != null })
        assertEquals(listOf(PageLook.GRAYSCALE, PageLook.GRAYSCALE), sides.map { it.look })
        // The user's own image is left as it was.
        assertTrue(File(picked.path!!).isFile)

        onMain { vm.removePage(sides[1].id) }
        assertTrue(onMain { vm.items.value.isEmpty() })
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /**
     * Cancel pressed on the front's prompt while the front is still on its way, a photo taken or an
     * image chosen: it is not added, and no crop screen opens.
     */
    @Test fun aSideOnItsWayWhenTheFlowEndsIsNotAdded() {
        val app = context.applicationContext as Application
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        onMain { vm.startCard() }
        val uri = onMain { vm.newCapture() }
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
        onMain {
            vm.photoTaken(true)
            vm.endCard()
        }
        waitUntil("the photo to be dealt with") { vm.pendingImports.value == 0 && !vm.openingCrop.value }
        assertTrue(onMain { vm.items.value.isEmpty() })
        assertNull(onMain { vm.crop.value })

        val picked = Uri.fromFile(PagePhotos.jpeg(PagePhotos.photo(), File(dir, "Front.jpg")))
        onMain {
            vm.startCard()
            vm.cardImage(picked)
            vm.endCard()
        }
        waitUntil("the image to be dealt with") { vm.pendingImports.value == 0 && !vm.openingCrop.value }
        assertTrue(onMain { vm.items.value.isEmpty() })
        assertNull(onMain { vm.crop.value })
        assertTrue(File(picked.path!!).isFile)
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /**
     * At the back's prompt, Android stopping Usugami keeps the card's front with its card number and
     * the flow asking for the back. No back side then leaves a card with its front alone, whose Add
     * back side asks for the back again. With the front gone meanwhile (its copy cleared), Done on
     * the back's crop screen ends the flow, the card keeping its back alone.
     */
    @Test fun aCardAndItsFlowSurviveTheSavedState() {
        val app = context.applicationContext as Application
        val handle = SavedStateHandle()
        val vm = onMain { MainViewModel(app, handle) }
        runBlocking { vm.awaitFileWork() }
        onMain { vm.startCard() }
        val uri = onMain { vm.newCapture() }
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
        onMain { vm.photoTaken(true) }
        waitUntil("the front's crop screen") { vm.crop.value != null }
        onMain { vm.finishCrop(vm.crop.value!!.shown(PagePhotos.QUAD), look = PageLook.BLACK_WHITE) }
        waitUntil("the front to be cropped") { vm.cropping.value.isEmpty() && vm.items.value.single().original != null }
        val front = onMain { vm.items.value.single() }
        val scan = checkNotNull(onMain { vm.card.value })
        assertEquals(CardScan(front.card, CardSide.BACK, PageLook.BLACK_WHITE), scan)
        // The flow is written to the saved state as it changes. ("items" and "card" are MainViewModel's saved-state keys.)
        waitUntil("the flow in the saved state") { handle.get<CardScan>("card") == scan }

        val restored = onMain { MainViewModel(app, SavedStateHandle(mapOf("items" to MainViewModel.packItems(listOf(front)), "card" to handle.get<CardScan>("card")))) }
        runBlocking { restored.awaitFileWork() }
        assertEquals(listOf(front), onMain { restored.items.value })
        assertEquals(scan, onMain { restored.card.value })
        assertTrue(onMain { restored.scanUnderWay() })

        // No back side: the card keeps its front alone; Add back side asks for the back, with the front's look.
        onMain { restored.endCard() }
        assertNull(onMain { restored.card.value })
        assertEquals(listOf(front), onMain { restored.items.value })
        onMain { restored.addCardBack(front.card) }
        assertEquals(scan, onMain { restored.card.value })

        // The back's crop screen, and the front gone meanwhile: the flow doesn't ask for a second back.
        val picked = Uri.fromFile(PagePhotos.jpeg(PagePhotos.photo(), File(dir, "Back.jpg")))
        onMain { restored.cardImage(picked) }
        waitUntil("the back's crop screen") { restored.crop.value != null }
        val back = onMain { restored.crop.value!! }
        assertEquals(CardSide.BACK, back.side)
        onMain { restored.remove(front.id) }
        assertTrue(onMain { restored.finishCrop(back.shown(PagePhotos.QUAD), look = back.look) })
        assertNull(onMain { restored.card.value })
        assertEquals(listOf(back.itemId), onMain { restored.items.value.map { it.id } })
        waitUntil("the back to be cropped") { restored.cropping.value.isEmpty() && restored.pendingImports.value == 0 }

        onMain { restored.clearAll() }
        assertNull(onMain { restored.card.value })
        runBlocking { restored.awaitFileWork() }
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!onMain(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /**
     * A card's side, [CARD_WIDTH] × [CARD_HEIGHT], in [paper]: a photo box, lines of print and a
     * band at the foot, all with a fine grain, so that a copy shrunk for the page is smaller than the
     * picture and the picture is not kept as it is.
     */
    private fun cardSide(paper: Int, seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(CARD_WIDTH, CARD_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(paper)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.rgb(90, 100, 120)
        canvas.drawRect(80f, 160f, 520f, 720f, paint)
        paint.color = Color.rgb(40, 40, 50)
        for (line in 0 until 6) canvas.drawRect(620f, 200f + line * 90f, 1500f - line * 60f, 230f + line * 90f, paint)
        canvas.drawRect(0f, 880f, CARD_WIDTH.toFloat(), 1000f, paint)
        val random = Random(seed)
        val row = IntArray(CARD_WIDTH)
        for (y in 0 until CARD_HEIGHT) {
            bitmap.getPixels(row, 0, CARD_WIDTH, 0, y, CARD_WIDTH, 1)
            for (x in row.indices) {
                val n = random.nextInt(-14, 15)
                val c = row[x]
                row[x] = Color.rgb((Color.red(c) + n).coerceIn(0, 255), (Color.green(c) + n).coerceIn(0, 255), (Color.blue(c) + n).coerceIn(0, 255))
            }
            bitmap.setPixels(row, 0, CARD_WIDTH, 0, y, CARD_WIDTH, 1)
        }
        return bitmap
    }

    private companion object {
        /** About twice what a side keeps at Recommended (1011 × 638: 85.6 mm at twice its 150 dpi), so that it is shrunk. */
        const val CARD_WIDTH = 1712
        const val CARD_HEIGHT = 1080
    }
}
