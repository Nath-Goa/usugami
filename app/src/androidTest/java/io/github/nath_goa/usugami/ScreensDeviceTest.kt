package io.github.nath_goa.usugami

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.app.UiModeManager
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
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.InkItem
import io.github.nath_goa.usugami.core.Redaction
import io.github.nath_goa.usugami.core.ShapeItem
import io.github.nath_goa.usugami.core.ShapeKind
import io.github.nath_goa.usugami.core.SignatureLayout
import io.github.nath_goa.usugami.core.TextItem
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.Appearance
import io.github.nath_goa.usugami.data.Appearances
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.SignatureInk
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.SplitMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

    /** Starts Usugami. Unless [firstRun], it is as if the user had already picked a language. */
    private fun launch(firstRun: Boolean = false): MainActivity {
        if (firstRun) AppLanguages.reset(app) else AppLanguages.skipChoice(app)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
    }

    /** Set by tests that change the app's language, which outlives the activity. */
    private var languageChanged = false

    @After fun tearDown() {
        activity?.let { onMain { it.finish() } }
        File(app.cacheDir, "output/test-picked").deleteRecursively()
        if (languageChanged) AppLanguages.reset(app)
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

    /** [res] in [language], as the translated screens show it. */
    private fun stringIn(language: AppLanguage, res: Int): String =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.tag)) })
            .getString(res)

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
        // Home stays in the tree while it slides away, and its cards name the tools as their buttons do.
        waitUntil("Home to slide away") { nodes().none { it.says(app.getString(R.string.home_section_edit)) } }
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

    /** Whether a radio button is the chosen one. */
    // isChecked gives way to getChecked() from Android 16, which older devices don't have.
    @Suppress("DEPRECATION")
    private val AccessibilityNodeInfo.chosen: Boolean get() = isChecked

    /** A choice shown as a radio button; Compose reports the selected one as checked. */
    private fun radio(label: String): AccessibilityNodeInfo {
        val node = find("the \"$label\" choice") { it.says(label) && ancestor(it) { node -> node.isCheckable } != null }
        return ancestor(node) { it.isCheckable }!!
    }

    @Test fun homeGroupsTheToolsUnderHeadings() {
        launch()
        val headings = listOf(R.string.home_section_create, R.string.home_section_organize, R.string.home_section_edit, R.string.home_section_optimize)
        val sections = listOf(
            listOf(R.string.tool_images, R.string.tool_word, R.string.tool_powerpoint, R.string.tool_epub),
            listOf(R.string.tool_merge, R.string.tool_split, R.string.tool_reorganize, R.string.tool_delete_pages, R.string.tool_rotate),
            listOf(R.string.tool_edit, R.string.tool_sign, R.string.tool_page_numbers, R.string.tool_watermark),
            listOf(R.string.tool_compress, R.string.tool_redact, R.string.tool_flatten),
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
            Thread.sleep(400)
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
        R.string.tool_images, R.string.tool_word, R.string.tool_powerpoint, R.string.tool_epub, R.string.tool_merge, R.string.tool_split,
        R.string.tool_reorganize, R.string.tool_delete_pages, R.string.tool_rotate, R.string.tool_edit, R.string.tool_sign,
        R.string.tool_page_numbers, R.string.tool_watermark, R.string.tool_compress, R.string.tool_redact, R.string.tool_flatten,
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

    private fun backKey() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

    private fun resumedMain(): MainActivity? =
        onMain { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() as? MainActivity }

    /**
     * Home's search: it finds tools by name, description and hidden words (any case, accents and word
     * order), shows them without the sections, says when nothing matches, clears with its button,
     * survives the activity being recreated, comes back after a tool opened from it, and Back clears
     * it before leaving Usugami. A screen reader hears how many tools were found.
     */
    @Test fun homeSearchFindsToolsAndBackClearsItFirst() {
        val first = launch()
        val field = searchField()
        // A screen reader says what the field is for: its placeholder is part of it.
        assertNotNull("The field says what it is for", ancestor(find("the placeholder") { it.says(app.getString(R.string.home_search_hint)) }) { it.isEditable })

        // The line over the results is read out by a screen reader each time it changes.
        val one = app.resources.getQuantityString(R.plurals.home_search_results, 1, 1)
        type(field, "Join")
        waitForTools(app.getString(R.string.tool_merge))
        val count = find("the count") { it.says(one) }
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, count.liveRegion)
        assertTrue("No section headings while searching", nodes().none { it.says(app.getString(R.string.home_section_organize)) })

        // Every word must begin a word of the tool's texts, in any order, whatever the case and accents
        // (here typed where the keyword "reduce size" has none).
        type(searchField(), "SIZE réduce")
        waitForTools(app.getString(R.string.tool_compress))
        // Tools with the word in their name come first.
        type(searchField(), "page")
        waitUntil("tools named with \"page\" first") {
            shownTools().take(2) == listOf(app.getString(R.string.tool_delete_pages), app.getString(R.string.tool_page_numbers))
        }

        type(searchField(), "zebra")
        val none = find("the empty state") { it.says(app.getString(R.string.home_search_none, "zebra")) }
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, none.liveRegion)
        waitForTools()

        click(find("the clear button") { it.says(app.getString(R.string.home_search_clear)) })
        find("the sections again") { it.says(app.getString(R.string.home_section_create)) }
        assertTrue("The field is empty", searchField().text.isNullOrEmpty())

        // The search survives the activity being recreated, as it is on rotation.
        type(searchField(), "combine")
        waitForTools(app.getString(R.string.tool_merge))
        onMain { first.recreate() }
        val activity = recreatedFrom(first)
        find("the search, kept") { it.isEditable && it.text?.toString() == "combine" }
        waitForTools(app.getString(R.string.tool_merge))

        // A tool opened from the results goes back to them.
        click(find("Merge PDF") { it.text?.contains(app.getString(R.string.tool_merge)) == true })
        waitUntil("Home to slide away") { nodes().none { it.isEditable && it.text?.toString() == "combine" } }
        backKey()
        find("the search, still there") { it.isEditable && it.text?.toString() == "combine" }
        waitForTools(app.getString(R.string.tool_merge))

        // Back clears the search first, staying on Home...
        backKey()
        find("the sections again") { it.says(app.getString(R.string.home_section_create)) }
        assertTrue("The field is empty", searchField().text.isNullOrEmpty())
        assertTrue("Usugami is still showing", resumedMain() === activity)
        // ...then leaves Usugami, as before.
        backKey()
        waitUntil("Usugami to leave") { resumedMain() == null }
    }

    /** In French, the search finds tools by French words, also without their accents. */
    @Test fun homeSearchFindsFrenchKeywords() {
        languageChanged = true
        val first = launch()
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
        type(searchField(), "zèbre")
        find("the empty state in French") { it.says(french.getString(R.string.home_search_none, "zèbre")) }
    }

    @Test fun rotateTurnsPagesWithButtons() {
        val activity = launch()
        val pdfVm = openToolWithAlpha(R.string.tool_rotate, activity)
        // Nothing to save until a page is turned.
        assertFalse(clickable(button(R.string.pdf_rotate_action)).isEnabled)

        click(find("page 2's right button") { it.says(app.getString(R.string.rotate_page_right, 2)) })
        waitUntil("page 2 to turn") { onMain { pdfVm.pages.value.map { it.rotation } } == listOf(0, 90, 0) }
        find("the page saying how it turned") { it.says(app.getString(R.string.rotated_right)) }
        click(find("the all-pages left button") { it.says(app.getString(R.string.rotate_all_left)) })
        waitUntil("every page to turn") { onMain { pdfVm.pages.value.map { it.rotation } } == listOf(270, 0, 270) }

        click(actionButton(R.string.pdf_rotate_action))
        val rotated = resultFile(pdfVm)
        assertEquals(listOf(270, 0, 270), PDDocument.load(rotated).use { doc -> doc.pages.map { it.rotation } })
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
                click(findBelow("the move button") { it.says(app.getString(label)) })
                // Each button moves on from where the signature is once the screen has caught up.
                Thread.sleep(250)
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

    /** The text field of a tool, the first on its screen (the file name comes last). */
    private fun firstField() = find("the text field") { it.isEditable }

    @Test fun deletePagesMarksPagesAndSaysWhatIsWrong() {
        val activity = launch()
        val pdfVm = openToolWithAlpha(R.string.tool_delete_pages, activity)
        assertFalse("Nothing to delete yet", clickable(button(R.string.pdf_delete_pages_action)).isEnabled)
        find("the count") { it.says(app.getString(R.string.delete_pages_count, 0, 3)) }

        // Tapping a page marks it: the field and the count follow.
        // Each page is a check box named after it.
        val page2 = app.getString(R.string.pdf_page_number, 2)
        assertFalse(radio(page2).chosen)
        click(radio(page2))
        waitUntil("page 2 to be marked") { nodes().any { it.isEditable && it.text?.toString() == "2" } }
        find("the new count") { it.says(app.getString(R.string.delete_pages_count, 1, 3)) }
        waitUntil("page 2 to show as marked") { radio(page2).chosen }

        // Every page can't go: the screen says so, and so does trying.
        val keepOne = app.getString(R.string.pdf_need_page)
        type(firstField(), "1-3")
        find("the warning") { it.says(keepOne) }
        click(actionButton(R.string.pdf_delete_pages_action))
        waitUntil("the message too") { nodes().count { it.says(keepOne) } >= 2 }
        type(firstField(), "9")
        click(actionButton(R.string.pdf_delete_pages_action))
        find("the message") { it.says(app.getString(R.string.pdf_pages_range, 3)) }

        type(firstField(), "2")
        click(actionButton(R.string.pdf_delete_pages_action))
        val result = resultFile(pdfVm)
        assertEquals("alpha_pages_deleted.pdf", result.name)
        val text = PDDocument.load(result).use { doc ->
            assertEquals(2, doc.numberOfPages)
            PDFTextStripper().getText(doc)
        }
        assertTrue(text.contains("ALPHA ONE") && text.contains("ALPHA THREE"))
        assertFalse(text.contains("ALPHA TWO"))
    }

    @Test fun reorganizeMovesTurnsAddsAndRemovesPagesWithButtons() {
        val first = launch()
        val firstVm = openToolWithAlpha(R.string.tool_reorganize, first)
        assertFalse("Nothing to save until the pages change", clickable(button(R.string.pdf_reorganize_action)).isEnabled)
        find("the page count") { it.says(app.resources.getQuantityString(R.plurals.page_count, 3, 3)) }
        val (a, b, c) = onMain { firstVm.pages.value }

        // Page 3 is chosen with a tap, then moved and turned with the buttons, which name it.
        click(findBelow("page 3") { it.says(app.getString(R.string.pdf_page_number, 3)) && ancestor(it) { node -> node.isClickable } != null })
        click(find("the move button") { it.says(app.getString(R.string.move_page_earlier, 3)) })
        waitUntil("page 3 to come second") { onMain { firstVm.pages.value } == listOf(a, c, b) }
        click(find("the turn button, for its new place") { it.says(app.getString(R.string.rotate_page_right, 2)) })
        waitUntil("page 3 to turn") { onMain { firstVm.pages.value.map { it.rotation } } == listOf(0, 90, 0) }
        assertTrue("Now there is something to save", clickable(actionButton(R.string.pdf_reorganize_action)).isEnabled)

        // A blank page goes in after the chosen page; Undo takes it out again.
        click(findBelow("Add pages") { it.says(app.getString(R.string.reorganize_add)) })
        click(find("Blank page") { it.says(app.getString(R.string.reorganize_add_blank)) })
        waitUntil("a blank page third") { onMain { firstVm.pages.value.getOrNull(2)?.isBlank == true } }
        findBelow("the blank page, named for a screen reader") { it.says(app.getString(R.string.reorganize_page_blank, 3)) }
        find("the new page count") { it.says(app.resources.getQuantityString(R.plurals.page_count, 4, 4)) }
        click(find("Undo") { it.says(app.getString(R.string.undo)) })
        waitUntil("the blank page to go") { onMain { firstVm.pages.value.none { it.isBlank } } }

        // A screen reader removes page 1 with the action its card offers.
        val remove = app.getString(R.string.remove_page, 1)
        val card = cardWith(remove)
        assertTrue(card.performAction(card.action(remove)!!.id))
        waitUntil("page 1 to go") { onMain { firstVm.pages.value.map { it.page } } == listOf(2, 1) }

        // The pages and the chosen one survive the activity being recreated, as on rotation.
        onMain { first.recreate() }
        val activity = recreatedFrom(first)
        find("the chosen page's buttons, still") { it.says(app.getString(R.string.rotate_page_right, 1)) }
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

    /** A tool button of Edit PDF, by its name, once it can be pressed; the screen scrolls up to it. */
    private fun editTool(label: Int) = findAbove("the \"${app.getString(label)}\" tool") {
        it.says(app.getString(label)) && ancestor(it) { node -> node.isClickable }?.isEnabled == true
    }

    /** Picks [label] from the Shape menu, and waits for the menu to close. */
    private fun addShape(label: Int) {
        click(editTool(R.string.edit_shape))
        click(find("the menu's \"${app.getString(label)}\"") { it.says(app.getString(label)) })
        waitUntil("the menu to close") { nodes().any { it.says(app.getString(R.string.pdf_edit_action)) } }
    }

    @Test fun editPdfAddsWithButtonsUndoesAndKeepsItThroughRecreation() {
        val first = launch()
        val firstVm = openToolWithAlpha(R.string.tool_edit, first)
        assertFalse("Nothing to save yet", clickable(button(R.string.pdf_edit_action)).isEnabled)
        find("what to do first") { it.says(app.getString(R.string.edit_hint_start)) }
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
        assertEquals("alpha_edited.pdf", result.name)
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

        // Back from the result, everything is still there to change.
        click(find("the back button") { it.says(app.getString(R.string.back_to_editing)) })
        waitUntil("the result to slide away") { nodes().none { it.says(app.getString(R.string.result_heading_one)) } }
        assertEquals(4, onMain { pdfVm.editItems.value.size })
        assertTrue(clickable(actionButton(R.string.pdf_edit_action)).isEnabled)
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
        repeat(3) {
            click(findBelow("the move right button") { it.says(app.getString(R.string.redact_move_right)) })
            // Each button moves on from where the box is once the screen has caught up.
            Thread.sleep(250)
        }
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

    @Test fun wordToPdfConvertsTheDocumentAndKeepsItThroughRecreation() {
        val first = launch()
        val firstVm = openTool(R.string.tool_word, first)
        find("the card to pick a file") { it.says(app.getString(R.string.pick_word)) }
        assertFalse("Nothing to convert yet", clickable(button(R.string.convert)).isEnabled)

        // An old binary .doc is refused, saying what to do.
        onMain { firstVm.addDocument(picked("minutes.doc", OLD_BINARY_HEADER + ByteArray(1024)), OfficeKind.WORD) }
        find("the message") { it.says(app.getString(R.string.office_legacy_word)) }
        assertNull(onMain { firstVm.document.value })

        onMain { firstVm.addDocument(pdf("report.docx", "Garden report.docx"), OfficeKind.WORD) }
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
    }

    @Test fun powerPointToPdfRefusesOtherFilesAndConverts() {
        val activity = launch()
        val pdfVm = openTool(R.string.tool_powerpoint, activity)
        find("the card to pick a file") { it.says(app.getString(R.string.pick_powerpoint)) }

        // A Word document isn't a presentation.
        onMain { pdfVm.addDocument(pdf("report.docx"), OfficeKind.POWERPOINT) }
        find("the message") { it.says(app.getString(R.string.office_not_powerpoint)) }
        assertNull(onMain { pdfVm.document.value })

        onMain { pdfVm.addDocument(pdf("deck.pptx"), OfficeKind.POWERPOINT) }
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
        val firstVm = openTool(R.string.tool_epub, first)
        find("the card to pick a file") { it.says(app.getString(R.string.pick_epub)) }
        assertFalse("Nothing to convert yet", clickable(button(R.string.convert)).isEnabled)

        // A file that isn't a book (pickable when a provider calls it just binary data), and a book with DRM.
        onMain { firstVm.addDocument(picked("notes.epub", "Not a book at all".toByteArray()), OfficeKind.EPUB) }
        find("the message") { it.says(app.getString(R.string.epub_not_recognized)) }
        onMain { firstVm.addDocument(picked("locked.epub", book(drm = true)), OfficeKind.EPUB) }
        find("the message") { it.says(app.getString(R.string.epub_protected)) }
        assertNull(onMain { firstVm.document.value })

        onMain { firstVm.addDocument(picked("Tiny Tales.epub", book()), OfficeKind.EPUB) }
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
        click(find("Settings on Home") { it.says(app.getString(R.string.settings)) })
        click(find("About in Settings") { it.says(app.getString(R.string.about)) })
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
        recreatedFrom(first)
        find("the text that was showing") { it.text?.toString() in below }
        assertTrue("The notices went back to the top", texts().none { it == title })
    }

    /** The first launch asks for a language, then opens Home; later launches go straight to Home. */
    @Test fun firstLaunchAsksForLanguage() {
        languageChanged = true
        launch(firstRun = true)
        for (language in AppLanguage.entries) find(language.nativeName) { it.says(language.nativeName) }
        click(find("English") { it.says(AppLanguage.ENGLISH.nativeName) })
        find("Home") { it.text?.contains(app.getString(R.string.tool_images)) == true }
        assertTrue(AppLanguages.isChosen(app))
        onMain { activity!!.finish() }
        launch(firstRun = false)
        find("Home on the next launch") { it.text?.contains(app.getString(R.string.tool_images)) == true }
        assertTrue("The language screen came back", nodes().none { it.says(AppLanguage.FRENCH.nativeName) })
    }

    /** Settings links to the source code and switches the language of every screen. */
    @Test fun settingsLinksToGitHubAndSwitchesLanguage() {
        languageChanged = true
        val first = launch()
        click(find("Settings on Home") { it.says(app.getString(R.string.settings)) })
        find("the GitHub link") { it.says(app.getString(R.string.about_source_detail)) }
        find("About in Settings") { it.says(app.getString(R.string.about)) }
        click(find("the language entry") { it.says(app.getString(R.string.settings_language)) })
        for (language in AppLanguage.entries) find("${language.nativeName} in the dialog") { it.says(language.nativeName) }
        click(find("Français") { it.says(AppLanguage.FRENCH.nativeName) })

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
        onMain { pdfVm.runSplit(SplitMode.EVERY_PAGE, "", combine = false, fileName = "alpha_split") }
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
        // Sharing isn't saving.
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
    }

    @Test fun aSinglePdfHasNoZipButton() {
        val activity = launch()
        unsavedCompressResult(activity)
        findBelow("Start over") { it.says(app.getString(R.string.start_over)) }
        assertTrue(nodes().none { it.says(app.getString(R.string.zip_button)) })
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
        val phoneDark = app.getSystemService(UiModeManager::class.java).nightMode == UiModeManager.MODE_NIGHT_YES
        assertEquals("The phone's setting is the default", Appearance.SYSTEM, Appearances.current(app))
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
