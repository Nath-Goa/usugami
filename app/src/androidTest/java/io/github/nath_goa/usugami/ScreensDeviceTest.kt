package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The real screens, read and driven through the accessibility tree TalkBack uses. Pages can be moved
 * without dragging, buttons repeated per file name their file, the password dialog names its PDF and
 * says when a password is wrong, messages and shared images don't get lost, and a long text keeps
 * its place.
 */
class ScreensDeviceTest {
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
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    private fun launch(): MainActivity {
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
    }

    @After fun tearDown() {
        activity?.let { onMain { it.finish() } }
        File(app.cacheDir, "output/test-picked").deleteRecursively()
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

    private fun find(what: String, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil(what) {
            found = nodes().firstOrNull(match)
            found != null
        }
        return found!!
    }

    private fun AccessibilityNodeInfo.says(text: String) =
        this.text?.toString() == text || contentDescription?.toString() == text

    private fun AccessibilityNodeInfo.action(label: String): AccessibilityAction? = actionList.firstOrNull { it.label == label }

    private fun hasAction(label: String) = nodes().any { it.action(label) != null }

    /** [node], or the clickable item it is part of, such as the button around a label. */
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return checkNotNull(target) { "Nothing to click at $node" }
    }

    private fun click(node: AccessibilityNodeInfo) = assertTrue(clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))

    private fun type(field: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
    }

    /**
     * Whether a screen reader stops on [node]: the item carrying the move actions must, or they can't
     * be reached. A page that is still fading in doesn't count yet.
     */
    private fun focusable(node: AccessibilityNodeInfo) = Build.VERSION.SDK_INT < Build.VERSION_CODES.P || node.isScreenReaderFocusable

    /** The card with [action], once a screen reader can stop on it. */
    private fun cardWith(action: String) = find("a card with \"$action\" that a screen reader can focus") { it.action(action) != null && focusable(it) }

    private fun photo(name: String): Uri {
        val file = File(app.cacheDir, "test-photos/$name").apply { parentFile!!.mkdirs() }
        file.outputStream().use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return Uri.fromFile(file)
    }

    /** A test PDF the way a picker gives it: a content:// URI with a display name. */
    private fun pdf(fixture: String, name: String = fixture): Uri {
        val file = File(app.cacheDir, "output/test-picked/$name").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    private fun openTool(title: Int, activity: MainActivity): PdfToolsViewModel {
        click(find("the tool on the home screen") { it.text?.contains(app.getString(title)) == true })
        return onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
    }

    @Test fun editorPagesCanBeMovedWithoutDragging() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        onMain { vm.addImages(listOf(photo("a.jpg"), photo("b.jpg"), photo("c.jpg"))) }
        waitUntil("the images to be added") { onMain { vm.items.value.size } == 3 }
        val ids = onMain { vm.items.value.map { it.id } }

        val earlier = app.getString(R.string.move_page_earlier, 2)
        val card = cardWith(earlier)
        assertNotNull(card.action(app.getString(R.string.move_page_later, 2)))
        assertTrue("Page 1 can't move earlier", !hasAction(app.getString(R.string.move_page_earlier, 1)))

        assertTrue(card.performAction(card.action(earlier)!!.id))
        waitUntil("page 2 to move first") { onMain { vm.items.value.map { it.id } } == listOf(ids[1], ids[0], ids[2]) }
        onMain { vm.clearAll() }
    }

    @Test fun mergePagesCanBeMovedWithoutDraggingAndFileButtonsNameTheirFile() {
        val activity = launch()
        val pdfVm = openTool(R.string.tool_merge, activity)
        onMain { pdfVm.addPdfs(listOf(pdf("alpha.pdf"), pdf("beta.pdf")), replace = false) }
        waitUntil("both PDFs to be added") { onMain { pdfVm.inputs.value.size } == 2 }

        find("alpha's move button") { it.says(app.getString(R.string.move_file_later, "alpha.pdf")) }
        find("beta's remove button") { it.says(app.getString(R.string.remove_file, "beta.pdf")) }

        click(find("the page mode switch") { it.says(app.getString(R.string.pdf_page_mode_switch)) })
        val earlier = app.getString(R.string.move_page_earlier, 2)
        val card = cardWith(earlier)
        assertTrue("Page 1 can't move earlier", !hasAction(app.getString(R.string.move_page_earlier, 1)))

        val pages = onMain { pdfVm.pages.value }
        assertTrue(card.performAction(card.action(earlier)!!.id))
        waitUntil("page 2 to move first") { onMain { pdfVm.pages.value } == listOf(pages[1], pages[0]) + pages.drop(2) }
    }

    @Test fun passwordDialogNamesThePdfAndSaysWhenThePasswordIsWrong() {
        val activity = launch()
        val pdfVm = openTool(R.string.tool_split, activity)
        onMain { pdfVm.addPdfs(listOf(pdf("protected.pdf", "statement.pdf")), replace = true) }

        find("the dialog to name the PDF") { it.says(app.getString(R.string.pdf_password_body, "statement.pdf")) }
        val open = app.getString(R.string.pdf_unlock)
        val field = find("the password field") { it.isEditable }
        assertTrue("The field is a password field", field.isPassword)
        assertTrue("Nothing to open without a password", !clickable(find("the open button") { it.says(open) }).isEnabled)
        val wrong = app.getString(R.string.pdf_wrong_password)
        type(field, "wrong")
        click(find("the open button to be enabled") { it.says(open) && clickable(it).isEnabled })
        find("the error in the dialog") { node -> listOf(node.text, node.contentDescription, node.error).any { it?.contains(wrong) == true } }

        type(find("the password field") { it.isEditable }, "test123")
        click(find("the open button to be enabled") { it.says(open) && clickable(it).isEnabled })
        waitUntil("the PDF to be opened") { onMain { pdfVm.locked.value.isEmpty() && pdfVm.inputs.value.size == 1 } }
        assertEquals("statement.pdf", onMain { pdfVm.inputs.value.single().name })
    }

    /** Home has no snackbar of its own to fall back on: a message must still show there. */
    @Test fun messagesShowOnTheHomeScreen() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        // Shared images Usugami can't read leave Home showing, with a message.
        onMain { vm.addImages(emptyList(), refused = 1) }
        find("the message on Home") { it.says(app.resources.getQuantityString(R.plurals.msg_failed_import, 1, 1)) }
    }

    /** Compresses a PDF and waits for its result, which isn't saved. */
    private fun unsavedCompressResult(activity: MainActivity): PdfToolsViewModel {
        val pdfVm = openTool(R.string.tool_compress, activity)
        onMain { pdfVm.addPdfs(listOf(pdf("alpha.pdf")), replace = true) }
        waitUntil("the PDF to be added") { onMain { pdfVm.inputs.value.size } == 1 }
        onMain { pdfVm.runCompress(PdfCompression.RECOMMENDED, "compressed") }
        find("the result screen") { it.says(app.getString(R.string.result_heading_one)) }
        return pdfVm
    }

    @Test fun imagesSharedOverAnUnsavedPdfAskFirst() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        val pdfVm = unsavedCompressResult(activity)

        onMain { vm.addImages(listOf(photo("shared1.jpg"))) }
        click(find("the Keep button") { it.says(app.getString(R.string.keep)) })
        waitUntil("the dialog to close") { nodes().none { it.says(app.getString(R.string.pdf_discard_title)) } }
        assertTrue("The PDF is kept", onMain { pdfVm.result.value != null })

        // Having said to keep it, more shared images don't ask again while the PDF is open.
        onMain { vm.addImages(listOf(photo("shared2.jpg"))) }
        waitUntil("the second image to be added") { onMain { vm.items.value.size } == 2 }
        Thread.sleep(1000)
        assertFalse(nodes().any { it.says(app.getString(R.string.pdf_discard_title)) })
        find("the result screen, still showing") { it.says(app.getString(R.string.result_heading_one)) }
        assertTrue(onMain { pdfVm.result.value != null })
        onMain { vm.clearAll() }
    }

    @Test fun imagesSharedOverAnUnsavedPdfCanDiscardIt() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        val pdfVm = unsavedCompressResult(activity)

        onMain { vm.addImages(listOf(photo("shared.jpg"))) }
        find("the question") { it.says(app.getString(R.string.pdf_discard_title)) }
        click(find("the Discard button") { it.says(app.getString(R.string.discard)) })
        find("the shared image in the editor") { it.says(app.getString(R.string.convert)) }
        waitUntil("the PDF to be discarded") { onMain { pdfVm.result.value == null && pdfVm.inputs.value.isEmpty() } }
        onMain { vm.clearAll() }
    }

    private fun texts() = nodes().mapNotNull { it.text?.toString() }.toSet()

    /** The texts on screen once they stop changing, as they do when scrolling has finished. */
    private fun settledTexts(): Set<String> {
        var last = texts()
        waitUntil("the screen to settle") {
            Thread.sleep(300)
            val now = texts()
            (now == last).also { last = now }
        }
        return last
    }

    /** A long text keeps the reader's place when the activity is recreated, as it is on rotation. */
    @Test fun legalTextKeepsItsPlaceWhenRecreated() {
        val first = launch()
        click(find("About on Home") { it.says(app.getString(R.string.about)) })
        click(find("the licenses entry") { it.says(app.getString(R.string.about_licenses)) })
        val title = "Third-party notices" // the heading of THIRD_PARTY_NOTICES.md
        find("the notices") { it.says(title) }
        waitUntil("About to slide away") { nodes().none { it.says(app.getString(R.string.about_privacy_policy)) } }
        val atTop = settledTexts()
        // Each scroll moves by about a screen, animated.
        val forward = AccessibilityAction.ACTION_SCROLL_FORWARD
        repeat(3) {
            assertTrue(find("the scrolling notices") { forward in it.actionList }.performAction(forward.id))
            settledTexts()
        }
        val shown = settledTexts()
        assertTrue("The notices didn't scroll", title !in shown)
        val below = shown - atTop

        onMain { first.recreate() }
        waitUntil("the activity to be recreated") {
            val resumed = onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED) }
            (resumed.singleOrNull() as? MainActivity)?.takeIf { it !== first }?.also { activity = it } != null
        }
        find("the text that was showing") { it.text?.toString() in below }
        assertTrue("The notices went back to the top", texts().none { it == title })
    }
}
