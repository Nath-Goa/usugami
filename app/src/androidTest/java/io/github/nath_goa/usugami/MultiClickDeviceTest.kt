package io.github.nath_goa.usugami

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ClipData
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.SettingsScreen
import io.github.nath_goa.usugami.ui.SplitMode
import io.github.nath_goa.usugami.ui.UsugamiTheme
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.random.Random

/**
 * Taps faster than the screens can change: a double tap, two buttons in the same frame, a tap while
 * a screen slides away, or before another app's screen has covered Usugami. Each action happens
 * once, and nothing tapped or picked late lands somewhere else or leaves an empty file behind.
 */
class MultiClickDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private var scenario: ActivityScenario<MainActivity>? = null
    private var activity: MainActivity? = null
    private val monitors = ArrayList<Instrumentation.ActivityMonitor>()
    private val work = File(app.cacheDir, "output/test-multi")
    private val photos = File(app.cacheDir, "test-multi-photos")

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Before fun setUp() {
        // The screens are in the phone's language, as [app]'s strings are.
        if (AppLanguages.reset(app)) Thread.sleep(1_000)
    }

    @After fun tearDown() {
        monitors.forEach(instrumentation::removeMonitor)
        compose.mainClock.autoAdvance = true
        scenario?.let { launched ->
            val vm = mainVm()
            val pdfVm = toolsVm()
            onMain { vm.clearAll() }
            // Its screens and their file work are over before the next test makes files of its own.
            launched.close()
            runBlocking {
                vm.awaitFileWork()
                pdfVm.awaitFileWork()
            }
        }
        work.deleteRecursively()
        photos.deleteRecursively()
    }

    private fun launch(): MainActivity {
        AppLanguages.skipChoice(app)
        val launched = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        scenario = launched
        launched.onActivity { activity = it }
        return activity!!.also {
            compose.waitForIdle()
            // Their cleanup of an earlier session's files would delete what the test makes.
            runBlocking {
                mainVm().awaitFileWork()
                toolsVm().awaitFileWork()
            }
        }
    }

    private fun mainVm(): MainViewModel = onMain { ViewModelProvider(activity!!)[MainViewModel::class.java] }
    private fun toolsVm(): PdfToolsViewModel = onMain { ViewModelProvider(activity!!)[PdfToolsViewModel::class.java] }

    private fun text(res: Int): String = app.getString(res)

    /** The button (or card, or menu item) labelled [label]. */
    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction())
    private fun button(label: Int) = button(text(label))

    /** What a tap on this does, to be run on the main thread: two of them in a row are two taps in the same frame. */
    private fun SemanticsNodeInteraction.tap(): () -> Unit {
        val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.OnClick].action) { "Not clickable" }
        return { action() }
    }

    /** Runs [taps] in one go on the main thread: taps that land before Usugami has drawn another frame. */
    private fun inOneFrame(vararg taps: () -> Unit) = onMain { taps.forEach { it() } }

    private fun waitFor(what: String, timeout: Long = 20_000, condition: () -> Boolean) {
        try {
            compose.waitUntil(timeout) { condition() }
        } catch (e: Throwable) {
            throw AssertionError("Timed out waiting for $what", e)
        }
    }

    /** Waits for [text] on the screen, as once the screen showing it has opened. */
    private fun waitForText(text: String) = waitFor("\"$text\" on the screen") {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    }

    /**
     * Catches the screens of other apps that Usugami starts and [matches] (a picker, the share screen,
     * a PDF viewer), and answers each with the next of [results] (the last one answers any more).
     * Without results they are started for real.
     */
    private inner class Catch(private val matches: (Intent) -> Boolean, private val results: List<Instrumentation.ActivityResult>) :
        Instrumentation.ActivityMonitor() {
        val started = CopyOnWriteArrayList<Intent>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (!matches(intent)) return null
            started += intent
            return results.getOrNull(started.size - 1) ?: results.lastOrNull()
        }
    }

    private fun catching(vararg actions: String, results: List<Instrumentation.ActivityResult> = listOf(cancelled())): Catch =
        Catch({ it.action in actions }, results).also {
            instrumentation.addMonitor(it)
            monitors += it
        }

    private fun cancelled() = Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)

    private var probes = 0

    /**
     * Returns once the answers [catching] gave the screens Usugami has started so far have reached
     * it: they arrive in order, so a probe started after them, and answered too, gets its answer last.
     */
    private fun awaitAnswers() {
        val answered = CountDownLatch(1)
        val probe = "${app.packageName}.test.PROBE"
        catching(probe)
        val launcher = onMain {
            activity!!.activityResultRegistry
                .register("test-probe-${probes++}", ActivityResultContracts.StartActivityForResult()) { answered.countDown() }
                .also { it.launch(Intent(probe)) }
        }
        try {
            assertTrue("The answers reached Usugami", answered.await(10, TimeUnit.SECONDS))
        } finally {
            onMain { launcher.unregister() }
        }
    }
    private fun picked(vararg uris: Uri) = Instrumentation.ActivityResult(
        Activity.RESULT_OK,
        Intent().apply {
            data = uris[0]
            clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        },
    )

    /** The photo picker, or the file picker where there is none. */
    private val photoPickers = arrayOf(MediaStore.ACTION_PICK_IMAGES, "androidx.activity.result.contract.action.PICK_IMAGES", Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT)

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)

    /** An empty document, as the "Save as" screen makes one. */
    private fun newDocument(name: String): Pair<File, Uri> {
        val file = File(work, name).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(0)) }
        return file to uriFor(file)
    }

    /** A test PDF the way a picker gives it. */
    private fun pdf(fixture: String, name: String = fixture): Uri {
        val file = File(work, "picked/$name").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) }
        return uriFor(file)
    }

    /** A photo; a [big] one, full of detail, takes a while to go into a PDF. */
    private fun photo(name: String, big: Boolean = false): Uri {
        val file = File(photos, name).apply { parentFile!!.mkdirs() }
        val bitmap = if (big) {
            Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).also { image ->
                val canvas = Canvas(image)
                val paint = Paint()
                val random = Random(name.hashCode())
                repeat(4_000) {
                    paint.color = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                    val x = random.nextInt(3000).toFloat()
                    val y = random.nextInt(2000).toFloat()
                    canvas.drawRect(x, y, x + random.nextInt(10, 200), y + random.nextInt(10, 200), paint)
                }
            }
        } else {
            Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }

    /** Opens the editor with [names] as its images. */
    private fun editorWith(vararg names: String, big: Boolean = false): MainViewModel {
        val vm = mainVm()
        val uris = names.map { photo(it, big) }
        onMain { vm.addImages(uris) }
        waitFor("the images to be added") { vm.items.value.size == names.size && vm.pendingImports.value == 0 }
        compose.waitForIdle()
        return vm
    }

    /** Images to PDF, with one image made into a PDF named [name]; returns once the result shows. */
    private fun imagesResult(name: String): MainViewModel {
        val vm = editorWith("$name.jpg")
        onMain { vm.convert(merge = true, fileName = name, compression = Compression.RECOMMENDED) }
        waitFor("the PDF") { vm.result.value != null }
        waitForText(text(R.string.result_heading_one))
        compose.waitForIdle()
        return vm
    }

    /** Opens the tool whose card on Home says [detail]. */
    private fun openTool(detail: Int): PdfToolsViewModel {
        // Tapped through its click action: a message at the bottom of Home may cover the card.
        onMain(button(detail).performScrollTo().tap())
        compose.waitForIdle()
        waitFor("the tool") { compose.onAllNodes(hasText(text(detail))).fetchSemanticsNodes().isEmpty() }
        return toolsVm()
    }

    private fun addPdf(pdfVm: PdfToolsViewModel, fixture: String = "alpha.pdf") {
        val uri = pdf(fixture)
        onMain { pdfVm.addPdfs(listOf(uri), replace = true) }
        waitFor("the PDF to be added") { pdfVm.inputs.value.size == 1 && !pdfVm.importing.value }
        compose.waitForIdle()
    }

    /** Split PDF, every page of alpha.pdf (three pages) into a PDF of its own. */
    private fun splitResult(): PdfToolsViewModel {
        val pdfVm = openTool(R.string.tool_split_detail)
        addPdf(pdfVm)
        onMain { pdfVm.runSplit(SplitMode.EVERY_PAGE, "", combine = false, fileName = "alpha") }
        waitFor("the PDFs") { pdfVm.result.value?.size == 3 }
        waitForText(text(R.string.result_heading_many))
        compose.waitForIdle()
        return pdfVm
    }

    private fun zipEntries(file: File): List<String> = ZipInputStream(file.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.map { it.name }.toList()
    }

    /** Whether Usugami is showing and takes taps: resumed, its window may still be coming back on screen until it has the focus. */
    private fun usugamiTakesTaps() = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity && it.hasWindowFocus() }
    }

    /** Whether another app's screen covers Usugami: it is stopped once that screen shows. */
    private fun usugamiCovered() = onMain {
        ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.STOPPED).any { it is MainActivity }
    }

    /**
     * Waits for the screen of another app Usugami has just started to cover it: until then Usugami
     * still takes taps, and [backToUsugami] would take it as back already and leave that screen open.
     */
    private fun awaitCovered() = waitFor("the other app's screen to cover Usugami", timeout = 30_000) { usugamiCovered() }

    /**
     * Goes back to Usugami from the screens of other apps started for real, until it takes taps again.
     * Back is pressed once another app's screen covers Usugami, and again only if it still does a while
     * later: pressed while that screen is still on its way, or as it goes, Back waits for a window to
     * take it, which on a slow phone can be Usugami's, which it would take out of its result.
     */
    private fun backToUsugami() {
        repeat(6) {
            waitFor("Usugami, or another app's screen over it", timeout = 30_000) { usugamiTakesTaps() || usugamiCovered() }
            if (usugamiTakesTaps()) return
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            // Gone, Usugami is back; still there a while later, it had another screen to go back from.
            try {
                waitFor("the other app's screen to go", timeout = 10_000) { !usugamiCovered() }
            } catch (_: AssertionError) {
                // Pressed again.
            }
        }
        throw AssertionError("Usugami didn't come back")
    }

    // ---- Another app's screen opens once per burst of taps ----

    /** Two taps on "Save as" before the "Save as" screen covers Usugami open it once, and the PDF is saved there. */
    @Test fun doubleTappingSaveAsOpensOneSaveScreen() {
        launch()
        val vm = imagesResult("Double")
        val (first, firstUri) = newDocument("first.pdf")
        val (_, secondUri) = newDocument("second.pdf")
        val create = catching(Intent.ACTION_CREATE_DOCUMENT, results = listOf(created(firstUri), created(secondUri)))
        val saveAs = button(R.string.save_as).tap()
        inOneFrame(saveAs, saveAs)
        waitFor("the PDF to be saved") { vm.saved.value && !vm.busySaving.value }
        assertEquals("\"Save as\" screens opened", 1, create.started.size)
        assertArrayEquals(onMain { vm.result.value!!.single().file.readBytes() }, first.readBytes())
    }

    private fun created(uri: Uri) = Instrumentation.ActivityResult(
        Activity.RESULT_OK,
        Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
    )

    /**
     * The real "Save as" screen and taps through the system's input: a tap right after the first
     * doesn't open another, and once Usugami is back, "Save as" opens it again.
     */
    @Test fun doubleTappingSaveAsOpensOneRealSaveScreen() {
        launch()
        imagesResult("Real")
        val create = Catch({ it.action == Intent.ACTION_CREATE_DOCUMENT }, emptyList()).also {
            instrumentation.addMonitor(it)
            monitors += it
        }
        val saveAs = button(R.string.save_as)
        try {
            tapOnScreen(saveAs, times = 2)
            Thread.sleep(1_500)
            assertEquals("\"Save as\" screens opened", 1, create.started.size)
            awaitCovered()
        } finally {
            backToUsugami()
        }
        // Once back, "Save as" opens it again.
        compose.waitForIdle()
        try {
            tapOnScreen(saveAs, times = 1)
            waitFor("the second \"Save as\" screen") { create.started.size == 2 }
            awaitCovered()
        } finally {
            backToUsugami()
        }
    }

    /**
     * Taps the middle of [node] [times] times, [gap] ms apart, through the system's input as a finger
     * does: each tap goes to whatever window is on top by then.
     */
    private fun tapOnScreen(node: SemanticsNodeInteraction, times: Int, gap: Long = 80) {
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val origin = IntArray(2).also { onMain { activity!!.window.decorView.getLocationOnScreen(it) } }
        val x = origin[0] + bounds.center.x
        val y = origin[1] + bounds.center.y
        repeat(times) { i ->
            if (i > 0) Thread.sleep(gap)
            val down = SystemClock.uptimeMillis()
            inject(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
            Thread.sleep(20)
            inject(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
        }
    }

    private fun inject(event: MotionEvent) {
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.uiAutomation.injectInputEvent(event, true)
        event.recycle()
    }

    /** A tool's several PDFs: two taps on "Save to folder" open one folder picker. */
    @Test fun doubleTappingSaveToFolderOpensOneFolderPicker() {
        launch()
        splitResult()
        val tree = catching(Intent.ACTION_OPEN_DOCUMENT_TREE)
        val saveAll = button(R.string.save_all_to_folder).tap()
        inOneFrame(saveAll, saveAll)
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals("Folder pickers opened", 1, tree.started.size)
    }

    /** Merge: two taps on "Add PDFs" open one picker; what it gives is added. */
    @Test fun doubleTappingAddPdfsOpensOnePicker() {
        launch()
        val pdfVm = openTool(R.string.tool_merge_detail)
        val open = catching(
            Intent.ACTION_OPEN_DOCUMENT,
            results = listOf(picked(pdf("alpha.pdf", "A.pdf"), pdf("beta.pdf", "B.pdf")), picked(pdf("alpha.pdf", "C.pdf"), pdf("beta.pdf", "D.pdf"))),
        )
        val add = button(R.string.add_pdfs).tap()
        inOneFrame(add, add)
        waitFor("the PDFs to be added") { pdfVm.inputs.value.size == 2 && !pdfVm.importing.value }
        assertEquals("Pickers opened", 1, open.started.size)
        assertEquals(listOf("A.pdf", "B.pdf"), pdfVm.inputs.value.map { it.name })
    }

    /** Images to PDF: two taps on "Photos" open one photo picker, and its photos are added once. */
    @Test fun doubleTappingAddPhotosOpensOnePickerAndAddsOnce() {
        launch()
        button(R.string.tool_images_detail).performClick()
        compose.waitForIdle()
        val vm = mainVm()
        val pick = catching(*photoPickers, results = listOf(picked(photo("one.jpg")), picked(photo("two.jpg"))))
        val photosButton = button(R.string.add_from_photos).tap()
        inOneFrame(photosButton, photosButton)
        waitFor("the photo to be added") { vm.items.value.isNotEmpty() && vm.pendingImports.value == 0 }
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals("Photo pickers opened", 1, pick.started.size)
        assertEquals(listOf("one.jpg"), vm.items.value.map { it.name })

        // "Photos", then "Files" in the "Add images" menu before it has gone: one picker.
        Thread.sleep(1_100)
        button(R.string.add_images).performClick()
        compose.waitForIdle()
        inOneFrame(button(R.string.add_from_photos).tap(), button(R.string.add_from_files).tap())
        waitFor("the second photo to be added") { vm.items.value.size == 2 && vm.pendingImports.value == 0 }
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals("Pickers opened", 2, pick.started.size)
        assertEquals(listOf("one.jpg", "two.jpg"), vm.items.value.map { it.name })
    }

    /** Several PDFs: two taps on "Save ZIP as…" in the menu open one "Save as" screen, and the ZIP file is saved there. */
    @Test fun doubleTappingSaveZipOpensOneSaveScreen() {
        launch()
        val pdfVm = splitResult()
        val (first, firstUri) = newDocument("first.zip")
        val (_, secondUri) = newDocument("second.zip")
        val create = catching(Intent.ACTION_CREATE_DOCUMENT, results = listOf(created(firstUri), created(secondUri)))
        button(R.string.zip_button).performScrollTo().performClick()
        compose.waitForIdle()
        val save = button(R.string.zip_save).tap()
        inOneFrame(save, save)
        waitFor("the ZIP file to be saved") { pdfVm.saved.value && !pdfVm.saving.value }
        assertEquals("\"Save as\" screens opened", 1, create.started.size)
        assertEquals(pdfVm.result.value!!.map { it.name }, zipEntries(first))
    }

    /** "Share ZIP", then "Save ZIP as…" in the same frame: only the first goes ahead. */
    @Test fun shareZipThenSaveZipInOneFrameDoesOne() {
        launch()
        splitResult()
        val chooser = catching(Intent.ACTION_CHOOSER)
        val create = catching(Intent.ACTION_CREATE_DOCUMENT)
        button(R.string.zip_button).performScrollTo().performClick()
        compose.waitForIdle()
        inOneFrame(button(R.string.zip_share).tap(), button(R.string.zip_save).tap())
        waitFor("the ZIP file to be shared") { chooser.started.size == 1 }
        compose.waitForIdle()
        assertEquals("\"Save as\" screens opened", 0, create.started.size)
    }

    /** Back (or Start over, or images shared) right after "Save" in the ZIP's "Save as" screen doesn't throw away the ZIP file. */
    @Test fun aZipFileBeingSavedIsntCancelledByLeaving() {
        val shown = launch()
        val pdfVm = splitResult()
        val names = pdfVm.result.value!!.map { it.name }
        val (document, uri) = newDocument("kept.zip")
        onMain {
            pdfVm.saveZipTo(uri)
            // Before the ZIP's progress dialog shows.
            shown.onBackPressedDispatcher.onBackPressed()
        }
        waitFor("the ZIP file to be saved") { pdfVm.saved.value && !pdfVm.saving.value }
        assertNotNull("Still on the result screen", pdfVm.result.value)
        assertEquals(names, zipEntries(document))
        assertTrue(pdfVm.saved.value)

        val (second, secondUri) = newDocument("kept2.zip")
        onMain {
            pdfVm.saveZipTo(secondUri)
            pdfVm.startOver()
        }
        waitFor("the second ZIP file") { !pdfVm.saving.value }
        assertNotNull("Start over waits for the save", pdfVm.result.value)
        assertEquals(names, zipEntries(second))
    }

    /** Images shared while a ZIP file of Images to PDF's PDFs is being saved don't cancel it. */
    @Test fun aZipFileBeingSavedIsntCancelledBySharedImages() {
        launch()
        val vm = editorWith("first.jpg", "second.jpg")
        onMain { vm.convert(merge = false, fileName = "Trip", compression = Compression.RECOMMENDED) }
        waitFor("the PDFs") { vm.result.value?.size == 2 }
        compose.waitForIdle()
        val (document, uri) = newDocument("trip.zip")
        val shared = photo("shared.jpg")
        onMain {
            vm.saveZipTo(uri)
            vm.addImages(listOf(shared))
        }
        waitFor("the ZIP file") { vm.zipProgress.value == null && document.length() > 0 }
        assertEquals(listOf("first.pdf", "second.pdf"), zipEntries(document))
    }

    /**
     * A tool's ZIP file being saved goes on when images shared meanwhile open the editor, which frees
     * the tool's files: they are freed once it is saved.
     */
    @Test fun aToolsZipFileBeingSavedIsntCancelledBySharedImages() {
        launch()
        val pdfVm = splitResult()
        val names = pdfVm.result.value!!.map { it.name }
        // The tool reset the moment the save starts, as the editor resets it once it has settled.
        val (early, earlyUri) = newDocument("early.zip")
        onMain {
            pdfVm.saveZipTo(earlyUri)
            pdfVm.reset()
        }
        waitFor("the ZIP file to be saved") { !pdfVm.saving.value }
        runBlocking { pdfVm.awaitFileWork() }
        assertEquals(names, zipEntries(early))
        assertTrue("Said to be saved", pdfVm.saved.value)

        // As it happens: the PDFs saved once, so images shared during a second save open the editor
        // without asking, and the tool is reset once it shows.
        addPdf(pdfVm)
        onMain { pdfVm.runSplit(SplitMode.EVERY_PAGE, "", combine = false, fileName = "alpha") }
        waitFor("the PDFs") { pdfVm.result.value?.size == 3 }
        compose.waitForIdle()
        val (_, firstUri) = newDocument("first.zip")
        onMain { pdfVm.saveZipTo(firstUri) }
        waitFor("the first ZIP file") { pdfVm.saved.value && !pdfVm.saving.value }
        val vm = mainVm()
        val (document, uri) = newDocument("split.zip")
        val shared = photo("shared.jpg")
        onMain {
            pdfVm.saveZipTo(uri)
            vm.addImages(listOf(shared))
        }
        waitFor("the editor, and the tool reset") { vm.items.value.size == 1 && pdfVm.result.value == null }
        waitFor("the ZIP file to be saved") { !pdfVm.saving.value }
        runBlocking { pdfVm.awaitFileWork() }
        assertEquals(names, zipEntries(document))
    }

    /**
     * Images shared while a tool's ZIP file is being saved open the editor, and the ZIP file's
     * progress and Cancel show over it, rather than Create waiting for it with no reason given. Once
     * it is done, it holds the PDFs.
     */
    @Test fun aZipFileSavedBehindTheEditorShowsItsProgress() {
        launch()
        val pdfVm = splitResult()
        val names = pdfVm.result.value!!.map { it.name }
        // Saved once, so the shared images open the editor without asking.
        val (_, firstUri) = newDocument("first.zip")
        onMain { pdfVm.saveZipTo(firstUri) }
        waitFor("the first ZIP file") { pdfVm.saved.value && !pdfVm.saving.value }
        val vm = mainVm()
        val (document, uri) = newDocument("held.zip")
        val shared = photo("shared.jpg")
        // The tool's file work is held up, so the ZIP file waits its turn while the editor opens.
        val release = holdFileWork(pdfVm)
        try {
            onMain {
                pdfVm.saveZipTo(uri)
                vm.addImages(listOf(shared))
            }
            waitFor("the editor") { vm.items.value.size == 1 && vm.pendingImports.value == 0 }
            waitForText(text(R.string.zip_making))
            assertTrue("On the editor", compose.onAllNodes(hasText(text(R.string.convert)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
            assertTrue("Cancel shows", compose.onAllNodes(hasText(text(R.string.cancel)) and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
        } finally {
            release()
        }
        waitFor("the ZIP file to be saved") { !pdfVm.saving.value }
        runBlocking { pdfVm.awaitFileWork() }
        waitFor("the progress to go") { compose.onAllNodes(hasText(text(R.string.zip_making))).fetchSemanticsNodes().isEmpty() }
        assertEquals(names, zipEntries(document))
    }

    /** Holds up [vm]'s file work, which goes one task at a time, until the returned function is called. */
    private fun holdFileWork(vm: PdfToolsViewModel): () -> Unit {
        val fileOps = PdfToolsViewModel::class.java.getDeclaredField("fileOps").apply { isAccessible = true }.get(vm) as CoroutineDispatcher
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        fileOps.dispatch(EmptyCoroutineContext, Runnable {
            held.countDown()
            release.await(30, TimeUnit.SECONDS)
        })
        assertTrue("File work held", held.await(10, TimeUnit.SECONDS))
        return { release.countDown() }
    }

    /**
     * A document picked in the "Save as" screen after leaving the result (Back before the screen
     * covered Usugami) gets nothing. Usugami says it wasn't saved, not that the PDF was cleared.
     */
    @Test fun aDocumentPickedAfterLeavingTheResultIsntSaved() {
        val shown = launch()
        val vm = imagesResult("Left")
        val (document, uri) = newDocument("left.pdf")
        val (zip, zipUri) = newDocument("left.zip")
        val create = catching(Intent.ACTION_CREATE_DOCUMENT, results = listOf(created(uri), created(zipUri)))
        val saveAs = button(R.string.save_as).tap()
        onMain {
            saveAs()
            shown.onBackPressedDispatcher.onBackPressed()
        }
        awaitAnswers()
        assertEquals("\"Save as\" screens opened", 1, create.started.size)
        val notSaved = text(R.string.msg_not_saved)
        waitForText(notSaved)
        assertNoText(app.resources.getQuantityString(R.plurals.msg_result_cleared, 1))
        assertEquals("Nothing was written into the document", 0, document.length())
        assertFalse(vm.saved.value)
        assertNull(vm.result.value)
        waitFor("the message to go") { compose.onAllNodes(hasText(notSaved)).fetchSemanticsNodes().isEmpty() }

        // A tool's ZIP file, the same.
        onMain { vm.startOver() }
        compose.waitForIdle()
        onMain { shown.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        val pdfVm = splitResult()
        button(R.string.zip_button).performScrollTo().performClick()
        compose.waitForIdle()
        val saveZip = button(R.string.zip_save).tap()
        onMain {
            saveZip()
            shown.onBackPressedDispatcher.onBackPressed()
        }
        awaitAnswers()
        assertEquals("\"Save as\" screens opened", 2, create.started.size)
        waitForText(notSaved)
        assertNoText(app.resources.getQuantityString(R.plurals.msg_result_cleared, 2))
        assertEquals("Nothing was written into the ZIP file", 0, zip.length())
        assertFalse(pdfVm.saved.value)
        assertNull(pdfVm.result.value)
    }

    private fun assertNoText(text: String) =
        assertTrue("\"$text\" on the screen", compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty())

    // ---- Each tap does one thing ----

    /** Two taps on "Save to Downloads" at double-tap speed save one copy, not "Name (1).pdf" as well. */
    @Test fun doubleTappingSaveToDownloadsSavesOneCopy() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        launch()
        val name = "Twice_${System.currentTimeMillis()}"
        try {
            val vm = imagesResult(name)
            button(R.string.save_to_downloads).performClick()
            waitFor("the PDF to be saved") { vm.saved.value && !vm.busySaving.value }
            button(R.string.save_to_downloads).performClick()
            compose.waitForIdle()
            waitFor("the second tap to be done with") { !vm.busySaving.value }
            Thread.sleep(500)
            assertEquals(listOf("$name.pdf"), downloads(name).map { it.second })

            // Saved again once the copy has gone.
            downloads(name).forEach { app.contentResolver.delete(it.first, null, null) }
            button(R.string.save_to_downloads).performClick()
            waitFor("the PDF to be saved again") { downloads(name).size == 1 && !vm.busySaving.value }

            // And the same for a tool's PDF.
            onMain { vm.startOver() }
            compose.waitForIdle()
            onMain { activity!!.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            val pdfVm = openTool(R.string.tool_compress_detail)
            addPdf(pdfVm)
            onMain { pdfVm.runCompress(PdfCompression.RECOMMENDED, "${name}_tool") }
            waitFor("the compressed PDF") { pdfVm.result.value != null }
            compose.waitForIdle()
            button(R.string.save_to_downloads).performClick()
            waitFor("the PDF to be saved") { pdfVm.saved.value && !pdfVm.saving.value }
            button(R.string.save_to_downloads).performClick()
            compose.waitForIdle()
            waitFor("the second tap to be done with") { !pdfVm.saving.value }
            Thread.sleep(500)
            assertEquals(listOf("${name}_tool.pdf"), downloads("${name}_tool").map { it.second })
        } finally {
            downloads(name).forEach { app.contentResolver.delete(it.first, null, null) }
        }
    }

    /**
     * "Save to Downloads" and "Save as" in the same frame: the document picked in the "Save as"
     * screen while the first save is under way gets the PDF too, rather than being left empty.
     */
    @Test fun aDocumentPickedDuringAnotherSaveIsSavedToo() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        launch()
        val name = "During_${System.currentTimeMillis()}"
        try {
            val vm = imagesResult(name)
            val (document, uri) = newDocument("during.pdf")
            onMain {
                vm.saveToDownloads()
                vm.saveTo(uri)
            }
            waitFor("both saves") { !vm.busySaving.value && document.length() > 0 }
            assertArrayEquals(onMain { vm.result.value!!.single().file.readBytes() }, document.readBytes())
            assertEquals(1, downloads(name).size)

            onMain { vm.startOver() }
            compose.waitForIdle()
            onMain { activity!!.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            val pdfVm = openTool(R.string.tool_compress_detail)
            addPdf(pdfVm)
            onMain { pdfVm.runCompress(PdfCompression.RECOMMENDED, "${name}_tool") }
            waitFor("the compressed PDF") { pdfVm.result.value != null }
            val (toolDocument, toolUri) = newDocument("during-tool.pdf")
            onMain {
                pdfVm.saveToDownloads()
                pdfVm.saveTo(toolUri)
            }
            waitFor("both saves") { !pdfVm.saving.value && toolDocument.length() > 0 }
            assertArrayEquals(onMain { pdfVm.result.value!!.single().file.readBytes() }, toolDocument.readBytes())
        } finally {
            downloads(name).forEach { app.contentResolver.delete(it.first, null, null) }
        }
    }

    /** Usugami's PDFs in Downloads whose names start with [prefix]: their entries and names. */
    private fun downloads(prefix: String): List<Pair<Uri, String>> {
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        return app.contentResolver.query(collection, columns, "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("$prefix%"), null)!!.use { c ->
            buildList { while (c.moveToNext()) add(ContentUris.withAppendedId(collection, c.getLong(0)) to c.getString(1)) }
        }
    }

    /** A fast double tap on "Reverse order" reverses the pages once. */
    @Test fun doubleTappingReverseOrderReversesOnce() {
        launch()
        val vm = editorWith("a.jpg", "b.jpg", "c.jpg")
        compose.onNode(hasContentDescription(text(R.string.sort)) and hasClickAction()).performClick()
        compose.waitForIdle()
        val reverse = button(R.string.sort_reverse).tap()
        inOneFrame(reverse, reverse)
        compose.waitForIdle()
        assertEquals(listOf("c.jpg", "b.jpg", "a.jpg"), vm.items.value.map { it.name })

        // As a finger does it: the second tap lands while the menu fades out.
        compose.onNode(hasContentDescription(text(R.string.sort)) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        button(R.string.sort_reverse).performClick()
        compose.mainClock.advanceTimeByFrame()
        button(R.string.sort_reverse).performClick()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), vm.items.value.map { it.name })
    }

    /** Opening a tool again while it is still sliding away starts it afresh, and its choices are kept after that. */
    @Test fun reopeningAToolWhileItSlidesAwayStartsAfresh() {
        launch()
        val watermark = text(R.string.tool_watermark)
        compose.onNode(hasSetTextAction()).performTextInput(watermark)
        compose.waitForIdle()
        val pdfVm = openTool(R.string.tool_watermark_detail)
        addPdf(pdfVm)
        val field = compose.onNode(hasSetTextAction() and hasText(text(R.string.watermark_text)))
        field.performTextInput("DRAFT")
        compose.waitForIdle()

        compose.mainClock.autoAdvance = false
        onMain { activity!!.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(100)
        onMain(button(R.string.tool_watermark_detail).tap())
        compose.mainClock.advanceTimeBy(500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        addPdf(pdfVm)
        fun typed() = compose.onNode(hasSetTextAction() and hasText(text(R.string.watermark_text)))
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        assertEquals("The tool starts afresh", "", typed())

        // What is typed now is kept when the activity is recreated, as on rotation.
        compose.onNode(hasSetTextAction() and hasText(text(R.string.watermark_text))).performTextInput("FRESH")
        compose.waitForIdle()
        val old = activity!!
        scenario!!.recreate().onActivity { activity = it }
        assertTrue("Recreated", activity !== old)
        compose.waitForIdle()
        assertEquals("FRESH", typed())
    }

    /** Back in the frames between "Create PDF" and its progress showing doesn't offer to throw the images away. */
    @Test fun backRightAfterCreateDoesntAskToDiscard() {
        val shown = launch()
        val vm = editorWith("page.jpg")
        button(R.string.convert).performClick()
        compose.waitForIdle()
        val create = button(R.string.create_pdf).tap()
        onMain {
            create()
            shown.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        assertTrue("Asked to discard the images", compose.onAllNodes(hasText(text(R.string.discard_title))).fetchSemanticsNodes().isEmpty())
        waitFor("the PDF") { vm.result.value != null }
        assertEquals(1, vm.items.value.size)
    }

    /** "Create PDF", then "Convert to PDF" underneath in the same frame, doesn't open the sheet again over the conversion. */
    @Test fun convertDuringAConversionDoesntReopenTheSheet() {
        launch()
        val vm = editorWith("1.jpg", "2.jpg", "3.jpg", "4.jpg", "5.jpg", "6.jpg", big = true)
        button(R.string.convert).performClick()
        compose.waitForIdle()
        val create = compose.onNode((hasText(text(R.string.create_pdf)) or hasText(pluralCreate(6))) and hasClickAction()).tap()
        val convert = button(R.string.convert).tap()
        compose.mainClock.autoAdvance = false
        inOneFrame(create, convert)
        compose.mainClock.advanceTimeBy(1_000)
        val converting = vm.progress.value != null
        val sheet = compose.onAllNodes(hasText(text(R.string.convert_title))).fetchSemanticsNodes().isNotEmpty()
        compose.mainClock.autoAdvance = true
        assumeTrue("The PDFs were made before the check", converting)
        assertFalse("The Convert sheet shows over the conversion", sheet)
        waitFor("the PDFs") { vm.result.value != null }
    }

    private fun pluralCreate(count: Int) = app.resources.getQuantityString(R.plurals.create_pdfs, count, count)

    /** A PDF picked after its tool has gone (Back before the picker covered Usugami) isn't loaded behind Home. */
    @Test fun aPdfPickedAfterLeavingTheToolIsIgnored() {
        val shown = launch()
        val pdfVm = openTool(R.string.tool_split_detail)
        val open = catching(Intent.ACTION_OPEN_DOCUMENT, results = listOf(picked(pdf("alpha.pdf"))))
        val pick = button(R.string.pick_pdf).tap()
        // Home doesn't settle (and free the tool's files) while the clock is stopped.
        compose.mainClock.autoAdvance = false
        onMain {
            pick()
            shown.onBackPressedDispatcher.onBackPressed()
        }
        awaitAnswers()
        val inputs = pdfVm.inputs.value.size
        val importing = pdfVm.importing.value
        compose.mainClock.autoAdvance = true
        assertEquals("Pickers opened", 1, open.started.size)
        assertEquals("PDFs loaded behind Home", 0, inputs)
        assertFalse(importing)
    }

    /** Two taps on Share, Open or "Source code" before the screen they open covers Usugami open it once. */
    @Test fun doubleTappingShareOpenOrSourceCodeOpensOnce() {
        launch()
        imagesResult("Shared")
        val chooser = catching(Intent.ACTION_CHOOSER)
        val share = button(R.string.share).tap()
        inOneFrame(share, share)
        compose.waitForIdle()
        Thread.sleep(1_500)
        assertEquals("Share screens opened", 1, chooser.started.size)

        val view = catching(Intent.ACTION_VIEW)
        val open = button(R.string.open).tap()
        inOneFrame(open, open)
        compose.waitForIdle()
        Thread.sleep(1_500)
        assertEquals("PDF viewers opened", 1, view.started.size)

        val vm = mainVm()
        onMain { vm.startOver() }
        compose.waitForIdle()
        onMain { activity!!.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNode(hasContentDescription(text(R.string.settings)) and hasClickAction()).performClick()
        compose.waitForIdle()
        val source = button(R.string.settings_github).tap()
        inOneFrame(source, source)
        compose.waitForIdle()
        Thread.sleep(500)
        assertEquals("Browser pages opened", 2, view.started.size)
    }

    /** Changing your mind in the language dialog before Usugami has switched applies the last choice. */
    @Test fun aSecondLanguageChoiceBeforeTheSwitchIsApplied() {
        val shown = launch()
        val chosen = CopyOnWriteArrayList<AppLanguage>()
        // As if the switch hadn't landed yet: the choices are only noted.
        onMain {
            shown.setContent {
                UsugamiTheme {
                    SettingsScreen(SnackbarHostState(), onBack = {}, onChooseLanguage = { chosen += it }, onChooseAppearance = {}, onSourceCode = {}, onDonate = {}, onAbout = {})
                }
            }
        }
        compose.waitForIdle()
        val shownLanguage = AppLanguage.shownIn(shown)
        val other = AppLanguage.entries.first { it != shownLanguage }
        button(R.string.settings_language).performClick()
        compose.onNode(hasText(other.nativeName) and isSelectable()).performClick()
        compose.waitForIdle()
        assertEquals(listOf(other), chosen.toList())

        button(R.string.settings_language).performClick()
        compose.onNode(hasText(other.nativeName) and isSelectable()).assertIsSelected()
        compose.onNode(hasText(shownLanguage.nativeName) and isSelectable()).performClick()
        compose.waitForIdle()
        assertEquals(listOf(other, shownLanguage), chosen.toList())
    }
}
