package io.github.nath_goa.usugami

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.core.ShortcutTarget
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.AppShortcuts
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.RecentFiles
import io.github.nath_goa.usugami.ui.CropSession
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.ScanStage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A scan through its real screens, read and driven through the accessibility tree a screen reader
 * uses, with the camera app stood in for: its photos taken first, then each cropped in turn, then its
 * pages' filters, and Save. Back at each stage, Discard photo, the camera closed without a photo, Take
 * another after cropping, a scan from the pages screen, the pages screen kept out of reach meanwhile,
 * each stage kept when the phone is turned and when Android stops Usugami, and what a screen reader
 * hears and does on its screens.
 */
class ScanFlowDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val automation = instrumentation.uiAutomation
    private var activity: MainActivity? = null

    @Before fun setUp() {
        // The screens are in the phone's language, as [app]'s strings are.
        if (AppLanguages.reset(app)) Thread.sleep(1_000)
        RecentFiles.of(app).run { clear(); runBlocking { awaitWrites() } }
    }

    @After fun tearDown() {
        activity?.let { shown ->
            val vm = vm()
            onMain { vm.clearAll() }
            runBlocking { vm.awaitFileWork() }
            onMain { shown.finish() }
        }
        File(app.cacheDir, "test-photos").deleteRecursively()
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what. On screen: ${texts()}" }
            Thread.sleep(100)
        }
    }

    /** Starts Usugami on Home, the language already chosen. */
    private fun launch(): MainViewModel {
        AppLanguages.skipChoice(app)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity = instrumentation.startActivitySync(intent) as MainActivity
        return vm()
    }

    /** The view model of the activity showing. */
    private fun vm(): MainViewModel = onMain { ViewModelProvider(activity!!)[MainViewModel::class.java] }

    /** The resumed MainActivity once it is a new one, after [old] was recreated. */
    private fun recreatedFrom(old: MainActivity) {
        var found: MainActivity? = null
        waitUntil("the activity to be recreated") {
            val resumed = onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED) }
            found = (resumed.singleOrNull() as? MainActivity)?.takeIf { it !== old }
            found != null
        }
        activity = found
    }

    /** The phone turned: the activity is recreated, its view models kept. */
    private fun turned() {
        val old = activity!!
        onMain { old.recreate() }
        recreatedFrom(old)
    }

    /**
     * Android stops Usugami, away behind the camera app, say, and starts it again: the activity is
     * recreated from the state it saved, with view models made anew from it, as after its process ended.
     */
    private fun stopped() {
        val old = activity!!
        onMain {
            old.viewModelStore.clear()
            old.recreate()
        }
        recreatedFrom(old)
    }

    /** Every node in the window that has focus (a dialog's, while one is open). */
    private fun nodes(): List<AccessibilityNodeInfo> {
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            all += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        automation.rootInActiveWindow?.let(::walk)
        return all
    }

    private fun texts() = nodes().mapNotNull { it.text?.toString() }

    private fun find(what: String, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil(what) {
            found = nodes().firstOrNull(match)
            found != null
        }
        return found!!
    }

    private fun AccessibilityNodeInfo.says(text: String) = this.text?.toString() == text || contentDescription?.toString() == text

    private fun says(label: Int, vararg args: Any) = app.getString(label, *args).let { text -> nodes().any { it.says(text) } }

    /** The nearest of [node] and its parents that [match]es, if any. */
    private fun ancestor(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !match(target)) target = target.parent
        return target
    }

    /** Whether one of the nodes under [this] [match]es. */
    private fun AccessibilityNodeInfo.holds(match: (AccessibilityNodeInfo) -> Boolean): Boolean =
        (0 until childCount).any { i -> getChild(i)?.let { match(it) || it.holds(match) } == true }

    private fun click(node: AccessibilityNodeInfo) {
        val target = checkNotNull(ancestor(node) { it.isClickable }) { "Nothing to click at $node" }
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    /** The button labelled [label] (with [args]). */
    private fun button(label: Int, vararg args: Any) = app.getString(label, *args).let { text ->
        find("the \"$text\" button") { it.says(text) && ancestor(it) { node -> node.isClickable } != null }
    }

    /** Presses the enabled button [label] once it can be: one on a screen still sliding in may not be pressable yet. */
    private fun press(label: Int) {
        val text = app.getString(label)
        waitUntil("\"$text\" to be pressed") {
            nodes().firstOrNull { it.says(text) && ancestor(it) { node -> node.isClickable }?.isEnabled == true }
                ?.let { ancestor(it) { node -> node.isClickable }!!.performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
    }

    /**
     * The Back key, once Usugami's window has the focus: a dialog can leave the screen before its
     * window gives the focus back, and a key pressed in between would be lost.
     */
    private fun backKey() {
        waitUntil("Usugami to have the focus") {
            onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity && it.hasWindowFocus() } }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    /**
     * Whether a radio button, check box or switch is on, as Usugami says now: the node may come from
     * the accessibility cache, which isn't always told when it changes.
     */
    // isChecked gives way to getChecked() from Android 16, which older devices don't have.
    @Suppress("DEPRECATION")
    private val AccessibilityNodeInfo.chosen: Boolean
        get() {
            refresh()
            return isChecked
        }

    /**
     * Stands in for the camera app: "takes" a page on a table into the file Usugami gives it, each
     * time, at once, or with [closed], is closed without taking one; counts the times it was opened.
     */
    private inner class Camera(private val closed: Boolean = false) : Instrumentation.ActivityMonitor() {
        private val photo: Bitmap = PagePhotos.photo()
        @Volatile var opened = 0

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != MediaStore.ACTION_IMAGE_CAPTURE) return null
            opened++
            if (closed) return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            val output = IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
            app.contentResolver.openOutputStream(output)!!.use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        }
    }

    private fun <T> withCamera(camera: Camera = Camera(), block: (Camera) -> T): T {
        instrumentation.addMonitor(camera)
        try {
            return block(camera)
        } finally {
            instrumentation.removeMonitor(camera)
        }
    }

    /** A scan's photos taken from the button [first] (Home's or the editor's Take photo), then with Take another, [count] in all. */
    private fun takePhotos(count: Int, first: () -> Unit = { click(button(R.string.add_from_camera)) }) = withCamera { camera ->
        first()
        scanPhotos(1)
        for (taken in 2..count) {
            press(R.string.crop_take_another)
            scanPhotos(taken)
        }
        assertEquals(count, camera.opened)
    }

    /**
     * Waits for a scan's Photos screen to show [count] photos, each with its Remove button, none still
     * being added. Its hint tells it from the editor, whose pages have Remove buttons too.
     */
    private fun scanPhotos(count: Int) = waitUntil("the scan's photos, $count of them") {
        says(R.string.scan_photos_hint) && !says(R.string.scan_adding_photo) && says(R.string.remove_page, count) && !says(R.string.remove_page, count + 1)
    }

    /** Waits for a scan's crop screen to show its image [place] of [total], and returns that image's session. */
    private fun scanImage(place: Int, total: Int): CropSession {
        find("image $place of $total on the crop screen") { it.says(app.getString(R.string.scan_place, place, total)) }
        find("its page found") { it.says(app.getString(R.string.crop_found)) }
        val vm = vm()
        val id = onMain { vm.scan.value!!.pages[place - 1] }
        waitUntil("image $place's crop screen") { onMain { vm.crop.value?.itemId == id } }
        return onMain { vm.crop.value!! }
    }

    /** Next on each of a scan's [total] images, from its image [from] on: its Filters screen then shows. */
    private fun nextOnEach(total: Int, from: Int = 1) {
        for (place in from..total) {
            scanImage(place, total)
            press(R.string.crop_next)
        }
        scanFilters()
    }

    /** Waits for a scan's Filters screen. */
    private fun scanFilters() = find("the scan's filters") { it.says(app.getString(R.string.filter_title)) }

    /** Page [number] in the row of a scan's pages on its Filters screen, chosen like a radio button. */
    private fun scanPage(number: Int): AccessibilityNodeInfo {
        val label = app.getString(R.string.pdf_page_number, number)
        val page = find("page $number in the row of the scan's pages") { it.says(label) && ancestor(it) { node -> node.isCheckable } != null }
        return ancestor(page) { it.isCheckable }!!
    }

    /**
     * Shows the scan's page [number] large on its Filters screen, tapped as a screen reader does until it
     * is: a page on a screen still sliding in may not take the tap yet (see [press]).
     */
    private fun showPage(number: Int) = waitUntil("page $number to be shown") {
        val page = scanPage(number)
        if (!page.chosen) page.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        page.chosen
    }

    /** The Filters screen's Same filter for all pages switch. */
    private fun sameFilterSwitch(): AccessibilityNodeInfo {
        val label = app.getString(R.string.scan_same_look)
        return checkNotNull(ancestor(find("the \"$label\" switch") { it.says(label) }) { it.isCheckable })
    }

    /**
     * The look [label], like a radio button, scrolled to as a screen reader would: along the looks' own
     * row (the row of the scan's pages above it may scroll too), towards the look, before those shown or after.
     */
    private fun look(label: Int): AccessibilityNodeInfo {
        val name = app.getString(label)
        val names = LOOKS.map(app::getString)
        val (left, right) = AccessibilityAction.ACTION_SCROLL_LEFT to AccessibilityAction.ACTION_SCROLL_RIGHT
        fun choice() = nodes().firstOrNull { it.says(name) }?.let { node -> ancestor(node) { it.isCheckable } }
        repeat(8) {
            choice()?.let { return it }
            val row = nodes().firstOrNull { (left in it.actionList || right in it.actionList) && it.holds { look -> names.any { name -> look.says(name) } } }
            val firstShown = names.indexOfFirst { shown -> row?.holds { it.says(shown) } == true }
            row?.performAction((if (names.indexOf(name) < firstShown) left else right).id)
            Thread.sleep(300)
        }
        var found: AccessibilityNodeInfo? = null
        waitUntil("the \"$name\" look") {
            found = choice()
            found != null
        }
        return found!!
    }

    /**
     * Chooses the look [label] for the page shown (or every page, with the switch on), and waits for it
     * to be the one chosen: a look on a screen still sliding in may not take the tap yet (see [press]).
     */
    private fun chooseLook(label: Int) = waitUntil("${app.getString(label)} to be chosen") {
        val choice = look(label)
        if (!choice.chosen) choice.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        choice.chosen
    }

    /** The slider [label] (Brightness or Contrast). */
    private fun slider(label: Int) = find("the ${app.getString(label)} slider") {
        it.says(app.getString(label)) && AccessibilityAction.ACTION_SET_PROGRESS in it.actionList
    }

    /** The corner [name] moved [steps] steps left with a screen reader's action. */
    private fun moveLeft(name: Int, steps: Int) {
        val left = app.getString(R.string.edit_move_left)
        repeat(steps) {
            val corner = find("the corner") { it.says(app.getString(name)) && it.action(left) != null }
            assertTrue(corner.performAction(corner.action(left)!!.id))
        }
    }

    private fun AccessibilityNodeInfo.action(label: String): AccessibilityAction? = actionList.firstOrNull { it.label == label }

    /** Where the top-left corner is on the image after [session]'s page found was moved [steps] steps left: each 0.4% of the photo's long side. */
    private fun movedLeft(session: CropSession, steps: Int): Float =
        session.found[CropQuad.TOP_LEFT].x - steps * 0.004f * PagePhotos.PHOTO_HEIGHT / PagePhotos.PHOTO_WIDTH

    /** The editor, once it shows. */
    private fun editor() = find("the editor") { it.says(app.getString(R.string.convert)) }

    /** Home, once it shows, and no empty editor with it. */
    private fun home() {
        waitUntil("Home") { says(R.string.scan_title) }
        assertFalse("The empty editor shows", says(R.string.empty_title))
    }

    /** The editor's pages, once each is made: their ids, crops and looks. */
    private fun pagesMade(count: Int): List<ImageItem> {
        val vm = vm()
        waitUntil("the $count pages to be made") {
            onMain { vm.cropping.value.isEmpty() && vm.pendingImports.value == 0 && vm.items.value.size == count && vm.scan.value == null }
        }
        return onMain { vm.items.value }
    }

    /** The launcher shortcut to [target], used while Usugami shows. */
    private fun shortcut(target: ShortcutTarget) = onMain { activity!!.startActivity(AppShortcuts.intent(activity!!, target)) }

    /**
     * Scan document from Home: three photos, Take photo then Take another, in the order taken; Done
     * crops each in that order, Next on each; the Filters screen gives the second page a look of its
     * own, then Same filter for all pages gives every page the next look; Save shows the three pages
     * in the editor, in order, each in its look.
     */
    @Test fun aScanGoesFromItsPhotosThroughEachCropToItsFiltersAndSave() {
        val vm = launch()
        takePhotos(3)
        val photos = onMain { vm.scan.value!!.pages }
        press(R.string.crop_done)
        for (place in 1..3) {
            assertEquals(photos[place - 1], scanImage(place, 3).itemId)
            press(R.string.crop_next)
        }
        scanFilters()
        fun looks() = onMain { vm.items.value.map { it.look } }
        waitUntil("page 1 to be shown") { scanPage(1).chosen }
        showPage(2)
        chooseLook(R.string.look_grayscale)
        waitUntil("the second page alone in grayscale") { looks() == listOf(PageLook.ENHANCED, PageLook.GRAYSCALE, PageLook.ENHANCED) }
        click(sameFilterSwitch())
        waitUntil("every page in grayscale") { looks() == List(3) { PageLook.GRAYSCALE } }
        chooseLook(R.string.look_black_white)
        waitUntil("every page in black and white") { looks() == List(3) { PageLook.BLACK_WHITE } }

        press(R.string.save)
        editor()
        val pages = pagesMade(3)
        assertEquals(photos, pages.map { it.id })
        assertTrue("$pages", pages.all { it.original != null })
        // Each page shows in its look, as a screen reader hears it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val name = app.getString(R.string.look_black_white)
            waitUntil("the pages to say their look") { nodes().count { it.stateDescription?.toString() == name } == 3 }
        }
    }

    /** Back on the scan's photos asks first: Keep stays with them, Discard drops them, and a scan from Home goes back Home. */
    @Test fun backOnThePhotosAsksFirstAndDiscardGoesHome() {
        val vm = launch()
        takePhotos(2)
        backKey()
        find("the question") { it.says(app.getString(R.string.scan_discard_title)) }
        click(button(R.string.keep))
        scanPhotos(2)
        assertEquals(ScanStage.PHOTOS to 2, onMain { vm.scan.value!!.run { stage to pages.size } })
        // The bar's arrow asks too.
        click(button(R.string.back))
        click(button(R.string.discard))
        home()
        assertNull(onMain { vm.scan.value })
        assertTrue(onMain { vm.items.value.isEmpty() })
        runBlocking { vm.awaitFileWork() }
        assertTrue("The camera's files are gone", File(app.cacheDir, "camera").list().isNullOrEmpty())
    }

    /**
     * Back on the crop screen goes to the image before, with the corners it was given with Next, and
     * from the first image to the scan's photos, all of them kept.
     */
    @Test fun backOnTheCropScreenGoesToTheImageBeforeAndFromTheFirstToThePhotos() {
        val vm = launch()
        takePhotos(2)
        press(R.string.crop_done)
        val first = scanImage(1, 2)
        moveLeft(R.string.crop_corner_top_left, 5)
        press(R.string.crop_next)
        scanImage(2, 2)
        backKey()
        val again = scanImage(1, 2)
        assertEquals(first.itemId, again.itemId)
        assertEquals(movedLeft(first, 5), again.start[CropQuad.TOP_LEFT].x, 1e-3f)
        // The bar's arrow, on the first image: its photos, both kept.
        click(button(R.string.back))
        scanPhotos(2)
        assertEquals(ScanStage.PHOTOS to 2, onMain { vm.scan.value!!.run { stage to pages.size } })
        assertNull(onMain { vm.crop.value })
    }

    /** Back on the scan's Filters goes to its last image on the crop screen; Next from there comes back to the looks chosen. */
    @Test fun backOnTheFiltersGoesToTheLastImage() {
        val vm = launch()
        takePhotos(2)
        press(R.string.crop_done)
        nextOnEach(2)
        showPage(2)
        chooseLook(R.string.look_grayscale)
        backKey()
        assertEquals(onMain { vm.scan.value!!.pages[1] }, scanImage(2, 2).itemId)
        press(R.string.crop_next)
        scanFilters()
        waitUntil("page 2, still shown") { scanPage(2).chosen }
        waitUntil("grayscale, still its look") { look(R.string.look_grayscale).chosen }
        assertEquals(listOf(PageLook.ENHANCED, PageLook.GRAYSCALE), onMain { vm.items.value.map { it.look } })
    }

    /**
     * A photo taken after going back from the Filters to the photos starts on Enhanced, as the scan's
     * other pages did when they first reached the Filters, whose own looks are kept.
     */
    @Test fun aPhotoTakenAfterGoingBackFromTheFiltersStartsOnEnhancedLikeTheOthers() {
        val vm = launch()
        takePhotos(1)
        press(R.string.crop_done)
        nextOnEach(1)
        chooseLook(R.string.look_grayscale)
        backKey()
        scanImage(1, 1)
        backKey()
        scanPhotos(1)
        withCamera {
            press(R.string.crop_take_another)
            scanPhotos(2)
        }
        press(R.string.crop_done)
        nextOnEach(2)
        showPage(2)
        waitUntil("Enhanced, the new page's look") { look(R.string.look_enhanced).chosen }
        assertEquals(listOf(PageLook.GRAYSCALE, PageLook.ENHANCED), onMain { vm.items.value.map { it.look } })
    }

    /**
     * Discard photo on the crop screen takes the photo shown out of the scan, and the next shows; on a
     * scan's only photo, the scan is over, and a scan from Home goes back Home.
     */
    @Test fun discardPhotoTakesThePhotoShownOutOfTheScan() {
        val vm = launch()
        takePhotos(2)
        val photos = onMain { vm.scan.value!!.pages }
        press(R.string.crop_done)
        scanImage(1, 2)
        press(R.string.crop_discard)
        assertEquals(photos[1], scanImage(1, 1).itemId)
        assertEquals(listOf(photos[1]), onMain { vm.items.value.map { it.id } })
        press(R.string.crop_discard)
        home()
        assertNull(onMain { vm.scan.value })
        assertTrue(onMain { vm.items.value.isEmpty() })
    }

    /** The camera closed without a photo after Take another: the scan's photos stay as they were, and Done goes on. */
    @Test fun aCameraClosedWithoutAPhotoLeavesThePhotosAsTheyWere() {
        val vm = launch()
        takePhotos(2)
        val photos = onMain { vm.scan.value!!.pages }
        withCamera(Camera(closed = true)) { camera ->
            press(R.string.crop_take_another)
            waitUntil("the camera to open") { camera.opened == 1 }
        }
        waitUntil("the camera's answer to be taken in") { onMain { !vm.cameraOpen.value } }
        scanPhotos(2)
        assertEquals(ScanStage.PHOTOS to photos, onMain { vm.scan.value!!.run { stage to pages } })
        press(R.string.crop_done)
        scanImage(1, 2)
    }

    /**
     * Take another after going back from cropping to the photos: the new photo comes after them, and
     * Done crops all of them again in order, each starting where it was left.
     */
    @Test fun takeAnotherAfterComingBackFromCroppingAddsAPhotoAfterTheOthers() {
        val vm = launch()
        takePhotos(2)
        val photos = onMain { vm.scan.value!!.pages }
        press(R.string.crop_done)
        val first = scanImage(1, 2)
        moveLeft(R.string.crop_corner_top_left, 3)
        press(R.string.crop_next)
        scanImage(2, 2)
        backKey()
        scanImage(1, 2)
        backKey()
        scanPhotos(2)
        withCamera { camera ->
            press(R.string.crop_take_another)
            scanPhotos(3)
            assertEquals(1, camera.opened)
        }
        val all = onMain { vm.scan.value!!.pages }
        assertEquals(photos, all.take(2))
        press(R.string.crop_done)
        assertEquals(movedLeft(first, 3), scanImage(1, 3).start[CropQuad.TOP_LEFT].x, 1e-3f)
        press(R.string.crop_next)
        nextOnEach(3, from = 2)
        press(R.string.save)
        editor()
        assertEquals(all, pagesMade(3).map { it.id })
    }

    /**
     * The pages screen's own Take photo: the scan's pages come after the pages already there, and its
     * Same filter for all pages reaches only its own pages, the older ones keeping their looks.
     */
    @Test fun aScanFromThePagesScreenAddsItsPagesAfterTheOthersAndGivesThemAloneItsLooks() {
        val vm = launch()
        val older = (1..2).map { Uri.fromFile(PagePhotos.jpeg(PagePhotos.photo(), File(app.cacheDir, "test-photos/older$it.jpg"))) }
        onMain { vm.addImages(older) }
        editor()
        waitUntil("the pages to be added") { onMain { vm.items.value.size == 2 && vm.pendingImports.value == 0 } }
        val olderIds = onMain { vm.items.value.map { it.id } }
        takePhotos(2) {
            click(button(R.string.add_images))
            click(button(R.string.add_from_camera))
        }
        val photos = onMain { vm.scan.value!!.pages }
        press(R.string.crop_done)
        nextOnEach(2)
        // Its own two pages, not those before.
        scanPage(2)
        assertFalse(says(R.string.pdf_page_number, 3))
        click(sameFilterSwitch())
        chooseLook(R.string.look_grayscale)
        waitUntil("the scan's pages in grayscale") { onMain { vm.items.value.filter { it.id in photos }.all { it.look == PageLook.GRAYSCALE } } }
        press(R.string.save)
        editor()
        val pages = pagesMade(4)
        assertEquals(olderIds + photos, pages.map { it.id })
        assertEquals(listOf(PageLook.ORIGINAL, PageLook.ORIGINAL, PageLook.GRAYSCALE, PageLook.GRAYSCALE), pages.map { it.look })
    }

    /**
     * While a scan goes on, at each of its stages, the pages screen (and its Convert) can't be reached:
     * the Images to PDF shortcut, used from a tool opened over the scan, shows the scan again, and so
     * does the Scan shortcut, which starts no other scan. Only Save shows the pages screen.
     */
    @Test fun aScanUnderWayKeepsThePagesScreenOutOfReachAndTheScanShortcutBringsItBack() {
        val vm = launch()
        withCamera { camera ->
            click(button(R.string.add_from_camera))
            scanPhotos(1)
            /** Merge PDF opened over the scan with its shortcut, then the shortcut to [target], back to the scan's [stage]. */
            fun leftAndBack(stage: () -> Unit) {
                for (target in listOf(ShortcutTarget.SCAN, ShortcutTarget.IMAGES)) {
                    shortcut(ShortcutTarget.MERGE)
                    find("Merge PDF") { it.says(app.getString(R.string.tool_merge)) }
                    shortcut(target)
                    stage()
                    assertFalse("The pages screen shows", says(R.string.convert))
                    assertTrue(onMain { vm.scanUnderWay() })
                }
            }
            leftAndBack { scanPhotos(1) }
            press(R.string.crop_done)
            leftAndBack { scanImage(1, 1) }
            press(R.string.crop_next)
            leftAndBack { scanFilters() }
            assertEquals("Only the scan's own photo was taken", 1, camera.opened)
        }
        press(R.string.save)
        editor()
        assertEquals(1, pagesMade(1).size)
    }

    /**
     * The phone turned at each stage keeps it: the photos (and the question whether to discard them,
     * while it shows), the image on the crop screen with the corner moved on it, and on the Filters
     * the page shown and the switch.
     */
    @Test fun eachStageIsKeptWhenThePhoneIsTurned() {
        launch()
        takePhotos(2)
        backKey()
        find("the question") { it.says(app.getString(R.string.scan_discard_title)) }
        turned()
        find("the question, still") { it.says(app.getString(R.string.scan_discard_title)) }
        click(button(R.string.keep))
        scanPhotos(2)
        turned()
        scanPhotos(2)

        press(R.string.crop_done)
        scanImage(1, 2)
        press(R.string.crop_next)
        val second = scanImage(2, 2)
        moveLeft(R.string.crop_corner_top_left, 4)
        turned()
        assertEquals(second.itemId, scanImage(2, 2).itemId)
        press(R.string.crop_next)

        scanFilters()
        showPage(2)
        click(sameFilterSwitch())
        waitUntil("the switch to be on") { sameFilterSwitch().chosen }
        turned()
        scanFilters()
        waitUntil("page 2, still shown") { scanPage(2).chosen && !scanPage(1).chosen }
        assertTrue(sameFilterSwitch().chosen)
        press(R.string.save)
        editor()
        assertEquals(movedLeft(second, 4), pagesMade(2)[1].original!!.corners[CropQuad.TOP_LEFT].x, 1e-3f)
    }

    /**
     * Android stopping Usugami at each stage loses nothing: the photos, the image on the crop screen
     * with the corner moved on it, and on the Filters the page shown, the switch and the looks.
     */
    @Test fun eachStageIsKeptWhenAndroidStopsUsugami() {
        launch()
        takePhotos(2)
        val photos = vm().let { vm -> onMain { vm.scan.value!!.pages } }
        stopped()
        scanPhotos(2)
        val restarted = vm()
        assertEquals(ScanStage.PHOTOS to photos, onMain { restarted.scan.value!!.run { stage to pages } })

        press(R.string.crop_done)
        scanImage(1, 2)
        press(R.string.crop_next)
        val second = scanImage(2, 2)
        moveLeft(R.string.crop_corner_top_left, 4)
        stopped()
        assertEquals(second.itemId, scanImage(2, 2).itemId)
        press(R.string.crop_next)

        scanFilters()
        showPage(2)
        click(sameFilterSwitch())
        chooseLook(R.string.look_grayscale)
        stopped()
        scanFilters()
        waitUntil("page 2, still shown") { scanPage(2).chosen && !scanPage(1).chosen }
        assertTrue(sameFilterSwitch().chosen)
        waitUntil("grayscale, still chosen") { look(R.string.look_grayscale).chosen }
        press(R.string.save)
        editor()
        val pages = pagesMade(2)
        assertEquals(photos, pages.map { it.id })
        assertEquals(List(2) { PageLook.GRAYSCALE }, pages.map { it.look })
        assertEquals(movedLeft(second, 4), pages[1].original!!.corners[CropQuad.TOP_LEFT].x, 1e-3f)
    }

    /**
     * What a screen reader hears and does on a scan's screens: each screen named as a pane; the photos
     * numbered, each with its Remove button; the crop screen's place among the images; on the Filters,
     * the pages as radio buttons saying which is shown, the looks, the sliders set as a screen reader
     * sets them and the switch.
     */
    @Test fun aScreenReaderHearsAndWorksAScansScreens() {
        val vm = launch()
        fun pane(title: String) = waitUntil("the pane \"$title\"") { nodes().any { it.paneTitle?.toString() == title } }
        takePhotos(3)
        pane(app.getString(R.string.scan_photos_title))
        for (number in 1..3) find("photo $number's number") { it.says(app.getString(R.string.pdf_page_number, number)) }
        click(button(R.string.remove_page, 2))
        scanPhotos(2)
        press(R.string.crop_done)
        scanImage(1, 2)
        pane(app.getString(R.string.scan_pane_title, app.getString(R.string.crop_title), app.getString(R.string.scan_place, 1, 2)))
        for (label in listOf(R.string.back, R.string.crop_discard, R.string.crop_rotate_left, R.string.crop_rotate_right)) button(label)
        press(R.string.crop_next)
        nextOnEach(2, from = 2)
        pane(app.getString(R.string.filter_title))

        // The pages, as radio buttons: the first is shown.
        waitUntil("page 1 to be shown") { scanPage(1).chosen && !scanPage(2).chosen }
        showPage(2)
        assertFalse(scanPage(1).chosen)
        waitUntil("Enhanced, its look") { look(R.string.look_enhanced).chosen }
        chooseLook(R.string.look_ink_saver)
        val progress = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 25f) }
        assertTrue(slider(R.string.filter_contrast).performAction(AccessibilityAction.ACTION_SET_PROGRESS.id, progress))
        waitUntil("the contrast to be set") { slider(R.string.filter_contrast).run { refresh(); rangeInfo.current } == 25f }
        val page2 = onMain { vm.scan.value!!.pages[1] }
        waitUntil("page 2 to have them") { onMain { vm.items.value.first { it.id == page2 }.run { look == PageLook.INK_SAVER && adjustments == LookAdjustments(contrast = 25) } } }
        val same = sameFilterSwitch()
        assertFalse(same.chosen)
        click(same)
        waitUntil("the switch to be on") { sameFilterSwitch().chosen }
        waitUntil("every page with page 2's") { onMain { vm.items.value.all { it.look == PageLook.INK_SAVER && it.adjustments == LookAdjustments(contrast = 25) } } }
        press(R.string.save)
        editor()
    }

    private companion object {
        /** The looks' names. */
        val LOOKS = listOf(
            R.string.look_original, R.string.look_lighten, R.string.look_enhanced, R.string.look_super_enhanced,
            R.string.look_grayscale, R.string.look_black_white, R.string.look_ink_saver,
        )
    }
}
