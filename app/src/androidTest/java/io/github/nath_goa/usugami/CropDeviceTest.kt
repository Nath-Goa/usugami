package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.CropPoint
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.PageCrop
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.OriginalImage
import io.github.nath_goa.usugami.data.PageCropper
import io.github.nath_goa.usugami.ui.MainViewModel
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
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Cropping a page out of a photo: the page found on it, straightened onto a rectangle (also from a
 * photo stored sideways with an EXIF orientation, and a PNG), and through the view model a photo
 * taken with the camera app added and cropped, cropped again from the original, put back whole,
 * discarded, kept through the saved state, and a photo not taken leaving nothing behind; a crop not
 * finished when Android stops Usugami made again, a photo back as Usugami starts again kept, a page
 * removed while it is cropped going quietly, and a page outlasting the image it was cropped from kept.
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
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
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

        // Done crops it to the corners as the screen shows them.
        assertTrue(onMain { vm.finishCrop(session.shown(PagePhotos.QUAD)) })
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
}
