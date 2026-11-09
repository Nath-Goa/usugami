package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.core.ToolSearch
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.Edition
import io.github.nath_goa.usugami.data.ocrEngine
import io.github.nath_goa.usugami.ui.MainViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What each edition offers: text recognition in Usugami and Usugami OCR, and none in Usugami Lite,
 * which doesn't mention it anywhere either. The same checks run in every edition, so each one also
 * proves that the others' answers are told apart.
 */
class EditionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private var activity: MainActivity? = null
    private val photos = File(app.cacheDir, "test-edition")

    @After fun tearDown() {
        activity?.let { activity ->
            instrumentation.runOnMainSync {
                ViewModelProvider(activity)[MainViewModel::class.java].clearAll()
                activity.finish()
            }
        }
        photos.deleteRecursively()
    }

    @Test fun buildMatchesTheEdition() {
        assertEquals(BuildConfig.EDITION, Edition.current.name.lowercase(Locale.ROOT))
        assertEquals(Edition.current != Edition.LITE, BuildConfig.HAS_OCR)
        assertEquals("An engine exactly when there is text recognition", BuildConfig.HAS_OCR, ocrEngine(app) != null)
        // The invisible font of the OCR text layer is left out of Usugami Lite.
        assertEquals(BuildConfig.HAS_OCR, "UsugamiOcr-Regular.ttf" in app.assets.list("fonts").orEmpty())
    }

    /** Home's card and hidden search words for Images to PDF mention text recognition only where there is some. */
    @Test fun homeMentionsTextRecognitionOnlyWhereThereIsSome() {
        val words = mapOf(
            AppLanguage.ENGLISH to "text recognition",
            AppLanguage.FRENCH to "reconnaissance de texte",
            AppLanguage.SPANISH to "reconocimiento de texto",
            AppLanguage.HINDI to "टेक्स्ट पहचान",
        )
        for (language in AppLanguage.entries) {
            val resources = app.createConfigurationContext(
                Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.tag)) },
            ).resources
            val detail = resources.getString(R.string.tool_images_detail)
            val keywords = resources.getString(R.string.search_keywords_images)
            assertEquals("$language: $detail", BuildConfig.HAS_OCR, detail.contains(words.getValue(language)))
            assertEquals("$language: $keywords", BuildConfig.HAS_OCR, keywords.split(", ").contains("OCR"))
        }
    }

    /**
     * Searching Home for a type of image Images to PDF takes finds it in every edition, though its
     * card no longer names the types. Usugami Lite has its own texts for the card and its search.
     */
    @Test fun homeSearchFindsImagesToPdfByImageType() {
        val resources = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply { setLocale(Locale.ENGLISH) },
        ).resources
        val text = resources.getString(R.string.tool_images_detail) + " " + resources.getString(R.string.search_keywords_images)
        for (type in listOf("jpg", "jpeg", "png", "heic", "webp")) {
            assertTrue("$type in $text", ToolSearch.matches(type, text))
        }
    }

    /** The Convert sheet has the OCR switch, except in Usugami Lite. */
    @Test fun convertSheetOffersOcrOnlyWithTextRecognition() {
        AppLanguages.skipChoice(app)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val main = (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
        val photo = File(photos, "page.jpg").apply { parentFile!!.mkdirs() }
        photo.outputStream().use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val vm = ViewModelProvider(main)[MainViewModel::class.java]
        instrumentation.runOnMainSync { vm.addImages(listOf(Uri.fromFile(photo))) }
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!(vm.items.value.size == 1 && vm.pendingImports.value == 0)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out adding the image" }
            Thread.sleep(100)
        }

        click(find(app.getString(R.string.convert)))
        // The sheet's title says Create PDF too, and shows as soon as the sheet starts to slide up. Its
        // button comes last, so once the button shows, the rest of the sheet is on the screen too.
        find(app.getString(R.string.create_pdf)) { node -> generateSequence(node) { it.parent }.any { it.isClickable } }
        // The switch's row reads its title and detail together.
        val ocrSwitch = nodes().any { it.text?.contains(app.getString(R.string.ocr_title)) == true }
        assertEquals(BuildConfig.HAS_OCR, ocrSwitch)
        if (!BuildConfig.HAS_OCR) {
            val texts = nodes().mapNotNull { it.text?.toString() }
            assertTrue("No mention of OCR in $texts", texts.none { it.contains("OCR") })
        }
    }

    /** Every node in the window that has focus (the sheet's, once it is open). */
    private fun nodes(): List<AccessibilityNodeInfo> {
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            all += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::walk)
        return all
    }

    private fun find(text: String, match: (AccessibilityNodeInfo) -> Boolean = { true }): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (true) {
            nodes().firstOrNull { (it.text?.toString() == text || it.contentDescription?.toString() == text) && match(it) }?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for \"$text\"" }
            Thread.sleep(100)
        }
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue(checkNotNull(target) { "Nothing to click at $node" }.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
}
