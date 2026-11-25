package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.TextTranslator
import io.github.nath_goa.usugami.data.TranslationEngine
import io.github.nath_goa.usugami.data.TranslationTestHook
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.languageName
import io.github.nath_goa.usugami.ui.languageWord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * Translate PDF's list of language packs (standard edition only), with an engine of its own in place
 * of ML Kit's (see TranslationTestHook). English is built into ML Kit: the engine always counts it
 * as on the phone, but it is no pack, so the list leaves it out; a pack deleted leaves the list, and
 * with none left the list says so.
 */
class TranslatePacksDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private var activity: MainActivity? = null
    private val picked = File(app.cacheDir, "output/test-picked")

    /** An engine with French's pack on the phone, and English built in as ML Kit's is. */
    private inner class FakeEngine : TranslationEngine {
        val packs: MutableSet<String> = Collections.synchronizedSet(hashSetOf("fr"))
        override val languages = listOf("de", "en", "fr")
        override suspend fun downloaded(): Set<String> = synchronized(packs) { packs + "en" }
        override suspend fun ensureReady(source: String, target: String, onDownload: (Float?) -> Unit) = Unit
        override fun open(source: String, target: String): TextTranslator = error("Not translating here")
        override suspend fun delete(tag: String) {
            packs -= tag
        }
        override suspend fun detect(text: String): String? = null
    }

    @After fun tearDown() {
        activity?.let { onMain { it.finish() } }
        TranslationTestHook.reset()
        picked.deleteRecursively()
    }

    @Test fun theListLeavesEnglishOutAndDeletesAPack() {
        val engine = FakeEngine()
        TranslationTestHook.engine = { engine }
        TranslationTestHook.detector = { _, _, _, _ -> "fr" }
        val main = launch()
        openWithAlpha(main)
        val locale = main.resources.configuration.locales[0]

        // The row says what it says of the packs too, as one button.
        click(findBelow("the packs row") { it.text?.contains(app.getString(R.string.translate_packs)) == true && ancestorClickable(it) })
        find("the list") { it.says(app.getString(R.string.translate_packs_intro)) }
        val deleteFrench = app.getString(R.string.translate_pack_delete, languageWord("fr", locale))
        find("French's delete button") { it.says(deleteFrench) }
        val english = listOf(languageName("en", locale), app.getString(R.string.translate_pack_delete, languageWord("en", locale)))
        assertTrue("English isn't listed", nodes().none { node -> english.any { node.says(it) } })

        click(find("French's delete button") { it.says(deleteFrench) })
        click(find("the dialog's Delete") { it.says(app.getString(R.string.translate_pack_delete_confirm)) })
        find("the list without packs") { it.says(app.getString(R.string.translate_packs_none)) }
        assertEquals(emptySet<String>(), engine.packs.toSet())
    }

    private fun launch(): MainActivity {
        AppLanguages.skipChoice(app)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
    }

    /** Opens Translate PDF from Home with alpha.pdf (three pages) in it. */
    private fun openWithAlpha(main: MainActivity) {
        find("Home") { it.says(app.getString(R.string.settings)) }
        click(findBelow("Translate PDF on Home") { it.says(app.getString(R.string.translate_title)) })
        waitUntil("Home to slide away") { nodes().none { it.says(app.getString(R.string.settings)) } }
        val vm = onMain { ViewModelProvider(main)[PdfToolsViewModel::class.java] }
        onMain { vm.addPdfs(listOf(pdf("alpha.pdf")), replace = true) }
        waitUntil("the PDF to be added") { onMain { vm.inputs.value.size } == 1 }
    }

    /** A test PDF the way a picker gives it: a content:// URI with a display name. */
    private fun pdf(fixture: String): Uri {
        val file = File(picked, fixture).apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

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

    /** Every node in the window that has focus (a dialog's or a sheet's, while one is open). */
    private fun nodes(): List<AccessibilityNodeInfo> {
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            all += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::walk)
        return all
    }

    private fun find(what: String, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        try {
            waitUntil(what) {
                found = nodes().firstOrNull(match)
                found != null
            }
        } catch (e: IllegalStateException) {
            throw IllegalStateException("${e.message}. On screen: ${nodes().mapNotNull { it.text ?: it.contentDescription }}", e)
        }
        return found!!
    }

    /** Like [find], for what may be further down: Compose leaves what is off the screen out of the tree, so this scrolls down. */
    private fun findBelow(what: String, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val down = AccessibilityAction.ACTION_SCROLL_DOWN
        repeat(12) {
            nodes().firstOrNull(match)?.let { return it }
            nodes().firstOrNull { down in it.actionList }?.performAction(down.id)
            Thread.sleep(500)
        }
        return find(what, match)
    }

    private fun AccessibilityNodeInfo.says(text: String) = this.text?.toString() == text || contentDescription?.toString() == text

    private fun ancestorClickable(node: AccessibilityNodeInfo): Boolean = generateSequence(node) { it.parent }.any { it.isClickable }

    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        checkNotNull(generateSequence(node) { it.parent }.firstOrNull { it.isClickable }) { "Nothing to click at $node" }

    private fun click(node: AccessibilityNodeInfo) = assertTrue(clickable(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
}
