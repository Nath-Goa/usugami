package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.ShapeItem
import io.github.nath_goa.usugami.core.ShapeKind
import io.github.nath_goa.usugami.core.TextItem
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Taps that come faster than the screen updates, in Redact PDF, Edit PDF, Sign PDF's pad and the page
 * tools: a double tap, two buttons or pages in the same frame, a menu entry tapped while the menu
 * fades away, controls used while they fade away, a button pressed while a finger drags or draws,
 * a tap while saving or adding a page waits for other file work, a button tapped over and over with
 * pages it can't use.
 */
class EditorsMultiClickDeviceTest {
    @get:Rule val rule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private var activity: MainActivity? = null

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Before fun setUp() {
        // A language picked by another test: the screens are in the phone's language, as app's strings are.
        if (AppLanguages.reset(app)) Thread.sleep(1_000)
    }

    @After fun tearDown() {
        rule.mainClock.autoAdvance = true
        activity?.let { onMain { it.finish() } }
        File(app.cacheDir, "output/test-picked").deleteRecursively()
    }

    private fun string(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun exists(matcher: SemanticsMatcher) = rule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, timeout: Long = 15_000) = rule.waitUntil(timeout) { exists(matcher) }

    /** A test PDF the way a picker gives it. */
    private fun pdf(fixture: String): Uri {
        val file = File(app.cacheDir, "output/test-picked/$fixture").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /** Starts Usugami, opens the one-PDF tool [title] from Home and puts alpha.pdf (three pages) in it. */
    private fun openWithAlpha(title: Int): PdfToolsViewModel {
        AppLanguages.skipChoice(app)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val main = (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
        val tool = hasText(string(title)) and hasClickAction()
        waitFor(tool)
        rule.onNode(tool).performScrollTo().performClick()
        // Home's settings button is always on its screen.
        rule.waitUntil(10_000) { !exists(hasContentDescription(string(R.string.settings))) }
        val vm = onMain { ViewModelProvider(main)[PdfToolsViewModel::class.java] }
        onMain { vm.addPdfs(listOf(pdf("alpha.pdf")), replace = true) }
        rule.waitUntil(10_000) { vm.inputs.value.size == 1 }
        return vm
    }

    /**
     * Taps each of [targets] in turn, all before the screen can draw again, as a quick double tap or
     * two fingers can. The touches go straight to the screen in one go on the main thread: the test's
     * own taps let the screen catch up between them.
     */
    private fun tapInOneFrame(vararg targets: SemanticsMatcher) {
        rule.waitForIdle()
        val spots = targets.map { matcher ->
            val node = rule.onNode(matcher).fetchSemanticsNode()
            val view = (node.root as ViewRootForTest).view
            view to node.positionInRoot + Offset(node.size.width / 2f, node.size.height / 2f)
        }
        onMain {
            var time = SystemClock.uptimeMillis()
            for ((view, at) in spots) {
                val screen = IntArray(2).also(view::getLocationOnScreen)
                val down = time
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    // Compose reads the place on the screen, as for a real touch.
                    val event = MotionEvent.obtain(down, time++, action, at.x + screen[0], at.y + screen[1], 0)
                    event.source = InputDevice.SOURCE_TOUCHSCREEN
                    event.offsetLocation(-screen[0].toFloat(), -screen[1].toFloat())
                    view.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
        }
        rule.waitForIdle()
    }

    /** Stops file work (reading a page's size, saving) until the latch it returns is counted down. */
    private fun holdFileOps(vm: PdfToolsViewModel): CountDownLatch {
        val fileOps = PdfToolsViewModel::class.java.getDeclaredField("fileOps").apply { isAccessible = true }.get(vm) as CoroutineDispatcher
        val busy = CountDownLatch(1)
        fileOps.dispatch(EmptyCoroutineContext, Runnable { busy.await(30, TimeUnit.SECONDS) })
        return busy
    }

    // Redact PDF

    /**
     * Redact PDF with box A (page 1), B (page 2) and C (page 1), A chosen, its controls in view.
     * The box's buttons pressed together are in [RedactQuickTapsDeviceTest].
     */
    private fun redactWithThreeBoxes(): Pair<PdfToolsViewModel, List<RedactBox>> {
        val vm = openWithAlpha(R.string.tool_redact)
        // Boxes can be added once the page is drawn.
        waitFor(hasText(string(R.string.redact_add_box)) and isEnabled())
        onMain {
            // A sits low on the page, close to the controls under it, so both show at once.
            vm.addRedactBox(RedactBox(0, 0.15f, 0.84f, 0.55f, 0.92f))
            vm.addRedactBox(RedactBox(1, 0.1f, 0.1f, 0.5f, 0.2f))
            vm.addRedactBox(RedactBox(0, 0.5f, 0.1f, 0.9f, 0.2f))
            vm.showRedactPage(0)
            vm.selectRedactBox(0)
        }
        val boxes = vm.redactBoxes.value
        assertEquals(3, boxes.size)
        rule.onNode(hasText(string(R.string.redact_delete_box)) and hasClickAction()).performScrollTo()
        return vm to boxes
    }

    @Test fun redactBoxDeletedWhileDraggedStaysDeletedAndTheOthersStay() {
        val (vm, boxes) = redactWithThreeBoxes()
        val (a, b, c) = boxes
        val preview = rule.onNode(hasContentDescription(app.resources.getQuantityString(R.plurals.redact_preview_description, 2, 1, 2)))
        val size = preview.fetchSemanticsNode().size
        val start = Offset((a.left + a.right) / 2 * size.width, (a.top + a.bottom) / 2 * size.height)
        // One finger drags A; another deletes it before the first lifts.
        preview.performTouchInput {
            down(0, start)
            moveBy(0, Offset(viewConfiguration.touchSlop + 8f, 0f))
            moveBy(0, Offset(24f, 0f))
        }
        rule.onNode(hasText(string(R.string.redact_delete_box)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        rule.onNode(hasContentDescription(app.resources.getQuantityString(R.plurals.redact_preview_description, 1, 1, 1))).performTouchInput { up(0) }
        rule.waitForIdle()
        assertEquals(listOf(b, c), vm.redactBoxes.value)
    }

    // Edit PDF

    private fun editWithAlpha(): PdfToolsViewModel {
        val vm = openWithAlpha(R.string.tool_edit)
        // The tools work once the page's size is known.
        waitFor(hasText(string(R.string.edit_shape)) and hasClickAction() and isEnabled())
        return vm
    }

    private fun openShapeMenu() {
        rule.onNode(hasText(string(R.string.edit_shape)) and hasClickAction()).performScrollTo().performClick()
        waitFor(hasText(string(R.string.edit_item_rectangle)) and hasClickAction())
        rule.waitForIdle()
    }

    @Test fun editDoubleTapOnDoneAddsTheTextOnce() {
        val vm = editWithAlpha()
        rule.onNode(hasText(string(R.string.edit_text)) and hasClickAction()).performScrollTo().performClick()
        val field = hasSetTextAction() and hasAnyAncestor(isDialog())
        waitFor(field)
        rule.onNode(field).performTextInput("Hello")
        val done = hasText(string(R.string.sign_done)) and hasClickAction()
        tapInOneFrame(done, done)
        assertEquals(listOf("Hello"), vm.editItems.value.map { (it as TextItem).text })
        assertFalse(exists(isDialog()))
    }

    @Test fun editDoubleTapOnAShapeAddsOneShape() {
        val vm = editWithAlpha()
        openShapeMenu()
        val rectangle = hasText(string(R.string.edit_item_rectangle)) and hasClickAction()
        tapInOneFrame(rectangle, rectangle)
        assertEquals(1, vm.editItems.value.size)

        // A second entry tapped while the menu fades away adds nothing either.
        openShapeMenu()
        rule.mainClock.autoAdvance = false
        rule.onNode(hasText(string(R.string.edit_item_rectangle)) and hasClickAction()).performClick()
        repeat(2) { rule.mainClock.advanceTimeByFrame() }
        rule.onNode(hasText(string(R.string.edit_item_ellipse)) and hasClickAction()).performClick()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(listOf(ShapeKind.RECTANGLE, ShapeKind.RECTANGLE), vm.editItems.value.map { (it as ShapeItem).kind })
    }

    @Test fun editTapWithAJitterOnlyChoosesAndKeepsRedo() {
        val vm = editWithAlpha()
        openShapeMenu()
        rule.onNode(hasText(string(R.string.edit_item_filled_rectangle)) and hasClickAction()).performClick()
        rule.waitUntil(5_000) { vm.editItems.value.size == 1 }
        // A move, then Undo: Redo can bring the move back.
        rule.onNode(hasContentDescription(string(R.string.edit_move_right))).performScrollTo().performClick()
        rule.waitUntil(5_000) { vm.canUndoEdit.value }
        rule.onNode(hasContentDescription(string(R.string.undo))).performScrollTo().performClick()
        rule.waitUntil(5_000) { vm.canRedoEdit.value }
        val placed = vm.editItems.value.single()
        // A real finger's tap on the box, in the middle of the page, moves a pixel or two.
        rule.onNode(hasContentDescription(string(R.string.edit_preview_description, 1))).performScrollTo().performTouchInput {
            down(center)
            moveBy(Offset(1.5f, 0f))
            moveBy(Offset(0f, -1f))
            up()
        }
        rule.waitForIdle()
        assertEquals(placed, vm.editItems.value.single())
        assertTrue("A tap leaves Redo as it was", vm.canRedoEdit.value)
        assertEquals(placed.id, vm.editSelected.value)

        // A drag still moves it all the way the finger went, as one change for Undo.
        val preview = rule.onNode(hasContentDescription(string(R.string.edit_preview_description, 1)))
        val width = preview.fetchSemanticsNode().size.width
        preview.performTouchInput { swipe(center, center + Offset(width / 5f, 0f), durationMillis = 300) }
        rule.waitForIdle()
        val moved = vm.editItems.value.single() as ShapeItem
        val pageWidth = (placed as ShapeItem).x1 + placed.x2
        assertEquals(pageWidth / 5f, moved.x1 - placed.x1, pageWidth / 50f)
        assertEquals(placed.y1, moved.y1, 0.5f)
        assertFalse(vm.canRedoEdit.value)
        rule.onNode(hasContentDescription(string(R.string.undo))).performClick()
        rule.waitForIdle()
        assertEquals(placed, vm.editItems.value.single())
    }

    @Test fun editSaveShowsAtOnceThatItIsWorkingEvenWhileFilesAreBusy() {
        val vm = editWithAlpha()
        openShapeMenu()
        rule.onNode(hasText(string(R.string.edit_item_rectangle)) and hasClickAction()).performClick()
        rule.waitUntil(5_000) { vm.editItems.value.size == 1 }
        // Other file work, such as a large PDF's preview being prepared, holds up the save.
        val busy = holdFileOps(vm)
        val save = hasText(string(R.string.pdf_edit_action)) and hasClickAction()
        try {
            rule.onNode(save).performClick()
            rule.waitForIdle()
            assertTrue("Saving shows at once", vm.processing.value)
            rule.onNode(save).assertIsNotEnabled()
            // Nothing more can be added meanwhile: it would be left out of the PDF.
            rule.onNode(hasText(string(R.string.edit_shape)) and hasClickAction()).performClick()
            rule.waitForIdle()
            assertFalse(exists(hasText(string(R.string.edit_item_ellipse)) and hasClickAction()))
        } finally {
            busy.countDown()
        }
        rule.waitUntil(20_000) { vm.result.value != null }
        assertEquals(1, vm.editItems.value.size)
    }

    // Page tools

    @Test fun reorganizeQuickTurnsEachTurnThePageAndUndoTakesBackOne() {
        val vm = openWithAlpha(R.string.tool_reorganize)
        val first = hasContentDescription(string(R.string.pdf_page_number, 1)) and hasClickAction()
        waitFor(first)
        rule.onNode(first).performScrollTo().performClick()
        val turn = hasContentDescription(string(R.string.rotate_page_right, 1))
        waitFor(turn)
        tapInOneFrame(turn, turn)
        assertEquals(180, vm.pages.value.first().rotation)
        rule.onNode(hasContentDescription(string(R.string.undo))).performClick()
        rule.waitForIdle()
        assertEquals(90, vm.pages.value.first().rotation)

        // Turned and then removed in the same frame: the page goes.
        val key = vm.pages.value.first().key
        tapInOneFrame(turn, hasContentDescription(string(R.string.remove_page, 1)))
        assertEquals(2, vm.pages.value.size)
        assertTrue(vm.pages.value.none { it.key == key })
    }

    @Test fun rotateQuickTurnsOfOnePageEachTurnIt() {
        val vm = openWithAlpha(R.string.tool_rotate)
        val turn = hasContentDescription(string(R.string.rotate_page_right, 1))
        waitFor(turn)
        rule.onNode(turn).performScrollTo()
        tapInOneFrame(turn, turn)
        assertEquals(listOf(180, 0, 0), vm.pages.value.map { it.rotation })
        // Slower taps go on from there.
        rule.onNode(turn).performClick()
        rule.waitForIdle()
        assertEquals(listOf(270, 0, 0), vm.pages.value.map { it.rotation })
    }

    @Test fun reorganizeDoubleTapOnResetLeavesOneUndoStep() {
        val vm = openWithAlpha(R.string.tool_reorganize)
        val first = hasContentDescription(string(R.string.pdf_page_number, 1)) and hasClickAction()
        waitFor(first)
        rule.onNode(first).performScrollTo().performClick()
        val remove = hasContentDescription(string(R.string.remove_page, 1))
        waitFor(remove)
        rule.onNode(remove).performClick()
        rule.waitUntil(5_000) { vm.pages.value.size == 2 }
        val reset = hasText(string(R.string.pdf_reset_pages)) and hasClickAction()
        rule.onNode(reset).performScrollTo()
        tapInOneFrame(reset, reset)
        assertEquals(vm.originalPages(), vm.pages.value)
        // One Undo brings the change back.
        rule.onNode(hasContentDescription(string(R.string.undo))).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(2, vm.pages.value.size)
    }

    @Test fun reorganizeBlankPageAskedForWhileFilesAreBusyIsAddedOnceAndSaved() {
        val vm = openWithAlpha(R.string.tool_reorganize)
        val first = hasContentDescription(string(R.string.pdf_page_number, 1)) and hasClickAction()
        waitFor(first)
        rule.onNode(first).performScrollTo().performClick()
        // A change first, so the PDF can be saved.
        rule.onNode(hasContentDescription(string(R.string.rotate_page_right, 1))).performClick()
        rule.waitUntil(5_000) { vm.pages.value.first().rotation == 90 }
        val add = hasText(string(R.string.reorganize_add)) and hasClickAction()
        val blank = hasText(string(R.string.reorganize_add_blank)) and hasClickAction()
        val save = hasText(string(R.string.pdf_reorganize_action)) and hasClickAction()
        // The page's size is read with the other file work, which is busy (a large PDF's preview, say).
        val busy = holdFileOps(vm)
        try {
            rule.onNode(add).performScrollTo().performClick()
            waitFor(blank)
            tapInOneFrame(blank, blank)
            // Nothing shows yet: asked for again, if the button lets it.
            if (exists(add and isEnabled())) {
                rule.onNode(add).performClick()
                waitFor(blank)
                rule.onNode(blank).performClick()
                rule.waitForIdle()
            }
            // And saved straight away.
            rule.onNode(save).performClick()
            rule.waitForIdle()
        } finally {
            busy.countDown()
        }
        rule.waitUntil(10_000) { vm.pages.value.any { it.isBlank } }
        rule.waitUntil(10_000) { !vm.importing.value && !vm.processing.value }
        Thread.sleep(500)
        rule.waitForIdle()
        assertEquals(1, vm.pages.value.count { it.isBlank })
        assertEquals(4, vm.pages.value.size)
        // A save asked for before the blank page came has it too.
        if (vm.result.value == null) rule.onNode(save).performClick()
        rule.waitUntil(20_000) { vm.result.value != null }
        assertEquals(4, PDDocument.load(vm.result.value!!.single().file).use { it.numberOfPages })
    }

    // Delete pages and Split PDF's page pickers

    @Test fun deletePagesTwoPagesTappedTogetherAreBothMarked() {
        openWithAlpha(R.string.tool_delete_pages)
        val page = { n: Int -> hasContentDescription(string(R.string.pdf_page_number, n)) and hasClickAction() }
        waitFor(page(3))
        rule.onNode(page(3)).performScrollTo()
        tapInOneFrame(page(2), page(3))
        val field = rule.onNode(hasSetTextAction() and hasText(string(R.string.delete_pages_field)))
        field.assert(hasText("2-3"))
        assertTrue(exists(hasText(string(R.string.delete_pages_count, 2, 3))))
        // Each tap goes on from the one before: page 2 unmarked, page 1 marked.
        tapInOneFrame(page(2), page(1))
        field.assert(hasText("1,3"))
    }

    @Test fun splitTwoPagesTappedTogetherAreBothSelected() {
        openWithAlpha(R.string.tool_split)
        val page = { n: Int -> hasContentDescription(string(R.string.pdf_page_number, n)) and hasClickAction() }
        waitFor(page(3))
        rule.onNode(page(3)).performScrollTo()
        tapInOneFrame(page(2), page(3))
        val field = rule.onNode(hasSetTextAction() and hasText(string(R.string.pdf_selected)))
        field.assert(hasText("2-3"))
        // In the order tapped: page 3 goes, and page 1 comes after page 2.
        tapInOneFrame(page(3), page(1))
        field.assert(hasText("2,1"))
    }

    @Test fun splitWithWrongPagesTappedRepeatedlySaysSoOnce() {
        openWithAlpha(R.string.tool_split)
        val field = hasSetTextAction() and hasText(string(R.string.pdf_selected))
        waitFor(field)
        rule.onNode(field).performScrollTo().performTextInput("abc")
        val split = hasText(string(R.string.pdf_split_action)) and hasClickAction()
        repeat(4) { rule.onNode(split).performClick() }
        val message = hasText(string(R.string.pdf_pages_format))
        waitFor(message)
        // The screen's timers run on the test's clock, which moves on only when told to: a message
        // shows for 4 seconds, and the next one comes as it goes.
        val shown = rule.mainClock.currentTime
        while (exists(message)) {
            assertTrue("Shown once, not once per tap", rule.mainClock.currentTime - shown < 8_000)
            rule.mainClock.advanceTimeBy(250)
        }
        rule.mainClock.advanceTimeBy(2_000)
        assertFalse("Shown again", exists(message))
        // Tapped again later, it says so again.
        rule.onNode(split).performClick()
        waitFor(message)
    }

    // Edit PDF, continued

    private fun editWithFilledRectangle(): Pair<PdfToolsViewModel, ShapeItem> {
        val vm = editWithAlpha()
        openShapeMenu()
        rule.onNode(hasText(string(R.string.edit_item_filled_rectangle)) and hasClickAction()).performClick()
        rule.waitUntil(5_000) { vm.editItems.value.size == 1 }
        rule.waitForIdle()
        return vm to vm.editItems.value.single() as ShapeItem
    }

    @Test fun editControlsFadingAwayChangeNothing() {
        val (vm, placed) = editWithFilledRectangle()
        // A new shape is red.
        val blue = hasContentDescription(string(R.string.color_blue)) and hasClickAction()
        val fill = hasContentDescription(string(R.string.edit_fill)) and hasClickAction()
        rule.onNode(blue).performScrollTo()
        rule.mainClock.autoAdvance = false
        // Page 2 is chosen in the strip: the shape on page 1 is let go, and its controls fade away…
        rule.onNode(hasContentDescription(string(R.string.pdf_page_number, 2)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        // …and are used while they still show.
        rule.onNode(blue).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNode(fill).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNode(hasContentDescription(string(R.string.sign_size)) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertEquals(placed, vm.editItems.value.single())
        // Undo takes back adding it, not a change made out of sight.
        rule.onNode(hasContentDescription(string(R.string.undo))).performScrollTo().performClick()
        rule.waitForIdle()
        assertTrue(vm.editItems.value.isEmpty())
    }

    /** Where [item] is on the preview [node] of a page the shape [placed] was put in the middle of. */
    private fun centerOf(item: ShapeItem, placed: ShapeItem, node: SemanticsNodeInteraction): Offset {
        val size = node.fetchSemanticsNode().size
        return Offset((item.x1 + item.x2) / 2 / (placed.x1 + placed.x2) * size.width, (item.y1 + item.y2) / 2 / (placed.y1 + placed.y2) * size.height)
    }

    @Test fun editDragGivesWayToWhatAnotherFingerDoes() {
        val (vm, placed) = editWithFilledRectangle()
        val preview = rule.onNode(hasContentDescription(string(R.string.edit_preview_description, 1)))
        preview.performScrollTo()
        fun startDrag() {
            val at = centerOf(vm.editItems.value.single() as ShapeItem, placed, preview)
            preview.performTouchInput {
                down(0, at)
                moveBy(0, Offset(viewConfiguration.touchSlop + 8f, 0f))
                moveBy(0, Offset(24f, 0f))
            }
            rule.waitForIdle()
        }
        fun endDrag(on: SemanticsNodeInteraction = preview) {
            on.performTouchInput {
                moveBy(0, Offset(24f, 0f))
                moveBy(0, Offset(24f, 0f))
                up(0)
            }
            rule.waitForIdle()
        }

        // A colour picked with another finger mid-drag stays, and the drag stops there.
        startDrag()
        assertNotEquals(placed, vm.editItems.value.single())
        rule.onNode(hasContentDescription(string(R.string.color_blue)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val blue = vm.editItems.value.single() as ShapeItem
        assertNotEquals(placed.color, blue.color)
        endDrag()
        assertEquals(blue, vm.editItems.value.single())

        // Undo mid-drag stays undone, and Redo can bring the drag back.
        startDrag()
        val dragged = vm.editItems.value.single()
        rule.onNode(hasContentDescription(string(R.string.undo))).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(blue, vm.editItems.value.single())
        endDrag()
        assertEquals(blue, vm.editItems.value.single())
        assertTrue("Redo is kept", vm.canRedoEdit.value)
        rule.onNode(hasContentDescription(string(R.string.redo))).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(dragged, vm.editItems.value.single())

        // Another page chosen mid-drag: the item no longer moves out of sight.
        startDrag()
        rule.onNode(hasContentDescription(string(R.string.pdf_page_number, 2)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        val left = vm.editItems.value.single()
        endDrag(rule.onNode(hasContentDescription(string(R.string.edit_preview_description, 2))))
        assertEquals(left, vm.editItems.value.single())
    }

    // Sign PDF's pad

    private fun openSignaturePad(): Pair<PdfToolsViewModel, SemanticsNodeInteraction> {
        val vm = openWithAlpha(R.string.tool_sign)
        val draw = hasText(string(R.string.sign_draw)) and hasClickAction()
        waitFor(draw)
        rule.onNode(draw).performScrollTo().performClick()
        val pad = hasContentDescription(string(R.string.sign_pad))
        waitFor(pad)
        rule.waitForIdle()
        val node = rule.onNode(pad)
        // A first stroke.
        node.performTouchInput { swipe(Offset(width * 0.2f, height * 0.5f), Offset(width * 0.5f, height * 0.4f), 200) }
        rule.waitForIdle()
        return vm to node
    }

    private fun startStroke(pad: SemanticsNodeInteraction) {
        pad.performTouchInput {
            down(0, Offset(width * 0.55f, height * 0.5f))
            moveBy(0, Offset(30f, 10f))
            moveBy(0, Offset(30f, -10f))
            moveBy(0, Offset(30f, 10f))
        }
        rule.waitForIdle()
    }

    @Test fun signaturePadClearedMidStrokeStaysEmpty() {
        val (_, pad) = openSignaturePad()
        val clear = hasText(string(R.string.sign_clear)) and hasClickAction()
        val done = hasText(string(R.string.sign_done)) and hasClickAction()
        rule.onNode(done).assertIsEnabled()
        // One finger signs on; another taps Clear.
        startStroke(pad)
        rule.onNode(clear).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        pad.performTouchInput {
            moveBy(0, Offset(30f, 0f))
            up(0)
        }
        rule.waitForIdle()
        rule.onNode(done).assertIsNotEnabled()
        rule.onNode(clear).assertIsNotEnabled()
    }

    @Test fun signatureDoneMidStrokeKeepsTheStrokeDrawn() {
        val (vm, pad) = openSignaturePad()
        startStroke(pad)
        rule.onNode(hasText(string(R.string.sign_done)) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(2, vm.signature.value!!.strokes.size)
    }
}
