package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.SystemClock
import android.system.Os
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.CropPoint
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageCrop
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.OriginalImage
import io.github.nath_goa.usugami.data.PageCropper
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.ScanQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Cropping a page out of a photo: the page found on it, straightened onto a rectangle (also from a
 * photo stored sideways with an EXIF orientation, and a PNG), and through the view model a photo
 * taken with the camera app added and cropped, cropped again from the original, put back whole,
 * discarded, kept through the saved state, and a photo not taken leaving nothing behind; a crop not
 * finished when Android stops Usugami made again (from the photo when the page cropped before has
 * gone, and not at all when the photo has), a photo back as Usugami starts again kept, a page removed
 * while it is cropped going quietly, and a page outlasting the image it was cropped from kept; the
 * turn, look and adjustments chosen reaching the page (or every page, also those still being added),
 * and a scan of uploaded images taking each in turn, leaving out one removed, stopping when closed and
 * going on after Android stops Usugami.
 */
class CropDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val dir = File(app.cacheDir, "test-crop")

    @After fun tearDown() {
        dir.deleteRecursively()
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

    private fun captures(): List<String> = File(app.cacheDir, "camera").list().orEmpty().toList()

    private fun original(file: File, format: ImageFormat, width: Int, height: Int, exif: Int = 1, quad: CropQuad = PagePhotos.QUAD) =
        OriginalImage(file.path, format, width, height, exif, quad)

    /** Within [tolerance] of [expected]'s colour in each channel. */
    private fun assertColor(what: String, expected: Int, actual: Int, tolerance: Int = 45) {
        val close = abs(Color.red(expected) - Color.red(actual)) <= tolerance &&
            abs(Color.green(expected) - Color.green(actual)) <= tolerance &&
            abs(Color.blue(expected) - Color.blue(actual)) <= tolerance
        assertTrue("$what: ${Integer.toHexString(actual)}, expected ${Integer.toHexString(expected)}", close)
    }

    /**
     * The straightened page is the test page: its shape, a corner square of the right colour in each
     * corner, and its middle line straight across the middle.
     */
    private fun assertStraightPage(file: File, width: Int, height: Int) {
        val page = checkNotNull(BitmapFactory.decodeFile(file.path))
        assertEquals(width, page.width)
        assertEquals(height, page.height)
        assertEquals("Shape", PagePhotos.PAGE_WIDTH.toDouble() / PagePhotos.PAGE_HEIGHT, width.toDouble() / height, 0.03)
        // Halfway into each corner square.
        val inX = PagePhotos.MARK * width / PagePhotos.PAGE_WIDTH / 2
        val inY = PagePhotos.MARK * height / PagePhotos.PAGE_HEIGHT / 2
        val spots = listOf(inX to inY, width - inX to inY, width - inX to height - inY, inX to height - inY)
        spots.forEachIndexed { i, (x, y) -> assertColor("Corner $i", PagePhotos.MARKS[i], page.getPixel(x, y)) }
        // The line across the middle is at the middle all the way along: the page is straight.
        for (fraction in listOf(0.25, 0.5, 0.75)) {
            val x = (width * fraction).toInt()
            val darkest = (height * 2 / 5 until height * 3 / 5).minBy { y -> Color.red(page.getPixel(x, y)) }
            assertEquals("Middle line at x=$x", height / 2.0, darkest.toDouble(), height * 0.01)
        }
        page.recycle()
    }

    @Test fun thePageIsFoundOnAPhoto() {
        val photo = PagePhotos.jpeg(PagePhotos.photo(), File(dir, "photo.jpg"))
        val outline = checkNotNull(PageCropper.findPage(original(photo, ImageFormat.JPEG, PagePhotos.PHOTO_WIDTH, PagePhotos.PHOTO_HEIGHT, quad = CropQuad.WHOLE)))
        assertTrue("Confidence ${outline.confidence}", outline.confident)
        for (i in 0..3) {
            val dx = (outline.quad[i].x - PagePhotos.QUAD[i].x) * PagePhotos.PHOTO_WIDTH
            val dy = (outline.quad[i].y - PagePhotos.QUAD[i].y) * PagePhotos.PHOTO_HEIGHT
            assertTrue("Corner $i at ${outline.quad[i]}, the page's at ${PagePhotos.QUAD[i]}", hypot(dx, dy) < 0.012 * PagePhotos.PHOTO_HEIGHT)
        }
        // A photo of the table alone has no page to find.
        val table = PagePhotos.jpeg(PagePhotos.photo(corners = floatArrayOf(-50f, -50f, -40f, -50f, -40f, -40f, -50f, -40f)), File(dir, "table.jpg"))
        val nothing = PageCropper.findPage(original(table, ImageFormat.JPEG, PagePhotos.PHOTO_WIDTH, PagePhotos.PHOTO_HEIGHT, quad = CropQuad.WHOLE))
        assertTrue("$nothing", nothing == null || !nothing.confident)
    }

    @Test fun theCroppedPageIsStraight() {
        val photo = PagePhotos.jpeg(PagePhotos.photo(), File(dir, "photo.jpg"))
        val target = File(dir, "page.jpg")
        val cropped = PageCropper.crop(original(photo, ImageFormat.JPEG, PagePhotos.PHOTO_WIDTH, PagePhotos.PHOTO_HEIGHT), PagePhotos.QUAD, target)
        assertEquals(PageCrop.outputSize(PagePhotos.QUAD, PagePhotos.PHOTO_WIDTH, PagePhotos.PHOTO_HEIGHT), cropped.width to cropped.height)
        assertStraightPage(target, cropped.width, cropped.height)
        // Nothing is kept of the photo's metadata, and nothing is left half-written.
        assertNull(ExifInterface(target.path).getAttribute(ExifInterface.TAG_ORIENTATION)?.takeIf { it != "0" && it != "1" })
        assertEquals(setOf("photo.jpg", "page.jpg"), dir.list()!!.toSet())
    }

    @Test fun aPhotoStoredSidewaysIsCroppedAsItShows() {
        // Stored a quarter turn anticlockwise, with EXIF saying to turn it clockwise, as phones do.
        val upright = PagePhotos.photo()
        val stored = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height, Matrix().apply { postRotate(-90f) }, true)
        val photo = PagePhotos.jpeg(stored, File(dir, "sideways.jpg"))
        ExifInterface(photo.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "48/1,51/1,24/1")
            saveAttributes()
        }
        val image = original(photo, ImageFormat.JPEG, stored.width, stored.height, exif = ExifInterface.ORIENTATION_ROTATE_90)
        // Found where it shows, upright.
        val outline = checkNotNull(PageCropper.findPage(image))
        assertTrue(outline.confident)
        assertTrue("${outline.quad}", outline.quad.isCloseTo(PagePhotos.QUAD, 0.012f))

        val target = File(dir, "page.jpg")
        val cropped = PageCropper.crop(image, PagePhotos.QUAD, target)
        assertStraightPage(target, cropped.width, cropped.height)
        val exif = ExifInterface(target.path)
        assertNull("No location", exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertEquals(ExifInterface.ORIENTATION_UNDEFINED, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
    }

    @Test fun aPngStaysAPngAndALargePhotoIsShrunk() {
        val png = File(dir, "page.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { PagePhotos.photo().compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val target = File(dir, "cropped.png")
        val cropped = PageCropper.crop(original(png, ImageFormat.PNG, PagePhotos.PHOTO_WIDTH, PagePhotos.PHOTO_HEIGHT), PagePhotos.QUAD, target)
        assertTrue("A PNG", target.readBytes().copyOf(4).contentEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertStraightPage(target, cropped.width, cropped.height)

        // A 17-megapixel photo: the page comes out at most 3000 pixels long.
        val corners = FloatArray(8) { PagePhotos.CORNERS[it] * 3 }
        val big = PagePhotos.jpeg(PagePhotos.photo(width = 3600, height = 4800, corners = corners), File(dir, "big.jpg"))
        val large = PageCropper.crop(original(big, ImageFormat.JPEG, 3600, 4800), PagePhotos.QUAD, File(dir, "large.jpg"))
        assertEquals(PageCrop.MAX_SIDE, maxOf(large.width, large.height))
        assertStraightPage(large.file, large.width, large.height)
    }

    @Test fun aPhotoTakenIsAddedCroppedCroppedAgainAndPutBackWhole() {
        val handle = SavedStateHandle()
        val vm = onMain { MainViewModel(app, handle) }
        runBlocking { vm.awaitFileWork() }
        // The camera app saves the photo into the file Usugami gives it.
        val uri = onMain { vm.newCapture() }
        assertEquals("${app.packageName}.files", uri.authority)
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
        onMain { vm.photoTaken(true) }

        // The crop screen opens for it, on the page found.
        waitUntil("the crop screen") { vm.crop.value != null }
        val session = onMain { vm.crop.value!! }
        val taken = onMain { vm.items.value.single() }
        assertEquals(taken.id, session.itemId)
        assertTrue(session.fromCamera)
        assertTrue(session.foundPage)
        assertTrue("${session.found}", session.found.isCloseTo(PagePhotos.QUAD, 0.012f))
        assertEquals(session.found, session.start)
        assertTrue(taken.name, taken.name.startsWith("Scan_") && taken.name.endsWith(".jpg"))
        assertTrue("The camera's file has gone", captures().isEmpty())

        // Done crops it to the corners as the screen shows them. Its page is in the editor from then on,
        // while it is made: a scan from Home that took it is over.
        assertTrue(onMain { vm.scanUnderWay() })
        assertEquals(Triple(true, true, false), onMain { Triple(vm.finishCrop(session.shown(PagePhotos.QUAD)), vm.cropping.value.isNotEmpty(), vm.scanUnderWay()) })
        assertNull(onMain { vm.crop.value })
        waitUntil("the page to be cropped") { vm.cropping.value.isEmpty() && vm.pendingImports.value == 0 }
        val cropped = onMain { vm.items.value.single() }
        val kept = checkNotNull(cropped.original)
        assertEquals(taken.path, kept.path)
        assertTrue(kept.corners.isCloseTo(PagePhotos.QUAD))
        assertNotEquals(taken.path, cropped.path)
        assertEquals(1, cropped.exifOrientation)
        assertStraightPage(File(cropped.path), cropped.width, cropped.height)
        assertTrue("The photo is kept for cropping again", File(kept.path).isFile)

        // Cropped again, it starts from the photo with the corners where they were, and the page
        // turned in the editor shows turned.
        onMain {
            vm.rotate(cropped.id, 90)
            vm.openCrop(cropped.id)
        }
        waitUntil("the crop screen again") { vm.crop.value != null }
        val again = onMain { vm.crop.value!! }
        assertFalse(again.fromCamera)
        assertEquals(90, again.rotation)
        assertTrue(again.start.isCloseTo(PagePhotos.QUAD))
        // The page's top-left corner shows top right, turned with the page.
        assertTrue(again.shown(again.start)[CropQuad.TOP_RIGHT].let { abs(it.x - (1 - PagePhotos.QUAD[0].y)) < 1e-4 && abs(it.y - PagePhotos.QUAD[0].x) < 1e-4 })
        // Moved a little, as the screen shows it: the page's top-left corner moves right on the photo.
        val moved = again.shown(again.start).let { it.moved(CropQuad.TOP_RIGHT, 0f, 0.02f) }
        assertTrue(onMain { vm.finishCrop(moved) })
        waitUntil("the page to be cropped again") { vm.cropping.value.isEmpty() && vm.items.value.single().path != cropped.path }
        val recropped = onMain { vm.items.value.single() }
        assertEquals(PagePhotos.QUAD[0].x + 0.02f, recropped.original!!.corners[CropQuad.TOP_LEFT].x, 1e-4f)
        assertEquals(90, recropped.rotation)
        // The page cropped before goes once Android has saved a state that no longer names it (as it
        // does when Usugami goes to the background), not before: see the test below.
        runBlocking { vm.awaitFileWork() }
        assertTrue("Kept while the state saved last may name it", File(cropped.path).isFile)
        onMain { handle.savedStateProvider().saveState() }
        waitUntil("the page cropped before to be deleted") { !File(cropped.path).exists() }

        // The whole image puts the photo back as it was taken.
        onMain { vm.openCrop(recropped.id) }
        waitUntil("the crop screen once more") { vm.crop.value != null }
        assertTrue(onMain { vm.finishCrop(CropQuad.WHOLE) })
        val whole = onMain { vm.items.value.single() }
        assertNull(whole.original)
        assertEquals(taken.path, whole.path)
        assertEquals(taken.exifOrientation, whole.exifOrientation)
        waitUntil("the cropped page to be deleted") { !File(recropped.path).exists() }

        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
        assertFalse(File(taken.path).exists())
    }

    @Test fun cornersThatMakeNoPageAreRefusedAndCrossedOnesPutInOrder() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        val uri = onMain { vm.newCapture() }
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
        onMain { vm.photoTaken(true) }
        waitUntil("the crop screen") { vm.crop.value != null }
        // A corner pushed inside the others: an arrow head, not a page.
        val dart = CropQuad(listOf(CropPoint(0.1f, 0.1f), CropPoint(0.9f, 0.1f), CropPoint(0.4f, 0.4f), CropPoint(0.1f, 0.9f)))
        assertFalse(onMain { vm.finishCrop(dart) })
        assertNotNull("The screen stays open", onMain { vm.crop.value })
        // Two corners swapped, so the sides cross: they are taken in order round the page.
        val crossed = CropQuad(listOf(PagePhotos.QUAD[0], PagePhotos.QUAD[2], PagePhotos.QUAD[1], PagePhotos.QUAD[3]))
        assertTrue(onMain { vm.finishCrop(crossed) })
        waitUntil("the page to be cropped") { vm.cropping.value.isEmpty() && vm.items.value.single().original != null }
        assertTrue(onMain { vm.items.value.single().original!!.corners.isCloseTo(PagePhotos.QUAD) })
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    @Test fun aPhotoDiscardedOrNotTakenLeavesNothing() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        val images = File(app.cacheDir, "images")
        val before = images.list().orEmpty().toSet()

        // Cancelled in the camera app, which had opened the file.
        app.contentResolver.openOutputStream(onMain { vm.newCapture() })!!.close()
        assertEquals(1, captures().size)
        onMain { vm.photoTaken(false) }
        runBlocking { vm.awaitFileWork() }
        assertTrue(captures().isEmpty())
        assertTrue(onMain { vm.items.value.isEmpty() })

        // The camera app said it saved the photo, but didn't.
        onMain {
            vm.newCapture()
            vm.photoTaken(true)
        }
        waitUntil("the empty photo to be dropped") { vm.pendingImports.value == 0 }
        assertTrue(captures().isEmpty())
        assertTrue(onMain { vm.items.value.isEmpty() })

        // Taken, then the crop screen left: the photo is discarded.
        val uri = onMain { vm.newCapture() }
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
        onMain { vm.photoTaken(true) }
        waitUntil("the crop screen") { vm.crop.value != null }
        onMain { vm.closeCrop() }
        assertTrue(onMain { vm.items.value.isEmpty() })
        runBlocking { vm.awaitFileWork() }
        assertEquals(before, images.list().orEmpty().toSet())
        assertTrue(captures().isEmpty())
    }

    @Test fun croppedPagesAndTheCropScreenSurviveTheSavedState() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        val uri = onMain { vm.newCapture() }
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
        onMain { vm.photoTaken(true) }
        waitUntil("the crop screen") { vm.crop.value != null }
        onMain { vm.finishCrop(vm.crop.value!!.shown(PagePhotos.QUAD)) }
        waitUntil("the page to be cropped") { vm.cropping.value.isEmpty() && vm.items.value.single().original != null }
        val cropped = onMain { vm.items.value.single().rotatedBy(270) }
        onMain { vm.rotate(cropped.id, 270) }
        onMain { vm.openCrop(cropped.id) }
        waitUntil("the crop screen") { vm.crop.value != null }
        val session = onMain { vm.crop.value!! }

        // Android stops Usugami: the list and the open crop screen come back, and the files stay.
        // ("items" and "crop" are MainViewModel's saved-state keys.)
        val handle = SavedStateHandle(mapOf("items" to MainViewModel.packItems(onMain { vm.items.value }), "crop" to session))
        val restored = onMain { MainViewModel(app, handle) }
        runBlocking { restored.awaitFileWork() }
        assertEquals(listOf(cropped), onMain { restored.items.value })
        assertEquals(session, onMain { restored.crop.value })
        assertTrue(File(cropped.path).isFile)
        assertTrue(File(cropped.original!!.path).isFile)

        // Cropped once more there, its new page has a file of its own.
        assertTrue(onMain { restored.finishCrop(session.shown(CropQuad.inset(0.2f))) })
        waitUntil("the page to be cropped") { restored.cropping.value.isEmpty() && restored.items.value.single().path != cropped.path }
        val again = onMain { restored.items.value.single() }
        assertTrue(File(again.path).isFile)
        assertNotEquals(File(cropped.path).name, File(again.path).name)

        onMain { restored.clearAll() }
        runBlocking { restored.awaitFileWork() }
        assertFalse(File(cropped.original!!.path).exists())
    }

    /** A photo taken as the crop screen shows it. */
    private fun takePhoto(vm: MainViewModel): ImageItem {
        val uri = onMain { vm.newCapture() }
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
        onMain { vm.photoTaken(true) }
        waitUntil("the crop screen") { vm.crop.value != null }
        return onMain { vm.items.value.single { it.id == vm.crop.value!!.itemId } }
    }

    /** The page's corners on the photo, one moved a little. */
    private val nudged = PagePhotos.QUAD.with(0, PagePhotos.QUAD.corners[0].x + 0.002f, PagePhotos.QUAD.corners[0].y)

    /**
     * A photo taken and cropped, then cropped again with [quad], Android saving Usugami's state before
     * the new page is ready: the photo, the page cropped first (still named by that state) and the
     * state saved.
     */
    private fun croppedAgainUnfinished(quad: CropQuad): Triple<ImageItem, ImageItem, Map<String, Any?>> {
        val handle = SavedStateHandle()
        val vm = onMain { MainViewModel(app, handle) }
        runBlocking { vm.awaitFileWork() }
        val taken = takePhoto(vm)
        onMain { assertTrue(vm.finishCrop(vm.crop.value!!.shown(PagePhotos.QUAD))) }
        waitUntil("the page to be made") { vm.cropping.value.isEmpty() }
        val before = onMain { vm.items.value.single() }
        onMain { vm.openCrop(taken.id) }
        waitUntil("the crop screen again") { vm.crop.value != null }
        val saved = onMain {
            assertTrue(vm.finishCrop(vm.crop.value!!.shown(quad)))
            mapOf("items" to MainViewModel.packItems(vm.items.value), "pending_crops" to handle.get<Any>("pending_crops"))
        }
        waitUntil("the new page to be made before Android stops Usugami") { vm.cropping.value.isEmpty() }
        assertNotEquals(before.path, onMain { vm.items.value.single().path })
        return Triple(taken, before, saved)
    }

    @Test fun aCropUnfinishedWhenAndroidStopsUsugamiIsMadeAgain() {
        val handle = SavedStateHandle()
        val vm = onMain { MainViewModel(app, handle) }
        runBlocking { vm.awaitFileWork() }
        val taken = takePhoto(vm)
        // Take another: the page is made in the background, and Android saves Usugami's state as the
        // camera app opens, before the page is ready. ("items" and "pending_crops" are MainViewModel's
        // saved-state keys.)
        val saved = onMain {
            assertTrue(vm.finishCrop(vm.crop.value!!.shown(PagePhotos.QUAD), PageLook.GRAYSCALE))
            assertNull(vm.items.value.single().original)
            mapOf("items" to MainViewModel.packItems(vm.items.value), "pending_crops" to handle.get<Any>("pending_crops"))
        }
        waitUntil("the page to be made before Android stops Usugami") { vm.cropping.value.isEmpty() }

        // Android starts Usugami again from what it saved: the photo, not cropped, is cropped again,
        // and a PDF waits for it.
        val (restored, cropping, pending) = onMain {
            MainViewModel(app, SavedStateHandle(saved)).let { Triple(it, it.cropping.value, it.pendingImports.value) }
        }
        assertEquals(setOf(taken.id), cropping)
        assertEquals(1, pending)
        waitUntil("the page to be made again") { restored.cropping.value.isEmpty() && restored.pendingImports.value == 0 }
        val page = onMain { restored.items.value.single() }
        assertEquals(taken.path, page.original!!.path)
        assertTrue(page.original!!.corners.isCloseTo(PagePhotos.QUAD))
        assertEquals(PageLook.GRAYSCALE, page.look)
        assertStraightPage(File(page.path), page.width, page.height)

        onMain { restored.clearAll() }
        runBlocking { restored.awaitFileWork() }
        assertEquals(emptyList<String>(), File(app.cacheDir, "images").list().orEmpty().toList())
    }

    /**
     * As above, for a page cropped again: the page cropped before stays until Android saves a state
     * that no longer names it, so that Usugami started again from the state saved before the new crop
     * was ready still has the page, and makes the crop again.
     */
    @Test fun aPageCroppedAgainUnfinishedWhenAndroidStopsUsugamiIsMadeAgain() {
        val (taken, before, saved) = croppedAgainUnfinished(nudged)
        assertTrue("The page cropped before is kept while a saved state names it", File(before.path).isFile)

        val restored = onMain { MainViewModel(app, SavedStateHandle(saved)) }
        waitUntil("the page to be made again") { restored.cropping.value.isEmpty() && restored.pendingImports.value == 0 }
        val page = onMain { restored.items.value.single() }
        assertEquals(taken.path, page.original!!.path)
        assertTrue(page.original!!.corners.isCloseTo(nudged))
        assertStraightPage(File(page.path), page.width, page.height)

        onMain { restored.clearAll() }
        runBlocking { restored.awaitFileWork() }
        assertEquals(emptyList<String>(), File(app.cacheDir, "images").list().orEmpty().toList())
    }

    /**
     * As above, when the system cleared the page cropped before from the cache while Usugami was away:
     * the page goes back on the photo, which it is cropped from all the same.
     */
    @Test fun aPageCroppedAgainUnfinishedIsMadeFromThePhotoWhenItsPageHasGone() {
        val (taken, before, saved) = croppedAgainUnfinished(nudged)
        assertTrue(File(before.path).delete())

        val restored = onMain { MainViewModel(app, SavedStateHandle(saved)) }
        waitUntil("the page to be made again") { restored.cropping.value.isEmpty() && restored.pendingImports.value == 0 }
        val page = onMain { restored.items.value.single() }
        assertEquals(taken.path, page.original!!.path)
        assertTrue(page.original!!.corners.isCloseTo(nudged))
        assertStraightPage(File(page.path), page.width, page.height)
        assertNull(runBlocking { withTimeoutOrNull(500) { restored.messages.first() } })

        onMain { restored.clearAll() }
        runBlocking { restored.awaitFileWork() }
        assertEquals(emptyList<String>(), File(app.cacheDir, "images").list().orEmpty().toList())
    }

    /**
     * As above, when the system cleared the photo instead, keeping the page cropped before: the page
     * stays as it was cropped, with the photo's file name (as in the last test below), and the new
     * crop, which can't be made without the photo, goes without a word. It is not made from the page
     * now in the photo's place.
     */
    @Test fun aPageCroppedAgainUnfinishedOutlastingThePhotoIsKeptAsItWas() {
        val (taken, before, saved) = croppedAgainUnfinished(CropQuad.inset(0.2f))
        assertTrue(File(taken.path).delete())

        val restored = onMain { MainViewModel(app, SavedStateHandle(saved)) }
        waitUntil("the crop to end") { restored.cropping.value.isEmpty() && restored.pendingImports.value == 0 }
        val page = onMain { restored.items.value.single() }
        assertEquals(before.copy(path = taken.path, original = null), page)
        assertStraightPage(File(page.path), page.width, page.height)
        assertFalse(File(before.path).exists())
        assertNull(runBlocking { withTimeoutOrNull(500) { restored.messages.first() } })

        onMain { restored.clearAll() }
        runBlocking { restored.awaitFileWork() }
        assertEquals(emptyList<String>(), File(app.cacheDir, "images").list().orEmpty().toList())
    }

    @Test fun aPhotoBackAsUsugamiStartsAgainIsKept() {
        val handle = SavedStateHandle()
        val vm = onMain { MainViewModel(app, handle) }
        runBlocking { vm.awaitFileWork() }
        val uri = onMain { vm.newCapture() }
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
        // Android stopped Usugami while the camera app was open; the photo comes back as Usugami
        // starts again, before its cleanup has run. ("capture" is MainViewModel's saved-state key.)
        val restored = onMain {
            MainViewModel(app, SavedStateHandle(mapOf("capture" to handle.get<String>("capture")))).also { it.photoTaken(true) }
        }
        waitUntil("the photo's crop screen") { restored.crop.value != null }
        assertEquals(1, onMain { restored.items.value.size })
        onMain { restored.closeCrop() }
        runBlocking { restored.awaitFileWork() }
        assertTrue(captures().isEmpty())
    }

    /**
     * A photo waiting its turn to be added when Android saved Usugami's state keeps its file until a
     * later state no longer names it: Usugami started again from that state adds it.
     */
    @Test fun aPhotoWaitingWhenTheStateWasSavedIsAddedWhenUsugamiStartsAgain() {
        val handle = SavedStateHandle()
        val vm = onMain { MainViewModel(app, handle) }
        runBlocking { vm.awaitFileWork() }
        val uri = onMain { vm.newCapture() }
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val saved = onMain {
            vm.photoTaken(true)
            handle.savedStateProvider().saveState()
            mapOf("items" to MainViewModel.packItems(emptyList()), "pending_photos" to handle.get<Any>("pending_photos"))
        }
        waitUntil("the photo's crop screen") { vm.crop.value != null }
        runBlocking { vm.awaitFileWork() }
        assertEquals("Kept while the state saved last names it", 1, captures().size)

        // Android closes Usugami and starts it again from that state.
        val restoredHandle = SavedStateHandle(saved)
        val restored = onMain { MainViewModel(app, restoredHandle) }
        waitUntil("the photo's crop screen") { restored.crop.value != null }
        assertEquals(1, onMain { restored.items.value.size })
        onMain { restoredHandle.savedStateProvider().saveState() }
        waitUntil("the camera's file to go") { captures().isEmpty() }
        onMain { restored.closeCrop() }
        runBlocking { restored.awaitFileWork() }
    }

    @Test fun aPageRemovedWhileItIsCroppedGoesWithoutAWord() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        val images = File(app.cacheDir, "images")
        val before = images.list().orEmpty().toSet()
        val taken = takePhoto(vm)
        onMain {
            assertTrue(vm.finishCrop(vm.crop.value!!.shown(PagePhotos.QUAD)))
            vm.remove(taken.id)
        }
        waitUntil("the crop to end") { vm.cropping.value.isEmpty() && vm.pendingImports.value == 0 }
        // No "couldn't crop the page" about a page that has gone.
        assertNull(runBlocking { withTimeoutOrNull(500) { vm.messages.first() } })
        runBlocking { vm.awaitFileWork() }
        assertEquals(before, images.list().orEmpty().toSet())
    }

    @Test fun aPageOutlastingTheImageItWasCroppedFromIsKept() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        takePhoto(vm)
        onMain { vm.finishCrop(vm.crop.value!!.shown(PagePhotos.QUAD)) }
        waitUntil("the page to be cropped") { vm.cropping.value.isEmpty() && vm.items.value.single().original != null }
        val cropped = onMain { vm.items.value.single() }

        // The system clears the oldest files in the cache first: the photo goes while Usugami is away,
        // and the page stays, as it is, with the photo's file name.
        assertTrue(File(cropped.original!!.path).delete())
        val restored = onMain { MainViewModel(app, SavedStateHandle(mapOf("items" to MainViewModel.packItems(listOf(cropped))))) }
        runBlocking { restored.awaitFileWork() }
        val kept = onMain { restored.items.value.single() }
        assertEquals(cropped.copy(path = cropped.original!!.path, original = null), kept)
        assertTrue(File(kept.path).isFile)
        assertFalse(File(cropped.path).exists())
        // As it is now, it comes back the same.
        val again = onMain { MainViewModel(app, SavedStateHandle(mapOf("items" to MainViewModel.packItems(listOf(kept))))) }
        runBlocking { again.awaitFileWork() }
        assertEquals(listOf(kept), onMain { again.items.value })

        // Cropped again, from the page; when that image goes too, the PDF is still made of the page.
        onMain { again.openCrop(kept.id) }
        waitUntil("the crop screen") { again.crop.value != null }
        assertTrue(onMain { again.finishCrop(again.crop.value!!.shown(CropQuad.inset(0.1f))) })
        waitUntil("the page to be cropped again") { again.cropping.value.isEmpty() && again.items.value.single().original != null }
        val recropped = onMain { again.items.value.single() }
        assertEquals(kept.path, recropped.original!!.path)
        assertTrue(File(kept.path).delete())
        onMain { again.convert(merge = true, fileName = "kept", compression = Compression.RECOMMENDED) }
        waitUntil("the PDF") { again.result.value != null }
        assertEquals(1, onMain { again.result.value!!.single().pageCount })
        assertEquals(recropped.copy(path = kept.path, original = null), onMain { again.items.value.single() })

        onMain { again.startOver() }
        runBlocking { again.awaitFileWork() }
        assertFalse(File(kept.path).exists())
    }

    /**
     * The turn, look and adjustments chosen on the crop screen reach the page, cropped where the page
     * is however the screen turned it. Opened again, a page starts as it is; a new photo starts with
     * the look chosen last (Enhanced, at first) and no adjustments, and a new scan on Enhanced again.
     */
    @Test fun theTurnLookAndAdjustmentsChosenReachThePage() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        takePhoto(vm)
        val session = onMain { vm.crop.value!! }
        assertEquals(PageLook.ENHANCED, session.look)
        assertEquals(LookAdjustments.NONE, session.adjustments)

        // Turned a quarter right on the screen, the corners with it.
        val adjustments = LookAdjustments(brightness = 20, contrast = -30)
        val turned = session.shown(PagePhotos.QUAD).turned(90)
        assertTrue(onMain { vm.finishCrop(turned, PageLook.SUPER_ENHANCED, adjustments = adjustments, rotation = 90) })
        waitUntil("the page to be cropped") { vm.cropping.value.isEmpty() && vm.items.value.single().original != null }
        val page = onMain { vm.items.value.single() }
        assertEquals(90, page.rotation)
        assertEquals(PageLook.SUPER_ENHANCED, page.look)
        assertEquals(adjustments, page.adjustments)
        assertTrue("${page.original!!.corners}", page.original!!.corners.isCloseTo(PagePhotos.QUAD))
        // Made upright: the PDF turns it.
        assertStraightPage(File(page.path), page.width, page.height)

        onMain { vm.openCrop(page.id) }
        waitUntil("its crop screen") { vm.crop.value != null }
        val again = onMain { vm.crop.value!! }
        assertEquals(listOf(90, PageLook.SUPER_ENHANCED, adjustments), listOf(again.rotation, again.look, again.adjustments))
        onMain { vm.closeCrop() }
        takePhoto(vm)
        val next = onMain { vm.crop.value!! }
        assertEquals(PageLook.SUPER_ENHANCED, next.look)
        assertEquals(LookAdjustments.NONE, next.adjustments)
        // A new scan from Home starts on Enhanced again.
        onMain {
            vm.closeCrop()
            vm.newScan()
        }
        takePhoto(vm)
        assertEquals(PageLook.ENHANCED, onMain { vm.crop.value!!.look })
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /** Apply to all pages gives every page the look and the adjustments; the turn stays the page's own. */
    @Test fun applyToAllGivesEveryPageTheLookAndAdjustments() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        val photos = (1..2).map { Uri.fromFile(PagePhotos.jpeg(PagePhotos.photo(), File(dir, "page$it.jpg"))) }
        onMain { vm.addImages(photos) }
        waitUntil("the images") { vm.items.value.size == 2 && vm.pendingImports.value == 0 }
        val (first, second) = onMain { vm.items.value }
        onMain { vm.openCrop(first.id) }
        waitUntil("the crop screen") { vm.crop.value != null }
        val adjustments = LookAdjustments(brightness = -10, contrast = 40)
        assertTrue(onMain { vm.finishCrop(CropQuad.WHOLE, PageLook.BLACK_WHITE, allPages = true, adjustments = adjustments, rotation = 180) })
        assertEquals(
            listOf(first.copy(look = PageLook.BLACK_WHITE, adjustments = adjustments, rotation = 180), second.copy(look = PageLook.BLACK_WHITE, adjustments = adjustments)),
            onMain { vm.items.value },
        )
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /**
     * Apply to all pages chosen on a scan's first image reaches an image still being added: it comes in
     * with that look and those adjustments, and its crop screen opens with them.
     */
    @Test fun applyToAllReachesTheImagesStillBeingAdded() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        val first = Uri.fromFile(PagePhotos.jpeg(PagePhotos.photo(), File(dir, "first.jpg")))
        val photo = PagePhotos.jpeg(PagePhotos.photo(), File(dir, "photo.jpg")).readBytes()
        // The second image is read from a pipe: it is being added until the test writes it in.
        val late = File(dir, "late.jpg").also { Os.mkfifo(it.path, "600".toInt(8)) }
        val letIn = thread(start = false, isDaemon = true) { FileOutputStream(late).use { it.write(photo) } }
        try {
            onMain { vm.scanImages(listOf(first, Uri.fromFile(late))) }
            waitUntil("the first image's crop screen") { vm.crop.value != null }
            assertEquals(1, onMain { vm.scan.value!!.coming })
            val adjustments = LookAdjustments(brightness = 30)
            assertTrue(onMain { vm.finishCrop(CropQuad.WHOLE, PageLook.BLACK_WHITE, allPages = true, adjustments = adjustments) })
            letIn.start()
            waitUntil("the second image's crop screen") { vm.crop.value != null }
            val second = onMain { vm.crop.value!! }
            assertEquals(PageLook.BLACK_WHITE to adjustments, second.look to second.adjustments)
            assertEquals(listOf(PageLook.BLACK_WHITE to adjustments, PageLook.BLACK_WHITE to adjustments), onMain { vm.items.value.map { it.look to it.adjustments } })
        } finally {
            // Also when the test failed first, so the image being added doesn't wait for ever.
            if (letIn.state == Thread.State.NEW) letIn.start()
        }
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /**
     * The images uploaded for a scan go through the crop screen one after another, in order, each
     * starting with the look chosen last; one removed meanwhile is left out, and closing the screen
     * stops the scan, also while the next is being opened, leaving the rest as they were added.
     */
    @Test fun aScanTakesEachImageInTurnLeavesOutOneRemovedAndStopsWhenClosed() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        val photos = (1..5).map { Uri.fromFile(PagePhotos.jpeg(PagePhotos.photo(), File(dir, "scan$it.jpg"))) }
        onMain { vm.scanImages(photos) }
        // The first opens as soon as it is added.
        waitUntil("the first image's crop screen") { vm.crop.value != null }
        waitUntil("the images to be added") { vm.items.value.size == 5 && vm.pendingImports.value == 0 }
        val ids = onMain { vm.items.value.map { it.id } }
        val first = onMain { vm.crop.value!! }
        assertTrue(first.queued)
        assertEquals(ids[0], first.itemId)
        assertEquals(PageLook.ENHANCED, first.look)
        assertEquals(ScanQueue(waiting = ids), onMain { vm.scan.value })

        assertTrue(onMain { vm.finishCrop(first.shown(PagePhotos.QUAD), PageLook.GRAYSCALE) })
        waitUntil("the second image's crop screen") { vm.crop.value?.itemId == ids[1] }
        val second = onMain { vm.crop.value!! }
        assertEquals(PageLook.GRAYSCALE, second.look)
        onMain { vm.remove(ids[2]) }
        // Counted out at once, while the second still shows.
        assertEquals(2 to 4, onMain { vm.scan.value!!.run { place to total } })
        // Next, and stopped at once, while the fourth is being opened: it isn't.
        val scan = onMain {
            assertTrue(vm.finishCrop(second.shown(PagePhotos.QUAD), second.look))
            vm.scan.value!!.also { vm.closeCrop() }
        }
        assertEquals(listOf(ids[3], ids[4]), scan.waiting)
        assertEquals(3 to 4, scan.place to scan.total)
        assertNull(onMain { vm.scan.value })
        assertNull(runBlocking { withTimeoutOrNull(2_000) { vm.crop.first { it != null } } })
        waitUntil("the pages to be cropped") { vm.cropping.value.isEmpty() && vm.pendingImports.value == 0 }
        val pages = onMain { vm.items.value }
        assertEquals(listOf(ids[0], ids[1], ids[3], ids[4]), pages.map { it.id })
        assertTrue("$pages", pages.take(2).all { it.original != null && it.look == PageLook.GRAYSCALE })
        assertTrue("$pages", pages.drop(2).all { it.original == null && it.look == PageLook.ORIGINAL })
        assertNull(onMain { vm.crop.value })
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /**
     * A scan goes on when Android starts Usugami again from the state it saved, without the images
     * that were still being added (which are gone), and ends with the last image that was added.
     */
    @Test fun aScanGoesOnWhenUsugamiStartsAgain() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        val photos = (1..3).map { Uri.fromFile(PagePhotos.jpeg(PagePhotos.photo(), File(dir, "scan$it.jpg"))) }
        onMain { vm.addImages(photos) }
        waitUntil("the images") { vm.items.value.size == 3 && vm.pendingImports.value == 0 }
        val ids = onMain { vm.items.value.map { it.id } }
        // Saved with the first image done, the second awaited, and one more being added. ("items" and
        // "scan" are MainViewModel's saved-state keys.)
        val saved = mapOf(
            "items" to MainViewModel.packItems(onMain { vm.items.value }),
            "scan" to ScanQueue(waiting = ids.drop(1), coming = 1, done = 1),
        )
        val restored = onMain { MainViewModel(app, SavedStateHandle(saved)) }
        waitUntil("the second image's crop screen") { restored.crop.value != null }
        assertEquals(ids[1], onMain { restored.crop.value!!.itemId })
        assertEquals(ScanQueue(waiting = ids.drop(1), done = 1), onMain { restored.scan.value })
        assertTrue(onMain { restored.finishCrop(CropQuad.WHOLE) })
        waitUntil("the third image's crop screen") { restored.crop.value?.itemId == ids[2] }
        assertTrue(onMain { restored.scan.value!!.isLast })
        assertTrue(onMain { restored.finishCrop(CropQuad.WHOLE) })
        assertNull(onMain { restored.scan.value })
        assertNull(onMain { restored.crop.value })
        onMain { restored.clearAll() }
        runBlocking { restored.awaitFileWork() }
    }
}
