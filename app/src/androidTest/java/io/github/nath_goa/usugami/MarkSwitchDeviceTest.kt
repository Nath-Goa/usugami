package io.github.nath_goa.usugami

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.MarkSetting
import io.github.nath_goa.usugami.data.PageMark
import io.github.nath_goa.usugami.data.RecentFiles
import io.github.nath_goa.usugami.data.SettingsStore
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The result screen's Usugami watermark: a switch, off at first, that puts the mark on the PDF made
 * and takes it off again, and is remembered; the arrow on its row opens the size, which changes the
 * mark on the PDF.
 */
class MarkSwitchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext
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
        RecentFiles.of(app).run { clear(); runBlocking { awaitWrites() } }
        SettingsStore(app).setMark(MarkSetting())
    }

    @After fun tearDown() {
        activity?.let { onMain { it.finish() } }
        File(app.cacheDir, "output/test-picked").deleteRecursively()
        // Later tests start with the mark off, as it is at first.
        SettingsStore(app).setMark(MarkSetting())
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

    private fun find(what: String, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil(what) {
            found = nodes().firstOrNull(match)
            found != null
        }
        return found!!
    }

    /** Like [find], scrolling down as a screen reader does until it shows. */
    private fun findBelow(what: String, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val down = AccessibilityAction.ACTION_SCROLL_DOWN
        repeat(10) {
            nodes().firstOrNull(match)?.let { return it }
            val scroller = nodes().firstOrNull { down in it.actionList } ?: return find(what, match)
            scroller.performAction(down.id)
            Thread.sleep(500)
        }
        return find(what, match)
    }

    private fun AccessibilityNodeInfo.says(text: String) = this.text?.toString() == text || contentDescription?.toString() == text

    private fun ancestor(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !match(target)) target = target.parent
        return target
    }

    private fun click(node: AccessibilityNodeInfo) = assertTrue(ancestor(node) { it.isClickable }!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))

    /** The switch's row, as a screen reader finds it: one item, a switch, saying what it is. */
    private fun markSwitch(): AccessibilityNodeInfo = ancestor(find("the watermark switch") { it.says(app.getString(R.string.mark_title)) }) { it.isCheckable }!!

    // isChecked gives way to getChecked() from Android 16, which older devices don't have.
    @Suppress("DEPRECATION")
    private val AccessibilityNodeInfo.on: Boolean
        get() {
            refresh()
            return isChecked
        }

    /** How many times each page of [file] has the mark: the streams that draw it, which say so. */
    private fun marksOn(file: File): List<Int> = PDDocument.load(file).use { doc ->
        doc.pages.map { page -> page.contentStreams.asSequence().count { it.cosObject.getDictionaryObject(PageMark.MARKER) is COSDictionary } }
    }

    @Test fun switchPutsTheMarkOnAndTakesItOff() {
        AppLanguages.skipChoice(app, null)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val main = (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
        click(findBelow("Reorganize PDF on Home") { it.text?.contains(app.getString(R.string.tool_reorganize)) == true })
        waitUntil("Home to slide away") { nodes().none { it.says(app.getString(R.string.settings)) } }
        val pdfVm = onMain { ViewModelProvider(main)[PdfToolsViewModel::class.java] }
        val picked = File(app.cacheDir, "output/test-picked/alpha.pdf").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("alpha.pdf").use { input -> picked.outputStream().use(input::copyTo) }
        onMain { pdfVm.addPdfs(listOf(FileProvider.getUriForFile(app, "${app.packageName}.files", picked)), replace = true) }
        waitUntil("the PDF to be added") { onMain { pdfVm.inputs.value.size } == 1 }
        click(find("page 2") { it.says(app.getString(R.string.pdf_page_number, 2)) && ancestor(it) { n -> n.isClickable } != null })
        click(find("page 2's right button") { it.says(app.getString(R.string.rotate_page_right, 2)) })
        click(find("the Save button") { it.refresh() && it.says(app.getString(R.string.pdf_reorganize_action)) && ancestor(it) { n -> n.isClickable }?.isEnabled == true })
        find("the result screen") { it.says(app.getString(R.string.result_heading_one)) }
        val file = onMain { pdfVm.result.value!!.single().file }

        // Off at first: the PDF is as the tool made it.
        assertFalse(markSwitch().on)
        assertEquals(listOf(0, 0, 0), marksOn(file))

        click(markSwitch())
        waitUntil("the mark to go on") { markSwitch().on && !onMain { pdfVm.marking.value } && marksOn(file) == listOf(1, 1, 1) }
        // Remembered, for every PDF made from now on.
        assertTrue(SettingsStore(app).mark.value.on)
        assertTrue(app.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("mark_on", false))

        // The arrow on its row opens the size and opacity; a screen reader's step on the size changes
        // the mark on the PDF.
        click(find("the arrow") { it.says(app.getString(R.string.mark_adjust)) })
        val size = find("the size slider") { it.says(app.getString(R.string.mark_size)) && AccessibilityAction.ACTION_SET_PROGRESS in it.actionList }
        val progress = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 60f) }
        assertTrue(size.performAction(AccessibilityAction.ACTION_SET_PROGRESS.id, progress))
        waitUntil("the size to be kept") { SettingsStore(app).mark.value.style.size == 60 }
        waitUntil("the PDF to have it") {
            !onMain { pdfVm.marking.value } && onMain { pdfVm.result.value!!.single().mark?.size } == 60 && marksOn(file) == listOf(1, 1, 1)
        }

        click(markSwitch())
        waitUntil("the mark to come off") { !markSwitch().on && !onMain { pdfVm.marking.value } && marksOn(file) == listOf(0, 0, 0) }
        assertFalse(SettingsStore(app).mark.value.on)
    }
}
