package io.github.nath_goa.usugami

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.Formatter
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.Compressor
import io.github.nath_goa.usugami.core.CropQuad
import io.github.nath_goa.usugami.core.InkItem
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.core.ReadFrom
import io.github.nath_goa.usugami.core.Redaction
import io.github.nath_goa.usugami.core.ShapeItem
import io.github.nath_goa.usugami.core.ShapeKind
import io.github.nath_goa.usugami.core.SharedKind
import io.github.nath_goa.usugami.core.ShortcutTarget
import io.github.nath_goa.usugami.core.SignatureLayout
import io.github.nath_goa.usugami.core.TextItem
import io.github.nath_goa.usugami.core.TextSelection
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.AppShortcuts
import io.github.nath_goa.usugami.data.Appearance
import io.github.nath_goa.usugami.data.Appearances
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfPrinting
import io.github.nath_goa.usugami.data.RecentFiles
import io.github.nath_goa.usugami.data.SignatureInk
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.ui.CropSession
import io.github.nath_goa.usugami.ui.DONATE_URL
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.Motion
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.SplitMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.sin

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

    /**
     * Starts Usugami. Unless [firstRun], it is as if the user had already picked a language: [language],
     * or none, so that the screens are in the phone's language, as [app]'s strings are.
     */
    private fun launch(firstRun: Boolean = false, language: AppLanguage? = null): MainActivity {
        if (language != null) languageChanged = true
        if (firstRun) AppLanguages.reset(app) else AppLanguages.skipChoice(app, language)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
    }

    /** Set by tests that change the app's language, which outlives the activity. */
    private var languageChanged = false

    /**
     * Forgets a language picked by a test, or left by a run stopped part way, so that the screens
     * are in the phone's language, as [app]'s strings are. From Android 13 the system applies that
     * to Usugami shortly after.
     */
    private fun forgetLanguage() {
        if (AppLanguages.reset(app)) Thread.sleep(1_000)
    }

    @Before fun setUp() {
        forgetLanguage()
        // Files saved by the tests before would show in Recent files on Home, and leave it while a test
        // reads Home as each deleted file is noticed, moving everything under it.
        RecentFiles.of(app).run { clear(); runBlocking { awaitWrites() } }
    }

    @After fun tearDown() {
        activity?.let { onMain { it.finish() } }
        File(app.cacheDir, "output/test-picked").deleteRecursively()
        downloadsMade.forEach { app.contentResolver.delete(it, null, null) }
        if (languageChanged) forgetLanguage()
        if (appearanceChanged) {
            Appearances.reset(app)
            Appearances.appliedByUsugami = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        }
    }

    /** The resumed MainActivity once it is a new one, after [old] was recreated. */
    private fun recreatedFrom(old: MainActivity): MainActivity {
        var found: MainActivity? = null
        waitUntil("the activity to be recreated") {
            val resumed = onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED) }
            found = (resumed.singleOrNull() as? MainActivity)?.takeIf { it !== old }
            found != null
        }
        return found!!.also { activity = it }
    }

    /** Usugami's strings in [language], as the translated screens show them. */
    private fun resourcesIn(language: AppLanguage): Resources =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.tag)) })
            .resources

    /** [res] in [language], as the translated screens show it. */
    private fun stringIn(language: AppLanguage, res: Int): String = resourcesIn(language).getString(res)

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
        try {
            waitUntil(what) {
                found = nodes().firstOrNull(match)
                found != null
            }
        } catch (e: IllegalStateException) {
            throw IllegalStateException("${e.message}. On screen: ${texts()}", e)
        }
        return found!!
    }

    /**
     * Like [find], for what may be further down: Compose leaves what is off the screen out of the
     * tree, so this scrolls down, as a screen reader does, until it shows.
     */
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

    /** Like [findBelow], scrolling up. */
    private fun findAbove(what: String, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val up = AccessibilityAction.ACTION_SCROLL_UP
        repeat(10) {
            nodes().firstOrNull(match)?.let { return it }
            val scroller = nodes().firstOrNull { up in it.actionList } ?: return find(what, match)
            scroller.performAction(up.id)
            Thread.sleep(500)
        }
        return find(what, match)
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

    /** A file with [bytes], the way a picker gives it. */
    private fun picked(name: String, bytes: ByteArray): Uri {
        val file = File(app.cacheDir, "output/test-picked/$name").apply { parentFile!!.mkdirs() }
        file.writeBytes(bytes)
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    private fun openTool(title: Int, activity: MainActivity): PdfToolsViewModel {
        // The tools further down Home are scrolled to, as a screen reader would.
        click(findBelow("the tool on the home screen") { it.text?.contains(app.getString(title)) == true })
        // Home leaves the tree all at once as it starts to slide away; until then its cards name the
        // tools as their buttons do. Its settings button is always on screen, unlike a section
        // scrolled out of view.
        waitUntil("Home to slide away") { nodes().none { it.says(app.getString(R.string.settings)) } }
        return onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
    }

    /** Opens the one-PDF tool [title] with alpha.pdf (three pages) in it. */
    private fun openToolWithAlpha(title: Int, activity: MainActivity): PdfToolsViewModel {
        val pdfVm = openTool(title, activity)
        onMain { pdfVm.addPdfs(listOf(pdf("alpha.pdf")), replace = true) }
        waitUntil("the PDF to be added") { onMain { pdfVm.inputs.value.size } == 1 }
        return pdfVm
    }

    /** The nearest of [node] and its parents that [match]es, if any. */
    private fun ancestor(node: AccessibilityNodeInfo, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !match(target)) target = target.parent
        return target
    }

    /** The button labelled [label] (not the screen's title, which may say the same). */
    private fun button(label: Int) = find("the \"${app.getString(label)}\" button") {
        it.says(app.getString(label)) && ancestor(it) { node -> node.isClickable } != null
    }

    /** The tool's main button, [label], once it can be pressed. */
    private fun actionButton(label: Int) = find("the enabled \"${app.getString(label)}\" button") {
        it.says(app.getString(label)) && ancestor(it) { node -> node.isClickable }?.isEnabled == true
    }

    /** The one PDF the tool made, once its result screen shows. */
    private fun resultFile(pdfVm: PdfToolsViewModel): File {
        find("the result screen") { it.says(app.getString(R.string.result_heading_one)) }
        return onMain { pdfVm.result.value!!.single().file }
    }

    /** Each page's characters, with their place on the page as shown. */
    private fun glyphsByPage(file: File): List<List<TextPosition>> = PDDocument.load(file).use { doc ->
        (1..doc.numberOfPages).map { number ->
            ArrayList<TextPosition>().also { glyphs ->
                object : PDFTextStripper() {
                    override fun processTextPosition(text: TextPosition) {
                        glyphs += text
                    }
                }.apply {
                    startPage = number
                    endPage = number
                }.getText(doc)
            }
        }
    }

    /**
     * Whether a radio button is the chosen one, as Usugami says now. The node may come from the
     * accessibility cache, which isn't always told when a choice changes, and would keep saying what
     * it was.
     */
    // isChecked gives way to getChecked() from Android 16, which older devices don't have.
    @Suppress("DEPRECATION")
    private val AccessibilityNodeInfo.chosen: Boolean
        get() {
            refresh()
            return isChecked
        }

    /** A choice shown as a radio button; Compose reports the selected one as checked. */
    private fun radio(label: String): AccessibilityNodeInfo {
        val node = find("the \"$label\" choice") { it.says(label) && ancestor(it) { node -> node.isCheckable } != null }
        return ancestor(node) { it.isCheckable }!!
    }

    @Test fun homeGroupsTheToolsUnderHeadings() {
        launch()
        // Scan document, above them, is one too: its title with what it says, read as one.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val scan = find("Scan document") { it.says(app.getString(R.string.scan_title)) }
            assertNotNull("A screen reader can jump to Scan document", ancestor(scan) { it.isHeading })
        }
        val headings = listOf(
            R.string.home_section_view,
            R.string.home_section_create,
            R.string.home_section_organize,
            R.string.home_section_edit,
            R.string.home_section_optimize,
            R.string.home_section_from_pdf,
        )
        val sections = listOf(
            listOf(
                R.string.tool_reader,
            ),
            listOf(
                R.string.tool_images,
                R.string.tool_documents,
            ),
            listOf(
                R.string.tool_merge,
                R.string.tool_split,
                R.string.tool_reorganize,
                R.string.tool_page_layout,
            ),
            listOf(
                R.string.tool_sign,
                R.string.tool_page_numbers,
                R.string.tool_watermark,
            ),
            listOf(
                R.string.tool_compress,
                R.string.tool_redact,
                R.string.tool_flatten,
                R.string.tool_document_info,
            ),
            listOf(
                R.string.tool_pdf_to_images_or_text,
            ),
        )
        val expected = headings.zip(sections).flatMap { (heading, tools) -> listOf(heading) + tools }.map(app::getString)
        val headingNames = headings.map(app::getString)

        /** What [node] names: a heading, or the tool whose card it is. */
        fun name(node: AccessibilityNodeInfo): String? {
            val text = node.text?.toString() ?: return null
            return if (text in headingNames) text else expected.firstOrNull { it !in headingNames && text.contains(it) }
        }
        // Read top to bottom, as a screen reader scrolls, noting each heading and card as it shows.
        val seen = ArrayList<String>()
        val down = AccessibilityAction.ACTION_SCROLL_DOWN
        repeat(12) {
            // Read once the list has stopped moving: a card read halfway through a scroll can be passed over.
            var before: List<Int>? = null
            waitUntil("Home to stop scrolling") {
                Thread.sleep(150)
                val tops = nodes().map { Rect().also(it::getBoundsInScreen).top }
                (tops == before).also { before = tops }
            }
            val shown = nodes().filter { name(it) != null }.sortedBy { Rect().also(it::getBoundsInScreen).top }
            for (node in shown) {
                val named = name(node)!!
                if (named in seen) continue
                seen += named
                if (named in headingNames) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) assertTrue("A screen reader can jump to $named", node.isHeading)
                } else {
                    assertTrue("$named can be opened", clickable(node).isEnabled)
                }
            }
            nodes().firstOrNull { down in it.actionList }?.performAction(down.id)
        }
        assertEquals("Each tool under its heading, in order", expected, seen)
    }

    /** Every tool's name, as its card on Home shows it. */
    private val toolNames = listOf(
        R.string.tool_reader,
        R.string.tool_images,
        R.string.tool_documents,
        R.string.tool_merge,
        R.string.tool_split,
        R.string.tool_reorganize,
        R.string.tool_page_layout,
        R.string.tool_sign,
        R.string.tool_page_numbers,
        R.string.tool_watermark,
        R.string.tool_compress,
        R.string.tool_redact,
        R.string.tool_flatten,
        R.string.tool_document_info,
        R.string.tool_pdf_to_images_or_text,
    )

    /** The tools whose cards are on screen, top to bottom, named in [resources]' language. */
    private fun shownTools(resources: Resources = app.resources): List<String> {
        val names = toolNames.map(resources::getString)
        return nodes()
            .mapNotNull { node -> node.text?.toString()?.let { text -> names.firstOrNull { text.contains(it) } }?.let { node to it } }
            .sortedBy { (node, _) -> Rect().also(node::getBoundsInScreen).top }
            .map { it.second }
    }

    /** Home's search field. */
    private fun searchField() = find("the search field") { it.isEditable }

    /** Waits until the cards on screen are [tools] (names), in that order. */
    private fun waitForTools(vararg tools: String, resources: Resources = app.resources) {
        try {
            waitUntil("the tools found to be ${tools.toList()}") { shownTools(resources) == tools.toList() }
        } catch (e: IllegalStateException) {
            throw IllegalStateException("${e.message}, not ${shownTools(resources)}", e)
        }
    }

    /**
     * The Back key, once Usugami's window has the focus: a dialog can leave the screen before its
     * window gives the focus back, and a key pressed in between goes to the closed window and is lost.
     */
    private fun backKey() {
        waitUntil("Usugami to have the focus") {
            onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).any { it is MainActivity && it.hasWindowFocus() } }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    private fun resumedMain(): MainActivity? =
        onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() as? MainActivity }

    /**
     * Home's search: it finds tools by name, description and hidden words (any case, accents and word
     * order), shows them without the sections, says when nothing matches, clears with its button,
     * survives the activity being recreated, comes back after a tool opened from it, and Back clears
     * it before leaving Usugami. A screen reader hears how many tools were found.
     */
    @Test fun homeSearchFindsToolsAndBackClearsItFirst() {
        // The words typed are English ones, so Usugami is in English, whatever the phone's language.
        val english = resourcesIn(AppLanguage.ENGLISH)
        val first = launch(language = AppLanguage.ENGLISH)
        val field = searchField()
        // A screen reader says what the field is for: its placeholder is part of it.
        assertNotNull("The field says what it is for", ancestor(find("the placeholder") { it.says(english.getString(R.string.home_search_hint)) }) { it.isEditable })

        // The line over the results is read out by a screen reader each time it changes.
        val one = english.getQuantityString(R.plurals.home_search_results, 1, 1)
        type(field, "Join")
        waitForTools(english.getString(R.string.tool_merge), resources = english)
        val count = find("the count") { it.says(one) }
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, count.liveRegion)
        assertTrue("No section headings while searching", nodes().none { it.says(english.getString(R.string.home_section_organize)) })

        // Every word must begin a word of the tool's texts, in any order, whatever the case and accents
        // (here typed where the keyword "reduce size" has none).
        type(searchField(), "SIZE réduce")
        waitForTools(english.getString(R.string.tool_compress), resources = english)
        // Documents to PDF, by a word for what Excel to PDF takes.
        type(searchField(), "spreadsheet")
        waitForTools(english.getString(R.string.tool_documents), resources = english)
        // Tools with the word in their name come first: Add page numbers before Merge PDF, which is
        // found by its description, whichever other tools have the word in their names.
        type(searchField(), "page")
        val numbers = english.getString(R.string.tool_page_numbers)
        val merge = english.getString(R.string.tool_merge)
        waitUntil("tools named with \"page\" first") {
            shownTools(english).let { it.indexOf(numbers) in 0 until it.indexOf(merge) }
        }
        // Words for turning and removing pages find Reorganize PDF, and only it.
        for (word in listOf("rotate", "delete")) {
            type(searchField(), word)
            waitForTools(english.getString(R.string.tool_reorganize), resources = english)
        }
        // Read and annotate, by what found it and what found Edit PDF, which is its Annotate.
        val reader = english.getString(R.string.tool_reader)
        for (word in listOf("edit", "annotate", "draw", "highlight", "text", "open", "view", "read")) {
            type(searchField(), word)
            waitUntil("\"$word\" to find Read and annotate") { reader in shownTools(english) }
        }

        type(searchField(), "zebra")
        val none = find("the empty state") { it.says(english.getString(R.string.home_search_none, "zebra")) }
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, none.liveRegion)
        waitForTools(resources = english)

        click(find("the clear button") { it.says(english.getString(R.string.home_search_clear)) })
        find("the sections again") { it.says(english.getString(R.string.home_section_create)) }
        assertTrue("The field is empty", searchField().text.isNullOrEmpty())

        // The search survives the activity being recreated, as it is on rotation.
        type(searchField(), "combine")
        waitForTools(english.getString(R.string.tool_merge), resources = english)
        onMain { first.recreate() }
        val activity = recreatedFrom(first)
        find("the search, kept") { it.isEditable && it.text?.toString() == "combine" }
        waitForTools(english.getString(R.string.tool_merge), resources = english)

        // A tool opened from the results goes back to them.
        click(find("Merge PDF") { it.text?.contains(english.getString(R.string.tool_merge)) == true })
        waitUntil("Home to slide away") { nodes().none { it.isEditable && it.text?.toString() == "combine" } }
        backKey()
        find("the search, still there") { it.isEditable && it.text?.toString() == "combine" }
        waitForTools(english.getString(R.string.tool_merge), resources = english)

        // Back clears the search first, staying on Home...
        backKey()
        find("the sections again") { it.says(english.getString(R.string.home_section_create)) }
        assertTrue("The field is empty", searchField().text.isNullOrEmpty())
        assertTrue("Usugami is still showing", resumedMain() === activity)
        // ...then leaves Usugami, as before.
        backKey()
        waitUntil("Usugami to leave") { resumedMain() == null }
    }

    /** In French, the search finds tools by French words, also without their accents. */
    @Test fun homeSearchFindsFrenchKeywords() {
        // From English, so that choosing French recreates the activity even on a phone in French.
        val first = launch(language = AppLanguage.ENGLISH)
        onMain { AppLanguages.choose(first, AppLanguage.FRENCH) }
        val french = recreatedFrom(first).resources
        assertEquals("fr", french.configuration.locales[0].language)

        // "assembler" is one of Merge PDF's hidden words in French.
        type(searchField(), "assembler")
        waitForTools(french.getString(R.string.tool_merge), resources = french)
        find("the count in French") { it.says(french.getQuantityString(R.plurals.home_search_results, 1, 1)) }
        // "réduire" and "taille" are Compress PDF's.
        type(searchField(), "REDUIRE taille")
        waitForTools(french.getString(R.string.tool_compress), resources = french)
        // "supprimer" (delete) is one of Reorganize PDF's French words.
        type(searchField(), "supprimer")
        waitForTools(french.getString(R.string.tool_reorganize), resources = french)
        // Edit PDF's French words find Read and annotate, whose Annotate it is now.
        for (word in listOf("annoter", "modifier")) {
            type(searchField(), word)
            waitUntil("\"$word\" to find Read and annotate") { french.getString(R.string.tool_reader) in shownTools(french) }
        }
        type(searchField(), "zèbre")
        find("the empty state in French") { it.says(french.getString(R.string.home_search_none, "zèbre")) }
    }

    /**
     * Reorganize PDF's page [number] in the grid, a check box named after it (and how it is turned,
     * once it is).
     */
    private fun pageBox(number: Int): AccessibilityNodeInfo {
        val page = app.getString(R.string.pdf_page_number, number)
        val named = find("page $number's check box") {
            it.refresh() && it.contentDescription?.toString().let { said -> said == page || said?.startsWith("$page, ") == true } &&
                ancestor(it) { node -> node.isCheckable } != null
        }
        return ancestor(named) { it.isCheckable }!!
    }

    /** What Reorganize PDF's Selected pages field holds, once it is [text]. */
    private fun pagesFieldReads(text: String) = find("the field reading \"$text\"") {
        it.refresh() && it.isEditable && it.text?.toString().orEmpty().let { now -> now == text || (text.isEmpty() && now == app.getString(R.string.reorganize_pages_field)) }
    }

    /**
     * Reorganize PDF's pages are selected by tapping them, by Select all and by typing their numbers,
     * and the field, the bar and the check boxes stay in step; the bar turns every page selected at
     * once, refuses to remove them all, and removes one; what is typed but can't be read is
     * explained once a page is tapped, and selects nothing.
     */
    @Test fun reorganizeSelectsPagesByTappingAndTypingThenTurnsAndRemovesThem() {
        val activity = launch()
        val pdfVm = openToolWithAlpha(R.string.tool_reorganize, activity)
        assertFalse("Nothing to save until the pages change", clickable(button(R.string.pdf_reorganize_action)).isEnabled)
        find("the bar, with nothing selected") { it.says(app.getString(R.string.reorganize_tap_pages)) }

        // A tap selects a page: the field, the bar and the check box say so.
        val page2 = app.getString(R.string.pdf_page_number, 2)
        assertFalse(pageBox(2).chosen)
        click(pageBox(2))
        pagesFieldReads("2")
        find("the bar, for page 2") { it.refresh() && it.text?.toString() == page2 && !it.isCheckable }
        waitUntil("page 2 to show as selected") { pageBox(2).chosen }

        // Select all selects every page, and becomes Select none.
        click(button(R.string.reorganize_select_all))
        pagesFieldReads("1-3")
        find("the count") { it.refresh() && it.says(app.getString(R.string.reorganize_selected_count, 3, 3)) }
        find("Select none") { it.refresh() && it.says(app.getString(R.string.reorganize_select_none)) }

        // The bar turns every page selected at once.
        click(find("the turn button for the pages selected") { it.says(app.getString(R.string.rotate_selected_left)) })
        waitUntil("every page to turn") { onMain { pdfVm.pages.value.map { it.rotation } } == listOf(270, 270, 270) }

        // But it can't remove them all: a PDF keeps at least one page.
        click(find("the remove button") { it.says(app.resources.getQuantityString(R.plurals.remove_selected_pages, 3, 3)) })
        find("the warning") { it.refresh() && it.says(app.getString(R.string.pdf_need_page)) }
        assertEquals(3, onMain { pdfVm.pages.value.size })

        // Pages that don't exist select nothing, and a page tapped then says why.
        type(pagesFieldReads("1-3"), "9")
        waitUntil("nothing to be selected") { onMain { pdfVm.chosenPages.value }.isEmpty() }
        click(pageBox(1))
        find("the reason") { it.refresh() && it.says(app.getString(R.string.pdf_pages_range, 3)) }
        pagesFieldReads("9")
        assertTrue("The tap selected nothing", onMain { pdfVm.chosenPages.value }.isEmpty())

        // A page typed is selected, and removed with the bar.
        type(pagesFieldReads("9"), "2")
        click(find("the remove button for page 2") { it.refresh() && it.says(app.getString(R.string.remove_page, 2)) })
        waitUntil("page 2 to go") { onMain { pdfVm.pages.value.map { it.page } } == listOf(0, 2) }
        pagesFieldReads("")
        assertTrue("Nothing is selected", onMain { pdfVm.chosenPages.value }.isEmpty())

        click(actionButton(R.string.pdf_reorganize_action))
        val result = resultFile(pdfVm)
        assertEquals("alpha_reorganized.pdf", result.name)
        PDDocument.load(result).use { doc ->
            assertEquals(listOf(270, 270), doc.pages.map { it.rotation })
            // Read from turned pages, the words may come out broken up: only the letters count.
            val text = PDFTextStripper().getText(doc).filterNot(Char::isWhitespace)
            assertTrue(text, text.contains("ALPHAONE") && text.contains("ALPHATHREE"))
            assertFalse(text.contains("ALPHATWO"))
        }
    }

    /** Back from a tool with changes asks first: Keep stays with them, Discard leaves. */
    @Test fun leavingAToolWithChangesAsksFirst() {
        val activity = launch()
        val pdfVm = openToolWithAlpha(R.string.tool_reorganize, activity)
        click(pageBox(2))
        click(find("page 2's right button") { it.says(app.getString(R.string.rotate_page_right, 2)) })
        waitUntil("page 2 to turn") { onMain { pdfVm.pages.value.map { it.rotation } } == listOf(0, 90, 0) }

        backKey()
        find("the question") { it.says(app.getString(R.string.tool_discard_title)) }
        click(find("Keep") { it.says(app.getString(R.string.keep)) })
        waitUntil("the question to go") { nodes().none { it.says(app.getString(R.string.tool_discard_title)) } }
        assertEquals(listOf(0, 90, 0), onMain { pdfVm.pages.value.map { it.rotation } })

        // Once the question has given the focus back (see [backKey]).
        backKey()
        click(find("Discard") { it.says(app.getString(R.string.discard)) })
        find("Home") { it.says(app.getString(R.string.settings)) }
    }

    @Test fun pageNumbersGoWhereChosenAndSkipPages() {
        val first = launch()
        openToolWithAlpha(R.string.tool_page_numbers, first)
        val topLeft = app.getString(R.string.position_top_left)
        assertTrue("Bottom center is the default", radio(app.getString(R.string.position_bottom_center)).chosen)
        assertFalse(radio(topLeft).chosen)
        click(radio(topLeft))
        waitUntil("top left to be chosen") { radio(topLeft).chosen }

        // The choice survives the activity being recreated, as it is on rotation.
        onMain { first.recreate() }
        val activity = recreatedFrom(first)
        waitUntil("top left to be chosen still") { radio(topLeft).chosen }
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }

        // The first text field is the pages to skip; the file name comes last.
        type(find("the pages to skip") { it.isEditable }, "1")
        click(actionButton(R.string.pdf_page_numbers_action))
        val glyphs = glyphsByPage(resultFile(pdfVm))
        // alpha.pdf has no digits near the top of its pages.
        val numbers = glyphs.map { page -> page.filter { it.unicode.single().isDigit() && it.y < 72 } }
        assertTrue("Page 1 was skipped", numbers[0].isEmpty())
        for (page in 2..3) {
            val number = numbers[page - 1].single()
            assertEquals("$page", number.unicode)
            assertTrue("Page $page is numbered at the top left", number.x < 72)
        }
    }

    @Test fun pageNumbersExplainBadPagesToSkip() {
        val activity = launch()
        openToolWithAlpha(R.string.tool_page_numbers, activity)
        type(find("the pages to skip") { it.isEditable }, "1-3")
        click(actionButton(R.string.pdf_page_numbers_action))
        find("the message") { it.says(app.getString(R.string.page_numbers_all_skipped)) }
        type(find("the pages to skip") { it.isEditable }, "7")
        click(actionButton(R.string.pdf_page_numbers_action))
        find("the message") { it.says(app.getString(R.string.pdf_pages_range, 3)) }
    }

    @Test fun watermarkNeedsTextAndNamesItsColors() {
        val activity = launch()
        val pdfVm = openToolWithAlpha(R.string.tool_watermark, activity)
        assertFalse("No watermark without text", clickable(button(R.string.pdf_watermark_action)).isEnabled)
        for (color in listOf(R.string.color_gray, R.string.color_black, R.string.color_red, R.string.color_blue, R.string.color_green)) {
            radio(app.getString(color))
        }
        val red = app.getString(R.string.color_red)
        click(radio(red))
        waitUntil("red to be chosen") { radio(red).chosen }
        click(radio(app.getString(R.string.opacity_opaque)))

        // Text no font on the phone can draw is refused, with a message.
        type(find("the watermark text") { it.isEditable }, "\u0378")
        click(actionButton(R.string.pdf_watermark_action))
        find("the message") { it.says(app.getString(R.string.watermark_no_font)) }

        type(find("the watermark text") { it.isEditable }, "Draft")
        click(actionButton(R.string.pdf_watermark_action))
        val glyphs = glyphsByPage(resultFile(pdfVm))
        for (page in glyphs) assertTrue(page.joinToString("") { it.unicode }.contains("Draft"))
    }

    /** Draws a made-up wave across [pad] with a finger, as the touchscreen would send it. */
    private fun drawOn(pad: AccessibilityNodeInfo) {
        val bounds = Rect().also(pad::getBoundsInScreen)
        val start = SystemClock.uptimeMillis()
        val points = (0..40).map { i -> 0.15f + 0.7f * i / 40 to 0.5f - 0.25f * sin(i * 0.5f) }
        points.forEachIndexed { i, (x, y) ->
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                points.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(start, start + 16L * i, action, bounds.left + x * bounds.width(), bounds.top + y * bounds.height(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
        }
    }

    @Test fun signatureIsDrawnPlacedWithButtonsAndSaved() {
        val first = launch()
        openToolWithAlpha(R.string.tool_sign, first)
        assertFalse("Nothing to sign without a signature", clickable(button(R.string.pdf_sign_action)).isEnabled)
        click(find("the card to draw a signature") { it.says(app.getString(R.string.sign_draw)) })
        val pad = find("the signature pad") { it.says(app.getString(R.string.sign_pad)) }
        assertFalse("Nothing drawn yet", clickable(button(R.string.sign_done)).isEnabled)
        drawOn(pad)
        val blue = app.getString(R.string.color_blue)
        click(radio(blue))
        waitUntil("blue ink to be chosen") { radio(blue).chosen }
        click(actionButton(R.string.sign_done))
        find("the drawn signature") { it.says(app.getString(R.string.sign_change)) }

        // A signature usually goes on the last page, so that is chosen first.
        assertTrue(radio(app.getString(R.string.pdf_page_number, 3)).chosen)
        val page2 = app.getString(R.string.pdf_page_number, 2)
        click(radio(page2))
        waitUntil("page 2 to be chosen") { radio(page2).chosen }
        findBelow("page 2 in the preview") { it.says(app.getString(R.string.sign_preview_description, 2)) }
        // Moved with buttons instead of dragging: five steps left and five up.
        repeat(5) {
            for (label in listOf(R.string.sign_move_left, R.string.sign_move_up)) {
                // Each press moves on from the last, even before the screen has caught up.
                click(findBelow("the move button") { it.says(app.getString(label)) })
            }
        }

        // It all survives the activity being recreated, as it is on rotation: the signature, on
        // page 2 (the preview says so), where the buttons moved it (the PDF below says so).
        onMain { first.recreate() }
        val activity = recreatedFrom(first)
        findBelow("page 2 in the preview, still") { it.says(app.getString(R.string.sign_preview_description, 2)) }
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
        val drawn = checkNotNull(onMain { pdfVm.signature.value })
        assertEquals(SignatureInk.BLUE, drawn.ink)

        /** Checks the signed PDF has the signature on page 2 only, where it starts moved a tenth of the page left and up. */
        fun checkSigned() = PDDocument.load(resultFile(pdfVm)).use { doc ->
            assertEquals(listOf(0, 1, 0), doc.pages.map { imagesOn(it).size })
            val page = doc.getPage(1)
            val width = page.cropBox.width
            val height = page.cropBox.height
            val start = SignatureLayout.default(width, height, drawn.aspect)
            val moved = start.copy(centerX = start.centerX - 0.1f, centerY = start.centerY - 0.1f)
            val area = SignatureLayout.pdfArea(moved, width, height, drawn.aspect)
            val corner = imagesOn(page).single().corner(0f, 0f)
            assertEquals(area.x, corner.x, 1f)
            assertEquals(area.y, corner.y, 1f)
        }
        click(actionButton(R.string.pdf_sign_action))
        checkSigned()

        // Back from the result, the page and the place are still as they were.
        click(find("the back button") { it.says(app.getString(R.string.back_to_editing)) })
        waitUntil("the result to slide away") { nodes().none { it.says(app.getString(R.string.result_heading_one)) } }
        findBelow("page 2 in the preview, after the result") { it.says(app.getString(R.string.sign_preview_description, 2)) }
        click(actionButton(R.string.pdf_sign_action))
        checkSigned()
    }

    @Test fun reorganizeMovesTurnsAddsAndRemovesPagesWithButtons() {
        val first = launch()
        val firstVm = openToolWithAlpha(R.string.tool_reorganize, first)
        assertFalse("Nothing to save until the pages change", clickable(button(R.string.pdf_reorganize_action)).isEnabled)
        find("the page count") { it.says(app.resources.getQuantityString(R.plurals.page_count, 3, 3)) }
        val (a, b, c) = onMain { firstVm.pages.value }

        // Page 3 is selected with a tap, then moved and turned with the buttons, which name it.
        click(findBelow("page 3") { it.says(app.getString(R.string.pdf_page_number, 3)) && ancestor(it) { node -> node.isClickable } != null })
        click(find("the move button") { it.says(app.getString(R.string.move_page_earlier, 3)) })
        waitUntil("page 3 to come second") { onMain { firstVm.pages.value } == listOf(a, c, b) }
        // The bar's buttons are named anew in place: read afresh (see relabelledButton).
        click(find("the turn button, for its new place") { it.refresh() && it.says(app.getString(R.string.rotate_page_right, 2)) })
        waitUntil("page 3 to turn") { onMain { firstVm.pages.value.map { it.rotation } } == listOf(0, 90, 0) }
        assertTrue("Now there is something to save", clickable(actionButton(R.string.pdf_reorganize_action)).isEnabled)

        // A blank page goes in after the selected page; Undo takes it out again.
        click(findBelow("Add pages") { it.says(app.getString(R.string.reorganize_add)) })
        click(find("Blank page") { it.says(app.getString(R.string.reorganize_add_blank)) })
        waitUntil("a blank page third") { onMain { firstVm.pages.value.getOrNull(2)?.isBlank == true } }
        findBelow("the blank page, named for a screen reader") { it.says(app.getString(R.string.reorganize_page_blank, 3)) }
        // Above: looking for the blank page before the screen showed it scrolls the screen down, and
        // that scroll can still be going on. The count's row, with Undo, is read afresh, and looked
        // for above again until Undo is pressed, once.
        val count = app.resources.getQuantityString(R.plurals.page_count, 4, 4)
        findAbove("the new page count") { it.says(count) && it.refresh() && it.says(count) }
        val undo = app.getString(R.string.undo)
        waitUntil("Undo to be pressed") {
            clickable(findAbove("Undo") { it.says(undo) && it.refresh() && it.says(undo) }).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        waitUntil("the blank page to go") { onMain { firstVm.pages.value.none { it.isBlank } } }

        // A screen reader removes page 1 with the action its card offers.
        val remove = app.getString(R.string.remove_page, 1)
        val card = cardWith(remove)
        assertTrue(card.performAction(card.action(remove)!!.id))
        waitUntil("page 1 to go") { onMain { firstVm.pages.value.map { it.page } } == listOf(2, 1) }

        // The pages and the selected one survive the activity being recreated, as on rotation.
        onMain { first.recreate() }
        val activity = recreatedFrom(first)
        find("the selected page's buttons, still") { it.says(app.getString(R.string.rotate_page_right, 1)) }
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
        click(actionButton(R.string.pdf_reorganize_action))
        val result = resultFile(pdfVm)
        assertEquals("alpha_reorganized.pdf", result.name)
        PDDocument.load(result).use { doc ->
            assertEquals(listOf(90, 0), doc.pages.map { it.rotation })
            val text = PDFTextStripper().getText(doc).filterNot(Char::isWhitespace)
            assertTrue(text, text.indexOf("ALPHATHREE") in 0 until text.indexOf("ALPHATWO"))
            assertFalse(text.contains("ALPHAONE"))
        }
    }

    /**
     * Like [findBelow], reading each node afresh: after the options changed the screen in place,
     * the tree as read here may still say what it said before, the screen's scrolling among it
     * (see relabelledButton).
     */
    private fun freshBelow(what: String, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val down = AccessibilityAction.ACTION_SCROLL_DOWN
        repeat(10) {
            nodes().firstOrNull { it.refresh() && match(it) }?.let { return it }
            val scroller = nodes().firstOrNull { it.refresh() && down in it.actionList } ?: return find(what) { it.refresh() && match(it) }
            scroller.performAction(down.id)
            Thread.sleep(500)
        }
        return find(what) { it.refresh() && match(it) }
    }

    /**
     * Page layout's Crop: its button is off until the box crops something; five presses of a step
     * button crop 5% off the left of the page; Some pages starts with the page shown; the box, the
     * choice and the pages typed survive the activity being recreated, as on rotation; and only the
     * page typed gets the narrower crop box.
     */
    @Test fun pageLayoutCropsWithButtonsAndKeepsItsChoicesThroughRecreation() {
        val first = launch()
        openToolWithAlpha(R.string.tool_page_layout, first)
        val cropPdf = app.getString(R.string.pdf_layout_crop_action)
        // The options give the button its words in place, which the tree as read here may not have caught up with.
        find("the \"$cropPdf\" button, off") {
            it.refresh() && it.says(cropPdf) && ancestor(it) { node -> node.isClickable }?.isEnabled == false
        }
        // Each press steps on from the last, even before the screen has caught up.
        val more = app.getString(R.string.layout_crop_more_left)
        repeat(5) { click(freshBelow("the step button") { it.says(more) }) }
        val some = app.getString(R.string.layout_pages_some)
        click(freshBelow("Some pages") { it.says(some) })
        waitUntil("Some pages to be chosen") { radio(some).chosen }
        find("the pages field, with the page shown") { it.refresh() && it.isEditable && it.text?.toString() == "1" }

        onMain { first.recreate() }
        val activity = recreatedFrom(first)
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
        freshBelow("Some pages, still") { it.says(some) }
        assertTrue("Some pages, still chosen", radio(some).chosen)
        find("the pages field, still") { it.refresh() && it.isEditable && it.text?.toString() == "1" }
        click(relabelledButton(R.string.pdf_layout_crop_action))

        val result = resultFile(pdfVm)
        assertEquals("alpha_layout.pdf", result.name)
        PDDocument.load(result).use { doc ->
            val cropped = doc.getPage(0).cropBox
            // 5% of an A4 page's 595 points, after five steps of 1%.
            assertEquals(29.75f, cropped.lowerLeftX, 0.5f)
            assertEquals(595f, cropped.upperRightX, 0.01f)
            assertEquals(0f, cropped.lowerLeftY, 0.01f)
            assertEquals(842f, cropped.upperRightY, 0.01f)
            for (index in 1..2) {
                val page = doc.getPage(index)
                assertNull("Page ${index + 1} isn't cropped", page.cosObject.getDictionaryObject(COSName.CROP_BOX))
                assertEquals(595f, page.cropBox.width, 0.01f)
            }
            assertEquals(listOf(0, 0, 0), doc.pages.map { it.rotation })
        }
    }

    /**
     * Page layout's Pages per sheet: with 4 to a sheet the preview of the first sheet holds alpha.pdf's
     * three pages and says there will be one sheet, and the PDF saved is that one sheet, an A4
     * page upright, with the three pages' text still text.
     */
    @Test fun pagesPerSheetShowsItsFirstSheetAndSaves() {
        val activity = launch()
        val pdfVm = openToolWithAlpha(R.string.tool_page_layout, activity)
        click(radio(app.getString(R.string.layout_mode_sheets)))
        click(freshBelow("4 pages per sheet") { it.says(app.resources.getQuantityString(R.plurals.layout_sheets_count, 4, 4)) })
        val preview = app.resources.getQuantityString(R.plurals.layout_sheets_preview_description, 3, 3)
        freshBelow("the first sheet, with the three pages") { it.says(preview) }
        val oneSheet = app.resources.getQuantityString(R.plurals.layout_sheets_made, 1, 1)
        freshBelow("the number of sheets") { it.says(oneSheet) }
        click(relabelledButton(R.string.pdf_page_layout_action))

        val result = resultFile(pdfVm)
        assertEquals("alpha_layout.pdf", result.name)
        PDDocument.load(result).use { doc ->
            assertEquals(1, doc.numberOfPages)
            val sheet = doc.getPage(0).mediaBox
            assertTrue("An A4 sheet, upright: ${sheet.width} x ${sheet.height}", sheet.width in 594f..596f && sheet.height in 841f..843f)
            val text = PDFTextStripper().getText(doc)
            assertTrue(text, listOf("ALPHA ONE", "ALPHA TWO", "ALPHA THREE").all { it in text })
        }
    }

    /** Page [index] of [file] as shown, a pixel per point, on white. */
    private fun renderPage(file: File, index: Int): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(index).use { page ->
                    Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    /** How many pixels of [bitmap] [match]. */
    private fun count(bitmap: Bitmap, match: (Int) -> Boolean): Int {
        val all = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(all, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return all.count(match)
    }

    /** A tool button of Annotate, by its name, once it can be pressed; the screen scrolls up to it. */
    private fun editTool(label: Int) = findAbove("the \"${app.getString(label)}\" tool") {
        it.says(app.getString(label)) && ancestor(it) { node -> node.isClickable }?.isEnabled == true
    }

    /** Picks [label] from the Shape menu, and waits for the menu to close. */
    private fun addShape(label: Int) {
        click(editTool(R.string.edit_shape))
        click(find("the menu's \"${app.getString(label)}\"") { it.says(app.getString(label)) })
        waitUntil("the menu to close") { nodes().any { it.says(app.getString(R.string.pdf_edit_action)) } }
    }

    /** alpha.pdf (three pages) opened in the reader, as a file picked with Read and annotate is, and its first page showing. */
    private fun alphaInTheReader(activity: MainActivity): PdfToolsViewModel {
        val mainVm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        onMain { mainVm.open(pdf("alpha.pdf"), null, ReadFrom.HOME) }
        find("the page showing, of three") { it.says(app.getString(R.string.reader_page_of, 1, 3)) }
        return onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
    }

    @Test fun annotateAddsWithButtonsUndoesAndKeepsItThroughRecreation() {
        val first = launch()
        val firstVm = alphaInTheReader(first)
        // With nothing added, More offers Share (the file as it was opened, which the pages show).
        click(find("More") { it.says(app.getString(R.string.reader_more)) })
        find("Share in More") { it.says(app.getString(R.string.share)) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("the menu to close") { nodes().none { it.says(app.getString(R.string.share)) } }
        click(find("Annotate") { it.says(app.getString(R.string.reader_annotate)) })
        find("what to do first") { it.says(app.getString(R.string.edit_hint_start)) }
        find("Annotate's title") { it.says(app.getString(R.string.reader_annotate)) }
        assertTrue("No Save PDF before anything is added", nodes().none { it.says(app.getString(R.string.pdf_edit_action)) })
        fun items() = onMain { firstVm.editItems.value }

        // Text is typed in a dialog, then put in the middle of the page and chosen; a screen reader hears so.
        click(editTool(R.string.edit_text))
        find("the dialog") { it.says(app.getString(R.string.edit_text_add)) }
        type(find("the text field") { it.isEditable }, "Checked by Ana")
        click(actionButton(R.string.sign_done))
        waitUntil("the dialog to close") { nodes().any { it.says(app.getString(R.string.pdf_edit_action)) } }
        val textName = app.getString(R.string.edit_item_text, "Checked by Ana")
        findBelow("the chosen text") { it.says(app.getString(R.string.edit_selected, textName)) }
        val added = items().single() as TextItem
        // Moved with a button rather than by dragging.
        click(findBelow("the move button") { it.says(app.getString(R.string.edit_move_right)) })
        waitUntil("the text to move right") { (items().single() as TextItem).x > added.x }

        // A filled rectangle, from the Shape menu: the screen says it only covers what is under it.
        addShape(R.string.edit_item_filled_rectangle)
        waitUntil("the rectangle") { items().size == 2 }
        findBelow("the hint about covering") { it.says(app.getString(R.string.edit_cover_hint)) }
        // Undo takes it away, and Redo brings it back.
        click(findAbove("Undo") { it.says(app.getString(R.string.undo)) && clickable(it).isEnabled })
        waitUntil("the rectangle to go") { items().size == 1 }
        click(find("Redo, once it can be pressed") { it.says(app.getString(R.string.redo)) && clickable(it).isEnabled })
        waitUntil("the rectangle to come back") { (items().getOrNull(1) as? ShapeItem)?.filled == true }

        // A screen reader finds what was added on the page by its name, and chooses it.
        // The text is under the rectangle, but it is smaller, so it can still be reached.
        val onPage = findBelow("the text on the page") { it.says(textName) && it.isClickable }
        assertTrue(onPage.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        waitUntil("the text to be chosen") { onMain { firstVm.editSelected.value } == added.id }

        // On page 2: an arrow, and a stroke drawn with the pen.
        val page2 = app.getString(R.string.pdf_page_number, 2)
        click(ancestor(findAbove("page 2") { it.says(page2) && ancestor(it) { node -> node.isCheckable } != null }) { it.isCheckable }!!)
        addShape(R.string.edit_item_arrow)
        waitUntil("the arrow on page 2") { items().any { it is ShapeItem && it.kind == ShapeKind.ARROW && it.page == 1 } }
        click(editTool(R.string.edit_draw))
        find("how to draw") { it.says(app.getString(R.string.edit_hint_draw)) }
        drawOn(findBelow("page 2") { it.says(app.getString(R.string.edit_preview_description, 2)) })
        waitUntil("a stroke on page 2") { items().any { it is InkItem && it.page == 1 && !it.highlighter } }
        click(editTool(R.string.edit_draw))

        // It all survives the activity being recreated, as it is on rotation, page 2 still showing.
        onMain { first.recreate() }
        val activity = recreatedFrom(first)
        findBelow("page 2, still") { it.says(app.getString(R.string.edit_preview_description, 2)) }
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
        assertEquals(4, onMain { pdfVm.editItems.value.size })

        click(actionButton(R.string.pdf_edit_action))
        val result = resultFile(pdfVm)
        assertEquals("alpha_annotated.pdf", result.name)
        PDDocument.load(result).use { doc ->
            val firstPage = PDFTextStripper().apply {
                startPage = 1
                endPage = 1
            }.getText(doc)
            assertTrue(firstPage, firstPage.contains("Checked by Ana"))
        }
        fun red(pixel: Int) = Color.red(pixel) > 160 && Color.green(pixel) < 90 && Color.blue(pixel) < 90
        fun blue(pixel: Int) = Color.blue(pixel) > 120 && Color.red(pixel) < 80
        // alpha.pdf's pages have a red box of their own. Page 2 has the red arrow and the blue
        // stroke too; page 3 shows just what it did.
        val original = File(app.cacheDir, "test-edit-original.pdf").apply { instrumentation.context.assets.open("alpha.pdf").use { input -> outputStream().use(input::copyTo) } }
        val second = renderPage(result, 1)
        // The arrow goes across the middle of the page, well below the page's own red box.
        val middle = Bitmap.createBitmap(second, 0, second.height / 2 - 20, second.width, 40)
        assertTrue("The arrow", count(middle, ::red) > 100)
        assertTrue("The stroke", count(second, ::blue) > 30)
        val third = renderPage(result, 2)
        assertEquals(count(renderPage(original, 2), ::red), count(third, ::red))
        assertEquals(0, count(third, ::blue))
        original.delete()

        // Back from the result, Annotate is as it was left, page 2 showing, everything still there to change.
        click(find("the back button") { it.says(app.getString(R.string.back_to_editing)) })
        waitUntil("the result to slide away") { nodes().none { it.says(app.getString(R.string.result_heading_one)) } }
        findBelow("page 2, as it was left") { it.says(app.getString(R.string.edit_preview_description, 2)) }
        assertEquals(4, onMain { pdfVm.editItems.value.size })
        assertTrue(clickable(actionButton(R.string.pdf_edit_action)).isEnabled)

        // Back to reading: page 2 of the pages, with what was added, and Save PDF still there.
        click(find("Back to reading") { it.says(app.getString(R.string.reader_back_to_reading)) })
        find("page 2 showing") { it.says(app.getString(R.string.reader_page_of, 2, 3)) }
        assertEquals(4, onMain { pdfVm.editItems.value.size })
        assertTrue(clickable(actionButton(R.string.pdf_edit_action)).isEnabled)
        assertTrue("Annotate is still offered", nodes().any { it.says(app.getString(R.string.reader_annotate)) })
        // The file as it was opened hasn't the additions the pages show: More doesn't offer to share,
        // print or hand it over meanwhile. Save PDF's result does, with them.
        click(find("More") { it.says(app.getString(R.string.reader_more)) })
        find("Tools in More") { it.says(app.getString(R.string.reader_tools)) }
        val offered = texts()
        for (hidden in listOf(R.string.share, R.string.print, R.string.reader_open_elsewhere)) {
            assertFalse("No ${app.getString(hidden)} while additions aren't saved", offered.contains(app.getString(hidden)))
        }
    }

    @Test fun textIsSelectedAndCopiedInTheReader() {
        val activity = launch()
        val pdfVm = alphaInTheReader(activity)
        val session = onMain { pdfVm.reader.session.value!! }
        // The first word of the first page, as the reader reads it, and where it is on the page.
        val glyphs = checkNotNull(runBlocking(Dispatchers.Main) { session.glyphs(0) }) { "The first page's text can't be read" }
        val first = glyphs.glyphs.indexOfFirst { it.wordStart && it.text.first().isLetter() }
        val box = glyphs.glyphs[first].box
        val word = checkNotNull(TextSelection.wordAt(glyphs, (box.left + box.right) / 2, (box.top + box.bottom) / 2, reach = 0f))
        val expected = TextSelection.text(glyphs, word)
        // The page, with the screen reader's Copy the text of this page; at the top of the list, so its
        // left and top edges show, and its scale is its width over the page's.
        val page = find("the first page") { it.action(app.getString(R.string.reader_copy_page)) != null && it.says(app.getString(R.string.reader_page_of_spoken, 1, 3)) }
        val bounds = Rect().also(page::getBoundsInScreen)
        val scale = bounds.width() / session.document.sizes[0].first
        val x = bounds.left + (box.left + box.right) / 2 * scale
        val y = bounds.top + (box.top + box.bottom) / 2 * scale
        // Something else on the clipboard first, so that only the copy can put the word there. Read
        // while Usugami is in front: from Android 10 only the app in front may read the clipboard.
        val clipboard = onMain { app.getSystemService(ClipboardManager::class.java) }
        onMain { clipboard.setPrimaryClip(ClipData.newPlainText("test", "Not copied yet")) }

        // A long-press on the word: the bar with Copy shows.
        val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, x, y)
        Thread.sleep(ViewConfiguration.getLongPressTimeout() + 400L)
        touch(down, MotionEvent.ACTION_UP, x, y)
        click(find("Copy") { it.says(app.getString(R.string.reader_copy)) })
        // Copy puts the word on the clipboard, and the bar goes.
        waitUntil("the word on the clipboard") { onMain { clipboard.primaryClip?.getItemAt(0)?.text?.toString() } == expected }
        waitUntil("the bar to go") { nodes().none { it.says(app.getString(R.string.reader_copy)) } }

        // A screen reader's "Copy the text of this page" copies the whole page, with no selecting.
        val pageNow = find("the first page") { it.action(app.getString(R.string.reader_copy_page)) != null && it.says(app.getString(R.string.reader_page_of_spoken, 1, 3)) }
        assertTrue(pageNow.performAction(pageNow.action(app.getString(R.string.reader_copy_page))!!.id))
        waitUntil("the page's text on the clipboard") { onMain { clipboard.primaryClip?.getItemAt(0)?.text?.toString() } == TextSelection.text(glyphs, glyphs.glyphs.indices) }
    }

    @Test fun flattenSaysWhatItDoesAndWhenThereIsNothingToFlatten() {
        val activity = launch()
        val pdfVm = openToolWithAlpha(R.string.tool_flatten, activity)
        find("what flattening does") { it.says(app.getString(R.string.flatten_note)) }
        click(actionButton(R.string.pdf_flatten_action))
        find("the message") { it.says(app.getString(R.string.flatten_nothing)) }
        assertNull(onMain { pdfVm.result.value })

        val form = formPdf(File(app.cacheDir, "test-form.pdf"))
        onMain { pdfVm.addPdfs(listOf(picked("form.pdf", form.readBytes())), replace = true) }
        find("the form's card") { it.says("form.pdf") }
        click(actionButton(R.string.pdf_flatten_action))
        val result = resultFile(pdfVm)
        assertEquals("form_flattened.pdf", result.name)
        PDDocument.load(result).use { doc ->
            assertTrue(doc.documentCatalog.acroForm?.fields.isNullOrEmpty())
            assertTrue(PDFTextStripper().getText(doc).contains("Jane Doe"))
        }
    }

    /** Drags a finger across [node] from ([x0], [y0]) to ([x1], [y1]), as shares of its size. */
    private fun dragOn(node: AccessibilityNodeInfo, x0: Float, y0: Float, x1: Float, y1: Float) {
        val bounds = Rect().also(node::getBoundsInScreen)
        val start = SystemClock.uptimeMillis()
        val steps = 20
        for (i in 0..steps + 1) {
            val t = minOf(i, steps).toFloat() / steps
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                steps + 1 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val x = bounds.left + (x0 + (x1 - x0) * t) * bounds.width()
            val y = bounds.top + (y0 + (y1 - y0) * t) * bounds.height()
            val event = MotionEvent.obtain(start, start + 16L * i, action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(automation.injectInputEvent(event, true))
            event.recycle()
        }
    }

    @Test fun redactFindsTextAndBoxesAreAddedMovedDrawnAndSaved() {
        val first = launch()
        val firstVm = openToolWithAlpha(R.string.tool_redact, first)
        assertFalse("Nothing to redact without a box", clickable(button(R.string.pdf_redact_action)).isEnabled)
        find("the note") { it.says(app.getString(R.string.redact_note)) }

        // Find text boxes the phrase on every page (each of alpha.pdf's three has it once).
        type(find("the Find text field") { it.isEditable }, "selectable TEXT")
        click(find("the Find button") { it.says(app.getString(R.string.redact_find_action)) && clickable(it).isEnabled })
        find("the message") { it.says(app.resources.getQuantityString(R.plurals.redact_found, 3, 3)) }
        assertEquals(listOf(0, 1, 2), onMain { firstVm.redactBoxes.value.map { it.page } })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            for (number in 1..3) assertEquals(app.getString(R.string.redact_page_marked), radio(app.getString(R.string.pdf_page_number, number)).stateDescription)
        }
        assertTrue("A box makes the PDF ready", clickable(button(R.string.pdf_redact_action)).isEnabled)

        // A box added with a button is chosen, then moved and widened with buttons.
        click(findBelow("the Add box button") { it.says(app.getString(R.string.redact_add_box)) })
        waitUntil("the box to be added and chosen") { onMain { firstVm.redactBoxes.value.size == 4 && firstVm.redactSelected.value == 3 } }
        val added = onMain { firstVm.redactBoxes.value[3] }
        assertEquals(0, added.page)
        // Quick presses each move a step, even before the screen has caught up.
        repeat(3) { click(findBelow("the move right button") { it.says(app.getString(R.string.redact_move_right)) }) }
        click(findBelow("the wider button") { it.says(app.getString(R.string.redact_wider)) })
        val step = Redaction.STEP
        waitUntil("the box to move and widen") {
            val box = onMain { firstVm.redactBoxes.value[3] }
            kotlin.math.abs(box.left - (added.left + 2.5f * step)) < 1e-4f && kotlin.math.abs(box.width - (added.width + step)) < 1e-4f
        }

        // The preview tells a screen reader how many boxes the page has and which is chosen, and
        // lets it step through them.
        val description = app.resources.getQuantityString(R.plurals.redact_preview_description, 2, 1, 2)
        val preview = findBelow("the preview") { it.says(description) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) assertEquals(app.getString(R.string.redact_box_chosen, 2, 2), preview.stateDescription)
        assertTrue(preview.performAction(preview.action(app.getString(R.string.redact_next_box))!!.id))
        waitUntil("the other box to be chosen") { onMain { firstVm.redactSelected.value } == 0 }

        // A box drawn with a finger.
        dragOn(find("the preview") { it.says(description) }, 0.1f, 0.85f, 0.6f, 0.95f)
        waitUntil("the drawn box") { onMain { firstVm.redactBoxes.value.size } == 5 }
        val drawn = onMain { firstVm.redactBoxes.value[4] }
        assertEquals(0.1f, drawn.left, 0.02f)
        assertEquals(0.95f, drawn.bottom, 0.02f)

        // It all survives the activity being recreated, as it is on rotation.
        onMain { first.recreate() }
        val activity = recreatedFrom(first)
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
        findBelow("the preview, still") { it.says(app.resources.getQuantityString(R.plurals.redact_preview_description, 3, 1, 3)) }
        assertEquals(5, onMain { pdfVm.redactBoxes.value.size })
        assertEquals(4, onMain { pdfVm.redactSelected.value })

        // The chosen box is deleted with its button; "Remove all" asks first.
        click(findBelow("the delete button") { it.says(app.getString(R.string.redact_delete_box)) })
        waitUntil("the box to go") { onMain { pdfVm.redactBoxes.value.size } == 4 }
        click(findBelow("Remove all") { it.says(app.getString(R.string.redact_remove_all)) })
        find("the question") { it.says(app.getString(R.string.redact_remove_all_title)) }
        click(find("Cancel") { it.says(app.getString(R.string.cancel)) })
        waitUntil("the dialog to close") { nodes().none { it.says(app.getString(R.string.redact_remove_all_title)) } }
        assertEquals(4, onMain { pdfVm.redactBoxes.value.size })

        click(actionButton(R.string.pdf_redact_action))
        val result = resultFile(pdfVm)
        assertEquals("alpha_redacted.pdf", result.name)
        PDDocument.load(result).use { doc ->
            assertEquals(3, doc.numberOfPages)
            // Every page had a box, so every page is a picture now.
            assertTrue(PDFTextStripper().getText(doc).isBlank())
        }

        // Back from the result, the boxes are still there.
        click(find("the back button") { it.says(app.getString(R.string.back_to_editing)) })
        waitUntil("the result to slide away") { nodes().none { it.says(app.getString(R.string.result_heading_one)) } }
        assertEquals(4, onMain { pdfVm.redactBoxes.value.size })
        assertTrue(clickable(actionButton(R.string.pdf_redact_action)).isEnabled)
    }

    /**
     * Two pages: "SECRET NAME" on the first, and on the second glyphs of a font that doesn't say
     * which letters they stand for (no ToUnicode map, a character collection of its own).
     */
    private fun unreadPdf(file: File): File = file.also {
        PDDocument().use { doc ->
            writeText(doc, PDPage(PDRectangle.A4).also(doc::addPage), "SECRET NAME")
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            page.cosObject.setItem(COSName.RESOURCES, COSDictionary().apply {
                setItem(COSName.FONT, COSDictionary().apply { setItem("C1", cjkFont("Identity-H", "Identity", 0)) })
            })
            page.setContents(PDStream(doc, "BT /C1 20 Tf 72 700 Td <000100020003> Tj ET\n".byteInputStream(Charsets.ISO_8859_1)))
            doc.save(file)
        }
    }

    /** Under Find text, a notice names the pages it couldn't read, and stays after the message. */
    @Test fun redactSaysWhichPagesFindTextCouldNotRead() {
        val activity = launch()
        val pdfVm = openTool(R.string.tool_redact, activity)
        onMain { pdfVm.addPdfs(listOf(picked("unread.pdf", unreadPdf(File(app.cacheDir, "test-unread.pdf")).readBytes())), replace = true) }
        find("the PDF's card") { it.says("unread.pdf") }
        type(find("the Find text field") { it.isEditable }, "secret")
        click(find("the Find button") { it.says(app.getString(R.string.redact_find_action)) && clickable(it).isEnabled })
        find("the message") { it.says(app.resources.getQuantityString(R.plurals.redact_found, 1, 1)) }
        find("the notice") { it.says(app.resources.getQuantityString(R.plurals.redact_find_unread, 1, "2")) }
        assertEquals(listOf(1), onMain { pdfVm.redactUnread.value.toList() })
    }

    /**
     * Opens the converter for [kind] (titled [title]) from Home, empty, as Documents to PDF does for
     * files of that kind that were all refused: the picker it opens belongs to another app.
     */
    private fun openConverter(kind: SharedKind, title: Int, activity: MainActivity): PdfToolsViewModel {
        find("Home") { it.says(app.getString(R.string.settings)) }
        val mainVm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        onMain { mainVm.openConverter(kind) }
        waitUntil("Home to slide away") { nodes().none { it.says(app.getString(R.string.settings)) } }
        find("the converter's title") { it.says(app.getString(title)) }
        return onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
    }

    /**
     * Home's Documents to PDF: the search finds it by what finds the four converters, and the files
     * picked open the converter of the kind most of them are, with the others named.
     */
    @Test fun documentsToPdfOpensTheConverterForTheFilesPicked() {
        val english = resourcesIn(AppLanguage.ENGLISH)
        val activity = launch(language = AppLanguage.ENGLISH)
        val documents = english.getString(R.string.tool_documents)
        findBelow("Documents to PDF") { it.text?.contains(documents) == true }
        // Words for what Word, PowerPoint, Excel and EPUB to PDF take; "spreadsheet" finds it alone.
        for (words in listOf("docx", "slides", "e-book")) {
            type(searchField(), words)
            waitUntil("\"$words\" to find Documents to PDF") { documents in shownTools(english) }
        }
        type(searchField(), "spreadsheet")
        waitForTools(documents, resources = english)
        click(find("the clear button") { it.says(english.getString(R.string.home_search_clear)) })
        find("the sections again") { it.says(english.getString(R.string.home_section_create)) }

        // As if picked: two presentations and a document.
        val mainVm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        onMain { mainVm.pickDocuments(listOf(pdf("deck.pptx", "Deck.pptx"), pdf("deck.pptx", "Deck 2.pptx"), pdf("report.docx", "Report.docx"))) }
        // The message shows for a few seconds only: looked for first.
        find("the message naming the document left out") { it.says(english.getQuantityString(R.plurals.share_other_kind, 1, 1, "Report.docx")) }
        find("PowerPoint to PDF") { it.says(english.getString(R.string.tool_powerpoint)) }
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
        waitUntil("both presentations to be added") { onMain { pdfVm.documents.value.map { it.name } } == listOf("Deck.pptx", "Deck 2.pptx") }
        find("the second presentation's card") { it.says("Deck 2.pptx") }

        // Back goes Home, as from a converter opened there.
        backKey()
        find("Home") { it.says(english.getString(R.string.settings)) }
        findBelow("Documents to PDF, on Home") { it.text?.contains(documents) == true }
    }

    @Test fun wordToPdfConvertsTheDocumentAndKeepsItThroughRecreation() {
        val first = launch()
        val firstVm = openConverter(SharedKind.WORD, R.string.tool_word, first)
        find("the card to pick a file") { it.says(app.getString(R.string.pick_word)) }
        assertFalse("Nothing to convert yet", clickable(button(R.string.convert)).isEnabled)

        // An old binary .doc is refused, saying what to do.
        onMain { firstVm.addDocuments(listOf(picked("minutes.doc", OLD_BINARY_HEADER + ByteArray(1024))), OfficeKind.WORD) }
        find("the message") { it.says(app.getString(R.string.office_legacy_word)) }
        assertTrue(onMain { firstVm.documents.value.isEmpty() })

        onMain { firstVm.addDocuments(listOf(pdf("report.docx", "Garden report.docx")), OfficeKind.WORD) }
        find("the document's card") { it.says("Garden report.docx") }
        // Its remove button names it, and the PDF is named after it.
        find("the remove button") { it.says(app.getString(R.string.remove_file, "Garden report.docx")) }
        find("the file name") { it.isEditable && it.text?.toString() == "Garden report" }
        find("what converts") { it.says(app.getString(R.string.office_note_word)) }

        // The document survives the activity being recreated, as it is on rotation.
        onMain { first.recreate() }
        val activity = recreatedFrom(first)
        find("the document's card, still") { it.says("Garden report.docx") }
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }

        click(actionButton(R.string.convert))
        val result = resultFile(pdfVm)
        assertEquals("Garden report.pdf", result.name)
        PDDocument.load(result).use { doc ->
            assertEquals(5, doc.numberOfPages)
            assertTrue(PDFTextStripper().getText(doc).contains("Riverside Community Garden"))
        }

        // Back from the result, the document is still there to convert again.
        click(find("the back button") { it.says(app.getString(R.string.back_to_editing)) })
        waitUntil("the result to slide away") { nodes().none { it.says(app.getString(R.string.result_heading_one)) } }
        find("the document's card, after the result") { it.says("Garden report.docx") }
        assertTrue(clickable(actionButton(R.string.convert)).isEnabled)

        // After Start over on the result, the next document's name shows, not the one before's.
        click(actionButton(R.string.convert))
        resultFile(pdfVm)
        // Once the tool's screen has slid away, its choices are put by for Back to editing.
        Thread.sleep(2L * Motion.SCREEN)
        onMain { pdfVm.startOver() }
        find("the card to pick a file, again") { it.says(app.getString(R.string.pick_word)) }
        onMain { pdfVm.addDocuments(listOf(pdf("report.docx", "Seed list.docx")), OfficeKind.WORD) }
        find("the next document's card") { it.says("Seed list.docx") }
        find("its file name") { it.isEditable && it.text?.toString() == "Seed list" }
    }

    @Test fun powerPointToPdfRefusesOtherFilesAndConverts() {
        val activity = launch()
        val pdfVm = openConverter(SharedKind.POWERPOINT, R.string.tool_powerpoint, activity)
        find("the card to pick a file") { it.says(app.getString(R.string.pick_powerpoint)) }

        // A Word document isn't a presentation.
        onMain { pdfVm.addDocuments(listOf(pdf("report.docx")), OfficeKind.POWERPOINT) }
        find("the message") { it.says(app.getString(R.string.office_not_powerpoint)) }
        assertTrue(onMain { pdfVm.documents.value.isEmpty() })

        onMain { pdfVm.addDocuments(listOf(pdf("deck.pptx")), OfficeKind.POWERPOINT) }
        find("the presentation's card") { it.says("deck.pptx") }
        find("what converts") { it.says(app.getString(R.string.office_note_powerpoint)) }
        click(actionButton(R.string.convert))
        PDDocument.load(resultFile(pdfVm)).use { doc ->
            // Seven slides, one of them hidden.
            assertEquals(6, doc.numberOfPages)
        }
    }

    /** A small EPUB 3 book, made up here; with [drm], its text is marked as encrypted. */
    private fun book(drm: Boolean = false): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            fun put(path: String, text: String) {
                zip.putNextEntry(java.util.zip.ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("book.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Tiny Tales</dc:title><dc:identifier id="id">x</dc:identifier></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""")
            put("c1.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>1</title></head><body><h1>The First Tale</h1><p>Once upon a time.</p></body></html>""")
            if (drm) put("META-INF/encryption.xml", """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#"><EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#aes128-cbc"/></EncryptedData></encryption>""")
        }
        return out.toByteArray()
    }

    @Test fun epubToPdfRefusesOtherFilesAndKeepsThePageSizeChosen() {
        val first = launch()
        val firstVm = openConverter(SharedKind.EPUB, R.string.tool_epub, first)
        find("the card to pick a file") { it.says(app.getString(R.string.pick_epub)) }
        assertFalse("Nothing to convert yet", clickable(button(R.string.convert)).isEnabled)

        // A file that isn't a book (pickable when a provider calls it just binary data), and a book with DRM.
        onMain { firstVm.addDocuments(listOf(picked("notes.epub", "Not a book at all".toByteArray())), OfficeKind.EPUB) }
        find("the message") { it.says(app.getString(R.string.epub_not_recognized)) }
        onMain { firstVm.addDocuments(listOf(picked("locked.epub", book(drm = true))), OfficeKind.EPUB) }
        find("the message") { it.says(app.getString(R.string.epub_protected)) }
        assertTrue(onMain { firstVm.documents.value.isEmpty() })

        onMain { firstVm.addDocuments(listOf(picked("Tiny Tales.epub", book())), OfficeKind.EPUB) }
        find("the book's card") { it.says("Tiny Tales.epub") }
        find("the remove button") { it.says(app.getString(R.string.remove_file, "Tiny Tales.epub")) }
        find("the file name") { it.isEditable && it.text?.toString() == "Tiny Tales" }
        find("what converts") { it.says(app.getString(R.string.office_note_epub)) }
        val a5 = app.getString(R.string.size_a5)
        val a4 = app.getString(R.string.size_a4)
        assertTrue("A5 is the default", radio(a5).chosen)
        click(radio(a4))
        waitUntil("A4 to be chosen") { radio(a4).chosen }

        // The book and the page size survive the activity being recreated, as on rotation.
        onMain { first.recreate() }
        val activity = recreatedFrom(first)
        find("the book's card, still") { it.says("Tiny Tales.epub") }
        waitUntil("A4 to be chosen still") { radio(a4).chosen }
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }

        click(actionButton(R.string.convert))
        val result = resultFile(pdfVm)
        assertEquals("Tiny Tales.pdf", result.name)
        PDDocument.load(result).use { doc ->
            val box = doc.getPage(0).mediaBox
            assertEquals(595f, box.width, 1.5f)
            assertEquals(842f, box.height, 1.5f)
            assertTrue(PDFTextStripper().getText(doc).contains("Once upon a time."))
            assertEquals("The First Tale", doc.documentCatalog.documentOutline.firstChild.title)
        }
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

    /**
     * Stands in for the camera app: catches the photo Usugami asks for, "takes" [photo] into the file
     * it was given and returns, or with [noCamera], fails as on a phone without a camera app. Without
     * a [photo], it leaves that file empty, as a camera app that opened it and saved nothing does, and
     * is closed without taking one, or with [saysTaken], says it took one.
     */
    private inner class Camera(
        private val photo: Bitmap?,
        private val noCamera: Boolean = false,
        private val saysTaken: Boolean = false,
    ) : Instrumentation.ActivityMonitor() {
        @Volatile var opened = 0
        @Volatile var taken = 0

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != MediaStore.ACTION_IMAGE_CAPTURE) return null
            if (noCamera) throw ActivityNotFoundException()
            opened++
            val output = IntentCompat.getParcelableExtra(intent, MediaStore.EXTRA_OUTPUT, Uri::class.java)!!
            assertTrue("The camera app may write the file", (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0)
            app.contentResolver.openOutputStream(output)!!.use { photo?.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (photo == null) return Instrumentation.ActivityResult(if (saysTaken) Activity.RESULT_OK else Activity.RESULT_CANCELED, null)
            taken++
            return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        }
    }

    private fun <T> withCamera(camera: Camera, block: () -> T): T {
        instrumentation.addMonitor(camera)
        try {
            return block()
        } finally {
            instrumentation.removeMonitor(camera)
        }
    }

    /**
     * Presses the enabled button [label] once it can be: one on a screen or step still sliding in
     * may not be pressable yet.
     */
    private fun press(label: Int) {
        val text = app.getString(label)
        waitUntil("\"$text\" to be pressed") {
            nodes().firstOrNull { it.says(text) && ancestor(it) { node -> node.isClickable }?.isEnabled == true }
                ?.let { clickable(it).performAction(AccessibilityNodeInfo.ACTION_CLICK) } == true
        }
    }

    /**
     * Opens Images to PDF from Home, and waits for Home to go: its Scan document card has a Take photo
     * button too.
     */
    private fun openImagesToPdf() {
        click(findBelow("Images to PDF on the home screen") { it.text?.contains(app.getString(R.string.tool_images)) == true })
        // Home leaves the tree all at once as it starts to slide away (see openTool).
        waitUntil("Home to slide away") { nodes().none { it.says(app.getString(R.string.settings)) } }
    }

    /** Opens Images to PDF from Home and takes a photo, with a [Camera] standing in; returns its crop screen's session. */
    private fun photoOnTheCropScreen(vm: MainViewModel): CropSession {
        openImagesToPdf()
        click(button(R.string.add_from_camera))
        find("the crop screen, which found the page") { it.says(app.getString(R.string.crop_found)) }
        return checkNotNull(onMain { vm.crop.value })
    }

    /** Where the crop step's handle [name] (a corner or a side) is on the screen. */
    private fun handle(name: Int): Rect {
        val actions = listOf(R.string.edit_move_left, R.string.edit_move_up).map(app::getString)
        return Rect().also(find("the handle") { it.says(app.getString(name)) && actions.any { action -> it.action(action) != null } }::getBoundsInScreen)
    }

    /** Waits for the crop step's image to stay put, once the screen has slid in. */
    private fun awaitCropImageStill() {
        // Named as a pane once it has come in.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val title = app.getString(R.string.crop_title)
            waitUntil("the crop step to come in") { nodes().any { it.paneTitle?.toString() == title } }
        }
        val image = app.getString(R.string.crop_image_description)
        fun bounds() = Rect().also(find("the image") { it.says(image) }::getBoundsInScreen)
        waitUntil("the crop screen to settle") {
            val now = bounds()
            Thread.sleep(300)
            now == bounds()
        }
    }

    /**
     * A finger on the screen at ([x], [y]), down since [downTime]: in real time, a frame apart, as
     * Android holds back touches timed ahead of the frames.
     */
    private fun touch(downTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        assertTrue(automation.injectInputEvent(event, true))
        event.recycle()
        Thread.sleep(16)
    }

    /** The finger down since [downTime] moved from one place to another, then held still a moment, as a finger is before it lets go. */
    private fun moveFinger(downTime: Long, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        for (i in 1..15) touch(downTime, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / 15, fromY + (toY - fromY) * i / 15)
        repeat(5) { touch(downTime, MotionEvent.ACTION_MOVE, toX, toY) }
    }

    /** The filters step's look [label], like a radio button, scrolled to as a screen reader would. */
    private fun look(label: Int): AccessibilityNodeInfo {
        val name = app.getString(label)
        val right = AccessibilityAction.ACTION_SCROLL_RIGHT
        repeat(8) {
            nodes().firstOrNull { it.says(name) && it.isCheckable }?.let { return it }
            nodes().firstOrNull { right in it.actionList }?.performAction(right.id) ?: return radio(name)
            Thread.sleep(300)
        }
        return radio(name)
    }

    /** The filters step's slider [label]. */
    private fun slider(label: Int) = find("the ${app.getString(label)} slider") {
        it.says(app.getString(label)) && AccessibilityAction.ACTION_SET_PROGRESS in it.actionList
    }

    /** Slider [label]'s value, as Usugami says now (see [chosen]). */
    private fun sliderValue(label: Int): Float = slider(label).run {
        refresh()
        rangeInfo.current
    }

    /** Sets slider [label] to [value], as a screen reader does. */
    private fun setSlider(label: Int, value: Float) {
        val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
        assertTrue(slider(label).performAction(AccessibilityAction.ACTION_SET_PROGRESS.id, arguments))
    }

    @Test fun photosAreTakenAndCroppedWithAScreenReadersActions() {
        val first = launch()
        val vm = onMain { ViewModelProvider(first)[MainViewModel::class.java] }
        val camera = Camera(PagePhotos.photo())
        withCamera(camera) {
            val session = photoOnTheCropScreen(vm)
            assertEquals(1, camera.taken)
            assertTrue(session.fromCamera)

            // The corners are items of their own, which a screen reader moves with actions: the
            // top-left corner five steps left.
            val left = app.getString(R.string.edit_move_left)
            repeat(5) {
                val corner = find("the top-left corner") { it.says(app.getString(R.string.crop_corner_top_left)) && it.action(left) != null }
                assertTrue(corner.performAction(corner.action(left)!!.id))
            }
            // Chosen with a tap, the bottom-right corner moves three steps down with the arrow buttons.
            click(find("the bottom-right corner") { it.says(app.getString(R.string.crop_corner_bottom_right)) && it.action(left) != null })
            // The corners and sides are chosen like radio buttons.
            waitUntil("the bottom-right corner to be chosen") {
                nodes().any { it.says(app.getString(R.string.crop_corner_bottom_right)) && it.isCheckable && it.chosen }
            }
            repeat(3) { click(button(R.string.edit_move_down)) }

            // The corners stay where they were moved when the activity is recreated, as on rotation.
            onMain { first.recreate() }
            val activity = recreatedFrom(first)
            find("the crop screen, again") { it.says(app.getString(R.string.crop_found)) }

            // Next goes on to the page's look, where Take another crops this photo and takes the next.
            press(R.string.crop_next)
            find("the filters step") { it.says(app.getString(R.string.filter_title)) }
            press(R.string.crop_take_another)
            waitUntil("the next photo's crop screen") { onMain { vm.crop.value?.let { it.itemId != session.itemId } == true } }
            assertEquals(2, camera.taken)
            // Leaving the crop screen of a photo just taken discards it.
            press(R.string.crop_discard)
            find("the editor") { it.says(app.getString(R.string.convert)) }
            waitUntil("the page to be cropped") { onMain { vm.items.value.size == 1 && vm.items.value.single().original != null } }
            val page = onMain { vm.items.value.single() }
            assertEquals(session.itemId, page.id)

            // Each step is 0.4% of the photo's long side.
            val step = 0.004f * PagePhotos.PHOTO_HEIGHT
            val corners = page.original!!.corners
            assertEquals(session.found[CropQuad.TOP_LEFT].x - 5 * step / PagePhotos.PHOTO_WIDTH, corners[CropQuad.TOP_LEFT].x, 1e-3f)
            assertEquals(session.found[CropQuad.TOP_LEFT].y, corners[CropQuad.TOP_LEFT].y, 1e-3f)
            assertEquals(session.found[CropQuad.BOTTOM_RIGHT].y + 3 * step / PagePhotos.PHOTO_HEIGHT, corners[CropQuad.BOTTOM_RIGHT].y, 1e-3f)

            // The page's Crop button opens it again from the photo, with Back instead of discarding;
            // its second step finishes with Done, without Take another. Back goes back a step at a
            // time, and leaves the page as it was.
            click(find("the page's crop button") { it.says(app.getString(R.string.crop_page, 1)) })
            find("the crop screen for the page") { it.says(app.getString(R.string.crop_title)) }
            find("its back button") { it.says(app.getString(R.string.back)) }
            assertTrue(nodes().none { it.says(app.getString(R.string.crop_discard)) })
            press(R.string.crop_next)
            find("its filters step") { it.says(app.getString(R.string.filter_title)) }
            actionButton(R.string.crop_done)
            assertTrue(nodes().none { it.says(app.getString(R.string.crop_take_another)) })
            backKey()
            find("its crop step, again") { it.says(app.getString(R.string.crop_found)) }
            backKey()
            find("the editor, again") { it.says(app.getString(R.string.convert)) }
            assertEquals(page, onMain { vm.items.value.single() })
            assertNull(onMain { vm.crop.value })

            // Usugami's editor has the page with the photo, and the camera's files have gone.
            assertTrue(File(app.cacheDir, "camera").list().isNullOrEmpty())
            onMain { ViewModelProvider(activity)[MainViewModel::class.java].clearAll() }
        }
        runBlocking { vm.awaitFileWork() }
    }

    /**
     * The filters step: the looks are chosen like radio buttons (a new scan starts Enhanced), and the
     * look, brightness and contrast reach the page, or with Apply to all pages every page. They are
     * kept when the activity is recreated; the next photo starts with the look chosen last and the
     * sliders at 0.
     */
    @Test fun theLookBrightnessAndContrastChosenReachThePageOrEveryPage() {
        val first = launch()
        val vm = onMain { ViewModelProvider(first)[MainViewModel::class.java] }
        val allPages = app.getString(R.string.look_all_pages)
        val camera = Camera(PagePhotos.photo())
        withCamera(camera) {
            val session = photoOnTheCropScreen(vm)
            press(R.string.crop_next)
            find("the filters step") { it.says(app.getString(R.string.filter_title)) }
            // With no other page, there is nothing to apply them to.
            waitUntil("Enhanced to be chosen") { look(R.string.look_enhanced).chosen }
            assertTrue(nodes().none { it.says(allPages) })
            click(look(R.string.look_black_white))
            waitUntil("black and white to be chosen") { look(R.string.look_black_white).chosen && !look(R.string.look_enhanced).chosen }
            setSlider(R.string.filter_brightness, 30f)
            setSlider(R.string.filter_contrast, -20f)

            // Kept when the activity is recreated, as on rotation.
            onMain { first.recreate() }
            val activity = recreatedFrom(first)
            find("the filters step, again") { it.says(app.getString(R.string.filter_title)) }
            waitUntil("black and white, still chosen") { look(R.string.look_black_white).chosen }
            assertEquals(30f, sliderValue(R.string.filter_brightness))
            assertEquals(-20f, sliderValue(R.string.filter_contrast))

            // Take another: the page has them, and the next photo starts in black and white, the
            // sliders at 0, with another page now to apply them to. Grayscale and more contrast, for both.
            press(R.string.crop_take_another)
            waitUntil("the next photo's crop screen") { onMain { vm.crop.value?.let { it.itemId != session.itemId } == true } }
            assertEquals(2, camera.taken)
            val firstPage = onMain { vm.items.value.first() }
            assertEquals(PageLook.BLACK_WHITE, firstPage.look)
            assertEquals(LookAdjustments(brightness = 30, contrast = -20), firstPage.adjustments)
            press(R.string.crop_next)
            find("the second filters step") { it.says(app.getString(R.string.filter_title)) }
            waitUntil("black and white, chosen for the next photo") { look(R.string.look_black_white).chosen }
            assertEquals(0f, sliderValue(R.string.filter_brightness))
            click(look(R.string.look_grayscale))
            setSlider(R.string.filter_contrast, 50f)
            val toggle = checkNotNull(ancestor(find("the \"$allPages\" check box") { it.says(allPages) }) { it.isCheckable })
            click(toggle)
            waitUntil("the box to be ticked") { toggle.chosen }
            press(R.string.crop_done)
            find("the editor") { it.says(app.getString(R.string.convert)) }
            waitUntil("both pages to be cropped") { onMain { vm.cropping.value.isEmpty() && vm.items.value.size == 2 && vm.items.value.all { it.original != null } } }
            assertEquals(listOf(PageLook.GRAYSCALE, PageLook.GRAYSCALE), onMain { vm.items.value.map { it.look } })
            assertEquals(List(2) { LookAdjustments(contrast = 50) }, onMain { vm.items.value.map { it.adjustments } })
            // Each page says its look to a screen reader.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val grayscale = app.getString(R.string.look_grayscale)
                waitUntil("the pages to say their look") { nodes().count { it.stateDescription?.toString() == grayscale } == 2 }
            }
            onMain { ViewModelProvider(activity)[MainViewModel::class.java].clearAll() }
        }
        runBlocking { vm.awaitFileWork() }
    }

    @Test fun aCornerDraggedPastTheOthersStaysUnderTheFingerAndTheImageStill() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        withCamera(Camera(PagePhotos.photo())) {
            val session = photoOnTheCropScreen(vm)
            val image = app.getString(R.string.crop_image_description)
            fun imageBounds() = Rect().also(find("the image") { it.says(image) }::getBoundsInScreen)
            // Measured once the screen has slid in.
            awaitCropImageStill()
            val before = imageBounds()
            val topLeft = handle(R.string.crop_corner_top_left)
            val bottomRight = handle(R.string.crop_corner_bottom_right)
            val x0 = topLeft.exactCenterX()
            val y0 = topLeft.exactCenterY()
            val start = SystemClock.uptimeMillis()

            // The top-left corner dragged across the page, past the others: they make no page, and
            // the hint says so without moving the image.
            val farX = x0 + (bottomRight.exactCenterX() - x0) * 0.8f
            val farY = y0 + (bottomRight.exactCenterY() - y0) * 0.8f
            touch(start, MotionEvent.ACTION_DOWN, x0, y0)
            moveFinger(start, x0, y0, farX, farY)
            find("the hint that the corners make no page") { it.says(app.getString(R.string.crop_not_a_page)) }
            assertEquals(before, imageBounds())
            // Still under the finger, it comes back near where it was, and the corners make a page again.
            moveFinger(start, farX, farY, x0 + 40, y0 + 40)
            touch(start, MotionEvent.ACTION_UP, x0 + 40, y0 + 40)
            find("the hint that the page was found") { it.says(app.getString(R.string.crop_found)) }
            assertEquals(before, imageBounds())

            // The page is cropped with the corner where the finger let go of it.
            press(R.string.crop_next)
            find("the filters step") { it.says(app.getString(R.string.filter_title)) }
            press(R.string.crop_done)
            waitUntil("the page to be cropped") { onMain { vm.cropping.value.isEmpty() && vm.items.value.singleOrNull()?.original != null } }
            val corner = onMain { vm.items.value.single() }.original!!.corners[CropQuad.TOP_LEFT]
            val found = session.found
            // The image's size on the screen, from the corners found.
            val width = (bottomRight.exactCenterX() - x0) / (found[CropQuad.BOTTOM_RIGHT].x - found[CropQuad.TOP_LEFT].x)
            val height = (bottomRight.exactCenterY() - y0) / (found[CropQuad.BOTTOM_RIGHT].y - found[CropQuad.TOP_LEFT].y)
            assertEquals(found[CropQuad.TOP_LEFT].x + 40 / width, corner.x, 0.01f)
            assertEquals(found[CropQuad.TOP_LEFT].y + 40 / height, corner.y, 0.01f)
            onMain { vm.clearAll() }
        }
        runBlocking { vm.awaitFileWork() }
    }

    /** A side dragged moves across itself, its corners going along the sides beside it, the others staying. */
    @Test fun aSideDraggedMovesWithItsCornersAlongTheSidesBeside() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        withCamera(Camera(PagePhotos.photo())) {
            val found = photoOnTheCropScreen(vm).found
            awaitCropImageStill()
            val topLeft = handle(R.string.crop_corner_top_left)
            val bottomRight = handle(R.string.crop_corner_bottom_right)
            val top = handle(R.string.crop_side_top)
            // The image's height on the screen, from the corners found.
            val height = (bottomRight.exactCenterY() - topLeft.exactCenterY()) / (found[CropQuad.BOTTOM_RIGHT].y - found[CropQuad.TOP_LEFT].y)

            // The top side, by the bar in its middle, dragged 60 pixels up.
            val x = top.exactCenterX()
            val y = top.exactCenterY()
            val start = SystemClock.uptimeMillis()
            touch(start, MotionEvent.ACTION_DOWN, x, y)
            moveFinger(start, x, y, x, y - 60)
            touch(start, MotionEvent.ACTION_UP, x, y - 60)
            press(R.string.crop_next)
            find("the filters step") { it.says(app.getString(R.string.filter_title)) }
            press(R.string.crop_done)
            waitUntil("the page to be cropped") { onMain { vm.cropping.value.isEmpty() && vm.items.value.singleOrNull()?.original != null } }
            val corners = onMain { vm.items.value.single() }.original!!.corners
            assertEquals(found[CropQuad.TOP_LEFT].y - 60 / height, corners[CropQuad.TOP_LEFT].y, 0.01f)
            assertEquals(found[CropQuad.TOP_RIGHT].y - 60 / height, corners[CropQuad.TOP_RIGHT].y, 0.01f)
            // Up the left side, which leans right as it rises.
            assertTrue("${corners[CropQuad.TOP_LEFT]}", corners[CropQuad.TOP_LEFT].x > found[CropQuad.TOP_LEFT].x)
            for (i in listOf(CropQuad.BOTTOM_RIGHT, CropQuad.BOTTOM_LEFT)) {
                assertEquals(found[i].x, corners[i].x, 1e-4f)
                assertEquals(found[i].y, corners[i].y, 1e-4f)
            }
            onMain { vm.clearAll() }
        }
        runBlocking { vm.awaitFileWork() }
    }

    /** Rotate right and left on the crop step turn the image and its corners: the page is turned, cropped as it was. */
    @Test fun turningOnTheCropStepTurnsThePageCroppedAsItWas() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        withCamera(Camera(PagePhotos.photo())) {
            val session = photoOnTheCropScreen(vm)
            // Right twice, left once: a quarter turn right. The page, taller than wide, shows wider.
            repeat(2) { click(button(R.string.crop_rotate_right)) }
            click(button(R.string.crop_rotate_left))
            waitUntil("the page to show turned") {
                val topLeft = handle(R.string.crop_corner_top_left)
                val bottomRight = handle(R.string.crop_corner_bottom_right)
                bottomRight.exactCenterX() - topLeft.exactCenterX() > bottomRight.exactCenterY() - topLeft.exactCenterY()
            }
            press(R.string.crop_next)
            find("the filters step") { it.says(app.getString(R.string.filter_title)) }
            press(R.string.crop_done)
            waitUntil("the page to be cropped") { onMain { vm.cropping.value.isEmpty() && vm.items.value.singleOrNull()?.original != null } }
            val page = onMain { vm.items.value.single() }
            assertEquals(90, page.rotation)
            assertTrue("${page.original!!.corners}", page.original!!.corners.isCloseTo(session.found))
            onMain { vm.clearAll() }
        }
        runBlocking { vm.awaitFileWork() }
    }

    /**
     * Scan document's Take photo opens the camera at once, over the editor. A scan from Home that
     * leaves no page goes back to Home, not to an empty editor: the camera closed without a photo, or
     * saying it took one it never saved, and an image picked that can't be read. Once a page is kept,
     * the editor stays, also when that page is removed while it is still being cropped.
     */
    @Test fun aScanFromHomeThatLeavesNoPageGoesBackHome() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        // Read afresh: the tree as read here can still have a screen that has gone.
        fun shows(label: Int) = app.getString(label).let { text -> nodes().any { it.says(text) && it.refresh() && it.says(text) } }
        val onHome = { shows(R.string.scan_title) && !shows(R.string.empty_title) }
        val unreadable = app.resources.getQuantityString(R.plurals.msg_failed_import, 1, 1)

        /**
         * Home, staying, once the step is over: the camera's answer taken in and, with [told], the user
         * told the image can't be read. Right after the tap Home still shows, before the editor has come
         * in and gone. The message is dismissed, as a screen reader can: messages wait their turn, and
         * the photo's crop screen below would have its Next under them.
         */
        fun stillHome(told: Boolean) {
            waitUntil("the camera's answer to be taken in") { onMain { !vm.cameraOpen.value && vm.pendingImports.value == 0 } }
            if (told) {
                val dismiss = AccessibilityAction.ACTION_DISMISS
                // Read at each look: the message moves with the screen showing it, which may be leaving.
                waitUntil("the message to be dismissed") {
                    nodes().firstOrNull { it.says(unreadable) }?.let { message -> ancestor(message) { dismiss in it.actionList } }
                        ?.performAction(dismiss.id) == true
                }
                waitUntil("the message to go") { nodes().none { it.says(unreadable) } }
            }
            var since = SystemClock.uptimeMillis()
            waitUntil("Home to stay, not the editor") {
                if (!onHome()) since = SystemClock.uptimeMillis()
                SystemClock.uptimeMillis() - since >= 1_000
            }
            assertTrue(onMain { vm.items.value.isEmpty() })
        }
        for ((camera, told) in listOf(Camera(null) to false, Camera(null, saysTaken = true) to true)) {
            withCamera(camera) {
                click(button(R.string.add_from_camera))
                waitUntil("the camera to open") { camera.opened == 1 }
                // The camera's file goes once Usugami knows it was closed.
                waitUntil("the camera's file to go") { File(app.cacheDir, "camera").list().isNullOrEmpty() }
            }
            stillHome(told)
        }
        // Only the start of a JPEG.
        val broken = File(app.cacheDir, "test-photos/broken.jpg").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(2_000))
        }
        pickToScan(listOf(Uri.fromFile(broken)))
        stillHome(told = true)

        withCamera(Camera(PagePhotos.photo())) {
            click(button(R.string.add_from_camera))
            // The photo is added and its page looked for before its crop screen comes in.
            find("the crop screen, which found the page") { it.says(app.getString(R.string.crop_found)) }
            press(R.string.crop_next)
            press(R.string.crop_done)
        }
        // Removed as soon as the editor shows it, also while it is still being cropped.
        click(find("the page's Remove button") { it.says(app.getString(R.string.remove_page, 1)) })
        find("the empty editor") { it.says(app.getString(R.string.empty_title)) }
        waitUntil("the page to be dropped") { onMain { vm.items.value.isEmpty() && vm.cropping.value.isEmpty() && vm.pendingImports.value == 0 } }
        Thread.sleep(1_000)
        assertFalse("Still the editor, not Home", onHome())
    }

    /**
     * Waits for the crop screen of an ID card's [side] (its name under the title, as a screen reader
     * hears it with the title, and the hint speaking of the card), running [meanwhile] at each look.
     */
    private fun cardCropScreen(side: String, meanwhile: (List<AccessibilityNodeInfo>) -> Unit = {}) {
        val pane = app.getString(R.string.scan_pane_title, app.getString(R.string.crop_title), side)
        waitUntil("the crop screen of the card's $side") {
            val shown = nodes()
            meanwhile(shown)
            shown.any { it.says(app.getString(R.string.card_crop_found)) } && shown.any { it.says(side) } &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || shown.any { it.paneTitle?.toString() == pane })
        }
    }

    /**
     * Home's ID card button: the front's prompt, Take photo, the front's crop screen (the prompt never
     * says "Back side" before it has come in), Next, Next, the back's prompt, Take photo, the back's
     * crop screen, Next, Done: the editor with one page, the card, whose two images share its number.
     */
    @Test fun anIdCardIsScannedFrontAndBackOntoOnePage() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        val front = app.getString(R.string.card_front)
        val back = app.getString(R.string.card_back)
        click(button(R.string.scan_id_card))
        // Its heading: Home has left the tree by then, with its own Take photo.
        find("the front's prompt") { it.says(front) }
        val camera = Camera(PagePhotos.photo())
        withCamera(camera) {
            press(R.string.add_from_camera)
            cardCropScreen(front) { shown ->
                assertTrue("The front's prompt said \"$back\" before its crop screen came in", shown.none { it.says(back) })
            }
            press(R.string.crop_next)
            find("the front's filters step") { it.says(app.getString(R.string.filter_title)) }
            press(R.string.crop_next)

            find("the back's prompt") { it.says(back) }
            press(R.string.add_from_camera)
            cardCropScreen(back)
            press(R.string.crop_next)
            find("the back's filters step") { it.says(app.getString(R.string.filter_title)) }
            press(R.string.crop_done)
        }
        assertEquals(2, camera.taken)
        find("the editor") { it.says(app.getString(R.string.convert)) }
        find("the card's page") { it.says(app.getString(R.string.crop_page, 1)) }
        assertEquals(1, nodes().count { it.says(app.getString(R.string.card_title)) })
        assertTrue(nodes().none { it.says(app.getString(R.string.crop_page, 2)) })
        val sides = onMain { vm.items.value }
        assertEquals(2, sides.size)
        assertTrue("$sides", sides[0].card != 0L && sides[0].card == sides[1].card)
        assertNull(onMain { vm.card.value })
        waitUntil("the sides to be cropped") { onMain { vm.cropping.value.isEmpty() && vm.pendingImports.value == 0 } }
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /**
     * Cancel on the ID card's front prompt goes back Home, not by way of the empty editor, with
     * nothing added. A card whose back is skipped with No back side is kept with its front alone: its
     * Add back side asks for the back, and Back from there leaves the card as it was.
     */
    @Test fun aCardWithoutABackIsKeptAndCancelFromTheFrontGoesHome() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        val front = app.getString(R.string.card_front)
        val back = app.getString(R.string.card_back)
        click(button(R.string.scan_id_card))
        find("the front's prompt") { it.says(front) }
        click(button(R.string.cancel))
        waitUntil("Home") {
            val shown = nodes()
            assertTrue("The empty editor showed on the way Home", shown.none { it.says(app.getString(R.string.empty_title)) })
            shown.any { it.says(app.getString(R.string.scan_title)) }
        }
        assertTrue(onMain { vm.items.value.isEmpty() && vm.card.value == null })

        click(button(R.string.scan_id_card))
        find("the front's prompt, again") { it.says(front) }
        withCamera(Camera(PagePhotos.photo())) {
            press(R.string.add_from_camera)
            cardCropScreen(front)
            press(R.string.crop_next)
            find("the front's filters step") { it.says(app.getString(R.string.filter_title)) }
            press(R.string.crop_next)
        }
        find("the back's prompt") { it.says(back) }
        press(R.string.card_no_back)
        val add = find("the card's Add back side") { it.says(app.getString(R.string.card_add_back)) }
        val card = onMain { vm.items.value.single() }
        assertNotEquals(0L, card.card)
        assertNull(onMain { vm.card.value })

        click(add)
        find("the back's prompt, again") { it.says(back) }
        backKey()
        find("the editor, again") { it.says(app.getString(R.string.card_add_back)) }
        assertNull(onMain { vm.card.value })
        assertEquals(listOf(card.id to card.card), onMain { vm.items.value.map { it.id to it.card } })
        waitUntil("the front to be cropped") { onMain { vm.cropping.value.isEmpty() && vm.pendingImports.value == 0 } }
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /**
     * A document shared to Usugami while a scan from Home is on the crop screen opens its converter,
     * and Back from there returns to the scan, still from Home: its photo discarded, Home shows again.
     */
    @Test fun aScanFromHomeLeftForASharedDocumentStillGoesBackHome() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        withCamera(Camera(PagePhotos.photo())) {
            click(button(R.string.add_from_camera))
            find("the crop screen") { it.says(app.getString(R.string.crop_found)) }
        }
        val stamp = System.currentTimeMillis()
        val word = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        shareTo(shareIntent(word, inDownloads("Report $stamp.docx", word, asset("report.docx"))))
        find("the shared document in Word to PDF") { it.says("Report $stamp.docx") }
        backKey()
        find("the scan's crop screen, again") { it.says(app.getString(R.string.crop_found)) }
        press(R.string.crop_discard)
        waitUntil("Home") { nodes().any { it.says(app.getString(R.string.scan_title)) } }
        assertTrue(nodes().none { it.says(app.getString(R.string.empty_title)) })
        assertTrue(onMain { vm.items.value.isEmpty() })
    }

    /**
     * A document shared to Usugami while a page is on the crop screen's filters step opens its
     * converter, and Back from there returns to that step with the corner moved and the look chosen,
     * also after the activity was recreated in the converter.
     */
    @Test fun aPageLeftForASharedDocumentKeepsItsCornersAndLook() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        withCamera(Camera(PagePhotos.photo())) {
            click(button(R.string.add_from_camera))
            find("the crop screen") { it.says(app.getString(R.string.crop_found)) }
        }
        val session = checkNotNull(onMain { vm.crop.value })
        val left = app.getString(R.string.edit_move_left)
        repeat(5) {
            val corner = find("the top-left corner") { it.says(app.getString(R.string.crop_corner_top_left)) && it.action(left) != null }
            assertTrue(corner.performAction(corner.action(left)!!.id))
        }
        press(R.string.crop_next)
        find("the filters step") { it.says(app.getString(R.string.filter_title)) }
        waitUntil("Enhanced to be chosen") { look(R.string.look_enhanced).chosen }
        click(look(R.string.look_black_white))
        waitUntil("black and white to be chosen") { look(R.string.look_black_white).chosen }

        val stamp = System.currentTimeMillis()
        val word = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        shareTo(shareIntent(word, inDownloads("Report $stamp.docx", word, asset("report.docx"))))
        find("the shared document in Word to PDF") { it.says("Report $stamp.docx") }
        // Also when the activity is recreated meanwhile, as on rotation.
        onMain { activity.recreate() }
        recreatedFrom(activity)
        find("the shared document, again") { it.says("Report $stamp.docx") }
        backKey()
        find("the filters step, again") { it.says(app.getString(R.string.filter_title)) }
        waitUntil("black and white, still chosen") { look(R.string.look_black_white).chosen }
        press(R.string.crop_done)
        waitUntil("the page to be cropped") { onMain { vm.cropping.value.isEmpty() && vm.items.value.singleOrNull()?.original != null } }
        val page = onMain { vm.items.value.single() }
        assertEquals(PageLook.BLACK_WHITE, page.look)
        // Each step is 0.4% of the photo's long side.
        val step = 0.004f * PagePhotos.PHOTO_HEIGHT / PagePhotos.PHOTO_WIDTH
        assertEquals(session.found[CropQuad.TOP_LEFT].x - 5 * step, page.original!!.corners[CropQuad.TOP_LEFT].x, 1e-3f)
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /** Picks the images at [uris] with Scan document's Upload images, the picker standing in. */
    private fun pickToScan(uris: List<Uri>) {
        val picked = Instrumentation.ActivityResult(
            Activity.RESULT_OK,
            Intent().apply {
                data = uris[0]
                clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            },
        )
        // The photo picker, or the file picker where there is none.
        val pickers = setOf(MediaStore.ACTION_PICK_IMAGES, "androidx.activity.result.contract.action.PICK_IMAGES", Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT)
        val picker = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent) = picked.takeIf { intent.action in pickers }
        }
        instrumentation.addMonitor(picker)
        try {
            click(button(R.string.scan_upload))
            waitUntil("the picker") { picker.hits == 1 }
        } finally {
            instrumentation.removeMonitor(picker)
        }
    }

    /**
     * Scan document's Upload images: the images picked go through both steps one after another, the
     * count under the title (and in the name a screen reader hears), Next until the last, which has
     * Done. Closed part way, the screen leaves the rest as they were added, in the editor.
     */
    @Test fun uploadedImagesAreCroppedOneAfterAnotherAndTheRestKeptWhenClosed() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        pickToScan((1..3).map { Uri.fromFile(PagePhotos.jpeg(PagePhotos.photo(), File(app.cacheDir, "test-photos/scan$it.jpg"))) })

        find("the first image's crop step") { it.says(app.getString(R.string.scan_place, 1, 3)) }
        waitUntil("the images to be added") { onMain { vm.items.value.size == 3 && vm.pendingImports.value == 0 } }
        val ids = onMain { vm.items.value.map { it.id } }
        for (i in 0..1) {
            waitUntil("image ${i + 1}'s crop screen") { onMain { vm.crop.value?.itemId == ids[i] } }
            find("its place") { it.says(app.getString(R.string.scan_place, i + 1, 3)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pane = app.getString(R.string.scan_pane_title, app.getString(R.string.crop_title), app.getString(R.string.scan_place, i + 1, 3))
                waitUntil("its step to be named with its place") { nodes().any { it.paneTitle?.toString() == pane } }
            }
            press(R.string.crop_next)
            find("its filters step") { it.says(app.getString(R.string.filter_title)) }
            press(R.string.crop_next)
        }
        waitUntil("the last image's crop screen") { onMain { vm.crop.value?.itemId == ids[2] } }
        find("its place") { it.says(app.getString(R.string.scan_place, 3, 3)) }
        press(R.string.crop_next)
        find("its filters step") { it.says(app.getString(R.string.filter_title)) }
        actionButton(R.string.crop_done)
        assertTrue("Done, not Next, on the last", nodes().none { it.says(app.getString(R.string.crop_next)) })

        // Back to its crop step, and closed there: the scan stops, and the last image stays as it was.
        backKey()
        find("its crop step, again") { it.says(app.getString(R.string.crop_found)) }
        press(R.string.crop_stop)
        find("the editor") { it.says(app.getString(R.string.convert)) }
        waitUntil("the pages to be cropped") { onMain { vm.cropping.value.isEmpty() && vm.pendingImports.value == 0 } }
        val pages = onMain { vm.items.value }
        assertEquals(ids, pages.map { it.id })
        assertTrue("$pages", pages.take(2).all { it.original != null && it.look == PageLook.ENHANCED })
        assertTrue("$pages", pages[2].original == null && pages[2].look == PageLook.ORIGINAL)
        assertNull(onMain { vm.scan.value })
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    @Test fun aPhoneWithoutACameraAppSaysSo() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        openImagesToPdf()
        withCamera(Camera(null, noCamera = true)) {
            click(button(R.string.add_from_camera))
            find("the message") { it.says(app.getString(R.string.msg_no_camera)) }
        }
        assertTrue(onMain { vm.items.value.isEmpty() })
        runBlocking { vm.awaitFileWork() }
        assertTrue("Nothing is left for a camera", File(app.cacheDir, "camera").list().isNullOrEmpty())
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

        // Recreated, as on rotation, it stays: the images restored with it weren't just shared.
        onMain { activity.recreate() }
        recreatedFrom(activity)
        Thread.sleep(1000)
        find("the result screen, after recreation") { it.says(app.getString(R.string.result_heading_one)) }
        assertFalse(nodes().any { it.says(app.getString(R.string.pdf_discard_title)) })

        // Leaving the tool shows the shared images, rather than leaving them unseen at Home.
        backKey()
        find("the tool") { it.says(app.getString(R.string.tool_compress)) }
        backKey()
        find("the shared images in the editor") { it.says(app.getString(R.string.convert)) }
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

    /**
     * A launcher shortcut used over a PDF made and not saved yet asks first. Keep leaves the PDF as it
     * was; the same shortcut used again, Discard opens its tool, empty. (The second use is what tells
     * that a shortcut taken by the screen doesn't stop the next one reaching it.)
     */
    @Test fun aShortcutOverAnUnsavedPdfAsksFirst() {
        val activity = launch()
        val pdfVm = unsavedCompressResult(activity)

        onMain { activity.startActivity(AppShortcuts.intent(activity, ShortcutTarget.MERGE)) }
        find("the question") { it.says(app.getString(R.string.pdf_discard_title)) }
        find("what it says") { it.says(app.getString(R.string.shortcut_discard_body)) }
        click(find("the Keep button") { it.says(app.getString(R.string.keep)) })
        // Gone before the shortcut is used again: while a question shows, a shortcut does nothing.
        waitUntil("the question to go") { nodes().none { it.says(app.getString(R.string.pdf_discard_title)) } }
        find("the result screen, still showing") { it.says(app.getString(R.string.result_heading_one)) }
        assertTrue("The PDF is kept", onMain { pdfVm.result.value != null })

        onMain { activity.startActivity(AppShortcuts.intent(activity, ShortcutTarget.MERGE)) }
        find("the question, again") { it.says(app.getString(R.string.pdf_discard_title)) }
        click(find("the Discard button") { it.says(app.getString(R.string.discard)) })
        find("Merge PDF") { it.says(app.getString(R.string.tool_merge)) }
        find("its card to add PDFs, empty") { it.says(app.getString(R.string.add_pdfs)) }
        waitUntil("the PDF to be discarded") { onMain { pdfVm.result.value == null && pdfVm.inputs.value.isEmpty() } }
    }

    /** Files put in Downloads by a test, as another app's files to share; deleted after it. */
    private val downloadsMade = ArrayList<Uri>()

    /**
     * A file the way another app shares it: in Downloads, through MediaStore, named [name] with the
     * type [type]. Usugami put it there, so it can read it, as a share lets it; its own files it
     * refuses to read on another app's behalf.
     */
    private fun inDownloads(name: String, type: String, bytes: ByteArray): Uri {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, type)
        }
        val uri = app.contentResolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
        downloadsMade += uri
        app.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }

    private fun asset(name: String): ByteArray = instrumentation.context.assets.open(name).use { it.readBytes() }

    /** A share of [uris], of [type], as another app sends it to Usugami. */
    private fun shareIntent(type: String, vararg uris: Uri): Intent {
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris.toList()))
        }
        return intent.setClass(app, MainActivity::class.java).setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Starts Usugami with [share], as when the user shares files to it from another app. */
    private fun launchSharing(share: Intent): MainActivity {
        AppLanguages.skipChoice(app)
        val intent = share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
    }

    /** Shares to Usugami while it shows. */
    private fun shareTo(share: Intent) = app.startActivity(share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun chooseATool() = find("Choose a tool") { it.says(app.getString(R.string.share_title)) }

    /**
     * A PDF shared to Usugami opens Choose a tool with every PDF tool, and stays through the activity
     * being recreated. The tool chosen opens with the PDF in it, and goes back to Choose a tool, the
     * PDF still there; from there Back goes Home.
     */
    @Test fun aSharedPdfOpensChooseAToolAndTheToolChosenTakesIt() {
        val name = "Shared ${System.currentTimeMillis()}.pdf"
        val first = launchSharing(shareIntent("application/pdf", inDownloads(name, "application/pdf", asset("alpha.pdf"))))
        chooseATool()
        find("the shared PDF") { it.says(name) }
        val pdfVm = onMain { ViewModelProvider(first)[PdfToolsViewModel::class.java] }
        assertEquals(3, onMain { pdfVm.inputs.value.single().pageCount })
        val tools = listOf(
            R.string.tool_reader,
            R.string.tool_merge,
            R.string.tool_split,
            R.string.tool_reorganize,
            R.string.tool_page_layout,
            R.string.tool_sign,
            R.string.tool_page_numbers,
            R.string.tool_watermark,
            R.string.tool_compress,
            R.string.tool_redact,
            R.string.tool_flatten,
            R.string.tool_document_info,
            R.string.tool_pdf_to_images_or_text,
        )
        for (tool in tools) findBelow("${app.getString(tool)} under Choose a tool") { it.says(app.getString(tool)) }
        assertFalse("No converter", texts().contains(app.getString(R.string.tool_word)))

        onMain { first.recreate() }
        recreatedFrom(first)
        chooseATool()
        // Scrolled down before, it still is.
        click(findAbove("Split PDF") { it.says(app.getString(R.string.tool_split)) })
        find("Split PDF's choices") { it.says(app.getString(R.string.pdf_selected)) }
        assertEquals(listOf(name), onMain { pdfVm.inputs.value.map { it.name } })
        backKey()
        chooseATool()
        // Still scrolled to the tool: the PDF above may be out of view, by a little or a lot.
        findAbove("the shared PDF, still there") { it.says(name) }
        backKey()
        find("Home") { it.says(app.getString(R.string.settings)) }
        waitUntil("the PDF to be put away") { onMain { pdfVm.inputs.value.isEmpty() } }
    }

    /** Several PDFs shared go to Merge PDF and the tools that work on several PDFs at once. */
    @Test fun severalSharedPdfsOfferMergeAndTheToolsForSeveral() {
        val stamp = System.currentTimeMillis()
        val pdfs = arrayOf(inDownloads("First $stamp.pdf", "application/pdf", asset("alpha.pdf")), inDownloads("Second $stamp.pdf", "application/pdf", asset("beta.pdf")))
        val activity = launchSharing(shareIntent("application/pdf", *pdfs))
        chooseATool()
        find("the second PDF") { it.says("Second $stamp.pdf") }
        find("the hint") { it.says(app.getString(R.string.share_several_hint)) }
        val others = listOf(
            R.string.tool_reader,
            R.string.tool_split,
            R.string.tool_reorganize,
            R.string.tool_sign,
            R.string.tool_redact,
        ).map(app::getString)
        val forSeveral = listOf(
            R.string.tool_merge,
            R.string.tool_page_numbers,
            R.string.tool_watermark,
            R.string.tool_compress,
            R.string.tool_flatten,
        )
        for (tool in forSeveral) {
            findBelow(app.getString(tool)) { it.says(app.getString(tool)) }
            assertTrue("Only the tools for several", texts().none { it in others })
        }
        click(find("Compress PDF") { it.says(app.getString(R.string.tool_compress)) })
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
        assertEquals(listOf("First $stamp.pdf", "Second $stamp.pdf"), onMain { pdfVm.inputs.value.map { it.name } })
        val compressBoth = app.resources.getQuantityString(R.plurals.pdf_compress_action_many, 2, 2)
        findBelow("the button that compresses both") { it.says(compressBoth) }
    }

    /**
     * A Word document shared opens Word to PDF with it, and a file shared with it that Usugami can't
     * use is named.
     */
    @Test fun aSharedWordFileOpensWordToPdfAndAFileLeftOutIsNamed() {
        val stamp = System.currentTimeMillis()
        val report = inDownloads("Report $stamp.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", asset("report.docx"))
        val notes = inDownloads("Notes $stamp.txt", "text/plain", "notes".toByteArray())
        // Looked for from the start: the message shows for a few seconds, which a slow phone can take
        // to finish starting Usugami.
        AppLanguages.skipChoice(app)
        app.startActivity(shareIntent("*/*", report, notes).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        find("the message naming the file left out") { it.says(app.resources.getQuantityString(R.plurals.share_unsupported, 1, 1, "Notes $stamp.txt")) }
        val activity = checkNotNull(resumedMain()) { "Usugami isn't showing" }.also { this.activity = it }
        find("Word to PDF") { it.says(app.getString(R.string.tool_word)) }
        find("the shared document") { it.says("Report $stamp.docx") }
        actionButton(R.string.convert)
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
        assertEquals(listOf("Report $stamp.docx"), onMain { pdfVm.documents.value.map { it.name } })
    }

    /**
     * A PDF opened from another app ("Open with") shows in the reader, with its name and the page
     * showing. Go to page, from the page showing, shows the page asked for, still after the activity
     * is recreated. Find text, searched with the keyboard's search key, marks the first match from the
     * page showing on and counts the PDF's; Next goes round, and closing the search brings the
     * reader's bar back. More, Tools leaves for Choose a tool with the PDF, Read and annotate first,
     * and Back from there goes Home. Opened again while Usugami shows, it shows again from its first
     * page, and Back leaves Usugami for the app that opened it.
     */
    @Test fun aPdfOpenedFromAnotherAppShowsInTheReaderAndBackLeavesUsugami() {
        val name = "Opened ${System.currentTimeMillis()}.pdf"
        val pdf = inDownloads(name, "application/pdf", asset("alpha.pdf"))
        // As another app asks Usugami to open a file; a new one each time, as the flags added go with it.
        fun openWith() = Intent(Intent.ACTION_VIEW).setDataAndType(pdf, "application/pdf").setClass(app, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val activity = launchSharing(openWith())
        find("the file's name in the bar") { it.says(name) }
        find("the page showing, of three") { it.says(app.getString(R.string.reader_page_of, 1, 3)) }
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
        val first = onMain { pdfVm.inputs.value.single().id }

        // Go to page, from the page showing: Go is on only for a page the PDF has.
        click(find("the page showing, a button") { it.says(app.getString(R.string.reader_page_of, 1, 3)) })
        val field = find("the page number") { it.isEditable }
        val go = { find("the Go button") { it.says(app.getString(R.string.reader_go)) } }
        type(field, "4")
        waitUntil("Go to be off for a page the PDF hasn't") { !clickable(go()).isEnabled }
        type(field, "3")
        waitUntil("Go to be on") { clickable(go()).isEnabled }
        click(go())
        find("page 3 showing") { it.says(app.getString(R.string.reader_page_of, 3, 3)) }
        // It stays there when the activity is recreated, as it is on rotation.
        onMain { activity.recreate() }
        recreatedFrom(activity)
        find("page 3, still showing") { it.says(app.getString(R.string.reader_page_of, 3, 3)) }

        // Find text, from the page showing: a word of page 3 as text extraction reads it. The match marked
        // is the first from page 3 on, numbered among the PDF's (the page indicator says "3 of 3" too: the
        // count is told by what a screen reader hears).
        val pages = PDDocument.load(asset("alpha.pdf")).use { doc ->
            (1..doc.numberOfPages).map { PDFTextStripper().apply { startPage = it; endPage = it }.getText(doc) }
        }
        val word = pages[2].split(Regex("\\s+")).first { it.isNotBlank() }
        val counts = pages.map { Regex(Regex.escape(word), RegexOption.IGNORE_CASE).findAll(it).count() }
        val marked = counts[0] + counts[1] + 1
        click(find("Find text") { it.says(app.getString(R.string.redact_find)) })
        val search = find("the search field") { it.isEditable }
        type(search, word)
        // The keyboard's search key.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertTrue(search.performAction(AccessibilityAction.ACTION_IME_ENTER.id))
        } else {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        }
        find("the match marked, counted") { it.says(app.getString(R.string.reader_find_count_spoken, marked, counts.sum())) }
        click(find("Next match") { it.says(app.getString(R.string.reader_find_next)) })
        find("the next match, round and round") { it.says(app.getString(R.string.reader_find_count_spoken, marked % counts.sum() + 1, counts.sum())) }
        // Closed, the matches go and the reader's bar is back.
        click(find("Close search") { it.says(app.getString(R.string.reader_find_close)) })
        find("the file's name, back in the bar") { it.says(name) }
        assertTrue(onMain { pdfVm.reader.session.value!!.found.value.matches.isEmpty() })

        // More, Tools: Choose a tool with the PDF, Read and annotate first. Back from there goes Home.
        click(find("More") { it.says(app.getString(R.string.reader_more)) })
        click(find("Tools in More") { it.says(app.getString(R.string.reader_tools)) })
        chooseATool()
        find("the PDF on Choose a tool") { it.says(name) }
        waitUntil("Read and annotate to be the first tool") { shownTools().firstOrNull() == app.getString(R.string.tool_reader) }
        assertEquals(listOf(first), onMain { pdfVm.inputs.value.map { it.id } })
        backKey()
        find("Home") { it.says(app.getString(R.string.settings)) }

        // Opened again while Usugami shows: the reader shows it anew.
        shareTo(openWith())
        waitUntil("the file opened again to show") { pdfVm.reader.session.value?.id.let { it != null && it != first } }
        find("the file's name, again") { it.says(name) }
        find("the page showing, again") { it.says(app.getString(R.string.reader_page_of, 1, 3)) }
        // Back returns to the app that opened it: Usugami steps behind it.
        backKey()
        waitUntil("Usugami to leave") { resumedMain() == null }
    }

    /**
     * Home's section View has Read and annotate; a file opened from Home shows in the reader, and Back
     * returns to Home. A result's Open shows its PDF in a preview, with its name and pages, Go to page and
     * Open with another app but no Tools, and Back returns to the result as it was, not saved.
     */
    @Test fun filesOpenInTheReaderFromHomeAndResultsInAPreview() {
        val activity = launch()
        find("the section View") { it.says(app.getString(R.string.home_section_view)) }
        find("Read and annotate") { it.text?.contains(app.getString(R.string.tool_reader)) == true }
        // As the file picked with Read and annotate is opened: the picker itself can't be driven.
        val mainVm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        onMain { mainVm.open(pdf("alpha.pdf"), null, ReadFrom.HOME) }
        find("the page showing, of three") { it.says(app.getString(R.string.reader_page_of, 1, 3)) }
        find("the file's name in the bar") { it.says("alpha.pdf") }
        backKey()
        find("Home") { it.says(app.getString(R.string.settings)) }

        val pdfVm = unsavedCompressResult(activity)
        val made = onMain { pdfVm.result.value }!!
        click(button(R.string.open))
        find("the preview's pages") { it.says(app.getString(R.string.reader_page_of, 1, 3)) }
        find("the result's name in the bar") { it.says(made.single().name) }
        click(find("More") { it.says(app.getString(R.string.reader_more)) })
        find("Go to page in More") { it.says(app.getString(R.string.reader_go_to_page)) }
        find("Open with another app in More") { it.says(app.getString(R.string.reader_open_elsewhere)) }
        assertFalse("No Tools in a preview", texts().contains(app.getString(R.string.reader_tools)))
        // The menu has the focus: Back closes it.
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil("the menu to close") { nodes().none { it.says(app.getString(R.string.reader_open_elsewhere)) } }

        backKey()
        find("the result screen, as it was") { it.says(app.getString(R.string.result_heading_one)) }
        assertSame(made, onMain { pdfVm.result.value })
        assertFalse("Not saved", onMain { pdfVm.saved.value })
        assertNull("The preview is closed", onMain { pdfVm.reader.preview.value })
    }

    /** A PDF shared over a PDF not saved yet asks first; kept, the shared PDF opens once the tool is left. */
    @Test fun filesSharedOverAnUnsavedPdfAskFirstAndWaitIfKept() {
        val activity = launch()
        val pdfVm = unsavedCompressResult(activity)
        val name = "Shared ${System.currentTimeMillis()}.pdf"
        shareTo(shareIntent("application/pdf", inDownloads(name, "application/pdf", asset("beta.pdf"))))
        find("the question") { it.says(app.getString(R.string.share_discard_body)) }
        click(find("the Keep button") { it.says(app.getString(R.string.keep)) })
        waitUntil("the dialog to close") { nodes().none { it.says(app.getString(R.string.share_discard_body)) } }
        find("the result, still showing") { it.says(app.getString(R.string.result_heading_one)) }
        assertTrue("The PDF is kept", onMain { pdfVm.result.value != null })

        backKey()
        find("the tool") { it.says(app.getString(R.string.tool_compress)) }
        assertEquals("The tool keeps its PDF", listOf("alpha.pdf"), onMain { pdfVm.inputs.value.map { it.name } })
        backKey()
        chooseATool()
        find("the shared PDF") { it.says(name) }
        assertEquals(listOf(name), onMain { pdfVm.inputs.value.map { it.name } })
    }

    /** A PDF whose pages are photos of noise, which takes a while to compress, the way a picker gives it. */
    private fun slowPdf(): Uri {
        val file = File(app.cacheDir, "output/test-picked/slow.pdf").apply { parentFile!!.mkdirs() }
        val random = java.util.Random(7)
        PDDocument().use { doc ->
            repeat(16) {
                val bitmap = Bitmap.createBitmap(IntArray(1200 * 1500) { Color.BLACK or random.nextInt(0x1000000) }, 1200, 1500, Bitmap.Config.ARGB_8888)
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { it.drawImage(JPEGFactory.createFromImage(doc, bitmap, 0.95f), 50f, 70f, 495f, 650f) }
                bitmap.recycle()
            }
            doc.save(file)
        }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /**
     * While a tool shows its progress the screen stays on, and it can go off again once the tool is
     * cancelled. The tool's file work is held meanwhile, so its progress shows however fast the phone is.
     */
    @Test fun theScreenStaysOnWhileAToolWorks() {
        val activity = launch()
        val pdfVm = openToolWithAlpha(R.string.tool_compress, activity)
        assertFalse("Not kept on before", keepsScreenOn())
        val held = holdFileWork(pdfVm)
        try {
            onMain { pdfVm.runCompress(PdfCompression.EXTREME, "held") }
            find("the progress") { it.says(app.getString(R.string.pdf_working)) }
            assertTrue("Kept on while it works", keepsScreenOn())
            onMain { pdfVm.cancel() }
        } finally {
            held.countDown()
        }
        waitUntil("the screen to be let go") { !keepsScreenOn() }
    }

    /** Holds [vm]'s file work, done on one thread in turn, until the latch it returns is counted down. */
    private fun holdFileWork(vm: PdfToolsViewModel): CountDownLatch {
        val fileOps = PdfToolsViewModel::class.java.getDeclaredField("fileOps").apply { isAccessible = true }.get(vm) as CoroutineDispatcher
        val held = CountDownLatch(1)
        fileOps.dispatch(EmptyCoroutineContext, Runnable { held.await(30, TimeUnit.SECONDS) })
        return held
    }

    /**
     * Whether Android keeps the screen on for a window of Usugami's: its window manager then holds a wake
     * lock for Usugami, called WindowManager, or WindowManager/displayId:0 on newer Android, one per display.
     */
    private fun keepsScreenOn(): Boolean {
        val dump = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys power")).bufferedReader().use { it.readText() }
        return dump.lines().any { ("'WindowManager'" in it || "'WindowManager/" in it) && "WorkSource{${app.applicationInfo.uid}" in it }
    }

    /** A PDF shared while a tool makes one waits for it; the PDF made isn't saved, so Usugami asks. */
    @Test fun filesSharedWhileAPdfIsMadeWaitForIt() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        val pdfVm = openTool(R.string.tool_compress, activity)
        onMain { pdfVm.addPdfs(listOf(slowPdf()), replace = true) }
        waitUntil("the PDF to be added") { onMain { pdfVm.inputs.value.size } == 1 }
        val name = "Shared ${System.currentTimeMillis()}.pdf"
        val share = shareIntent("application/pdf", inDownloads(name, "application/pdf", asset("alpha.pdf")))
        // Whether the PDF is still being made as Usugami starts taking the shared PDF in, read then, on
        // the main thread: busy drawing while the PDF is made, it gets to a check made from here late.
        val makingWhenTakenIn = CompletableDeferred<Boolean>()
        val watch = onMain {
            MainScope().launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
                vm.sharing.first { it != null }
                makingWhenTakenIn.complete(pdfVm.processing.value)
            }
        }
        try {
            // Shared just as the PDF starts to be made, as the system hands a share to Usugami showing:
            // sent through the system instead, it can reach a slow phone's busy main thread after the
            // PDF is made.
            onMain {
                pdfVm.runCompress(PdfCompression.EXTREME, "slow")
                instrumentation.callActivityOnNewIntent(activity, share)
            }
            val making = runBlocking { withTimeout(10_000) { makingWhenTakenIn.await() } }
            assertTrue("The PDF is still being made as the shared PDF is taken in", making)
        } finally {
            watch.cancel()
        }
        waitUntil("the shared PDF to be copied in") { onMain { vm.sharedFiles.value.isNotEmpty() || !pdfVm.processing.value } }
        assertEquals("The tool keeps its PDF meanwhile", listOf("slow.pdf"), onMain { pdfVm.inputs.value.map { it.name } })

        val deadline = SystemClock.uptimeMillis() + 120_000
        while (onMain { pdfVm.processing.value }) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for the PDF to be made" }
            Thread.sleep(200)
        }
        find("the question, once the PDF is made") { it.says(app.getString(R.string.share_discard_body)) }
        click(find("the Discard button") { it.says(app.getString(R.string.discard)) })
        chooseATool()
        find("the shared PDF") { it.says(name) }
        assertEquals(listOf(name), onMain { pdfVm.inputs.value.map { it.name } })
    }

    /** A shared PDF with a password is asked for on Choose a tool, which shows its tools once it is open. */
    @Test fun aSharedPdfWithAPasswordIsAskedForOnChooseATool() {
        val name = "Statement ${System.currentTimeMillis()}.pdf"
        launchSharing(shareIntent("application/pdf", inDownloads(name, "application/pdf", asset("protected.pdf"))))
        find("the password dialog, naming the PDF") { it.says(app.getString(R.string.pdf_password_body, name)) }
        type(find("the password field") { it.isEditable }, "test123")
        click(actionButton(R.string.pdf_unlock))
        chooseATool()
        find("the shared PDF") { it.says(name) }
        findBelow("Sign PDF") { it.says(app.getString(R.string.tool_sign)) }
    }

    /**
     * Images shared while Choose a tool shows wait in the editor, rather than put the shared PDF away:
     * Choose a tool stays, and a tool opened from it goes back to it, not to the images. Back from
     * there shows them.
     */
    @Test fun imagesSharedOverChooseAToolWaitForIt() {
        val name = "Shared ${System.currentTimeMillis()}.pdf"
        val activity = launchSharing(shareIntent("application/pdf", inDownloads(name, "application/pdf", asset("alpha.pdf"))))
        chooseATool()
        find("the shared PDF") { it.says(name) }
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
        onMain { vm.addImages(listOf(photo("shared.jpg"))) }
        waitUntil("the image to be added") { onMain { vm.items.value.size } == 1 }
        assertTrue("Choose a tool stays", settledTexts().contains(app.getString(R.string.share_title)))
        assertEquals(listOf(name), onMain { pdfVm.inputs.value.map { it.name } })

        click(findBelow("Compress PDF") { it.says(app.getString(R.string.tool_compress)) })
        waitUntil("Choose a tool to slide away") { nodes().none { it.says(app.getString(R.string.share_title)) } }
        backKey()
        chooseATool()
        // Scrolled down to the tool before.
        findAbove("the shared PDF, still there") { it.says(name) }
        backKey()
        find("the shared image in the editor") { it.says(app.getString(R.string.convert)) }
        onMain { vm.clearAll() }
    }

    /**
     * A Word document shared over a PDF not saved yet, kept, opens Word to PDF once the tool is left;
     * a PDF shared there then opens Choose a tool, rather than waiting for the PDF kept before.
     */
    @Test fun aPdfSharedOverAConverterOpenedForASharedDocumentOpens() {
        val activity = launch()
        unsavedCompressResult(activity)
        val stamp = System.currentTimeMillis()
        val word = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        shareTo(shareIntent(word, inDownloads("Report $stamp.docx", word, asset("report.docx"))))
        find("the question") { it.says(app.getString(R.string.share_discard_body)) }
        click(find("the Keep button") { it.says(app.getString(R.string.keep)) })
        waitUntil("the dialog to close") { nodes().none { it.says(app.getString(R.string.share_discard_body)) } }
        backKey()
        find("the tool") { it.says(app.getString(R.string.tool_compress)) }
        backKey()
        find("the shared document in Word to PDF") { it.says("Report $stamp.docx") }

        val name = "Shared $stamp.pdf"
        shareTo(shareIntent("application/pdf", inDownloads(name, "application/pdf", asset("alpha.pdf"))))
        chooseATool()
        find("the shared PDF") { it.says(name) }
    }

    /**
     * Images shared while Word to PDF shows a Word document shared to Usugami wait in the editor,
     * rather than put the document away; Back from there shows them.
     */
    @Test fun imagesSharedOverASharedDocumentWaitForIt() {
        val stamp = System.currentTimeMillis()
        val word = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val activity = launchSharing(shareIntent(word, inDownloads("Report $stamp.docx", word, asset("report.docx"))))
        find("the shared document in Word to PDF") { it.says("Report $stamp.docx") }
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        val pdfVm = onMain { ViewModelProvider(activity)[PdfToolsViewModel::class.java] }
        onMain { vm.addImages(listOf(photo("shared.jpg"))) }
        waitUntil("the image to be added") { onMain { vm.items.value.size } == 1 }
        assertTrue("Word to PDF stays", settledTexts().contains(app.getString(R.string.tool_word)))
        assertEquals(listOf("Report $stamp.docx"), onMain { pdfVm.documents.value.map { it.name } })

        backKey()
        waitUntil("Word to PDF to go") { nodes().none { it.says(app.getString(R.string.tool_word)) } }
        find("the shared image in the editor") { it.says(app.getString(R.string.convert)) }
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
        click(find("Settings on Home") { it.says(app.getString(R.string.settings)) })
        click(find("About in Settings") { it.says(app.getString(R.string.about)) })
        click(find("the licenses entry") { it.says(app.getString(R.string.about_licenses)) })
        val title = "Third-party notices" // the heading of THIRD_PARTY_NOTICES.md
        find("the notices") { it.says(title) }
        waitUntil("About to slide away") { nodes().none { it.says(app.getString(R.string.about_privacy_policy)) } }
        val atTop = settledTexts()
        // Each scroll moves by about a screen, animated. What a screen reader is told lags behind the
        // screen, by a while on a slow phone, and two looks at it can find the same screen from before
        // the scroll: each is read once the tree shows something new and its events have stopped.
        val forward = AccessibilityAction.ACTION_SCROLL_FORWARD
        repeat(3) {
            val before = texts()
            assertTrue(find("the scrolling notices") { forward in it.actionList }.performAction(forward.id))
            waitUntil("the notices to scroll") { texts() != before }
            automation.waitForIdle(500, 10_000)
        }
        val shown = settledTexts()
        assertTrue("The notices didn't scroll", title !in shown)
        val below = shown - atTop

        onMain { first.recreate() }
        recreatedFrom(first)
        find("the text that was showing") { it.text?.toString() in below }
        assertTrue("The notices went back to the top", texts().none { it == title })
    }

    /** The first launch asks for a language, then opens Home; later launches go straight to Home. */
    @Test fun firstLaunchAsksForLanguage() {
        languageChanged = true
        launch(firstRun = true)
        // Seven cards may not all fit on the screen: those further down show as it scrolls.
        for (language in AppLanguage.entries) findBelow(language.nativeName) { it.says(language.nativeName) }
        click(findAbove("English") { it.says(AppLanguage.ENGLISH.nativeName) })
        // In English now, whatever the phone's language.
        val images = stringIn(AppLanguage.ENGLISH, R.string.tool_images)
        find("Home") { it.text?.contains(images) == true }
        assertTrue(AppLanguages.isChosen(app))
        onMain { activity!!.finish() }
        launch(firstRun = false)
        find("Home on the next launch") { it.text?.contains(images) == true }
        assertTrue("The language screen came back", nodes().none { it.says(AppLanguage.FRENCH.nativeName) })
    }

    /** Settings links to the source code and to donations, and switches the language of every screen. */
    @Test fun settingsLinksToGitHubAndSwitchesLanguage() {
        // From English, so that choosing French recreates the activity even on a phone in French.
        val first = launch(language = AppLanguage.ENGLISH)
        fun english(res: Int) = stringIn(AppLanguage.ENGLISH, res)
        click(find("Settings on Home") { it.says(english(R.string.settings)) })
        find("the GitHub link") { it.says(english(R.string.about_source_detail)) }
        // The donation link opens its page in the browser.
        val donate = intercept(Intent.ACTION_VIEW, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)) {
            click(find("the donation link") { it.says(english(R.string.settings_donate)) })
        }
        assertEquals(DONATE_URL, donate.dataString)
        find("About in Settings") { it.says(english(R.string.about)) }
        click(find("the language entry") { it.says(english(R.string.settings_language)) })
        for (language in AppLanguage.entries) findBelow("${language.nativeName} in the dialog") { it.says(language.nativeName) }
        click(findAbove("Français") { it.says(AppLanguage.FRENCH.nativeName) })

        val french = recreatedFrom(first)
        assertEquals("fr", onMain { french.resources.configuration.locales[0].language })
        // Still on Settings, now in French.
        find("Settings in French") { it.says(stringIn(AppLanguage.FRENCH, R.string.settings)) }
        assertNotEquals("Settings isn't translated", stringIn(AppLanguage.ENGLISH, R.string.settings), stringIn(AppLanguage.FRENCH, R.string.settings))
        // Messages made outside the screens are in French too.
        val vm = onMain { ViewModelProvider(french)[MainViewModel::class.java] }
        onMain { vm.showMessage(R.string.msg_no_browser) }
        find("a French message") { it.says(stringIn(AppLanguage.FRENCH, R.string.msg_no_browser)) }
    }

    /**
     * Catches the next screen of another app that Usugami starts with [action], such as the share
     * screen, instead of starting it, and answers with [result].
     */
    private class Intercept(private val action: String, private val result: Instrumentation.ActivityResult) : Instrumentation.ActivityMonitor() {
        @Volatile var intent: Intent? = null

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
            if (intent.action != action) return null
            this.intent = intent
            return result
        }
    }

    /** Runs [block] (which makes Usugami start a screen with [action]) and returns the intent caught. */
    private fun intercept(action: String, result: Instrumentation.ActivityResult, block: () -> Unit): Intent {
        val monitor = Intercept(action, result)
        instrumentation.addMonitor(monitor)
        try {
            block()
            waitUntil("Usugami to start $action") { monitor.intent != null }
            return monitor.intent!!
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    /** Each entry of a ZIP file: its name and bytes, in order. Every one must be stored as it is. */
    private fun zipEntries(input: InputStream): List<Pair<String, ByteArray>> = ZipInputStream(input).use { zip ->
        generateSequence { zip.nextEntry }.map { entry ->
            assertEquals(ZipEntry.STORED, entry.method)
            entry.name to zip.readBytes()
        }.toList()
    }

    /** The ZIP file holds exactly [files], in order, byte for byte. */
    private fun assertZipHolds(files: List<PdfFile>, entries: List<Pair<String, ByteArray>>) {
        assertEquals(files.map { it.name }, entries.map { it.first })
        for ((file, entry) in files.zip(entries)) assertTrue(file.name, file.file.readBytes().contentEquals(entry.second))
    }

    /** Saves the result's PDFs as one ZIP file through the menu and a "Save as" screen; returns what was written. */
    private fun saveZipThroughTheMenu(expectedName: String): List<Pair<String, ByteArray>> {
        val document = File(app.cacheDir, "output/test-picked/saved.zip").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(0)) }
        val picked = FileProvider.getUriForFile(app, "${app.packageName}.files", document)
        click(findBelow("the ZIP button") { it.says(app.getString(R.string.zip_button)) })
        val create = intercept(Intent.ACTION_CREATE_DOCUMENT, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(picked))) {
            click(find("Save ZIP in the menu") { it.says(app.getString(R.string.zip_save)) })
        }
        assertEquals("application/zip", create.type)
        assertEquals("The ZIP file is named after the run", expectedName, create.getStringExtra(Intent.EXTRA_TITLE))
        find("the message") { it.says(app.getString(R.string.msg_saved)) }
        return document.inputStream().use(::zipEntries)
    }

    @Test fun severalPdfsAreSharedOrSavedAsOneZip() {
        val activity = launch()
        val pdfVm = openToolWithAlpha(R.string.tool_split, activity)
        onMain { pdfVm.runSplit(SplitMode.EVERY_PAGE, "", combine = false, fileName = "alpha") }
        find("the result screen") { it.says(app.getString(R.string.result_heading_many)) }
        val files = onMain { pdfVm.result.value!! }
        assertEquals(3, files.size)

        // The button opens a menu with the two ways; Share opens the share screen with the ZIP file.
        click(findBelow("the ZIP button") { it.says(app.getString(R.string.zip_button)) })
        find("Save ZIP in the menu") { it.says(app.getString(R.string.zip_save)) }
        val chooser = intercept(Intent.ACTION_CHOOSER, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)) {
            click(find("Share ZIP in the menu") { it.says(app.getString(R.string.zip_share)) })
        }
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/zip", send.type)
        assertTrue((send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("alpha_split.zip", uri.lastPathSegment)
        assertZipHolds(files, app.contentResolver.openInputStream(uri)!!.use(::zipEntries))
        // Sharing isn't saving. The note is further down, below the Usugami watermark's switch: the
        // screen is scrolled once the menu, whose window takes the screen reader's place, has gone.
        waitUntil("the menu to close") { nodes().none { it.says(app.getString(R.string.zip_share)) } }
        findBelow("the note that the PDFs aren't saved") { it.says(app.getString(R.string.not_saved_note)) }

        // Save writes the ZIP file into the document the "Save as" screen made, and counts as saving.
        assertZipHolds(files, saveZipThroughTheMenu("alpha_split.zip"))
        assertTrue(onMain { pdfVm.saved.value })
        waitUntil("the note to go") { nodes().none { it.says(app.getString(R.string.not_saved_note)) } }
    }

    @Test fun pdfsMadeOnePerImageCanBeSavedAsOneZip() {
        val activity = launch()
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        onMain { vm.addImages(listOf(photo("first.jpg"), photo("second.jpg"))) }
        waitUntil("the images to be added") { onMain { vm.items.value.size == 2 && vm.pendingImports.value == 0 } }
        find("the editor") { it.says(app.getString(R.string.convert)) }
        onMain { vm.convert(merge = false, fileName = "Trip", compression = Compression.RECOMMENDED) }
        find("the result screen") { it.says(app.getString(R.string.result_heading_many)) }
        val files = onMain { vm.result.value!! }
        assertEquals(listOf("first.pdf", "second.pdf"), files.map { it.name })
        assertZipHolds(files, saveZipThroughTheMenu("Trip.zip"))
        onMain { vm.startOver() }
        // Its cleanup would delete what the next test makes.
        runBlocking { vm.awaitFileWork() }
    }

    @Test fun aSinglePdfHasNoZipButton() {
        val activity = launch()
        unsavedCompressResult(activity)
        findBelow("Start over") { it.says(app.getString(R.string.start_over)) }
        if (PdfPrinting.available(app)) find("the Print button") { it.says(app.getString(R.string.print)) }
        assertTrue(nodes().none { it.says(app.getString(R.string.zip_button)) })
    }

    private fun top(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen).top

    /** The choice [label] (a radio button) that comes first below [top] on the screen. */
    private fun choiceBelow(label: String, top: Int): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil("the \"$label\" choice below $top") {
            found = nodes().filter { it.says(label) }.mapNotNull { node -> ancestor(node) { it.isCheckable } }.filter { top(it) > top }.minByOrNull(::top)
            found != null
        }
        return found!!
    }

    /** Usugami's PDFs in Downloads whose names start with [prefix]: their entries and names. */
    private fun downloads(prefix: String): List<Pair<Uri, String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        return app.contentResolver.query(collection, columns, "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("$prefix%"), null)!!.use { c ->
            buildList { while (c.moveToNext()) add(ContentUris.withAppendedId(collection, c.getLong(0)) to c.getString(1)) }
        }
    }

    /**
     * Compress PDF with several PDFs: a level for all of them, one PDF's own level and name, all kept
     * through the activity being recreated; then each PDF of the result is saved with its own button.
     */
    @Test fun severalPdfsAreCompressedEachWithItsOwnLevelAndNameAndSavedOnTheirOwn() {
        val first = launch()
        val pdfVm = openTool(R.string.tool_compress, first)
        find("the card to choose PDFs") { it.says(app.getString(R.string.pick_pdfs)) }
        find("the hint that several can be chosen") { it.says(app.getString(R.string.pick_several_hint)) }
        // Alpha gets smaller; the notes can't.
        val compact = compactPdf(File(app.cacheDir, "output/test-picked/compact.pdf").apply { parentFile!!.mkdirs() }, "NOTES")
        onMain { pdfVm.addPdfs(listOf(pdf("alpha.pdf"), picked("notes.pdf", compact.readBytes())), replace = false) }
        waitUntil("the PDFs to be added") { onMain { pdfVm.inputs.value.size } == 2 }
        val (alpha, notes) = onMain { pdfVm.inputs.value }
        find("the notes' remove button, named") { it.says(app.getString(R.string.remove_file, "notes.pdf")) }

        // The level for all sets both.
        val forAll = top(find("the level for all") { it.says(app.getString(R.string.compression_for_all)) })
        click(choiceBelow(app.getString(R.string.compression_extreme), forAll))
        waitUntil("both PDFs to be at Extreme") { onMain { pdfVm.batch.value.common } == PdfCompression.EXTREME.ordinal }
        // The notes' own level and name, in its card.
        val less = app.getString(R.string.compression_less)
        click(choiceBelow(less, top(find("the notes' card") { it.says("notes.pdf") })))
        waitUntil("the notes alone to be at Less") { onMain { pdfVm.compressionOf(notes) } == PdfCompression.LESS }
        assertEquals(PdfCompression.EXTREME, onMain { pdfVm.compressionOf(alpha) })
        // Once the screen has caught up with the levels.
        waitUntil("none to be chosen for all while they differ") { !choiceBelow(app.getString(R.string.compression_extreme), forAll).chosen }
        val stamp = "Batch ${System.currentTimeMillis()}"
        type(nodes().filter { it.isEditable && top(it) > top(find("the notes' card") { it.says("notes.pdf") }) }.minBy(::top), "$stamp notes")
        onMain { pdfVm.setBatchName(alpha.id, "$stamp alpha") }

        // All of it survives the activity being recreated, as on rotation.
        onMain { first.recreate() }
        recreatedFrom(first)
        findBelow("the notes' name, still typed") { it.isEditable && it.text?.toString() == "$stamp notes" }
        assertTrue("The notes' own level, still chosen", choiceBelow(less, top(findAbove("the notes' card") { it.says("notes.pdf") })).chosen)

        val compress = app.resources.getQuantityString(R.plurals.pdf_compress_action_many, 2, 2)
        click(find("the enabled button") { it.says(compress) && ancestor(it) { node -> node.isClickable }?.isEnabled == true })
        find("the result screen") { it.says(app.getString(R.string.result_heading_many)) }
        val files = onMain { pdfVm.result.value!! }
        assertEquals(listOf("$stamp alpha.pdf", "$stamp notes.pdf"), files.map { it.name })
        // Alpha says how much smaller it got, the notes that their original was kept.
        val percent = checkNotNull(Compressor.savedPercent(files[0].originalBytes, files[0].sizeBytes))
        val from = Formatter.formatShortFileSize(app, files[0].originalBytes)
        find("how much smaller alpha got") { it.says(app.getString(R.string.compressed_from, from, percent)) }
        find("the notes' original kept") { it.says(app.getString(R.string.original_kept)) }
        find("the notes' own Open button") { it.says(app.getString(R.string.open_file, files[1].name)) }
        find("the notes' own Share button") { it.says(app.getString(R.string.share_file, files[1].name)) }
        if (PdfPrinting.available(app)) find("the notes' own Print button") { it.says(app.getString(R.string.print_file, files[1].name)) }

        // The notes alone, with "Save as" (offered in a menu beside Downloads where there are Downloads).
        val document = File(app.cacheDir, "output/test-picked/saved-one.pdf").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(0)) }
        val picked = FileProvider.getUriForFile(app, "${app.packageName}.files", document)
        val hasDownloads = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        try {
            val create = intercept(Intent.ACTION_CREATE_DOCUMENT, Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(picked))) {
                click(find("the notes' own Save button") { it.says(app.getString(R.string.save_file, files[1].name)) })
                if (hasDownloads) click(find("Save as in the menu") { it.says(app.getString(R.string.save_as)) })
            }
            assertEquals("The document is named after the notes' PDF", files[1].name, create.getStringExtra(Intent.EXTRA_TITLE))
            waitUntil("the notes' PDF to be saved") { onMain { pdfVm.savedFiles.value } == setOf(files[1].name) }
            assertTrue(files[1].file.readBytes().contentEquals(document.readBytes()))
            assertFalse("Alpha isn't saved yet", onMain { pdfVm.saved.value })
            findBelow("the note that not all are saved") { it.says(app.getString(R.string.not_saved_note)) }

            // Alpha alone, into Downloads.
            if (hasDownloads) {
                click(findAbove("alpha's own Save button") { it.says(app.getString(R.string.save_file, files[0].name)) })
                // Once the menu shows: until then the screen's own "Save to Downloads" (all of them) is there.
                find("the menu") { it.says(app.getString(R.string.save_as)) }
                click(find("Save to Downloads in the menu") { it.says(app.getString(R.string.save_to_downloads)) })
                find("the message") { it.says(app.getString(R.string.msg_saved_downloads, files[0].name)) }
                waitUntil("both to count as saved") { onMain { pdfVm.saved.value } }
                assertEquals(listOf(files[0].name), downloads(stamp).map { it.second })
            }
        } finally {
            downloads(stamp).forEach { app.contentResolver.delete(it.first, null, null) }
        }
    }

    /** A one-page PDF of [text] in blue, the way a picker gives it; or of a gradient Black and white can't turn gray ([addUnconvertibleMeshPage]). */
    private fun coloredPdf(name: String, text: String? = null): Uri {
        val out = java.io.ByteArrayOutputStream()
        PDDocument().use { doc ->
            if (text == null) {
                addUnconvertibleMeshPage(doc)
            } else {
                val page = PDPage(PDRectangle.A4).also(doc::addPage)
                PDPageContentStream(doc, page).use { content ->
                    content.beginText()
                    content.setNonStrokingColor(0.1f, 0.2f, 0.8f)
                    content.setFont(PDType1Font.HELVETICA, 24f)
                    content.newLineAtOffset(72f, 700f)
                    content.showText(text)
                    content.endText()
                }
            }
            doc.save(out)
        }
        return picked(name, out.toByteArray())
    }

    /**
     * Compress PDF's Black and white: off at first, on with a tap and still on after the activity is
     * recreated; with a second PDF it shows once, for both, and both come out gray. Back from the
     * result it is still on, and a PDF with a part it can't turn gray says so on its card.
     */
    @Test fun compressHasABlackAndWhiteSwitchForAllPdfs() {
        val first = launch()
        val pdfVm = openTool(R.string.tool_compress, first)
        onMain { pdfVm.addPdfs(listOf(coloredPdf("blue.pdf", "BLUE")), replace = false) }
        waitUntil("the PDF to be added") { onMain { pdfVm.inputs.value.size } == 1 }
        val title = app.getString(R.string.compress_gray)
        // The row, which flips the switch: under the levels, so above the file name.
        fun blackAndWhite() = checkNotNull(ancestor(findAbove("the Black and white row") { it.says(title) }) { it.isCheckable })
        find("what it does") { it.says(app.getString(R.string.compress_gray_detail)) }
        assertFalse("Off at first", blackAndWhite().chosen)
        click(blackAndWhite())
        waitUntil("Black and white to be on") { blackAndWhite().chosen }

        // As on rotation.
        onMain { first.recreate() }
        recreatedFrom(first)
        assertTrue("Still on after the activity is recreated", blackAndWhite().chosen)

        onMain { pdfVm.addPdfs(listOf(coloredPdf("navy.pdf", "NAVY")), replace = false) }
        waitUntil("the PDFs to be added") { onMain { pdfVm.inputs.value.size } == 2 }
        // Read afresh: the detail changes in place.
        find("what it does for all") { it.refresh() && it.says(app.getString(R.string.compress_gray_detail_many)) }
        assertEquals("One switch for both", 1, nodes().count { it.refresh() && it.says(title) })
        assertTrue("Still on with two PDFs", blackAndWhite().chosen)
        val compress = app.resources.getQuantityString(R.plurals.pdf_compress_action_many, 2, 2)
        click(find("the enabled button") { it.refresh() && it.says(compress) && ancestor(it) { node -> node.isClickable }?.isEnabled == true })
        find("the result screen") { it.says(app.getString(R.string.result_heading_many)) }
        val files = onMain { pdfVm.result.value!! }
        assertEquals(listOf("blue_compressed.pdf", "navy_compressed.pdf"), files.map { it.name })
        assertTrue("Both are gray: ${files.map { it.gray }}", files.all { it.gray?.changed == true && it.gray?.colorPages?.isEmpty() == true })

        // Back at the tool, still on; a PDF it can't turn gray all through.
        backKey()
        assertTrue("Still on after the result", blackAndWhite().chosen)
        onMain { pdfVm.addPdfs(listOf(coloredPdf("mesh.pdf")), replace = true) }
        waitUntil("the PDF to replace them") { onMain { pdfVm.inputs.value.map { it.name } } == listOf("mesh.pdf") }
        find("what it does for one") { it.refresh() && it.says(app.getString(R.string.compress_gray_detail)) }
        click(relabelledButton(R.string.pdf_compress_action))
        find("the result screen") { it.says(app.getString(R.string.result_heading_one)) }
        assertEquals(listOf(0), onMain { pdfVm.result.value!!.single().gray?.colorPages })
        find("the line under the PDF") { it.says(app.resources.getQuantityString(R.plurals.gray_color_left, 1, 1, "1")) }
        find("the message") { it.says(app.getString(R.string.pdf_gray_partial)) }
    }

    /**
     * Add watermark with one PDF looks as before (its card, its name); with a second, the watermark
     * goes for both, previewed on the first, and each PDF has a name of its own on the result.
     */
    @Test fun severalPdfsGetTheSameWatermarkEachUnderItsOwnName() {
        val activity = launch()
        val pdfVm = openTool(R.string.tool_watermark, activity)
        find("the card to choose PDFs") { it.says(app.getString(R.string.pick_pdfs)) }
        find("the hint that several can be chosen") { it.says(app.getString(R.string.pick_several_hint)) }
        onMain { pdfVm.addPdfs(listOf(pdf("alpha.pdf")), replace = false) }
        waitUntil("the PDF to be added") { onMain { pdfVm.inputs.value.size } == 1 }
        find("the PDF's card") { it.says("alpha.pdf") }
        findBelow("the file name") { it.isEditable && it.text?.toString() == "alpha_watermarked" }
        assertTrue("No word of several PDFs", nodes().none { it.says(app.getString(R.string.watermark_for_all_hint)) })

        onMain { pdfVm.addPdfs(listOf(pdf("beta.pdf")), replace = false) }
        waitUntil("the PDFs to be added") { onMain { pdfVm.inputs.value.size } == 2 }
        // Once the screen lists both: the watermark text is the first field, above the PDFs and their names.
        findBelow("the hint that it goes on every PDF") { it.says(app.getString(R.string.watermark_for_all_hint)) }
        type(findAbove("the watermark text") { it.isEditable && it.text?.endsWith("_watermarked") != true }, "Draft")
        findBelow("the preview, of the first PDF") { it.says(app.getString(R.string.watermark_preview_description)) }
        val beta = top(findBelow("beta's card") { it.says("beta.pdf") })
        find("beta's remove button, named") { it.says(app.getString(R.string.remove_file, "beta.pdf")) }
        val stamp = "Mark ${System.currentTimeMillis()}"
        type(findBelow("beta's name") { it.isEditable && it.text?.toString() == "beta_watermarked" && top(it) > beta }, "$stamp beta")

        val action = app.resources.getQuantityString(R.plurals.pdf_watermark_action_many, 2, 2)
        // Read afresh: the accessibility cache may still hold the button's words for one PDF.
        click(find("the enabled button") { it.refresh() && it.says(action) && ancestor(it) { node -> node.isClickable }?.isEnabled == true })
        find("the result screen") { it.says(app.getString(R.string.result_heading_many)) }
        val files = onMain { pdfVm.result.value!! }
        assertEquals(listOf("alpha_watermarked.pdf", "$stamp beta.pdf"), files.map { it.name })
        for (file in files) assertTrue(PDDocument.load(file.file).use { PDFTextStripper().getText(it) }.filterNot(Char::isWhitespace).contains("Draft"))
        find("beta's own Save button") { it.says(app.getString(R.string.save_file, files[1].name)) }
    }

    /**
     * Word to PDF with several files: each with the name its PDF gets and a button to add more, one
     * button converting them all, and a PDF of each on the result screen.
     */
    @Test fun severalWordFilesAreConvertedEachIntoItsOwnPdf() {
        val activity = launch()
        val pdfVm = openConverter(SharedKind.WORD, R.string.tool_word, activity)
        find("the card to choose files") { it.says(app.getString(R.string.pick_word)) }
        find("the hint that several can be chosen") { it.says(app.getString(R.string.pick_several_hint)) }
        onMain { pdfVm.addDocuments(listOf(pdf("report.docx", "Garden report.docx"), pdf("report.docx", "Notes.docx")), OfficeKind.WORD) }
        waitUntil("the files to be added") { onMain { pdfVm.documents.value.size } == 2 }
        find("what converts") { it.says(app.getString(R.string.office_note_word)) }
        val notes = top(findBelow("the notes' card") { it.says("Notes.docx") })
        find("the notes' remove button, named") { it.says(app.getString(R.string.remove_file, "Notes.docx")) }
        type(findBelow("the notes' name") { it.isEditable && it.text?.toString() == "Notes" && top(it) > notes }, "Meeting notes")
        findBelow("the button to add more") { it.says(app.getString(R.string.add_word)) }

        val convert = app.resources.getQuantityString(R.plurals.convert_many, 2, 2)
        click(find("the enabled button") { it.says(convert) && ancestor(it) { node -> node.isClickable }?.isEnabled == true })
        find("the result screen") { it.says(app.getString(R.string.result_heading_many)) }
        val files = onMain { pdfVm.result.value!! }
        assertEquals(listOf("Garden report.pdf", "Meeting notes.pdf"), files.map { it.name })
        find("the notes' own Open button") { it.says(app.getString(R.string.open_file, "Meeting notes.pdf")) }
    }

    /** Home's search finds the tools that take several files at once by words such as "several". */
    @Test fun homeSearchFindsTheToolsThatTakeSeveralFiles() {
        val english = resourcesIn(AppLanguage.ENGLISH)
        launch(language = AppLanguage.ENGLISH)
        val several = listOf(
            R.string.tool_documents,
            R.string.tool_page_numbers,
            R.string.tool_watermark,
            R.string.tool_compress,
            R.string.tool_flatten,
        ).map(english::getString)
        val count = english.getQuantityString(R.plurals.home_search_results, several.size, several.size)
        for (words in listOf("several", "Batch", "many")) {
            type(searchField(), words)
            // All five, in Home's order (on a small screen, the first of them).
            waitUntil("the tools found by \"$words\"") {
                val shown = shownTools(english)
                nodes().any { it.says(count) } && shown.isNotEmpty() && shown == several.take(shown.size)
            }
        }
    }

    /**
     * PDF to images or text, Page layout and Document info open from Home, take a PDF and go back
     * Home with Back. What their buttons do is for their own tests.
     */
    @Test fun theNewToolsOpenFromHomeAndTakeAPdf() {
        val activity = launch()
        // In Home's order, top to bottom, as each is scrolled to from where Home was left.
        for (title in listOf(R.string.tool_page_layout, R.string.tool_document_info, R.string.tool_pdf_to_images_or_text)) {
            val pdfVm = openToolWithAlpha(title, activity)
            find("${app.getString(title)}'s screen") { it.says(app.getString(title)) }
            find("the PDF's card") { it.says("alpha.pdf") }
            backKey()
            find("Home") { it.says(app.getString(R.string.settings)) }
            waitUntil("the PDF to be put away") { onMain { pdfVm.inputs.value.isEmpty() } }
        }
    }

    /**
     * A tool's result of images (as PDF to images or text makes) shows as images: its own heading, each
     * image's name with its size and no page count, and no Usugami watermark, which is only for PDFs.
     */
    @Test fun aResultOfImagesShowsAsImagesWithoutTheWatermarkSwitch() {
        val activity = launch()
        val pdfVm = openToolWithAlpha(R.string.tool_page_layout, activity)
        val names = listOf("alpha_p1.jpg", "alpha_p2.jpg")
        onMain {
            pdfVm.process("alpha_pages", pdfVm.inputs.value) {
                // Written by the job: the cleanup that opening the tool started runs before it, on the same thread.
                val dir = File(app.cacheDir, "output/tools").apply { mkdirs() }
                names.map { name ->
                    val image = File(dir, name)
                    image.outputStream().use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
                    PdfFile(image, 1, 0)
                }
            }
        }
        find("the heading over images") { it.says(app.getString(R.string.result_heading_image_many)) }
        find("the first image's row") { it.says(names[0]) }
        // Before any scrolling, while the top of the screen shows: what is off it isn't in the tree.
        assertTrue("No watermark switch", nodes().none { it.says(app.getString(R.string.mark_title)) })
        val onePage = app.resources.getQuantityString(R.plurals.page_count, 1, 1)
        assertTrue("No page count", nodes().none { it.text?.contains(onePage) == true })
        findBelow("the second image's row") { it.says(names[1]) }
    }

    /**
     * The tool's main button once it says [label] and can be pressed. The options change its words in
     * place, which the tree as read here may not have caught up with: each node is read afresh.
     */
    private fun relabelledButton(label: Int) = find("the enabled \"${app.getString(label)}\" button") {
        it.refresh() && it.says(app.getString(label)) && ancestor(it) { node -> node.isClickable }?.isEnabled == true
    }

    /**
     * PDF to images or text makes alpha.pdf's text into a text file: the options tell the screen's
     * button what it makes, the result shows the file with the beginning of its text and no Usugami
     * watermark, and Copy puts the whole text on the clipboard.
     */
    @Test fun pdfToTextShowsTheTextAndCopiesIt() {
        val activity = launch()
        val pdfVm = openToolWithAlpha(R.string.tool_pdf_to_images_or_text, activity)
        relabelledButton(R.string.export_images_action)
        click(find("the Text choice") { it.says(app.getString(R.string.export_kind_text)) })
        click(relabelledButton(R.string.export_text_action))
        find("the heading over a text file") { it.says(app.getString(R.string.result_heading_text_one)) }
        find("the text file's row") { it.says("alpha.txt") }
        assertTrue("No watermark switch", nodes().none { it.says(app.getString(R.string.mark_title)) })
        findBelow("the text in its card") { it.text?.contains("ALPHA ONE") == true }
        val whole = onMain { pdfVm.result.value!!.single().file.readText() }
        // Something else on the clipboard first, so that only the copy can put the text there. Read
        // while Usugami is in front: from Android 10 only the app in front may read the clipboard.
        val clipboard = onMain { app.getSystemService(ClipboardManager::class.java) }
        onMain { clipboard.setPrimaryClip(ClipData.newPlainText("test", "Not copied yet")) }
        click(find("the Copy button") { it.says(app.getString(R.string.copy_text)) })
        waitUntil("the whole text on the clipboard") { onMain { clipboard.primaryClip?.getItemAt(0)?.text?.toString() } == whole }
    }

    /** Set by tests that change the appearance, which outlives the activity (and Usugami, from Android 12). */
    private var appearanceChanged = false

    /**
     * The middle brightness of a few spots low in [activity]'s window, where the screens have only
     * their background. The window is drawn into a bitmap here rather than captured from the
     * screen, which needs nothing from the graphics card of a busy emulator.
     */
    private fun backgroundLuminance(activity: MainActivity): Float {
        val view = activity.window.decorView
        val shot = onMain {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        val spots = listOf(0.05f, 0.5f, 0.95f).flatMap { x ->
            listOf(0.8f, 0.86f, 0.92f).map { y -> Color.luminance(shot.getPixel((shot.width * x).toInt(), (shot.height * y).toInt())) }
        }
        shot.recycle()
        return spots.sorted()[spots.size / 2]
    }

    /** [activity] is dark, or light: its configuration, what it draws, and its status bar icons. */
    private fun assertShows(dark: Boolean, activity: MainActivity) {
        val night = onMain { activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK }
        assertEquals(if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO, night)
        waitUntil("the screen to be ${if (dark) "dark" else "light"}") {
            val luminance = backgroundLuminance(activity)
            if (dark) luminance < 0.1f else luminance > 0.8f
        }
        // Light icons on a dark screen, dark ones on a light screen.
        assertEquals(!dark, onMain { WindowCompat.getInsetsController(activity.window, activity.window.decorView).isAppearanceLightStatusBars })
    }

    /** Opens Settings > Appearance and picks [choice]; the current choice is checked in the dialog. */
    private fun chooseAppearance(current: Int, choice: Int) {
        click(find("the appearance row") { it.says(app.getString(R.string.settings_appearance)) })
        assertTrue("${app.getString(current)} is chosen", radio(app.getString(current)).chosen)
        click(radio(app.getString(choice)))
        waitUntil("the dialog to close") { nodes().none { it.isCheckable } }
    }

    /**
     * Settings > Appearance starts on the phone's setting; Dark and Light then hold through the
     * activity being recreated (as on rotation), Usugami being opened again and the first-launch
     * language screen, and System follows the phone again.
     */
    private fun appearanceSwitchesAndStays() {
        appearanceChanged = true
        languageChanged = true
        // A run stopped part way leaves its choice behind, and the reset after a test that failed
        // may not have reached Usugami yet: from Android 12 the system applies it to Usugami's
        // configuration shortly after, even once the saved choice is gone.
        Appearances.reset(app)
        Thread.sleep(1_000)
        // Whether the phone is dark now: UiModeManager.nightMode only says how it is set, and on a
        // schedule (or with Battery Saver) it may be dark either way.
        val phoneDark = (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        var activity = launch()
        click(find("Settings on Home") { it.says(app.getString(R.string.settings)) })
        find("the appearance row saying System") { it.says(app.getString(R.string.appearance_system)) }
        assertShows(phoneDark, activity)

        chooseAppearance(R.string.appearance_system, R.string.appearance_dark)
        if (!phoneDark) activity = recreatedFrom(activity)
        assertShows(dark = true, activity)
        find("the row saying Dark") { it.says(app.getString(R.string.appearance_dark)) }
        assertEquals(Appearance.DARK, Appearances.current(app))

        onMain { activity.recreate() }
        activity = recreatedFrom(activity)
        assertShows(dark = true, activity)
        find("Settings, still") { it.says(app.getString(R.string.appearance_dark)) }

        // Opened again, and the first launch's language screen too.
        onMain { activity.finish() }
        activity = launch()
        assertShows(dark = true, activity)
        onMain { activity.finish() }
        activity = launch(firstRun = true)
        find("the language screen") { it.says(AppLanguage.ENGLISH.nativeName) }
        assertShows(dark = true, activity)
        // Not choosing a language there, which could recreate the activity in it.
        onMain { activity.finish() }
        activity = launch()
        click(find("Settings on Home") { it.says(app.getString(R.string.settings)) })

        chooseAppearance(R.string.appearance_dark, R.string.appearance_light)
        activity = recreatedFrom(activity)
        assertShows(dark = false, activity)
        find("the row saying Light") { it.says(app.getString(R.string.appearance_light)) }

        chooseAppearance(R.string.appearance_light, R.string.appearance_system)
        if (phoneDark) activity = recreatedFrom(activity)
        assertShows(phoneDark, activity)
        find("the row saying System") { it.says(app.getString(R.string.appearance_system)) }
        assertEquals(Appearance.SYSTEM, Appearances.current(app))
    }

    /** From Android 12, the system keeps the appearance for Usugami and applies it. */
    @Test fun appearanceAppliedBySystemSwitchesAndStays() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        appearanceSwitchesAndStays()
    }

    /** Below Android 12, Usugami applies it itself; tried here on any phone. */
    @Test fun appearanceAppliedByUsugamiSwitchesAndStays() {
        Appearances.appliedByUsugami = true
        appearanceSwitchesAndStays()
    }

    private companion object {
        /** How old binary Word and PowerPoint files (.doc, .ppt) start. */
        val OLD_BINARY_HEADER = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    }
}
