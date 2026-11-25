package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.Edition
import io.github.nath_goa.usugami.ui.translationAvailable
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * Translate PDF is the standard edition's only: Usugami Lite and Usugami OCR have no such tool, no
 * text of it, and Home neither lists nor finds it. (The standard edition's Home is
 * TranslateToolDeviceTest's.)
 */
class TranslateAbsentDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private var activity: MainActivity? = null
    private var languageChosen = false

    @After fun tearDown() {
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        // Back to the phone's language, which the other tests' strings are in.
        if (languageChosen && AppLanguages.reset(app)) Thread.sleep(1_000)
    }

    @Test fun onlyTheStandardEditionTranslates() {
        assertEquals(Edition.current == Edition.STANDARD, BuildConfig.HAS_TRANSLATION)
        assertEquals(BuildConfig.HAS_TRANSLATION, translationAvailable)
        // The other editions carry none of its texts.
        @Suppress("DiscouragedApi") // looked up by name: the other editions' R has no such field
        val title = app.resources.getIdentifier("translate_title", "string", app.packageName)
        assertEquals(BuildConfig.HAS_TRANSLATION, title != 0)
    }

    @Test fun homeNeitherListsNorFindsTranslatePdfWithoutTranslating() {
        assumeFalse("The standard edition's Home is TranslateToolDeviceTest's", BuildConfig.HAS_TRANSLATION)
        // In English, the language of the words looked for.
        languageChosen = true
        AppLanguages.skipChoice(app, AppLanguage.ENGLISH)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity = instrumentation.startActivitySync(intent) as MainActivity
        val english = activity!!.resources
        // The search, at the top, finds nothing by the tool's word.
        val field = find("the search field") { it.isEditable }
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "translate") }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
        find("nothing found") { it.text?.toString() == english.getString(R.string.home_search_none, "translate") }

        // A Home opened afresh, read top to bottom as a screen reader scrolls, to the last tool.
        instrumentation.runOnMainSync { activity!!.finish() }
        activity = instrumentation.startActivitySync(intent) as MainActivity
        find("Home") { it.contentDescription?.toString() == english.getString(R.string.settings) }
        val last = english.getString(R.string.tool_pdf_to_images_or_text)
        val seen = HashSet<String>()
        val down = AccessibilityAction.ACTION_SCROLL_DOWN
        for (step in 0 until 12) {
            nodes().mapNotNullTo(seen) { it.text?.toString() }
            if (seen.any { it.contains(last) }) break
            nodes().firstOrNull { down in it.actionList }?.performAction(down.id)
            Thread.sleep(500)
        }
        assertTrue("Home reaches $last: $seen", seen.any { it.contains(last) })
        assertTrue("No Translate PDF on Home: $seen", seen.none { it.contains("Translate", ignoreCase = true) })
    }

    /** Every node in the window that has focus. */
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
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            nodes().firstOrNull(match)?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }
}
