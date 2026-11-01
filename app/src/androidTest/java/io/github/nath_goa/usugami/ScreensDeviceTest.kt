package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.core.SignatureLayout
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.SignatureInk
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
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
        for (heading in listOf(R.string.home_section_create, R.string.home_section_edit)) {
            val node = find("the heading") { it.says(app.getString(heading)) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) assertTrue("A screen reader can jump to it", node.isHeading)
        }
        val tools = listOf(
            R.string.tool_images, R.string.tool_word, R.string.tool_powerpoint, R.string.tool_epub, R.string.tool_split, R.string.tool_merge,
            R.string.tool_compress, R.string.tool_rotate, R.string.tool_page_numbers, R.string.tool_watermark, R.string.tool_sign,
        )
        for (tool in tools) {
            val card = findBelow("the ${app.getString(tool)} card") { it.text?.contains(app.getString(tool)) == true }
            assertTrue(clickable(card).isEnabled)
        }
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

    private companion object {
        /** How old binary Word and PowerPoint files (.doc, .ppt) start. */
        val OLD_BINARY_HEADER = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    }
}
