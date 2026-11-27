package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.ToolSearch
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.PageNotDrawn
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.TextTranslator
import io.github.nath_goa.usugami.data.TranslateOutcome
import io.github.nath_goa.usugami.data.TranslateProgress
import io.github.nath_goa.usugami.data.TranslationEngine
import io.github.nath_goa.usugami.data.TranslationProblem
import io.github.nath_goa.usugami.data.TranslationTestHook
import io.github.nath_goa.usugami.data.TranslationUnavailableException
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.defaultTarget
import io.github.nath_goa.usugami.ui.languageName
import io.github.nath_goa.usugami.ui.namesListed
import io.github.nath_goa.usugami.ui.runTranslate
import io.github.nath_goa.usugami.ui.translationTag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.Locale

/**
 * Translate PDF's screen (standard edition only), with an engine of its own in place of ML Kit's
 * (see TranslationTestHook): no network, and translating itself is PdfTranslationDeviceTest's. Home
 * lists the tool and its search finds it in every language; the languages start as detected and as
 * the app's, and the same language twice turns the button off; packs missing from the phone are
 * asked about before a run, which ends at the result with its notes; a pack that can't download is
 * said in a dialog.
 */
class TranslateToolDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private var activity: MainActivity? = null
    private val picked = File(app.cacheDir, "output/test-picked")

    /** The languages the fake engine has: every language of the app, so To can start as the app's. */
    private val languages = listOf("de", "en", "es", "fr", "hi", "id", "pt")

    /** What the fake runner was asked to do: the pages, From and To. */
    private val runs = Collections.synchronizedList(ArrayList<Triple<List<Int>, String, String>>())

    /**
     * A translation engine with [languages], whose packs on the phone are [packs]: English is built
     * in, as in ML Kit (always listed as on the phone, never downloaded); making a pair ready
     * downloads the other packs missing (it says how far, size unknown, then half done), and it
     * translates a text into its letters backwards between marks.
     */
    private inner class FakeEngine(onPhone: Set<String>) : TranslationEngine {
        val packs: MutableSet<String> = Collections.synchronizedSet(HashSet(onPhone))
        override val languages: List<String> get() = this@TranslateToolDeviceTest.languages
        override suspend fun downloaded(): Set<String> = synchronized(packs) { packs + "en" }
        override suspend fun ensureReady(source: String, target: String, onDownload: (Float?) -> Unit) {
            for (tag in listOf(source, target) - "en") {
                if (tag in packs) continue
                onDownload(null)
                onDownload(0.5f)
                packs += tag
            }
        }
        override fun open(source: String, target: String): TextTranslator = object : TextTranslator {
            override suspend fun translate(text: String): String = "«" + text.reversed() + "»"
            override fun close() = Unit
        }
        override suspend fun delete(tag: String) {
            packs -= tag
        }
        override suspend fun detect(text: String): String? = null
    }

    @Before fun setUp() {
        // The PDF is detected as French, whatever it says.
        TranslationTestHook.detector = { _, _, _, _ -> "fr" }
        // A run makes the packs ready, as the engine's own does, and a one-page PDF; page 2 had no text.
        TranslationTestHook.runner = { engine, _, pages, translation, _, source, target, typedName, check, onProgress ->
            runs += Triple(pages, source, target)
            translation.ensureReady(source, target) { fraction ->
                onProgress(TranslateProgress(TranslateProgress.Stage.DOWNLOAD, ((fraction ?: 0f) * 100).toInt(), if (fraction == null) 0 else 100))
            }
            onProgress(TranslateProgress(TranslateProgress.Stage.PAGE, 0, pages.size))
            check()
            val file = engine.newOutput {
                val made = engine.outputFile(FileNames.sanitize(typedName, "Translated"))
                val document = PdfDocument()
                try {
                    val page = document.startPage(PdfDocument.PageInfo.Builder(300, 200, 1).create())
                    page.canvas.drawText("«" + "olleH".reversed() + "»", 20f, 100f, Paint().apply { textSize = 18f })
                    document.finishPage(page)
                    made.outputStream().use(document::writeTo)
                } finally {
                    document.close()
                }
                PdfFile(made, pageCount = 1, originalBytes = 0)
            }
            TranslateOutcome(file, noText = listOf(1), unread = emptyList(), partly = emptyList())
        }
    }

    @After fun tearDown() {
        activity?.let { onMain { it.finish() } }
        for (vm in models) {
            onMain { vm.reset() }
            // Its cleanup would delete the copies the next test makes.
            runBlocking { vm.awaitFileWork() }
        }
        TranslationTestHook.reset()
        picked.deleteRecursively()
    }

    /** View models of the tests' own, without the screen. */
    private val models = ArrayList<PdfToolsViewModel>()

    private fun newViewModel() = onMain { PdfToolsViewModel(app, SavedStateHandle()) }.also { models += it }

    /** A page that can't be drawn is named, as PDF to images or text names it, so that it can be left out. */
    @Test fun aPageThatCantBeDrawnIsNamed() {
        TranslationTestHook.engine = { FakeEngine(onPhone = languages.toSet()) }
        TranslationTestHook.runner = { _, _, _, _, _, _, _, _, _, _ -> throw PageNotDrawn(2, null) }
        val vm = newViewModel()
        onMain { vm.addPdfs(listOf(pdf("alpha.pdf")), replace = true) }
        waitUntil("the PDF to be added") { onMain { vm.inputs.value.size } == 1 }
        val input = onMain { vm.inputs.value.single() }
        onMain { vm.runTranslate(input, "", "fr", "en", "alpha") }
        val said = runBlocking { withTimeout(30_000) { vm.messages.first() } }
        assertEquals(onMain { vm.strings.getString(R.string.export_page_failed, 3) }, said)
    }

    /** Home lists Translate PDF under Convert from PDF, after PDF to images or text, and its search finds it in every language. */
    @Test fun homeListsTranslatePdfAndItsSearchFindsIt() {
        launch()
        find("Home") { it.says(app.getString(R.string.settings)) }
        val title = app.getString(R.string.translate_title)
        val card = clickable(findBelow("Translate PDF on Home") { it.says(title) })
        assertTrue("It can be opened", card.isEnabled)
        val detail = find("what it does") { it.says(app.getString(R.string.translate_detail)) }
        assertEquals("Its name and what it does are one button", card, clickable(detail))
        // In the tree's order, which is Home's (the test's cache may keep the place a node had while
        // Home scrolled, so places aren't compared).
        val shown = nodes().mapNotNull { it.text?.toString() }
        val section = shown.indexOf(app.getString(R.string.home_section_from_pdf))
        val images = shown.indexOf(app.getString(R.string.tool_pdf_to_images_or_text))
        assertTrue("Under Convert from PDF, after PDF to images or text: $shown", shown.indexOf(title) > maxOf(section, images) && minOf(section, images) >= 0)

        // The search, at the top of a Home opened afresh, finds it.
        onMain { activity!!.finish() }
        launch()
        type(find("the search field") { it.isEditable }, "translation")
        find("Translate PDF found") { it.says(title) && ancestorClickable(it) }

        // Each language finds it by its own words for translating.
        val words = mapOf(
            AppLanguage.ENGLISH to "translate",
            AppLanguage.FRENCH to "traduction",
            AppLanguage.SPANISH to "traducir",
            AppLanguage.HINDI to "अनुवाद",
            AppLanguage.GERMAN to "Übersetzung",
            AppLanguage.PORTUGUESE to "tradução",
            AppLanguage.INDONESIAN to "terjemahan",
        )
        for ((language, word) in words) {
            val res = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.tag)) }).resources
            val text = listOf(R.string.translate_title, R.string.translate_detail, R.string.translate_keywords).joinToString(" ") { res.getString(it) }
            assertTrue("$language: \"$word\" in $text", ToolSearch.matches(word, text))
        }
    }

    /**
     * From starts as the language detected, To as the app's; To set to the same turns the button off
     * and says so. The packs missing are named before the run, which asks to download them, shows
     * the result with its notes, and leaves the packs on the phone.
     */
    @Test fun aRunAsksToDownloadMissingPacksAndEndsAtTheResultWithItsNotes() {
        val engine = FakeEngine(onPhone = emptySet())
        TranslationTestHook.engine = { engine }
        val main = launch()
        val vm = openWithAlpha(main)
        val locale = main.resources.configuration.locales[0]
        val target = defaultTarget(AppLanguage.shownIn(main).translationTag(), "fr")
        fun chip(label: Int, tag: String) = app.getString(R.string.option_value, app.getString(label), languageName(tag, locale))

        find("From, detected") { it.says(chip(R.string.translate_from, "fr")) }
        find("the detected hint") { it.says(app.getString(R.string.translate_detected)) }
        find("To, the app's language") { it.says(chip(R.string.translate_to, target)) }
        // English is built in, never a pack to download, nor named as one.
        val missing = listOf("fr", target) - "en"
        val needs = app.resources.getQuantityString(R.plurals.translate_packs_needed, missing.size, namesListed(missing, locale))
        find("the packs needed") { it.text?.contains(needs) == true }

        // The same language twice: the button is off, and a line says why.
        click(find("the To chip") { it.says(chip(R.string.translate_to, target)) })
        click(find("French in the menu") { it.says(languageName("fr", locale)) })
        find("the same-language line") { it.says(app.getString(R.string.translate_same)) }
        waitUntil("the button to be off") { !actionButton().isEnabled }
        click(find("the To chip, French") { it.says(chip(R.string.translate_to, "fr")) })
        click(find("${languageName(target, locale)} in the menu") { it.says(languageName(target, locale)) })
        waitUntil("the button to be on") { actionButton().isEnabled }

        click(actionButton())
        find("the download dialog") { it.says(app.resources.getQuantityString(R.plurals.translate_download_title, missing.size)) }
        find("what it downloads") { it.says(app.resources.getQuantityString(R.plurals.translate_download_body, missing.size, namesListed(missing, locale))) }
        click(find("Download and translate") { it.says(app.getString(R.string.translate_download_confirm)) })

        find("the result screen") { it.says(app.getString(R.string.result_heading_one)) }
        findBelow("the note on page 2") { it.text?.contains(app.resources.getQuantityString(R.plurals.translate_note_no_text, 1, 1, "2")) == true }
        assertEquals(listOf(Triple(listOf(0, 1, 2), "fr", target)), runs.toList())
        assertEquals("alpha_translated.pdf", onMain { vm.result.value!!.single().name })
        assertTrue("The packs downloaded stay", engine.packs.containsAll(missing))
    }

    /** A pack that can't download (no internet) is said in a dialog, and there is no result. */
    @Test fun aPackThatCantDownloadIsSaidInADialog() {
        TranslationTestHook.engine = { FakeEngine(onPhone = languages.toSet()) }
        TranslationTestHook.runner = { _, _, _, _, _, _, _, _, _, _ -> throw TranslationUnavailableException(TranslationProblem.NO_INTERNET) }
        val main = launch()
        val vm = openWithAlpha(main)
        find("From, detected") { it.text?.contains(app.getString(R.string.translate_detected)) == true }
        click(actionButton())
        find("the dialog") { it.says(app.getString(R.string.translate_problem_title)) }
        find("why") { it.says(app.getString(R.string.translate_problem_no_internet)) }
        click(find("OK") { it.says(app.getString(R.string.ok)) })
        waitUntil("the dialog to go") { nodes().none { it.says(app.getString(R.string.translate_problem_title)) } }
        assertFalse("No result", onMain { vm.result.value != null })
        assertTrue("Still on the tool", nodes().any { it.text?.contains(app.getString(R.string.translate_packs)) == true })
    }

    private fun launch(): MainActivity {
        AppLanguages.skipChoice(app)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
    }

    /** Opens Translate PDF from Home with alpha.pdf (three pages) in it. */
    private fun openWithAlpha(main: MainActivity): PdfToolsViewModel {
        find("Home") { it.says(app.getString(R.string.settings)) }
        click(findBelow("Translate PDF on Home") { it.says(app.getString(R.string.translate_title)) })
        waitUntil("Home to slide away") { nodes().none { it.says(app.getString(R.string.settings)) } }
        val vm = onMain { ViewModelProvider(main)[PdfToolsViewModel::class.java] }
        onMain { vm.addPdfs(listOf(pdf("alpha.pdf")), replace = true) }
        waitUntil("the PDF to be added") { onMain { vm.inputs.value.size } == 1 }
        return vm
    }

    /** A test PDF the way a picker gives it: a content:// URI with a display name. */
    private fun pdf(fixture: String): Uri {
        val file = File(picked, fixture).apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /** The tool's button, Translate PDF, in the bar at the bottom (not the screen's title, which says the same). */
    private fun actionButton(): AccessibilityNodeInfo =
        clickable(find("the Translate PDF button") { it.says(app.getString(R.string.translate_action)) && ancestorClickable(it) })

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

    /** Every node in the window that has focus (a dialog's or a menu's, while one is open). */
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

    private fun type(field: AccessibilityNodeInfo, text: String) {
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments))
    }
}
