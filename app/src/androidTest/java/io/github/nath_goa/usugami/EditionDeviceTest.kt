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
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.ocrEngine
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.label
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

    /**
     * Home's card and hidden search words for Images to PDF mention text recognition only where there
     * is some, in every language: so each language's texts, Usugami Lite's own among them, are in the APK.
     */
    @Test fun homeMentionsTextRecognitionOnlyWhereThereIsSome() {
        val words = mapOf(
            AppLanguage.ENGLISH to "text recognition",
            AppLanguage.FRENCH to "reconnaissance de texte",
            AppLanguage.SPANISH to "reconocimiento de texto",
            AppLanguage.HINDI to "टेक्स्ट पहचान",
            AppLanguage.GERMAN to "Texterkennung",
            AppLanguage.PORTUGUESE to "reconhecimento de texto",
            AppLanguage.INDONESIAN to "pengenalan teks",
        )
        for (language in AppLanguage.entries) {
            val phrase = words.getValue(language)
            val resources = app.createConfigurationContext(
                Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.tag)) },
            ).resources
            val detail = resources.getString(R.string.tool_images_detail)
            val keywords = resources.getString(R.string.search_keywords_images)
            assertEquals("$language: $detail", BuildConfig.HAS_OCR, detail.contains(phrase))
            assertEquals("$language: $keywords", BuildConfig.HAS_OCR, keywords.split(", ").contains("OCR"))
        }
    }

    /**
     * Each language is told from the locale a screen is built in, whatever its region and however this
     * Android writes the code: Indonesian's tag is "id" and Android's code "in", Portuguese's tag is
     * "pt-BR" and a phone in Portugal's Portuguese is shown it too. A language Usugami lacks is English.
     */
    @Test fun everyLanguageIsKnownFromItsLocale() {
        fun shownIn(locale: Locale) = AppLanguage.shownIn(
            app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(locale) }),
        )
        for (language in AppLanguage.entries) assertEquals(language.tag, language, shownIn(Locale.forLanguageTag(language.tag)))
        assertEquals(AppLanguage.PORTUGUESE, shownIn(Locale.forLanguageTag("pt-PT")))
        assertEquals(AppLanguage.GERMAN, shownIn(Locale.forLanguageTag("de-AT")))
        @Suppress("DEPRECATION") // the code Android has always used for Indonesian
        assertEquals(AppLanguage.INDONESIAN, shownIn(Locale("in")))
        assertEquals(AppLanguage.ENGLISH, shownIn(Locale.forLanguageTag("ja")))
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

    /**
     * The Convert sheet has the OCR switch, except in Usugami Lite. Switched on, it shows the text
     * language as a chip, starting on the app's language, whose menu offers every language.
     */
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
            return
        }
        click(nodes().first { it.text?.contains(app.getString(R.string.ocr_title)) == true })
        fun chip(language: OcrLanguage) = app.getString(R.string.option_value, app.getString(R.string.ocr_language), app.getString(language.label))
        click(find(chip(AppLanguage.shownIn(main).ocr)))
        click(find(app.getString(OcrLanguage.GERMAN.label)))
        find(chip(OcrLanguage.GERMAN))
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
