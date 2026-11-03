package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.Redaction
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Redact PDF's box buttons pressed again, or together, before the screen has caught up. Unlike
 * [EditorsMultiClickDeviceTest], this runs the screen as the app does, without Compose's test rule,
 * whose dispatcher passes what the view model says to the screen at once: here, as on a phone,
 * it reaches the screen a moment later, and quick taps can come in between.
 */
class RedactQuickTapsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val automation = instrumentation.uiAutomation
    private var activity: MainActivity? = null

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    @Before fun setUp() {
        if (AppLanguages.reset(app)) Thread.sleep(1_000)
    }

    @After fun tearDown() {
        activity?.let { onMain { it.finish() } }
        File(app.cacheDir, "output/test-picked").deleteRecursively()
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            all += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        automation.rootInActiveWindow?.let(::walk)
        return all
    }

    private fun AccessibilityNodeInfo.says(text: String) = this.text?.toString() == text || contentDescription?.toString() == text

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return checkNotNull(target) { "Nothing to click at $node" }
    }

    /** The screen's content, as Compose has it now (not as a screen reader last heard of it). On the main thread. */
    private fun screen(): ViewRootForTest {
        fun find(view: View): ViewRootForTest? = when (view) {
            is ViewRootForTest -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        return checkNotNull(find(activity!!.window.decorView)) { "No Compose content" }
    }

    private fun SemanticsNode.says(text: String) =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true

    private fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }

    /** The button saying [text], if Compose has it. On the main thread. */
    private fun find(text: String): SemanticsNode? =
        screen().semanticsOwner.rootSemanticsNode.all().firstOrNull { it.says(text) && SemanticsActions.OnClick in it.config }

    private fun node(text: String): SemanticsNode? = onMain { find(text) }

    /** Whether the button saying [text] is there and can be pressed. */
    private fun enabled(text: String) = node(text)?.let { SemanticsProperties.Disabled !in it.config } == true

    /**
     * Presses each button saying one of [texts] in turn, all in one go on the main thread, as when
     * taps reach Usugami together because it was busy for a moment: each acts as the screen showed
     * it, before the screen has caught up with the ones before.
     */
    private fun pressTogether(vararg texts: String) = onMain {
        val presses = texts.map { text -> checkNotNull(find(text)?.config?.get(SemanticsActions.OnClick)?.action) { "No \"$text\"" } }
        for (press in presses) press()
    }

    private fun pdf(fixture: String): Uri {
        val file = File(app.cacheDir, "output/test-picked/$fixture").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /** Redact PDF on alpha.pdf with box A (page 1), B (page 2) and C (page 1), A chosen. */
    private fun redactWithThreeBoxes(): Pair<PdfToolsViewModel, List<RedactBox>> {
        AppLanguages.skipChoice(app)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val main = (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
        val tool = app.getString(R.string.tool_redact)
        waitUntil("Home") { nodes().any { it.says(app.getString(R.string.settings)) } }
        // Further down Home: scrolled to, as a screen reader would.
        fun onHome() = nodes().firstOrNull { it.text?.contains(tool) == true }
        repeat(10) {
            if (onHome() != null) return@repeat
            nodes().firstOrNull { AccessibilityAction.ACTION_SCROLL_DOWN in it.actionList }?.performAction(AccessibilityAction.ACTION_SCROLL_DOWN.id)
            Thread.sleep(500)
        }
        clickable(checkNotNull(onHome()) { "No $tool on Home" }).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        waitUntil("Home to slide away") { nodes().none { it.says(app.getString(R.string.settings)) } }
        val vm = onMain { ViewModelProvider(main)[PdfToolsViewModel::class.java] }
        onMain { vm.addPdfs(listOf(pdf("alpha.pdf")), replace = true) }
        // Boxes can be added once the page is drawn.
        waitUntil("the preview's copy") { onMain { vm.previewView.value } != null }
        waitUntil("the page to be drawn") { enabled(app.getString(R.string.redact_add_box)) }
        onMain {
            vm.addRedactBox(RedactBox(0, 0.15f, 0.84f, 0.55f, 0.92f))
            vm.addRedactBox(RedactBox(1, 0.1f, 0.1f, 0.5f, 0.2f))
            vm.addRedactBox(RedactBox(0, 0.5f, 0.1f, 0.9f, 0.2f))
            vm.showRedactPage(0)
            vm.selectRedactBox(0)
        }
        return vm to vm.redactBoxes.value
    }

    @Test fun doubleTapOnDeleteBoxDeletesOnlyTheChosenBox() {
        val (vm, boxes) = redactWithThreeBoxes()
        val (_, b, c) = boxes
        val delete = app.getString(R.string.redact_delete_box)
        waitUntil("the box's controls") { node(delete) != null }
        pressTogether(delete, delete)
        Thread.sleep(500)
        // B, on page 2 and out of sight, would otherwise go too, and its area be left unredacted.
        assertEquals(listOf(b, c), onMain { vm.redactBoxes.value })
        assertEquals(-1, onMain { vm.redactSelected.value })
    }

    @Test fun deleteAndAStepTogetherLeaveTheOtherBoxesAlone() {
        val (vm, boxes) = redactWithThreeBoxes()
        val (_, b, c) = boxes
        val delete = app.getString(R.string.redact_delete_box)
        waitUntil("the box's controls") { node(delete) != null }
        pressTogether(delete, app.getString(R.string.redact_move_right))
        Thread.sleep(500)
        assertEquals(listOf(b, c), onMain { vm.redactBoxes.value })
    }

    @Test fun quickStepsEachMoveTheChosenBox() {
        val (vm, boxes) = redactWithThreeBoxes()
        val right = app.getString(R.string.redact_move_right)
        waitUntil("the box's controls") { node(right) != null }
        pressTogether(right, right)
        Thread.sleep(500)
        val moved = onMain { vm.redactBoxes.value }
        assertEquals(boxes[0].left + 2 * Redaction.STEP, moved[0].left, 1e-4f)
        assertEquals(boxes.drop(1), moved.drop(1))
    }
}
