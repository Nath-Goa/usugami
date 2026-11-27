package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.system.Os
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.ui.CropSession
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.ScanQueue
import io.github.nath_goa.usugami.ui.ScanStage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * A scan through the view model, as its screens drive it: the photos taken first, waiting for Done,
 * then cropped one after another in the order taken, Back going to the image before with the corners
 * it was given (or back to the photos), Discard photo going on to the next; an image still being
 * added when cropping stops joining the looks; each stage as it was when Android starts Usugami
 * again; and a page removed at each stage left out at once.
 */
class ScanDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val dir = File(app.cacheDir, "test-scan")

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

    /** The camera app opened for the scan going on (one starts with it if none is): the file it saves the photo in. */
    private fun openCamera(vm: MainViewModel): Uri = onMain {
        assertTrue(vm.startScan())
        vm.newCapture()
    }

    private fun savePhoto(uri: Uri) {
        app.contentResolver.openOutputStream(uri)!!.use { PagePhotos.photo().compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    /** A photo taken for the scan going on: its id, once it is the scan's last photo. */
    private fun takePhoto(vm: MainViewModel): Long {
        val count = onMain { vm.scan.value?.pages?.size ?: 0 }
        savePhoto(openCamera(vm))
        onMain { vm.photoTaken(true) }
        waitUntil("the photo among the scan's") { vm.scan.value?.pages?.size == count + 1 }
        return onMain { vm.scan.value!!.pages.last() }
    }

    /** The crop screen once it shows image [id]. */
    private fun cropScreen(vm: MainViewModel, id: Long): CropSession {
        waitUntil("image $id's crop screen") { vm.crop.value?.itemId == id }
        return onMain { vm.crop.value!! }
    }

    /** Next on image [id]'s crop screen, once it shows, with the page's corners at [quad] on the image. */
    private fun next(vm: MainViewModel, id: Long, quad: CropQuad = PagePhotos.QUAD) {
        val session = cropScreen(vm, id)
        assertTrue(onMain { vm.finishCrop(session.shown(quad)) })
    }

    private fun place(vm: MainViewModel) = onMain { vm.scan.value!!.run { place to total } }

    /** Usugami started again from what Android saved of [vm]'s state in [handle]: the new view model, and its handle. */
    private fun restart(vm: MainViewModel, handle: SavedStateHandle): Pair<MainViewModel, SavedStateHandle> = onMain {
        // ("items" is MainViewModel's saved-state key, which it packs only as the state is saved.)
        val saved = handle.keys().associateWith { handle.get<Any?>(it) } + ("items" to MainViewModel.packItems(vm.items.value))
        val restored = SavedStateHandle(saved)
        MainViewModel(app, restored) to restored
    }

    @Test fun photosWaitForDoneThenAreCroppedInTurnAndBackGoesToTheImageBefore() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        val first = takePhoto(vm)
        val second = takePhoto(vm)
        // The photos wait for Done, in the order taken, with no crop screen.
        assertEquals(ScanQueue(stage = ScanStage.PHOTOS, camera = true, pages = listOf(first, second)), onMain { vm.scan.value })
        assertNull(onMain { vm.crop.value })
        assertEquals(listOf(first, second), onMain { vm.items.value.map { it.id } })

        // Done: the first, its corners alone, on the page found.
        onMain { vm.cropScan() }
        val one = cropScreen(vm, first)
        assertTrue(one.staged && one.fromCamera && one.foundPage)
        assertEquals(1 to 2, place(vm))
        // Next: its page is made in the background, and the second shows.
        assertTrue(onMain { vm.finishCrop(one.shown(PagePhotos.QUAD)) })
        cropScreen(vm, second)
        assertEquals(2 to 2, place(vm))
        // Back: the first, once its page is made, with the corners it was given.
        onMain { vm.scanBack() }
        val again = cropScreen(vm, first)
        assertTrue("${again.start}", again.start.isCloseTo(PagePhotos.QUAD))
        assertNotNull(onMain { vm.items.value.first { it.id == first }.original })
        assertEquals(1 to 2, place(vm))
        // Left whole this time, which it comes back as.
        assertTrue(onMain { vm.finishCrop(CropQuad.WHOLE) })
        cropScreen(vm, second)
        onMain { vm.scanBack() }
        assertEquals(CropQuad.WHOLE, cropScreen(vm, first).start)
        assertNull(onMain { vm.items.value.first { it.id == first }.original })
        // Back on the first image: the photos again, none lost.
        onMain { vm.scanBack() }
        assertEquals(ScanStage.PHOTOS to listOf(first, second), onMain { vm.scan.value!!.run { stage to pages } })
        assertNull(onMain { vm.crop.value })

        // Done again, and Next on each: the looks, each page on Enhanced, the first shown.
        onMain { vm.cropScan() }
        next(vm, first)
        next(vm, second)
        assertEquals(ScanStage.LOOKS to first, onMain { vm.scan.value!!.run { stage to shownPage } })
        assertNull(onMain { vm.crop.value })
        assertTrue(onMain { vm.items.value.all { it.look == PageLook.ENHANCED } })
        waitUntil("the pages to be cropped") { vm.cropping.value.isEmpty() && vm.pendingImports.value == 0 }
        assertTrue(onMain { vm.items.value.all { it.original != null } })
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    @Test fun discardPhotoGoesOnToTheNextImageOrTheLooksOrEndsTheScan() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        val images = File(app.cacheDir, "images")
        val before = images.list().orEmpty().toSet()
        val photos = List(3) { takePhoto(vm) }
        onMain { vm.cropScan() }
        cropScreen(vm, photos[0])
        // Discarded: it goes, and the next shows.
        onMain { vm.discardScanPhoto() }
        cropScreen(vm, photos[1])
        assertEquals(photos.drop(1), onMain { vm.items.value.map { it.id } })
        assertEquals(1 to 2, place(vm))
        assertTrue(onMain { vm.finishCrop(CropQuad.WHOLE) })
        cropScreen(vm, photos[2])
        // The last discarded: on to the looks, with the one left.
        onMain { vm.discardScanPhoto() }
        assertEquals(ScanStage.LOOKS to listOf(photos[1]), onMain { vm.scan.value!!.run { stage to pages } })
        onMain { vm.saveScan() }
        assertEquals(listOf(photos[1]), onMain { vm.items.value.map { it.id } })

        // A scan of one photo, discarded on the crop screen: over, and the page before stays.
        val only = takePhoto(vm)
        onMain { vm.cropScan() }
        cropScreen(vm, only)
        onMain { vm.discardScanPhoto() }
        assertNull(onMain { vm.scan.value })
        assertNull(onMain { vm.crop.value })
        assertEquals(listOf(photos[1]), onMain { vm.items.value.map { it.id } })
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
        assertEquals(before, images.list().orEmpty().toSet())
    }

    /**
     * Stop cropping while the next uploaded image is still being added: it joins the scan's looks,
     * whole, with the look its pages have all been given.
     */
    @Test fun anImageStillBeingAddedWhenCroppingStopsJoinsTheLooks() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        val picked = Uri.fromFile(PagePhotos.jpeg(PagePhotos.photo(), File(dir, "page.jpg")))
        val photo = PagePhotos.jpeg(PagePhotos.photo(), File(dir, "photo.jpg")).readBytes()
        // The second image is read from a pipe: it is being added until the test writes it in.
        val late = File(dir, "late.jpg").also { Os.mkfifo(it.path, "600".toInt(8)) }
        val letIn = thread(start = false, isDaemon = true) { FileOutputStream(late).use { it.write(photo) } }
        try {
            onMain { vm.scanImages(listOf(picked, Uri.fromFile(late))) }
            waitUntil("the first image") { vm.scan.value?.pages?.size == 1 }
            val first = onMain { vm.scan.value!!.pages.single() }
            val one = cropScreen(vm, first)
            assertEquals(1 to 2, place(vm))
            // Next: the screen waits for the second, and cropping stops there.
            assertTrue(onMain { vm.finishCrop(one.shown(PagePhotos.QUAD)) })
            assertEquals(2 to 2, place(vm))
            onMain {
                vm.stopScanCropping()
                vm.setScanSameLook(true)
                vm.setScanLook(PageLook.GRAYSCALE)
            }
            assertEquals(ScanStage.LOOKS to listOf(first), onMain { vm.scan.value!!.run { stage to pages } })
            letIn.start()
            waitUntil("the second image") { vm.scan.value?.pages?.size == 2 && vm.pendingImports.value == 0 }
            val pages = onMain { vm.items.value }
            assertEquals(onMain { vm.scan.value!!.pages }, pages.map { it.id })
            assertTrue("$pages", pages.all { it.look == PageLook.GRAYSCALE })
            assertEquals(listOf(true, false), pages.map { it.original != null })
            assertNull(onMain { vm.crop.value })
        } finally {
            // Also when the test failed first, so the image being added doesn't wait for ever.
            if (letIn.state == Thread.State.NEW) letIn.start()
        }
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /**
     * Android stopping Usugami at each stage loses nothing: the photos, also with the camera open for
     * the next; the image on the crop screen, as it was; the looks, with the page shown and the same
     * filter for all.
     */
    @Test fun eachStageComesBackWhenAndroidStopsUsugami() {
        val handle = SavedStateHandle()
        val vm = onMain { MainViewModel(app, handle) }
        runBlocking { vm.awaitFileWork() }
        val first = takePhoto(vm)
        // The photos, the camera open for the next: that photo comes back to the scan.
        savePhoto(openCamera(vm))
        val (photos, photosHandle) = restart(vm, handle)
        assertEquals(ScanQueue(stage = ScanStage.PHOTOS, camera = true, pages = listOf(first)), onMain { photos.scan.value })
        assertTrue(onMain { photos.cameraOpen.value })
        onMain { photos.photoTaken(true) }
        waitUntil("the photo back from the camera") { photos.scan.value?.pages?.size == 2 && photos.pendingImports.value == 0 }
        val second = onMain { photos.scan.value!!.pages.last() }

        // Cropping, on the second image: its crop screen comes back as it was.
        onMain { photos.cropScan() }
        next(photos, first)
        val session = cropScreen(photos, second)
        waitUntil("the crop screen in the saved state, and the first page made") {
            photosHandle.get<CropSession>("crop") == session && photos.cropping.value.isEmpty()
        }
        val (cropping, croppingHandle) = restart(photos, photosHandle)
        assertEquals(session, onMain { cropping.crop.value })
        assertEquals(ScanStage.CROP to (2 to 2), onMain { cropping.scan.value!!.run { stage to (place to total) } })

        // The looks, the second page shown with the same filter for all.
        val chosen = onMain {
            assertTrue(cropping.finishCrop(session.shown(PagePhotos.QUAD)))
            cropping.selectScanPage(second)
            cropping.setScanSameLook(true)
            cropping.setScanAdjustments(LookAdjustments(contrast = 40))
            cropping.scan.value!!
        }
        assertEquals(ScanQueue(stage = ScanStage.LOOKS, camera = true, pages = listOf(first, second), selected = second, sameLook = true, styled = true), chosen)
        val (looks, _) = restart(cropping, croppingHandle)
        assertEquals(chosen, onMain { looks.scan.value })
        assertEquals(List(2) { PageLook.ENHANCED to LookAdjustments(contrast = 40) }, onMain { looks.items.value.map { it.look to it.adjustments } })
        waitUntil("the pages to be made") { looks.cropping.value.isEmpty() && looks.pendingImports.value == 0 }
        onMain { looks.saveScan() }
        assertFalse(onMain { looks.scanUnderWay() })
        onMain { looks.clearAll() }
        runBlocking { looks.awaitFileWork() }
    }

    @Test fun aPageRemovedAtEachStageIsLeftOutAtOnce() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        runBlocking { vm.awaitFileWork() }
        val photos = List(5) { takePhoto(vm) }
        // The photos: one removed (its own button).
        onMain { vm.remove(photos[1]) }
        assertEquals(listOf(photos[0], photos[2], photos[3], photos[4]), onMain { vm.scan.value!!.pages })

        // Cropping: one still to come, then the one showing, whose next one then shows.
        onMain { vm.cropScan() }
        cropScreen(vm, photos[0])
        onMain { vm.remove(photos[4]) }
        assertEquals(1 to 3, place(vm))
        onMain { vm.remove(photos[0]) }
        cropScreen(vm, photos[2])
        assertEquals(1 to 2, place(vm))
        assertTrue(onMain { vm.finishCrop(CropQuad.WHOLE) })
        next(vm, photos[3], CropQuad.WHOLE)

        // The looks: the page shown removed, the first is shown; the last removed, the scan is over.
        onMain { vm.selectScanPage(photos[3]) }
        onMain { vm.remove(photos[3]) }
        assertEquals(ScanStage.LOOKS to photos[2], onMain { vm.scan.value!!.run { stage to shownPage } })
        onMain { vm.remove(photos[2]) }
        assertNull(onMain { vm.scan.value })
        assertTrue(onMain { vm.items.value.isEmpty() })
        runBlocking { vm.awaitFileWork() }
    }
}
