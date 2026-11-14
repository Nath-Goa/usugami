package io.github.nath_goa.usugami

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import io.github.nath_goa.usugami.core.ExportImageFormat
import io.github.nath_goa.usugami.core.ExportImageSize
import io.github.nath_goa.usugami.core.ExportImages
import io.github.nath_goa.usugami.core.OutputType
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.MarkSetting
import io.github.nath_goa.usugami.data.OcrLanguage
import io.github.nath_goa.usugami.data.SettingsStore
import io.github.nath_goa.usugami.ui.ExportNotes
import io.github.nath_goa.usugami.ui.NotedPages
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.exampleImageName
import io.github.nath_goa.usugami.ui.runExportImages
import io.github.nath_goa.usugami.ui.runExportText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PDF to images or text through the view model: the runs the tool's button starts, what they make
 * and say, and what they note for the result, kept while Android stops Usugami in the background.
 */
class ExportResultDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val models = ArrayList<PdfToolsViewModel>()
    private val picked = File(app.cacheDir, "output/test-picked")

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (!onMain(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()) = onMain { PdfToolsViewModel(app, handle) }.also { models += it }

    /** In Usugami's language, which may not be English. */
    private val strings get() = AppLanguages.localized(app).resources

    /** [file] the way a picker gives it: a content:// URI with a display name. */
    private fun picked(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)

    private fun add(vm: PdfToolsViewModel, uri: Uri) {
        onMain { vm.addPdfs(listOf(uri), replace = false) }
        waitUntil("the PDF to be added") { !vm.importing.value && vm.inputs.value.isNotEmpty() }
    }

    @After fun tearDown() {
        for (vm in models) {
            onMain { vm.reset() }
            // Its cleanup would delete the copies the next test makes.
            runBlocking { vm.awaitFileWork() }
        }
        picked.deleteRecursively()
        // Later tests start with the watermark off, as it is at first.
        SettingsStore(app).setMark(MarkSetting())
    }

    @Test fun imagesAndTextAreMadeThroughTheViewModel() {
        SettingsStore(app).setMark(MarkSetting(on = true))
        PDFBoxResourceLoader.init(app)
        // A page of A4 with a word on it, and a poster 5000 points square: too big for 300 dpi.
        val poster = File(picked.apply { mkdirs() }, "Poster.pdf")
        PDDocument().use { doc ->
            writeText(doc, PDPage(PDRectangle.A4).also(doc::addPage), "POSTER")
            doc.addPage(PDPage(PDRectangle(5000f, 5000f)))
            doc.save(poster)
        }
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        add(vm, picked(poster))
        assertTrue("The watermark is on", onMain { vm.mark.value.on })
        val input = onMain { vm.inputs.value.single() }
        onMain { vm.runExportImages(input, "", ExportImageFormat.JPG, ExportImageSize.LARGE, "Poster") }
        waitUntil("the images to be made") { !vm.processing.value }

        val files = onMain { vm.result.value } ?: throw AssertionError("No result: ${runBlocking { withTimeoutOrNull(1_000) { vm.messages.first() } }}")
        assertEquals("All pages, in order, named as typed", listOf("Poster_p1.jpg", "Poster_p2.jpg"), files.map { it.name })
        assertTrue(files.all { it.type == OutputType.JPEG && it.file.isFile })
        assertEquals("Poster_images.zip", onMain { vm.zipFileName() })
        waitUntil("the watermark to settle") { !vm.marking.value }
        assertNull("Nothing said, the watermark on images neither", runBlocking { withTimeoutOrNull(1_000) { vm.messages.first() } })
        val notes = ExportNotes(reduced = NotedPages(1, "2"))
        assertEquals("The poster's image got fewer pixels", notes, onMain { vm.fromPdf.notes.value })

        // Android stops Usugami in the background and starts it again from the saved state.
        val again = newViewModel(handle)
        runBlocking { again.awaitFileWork() }
        assertEquals(files.map { it.file }, onMain { again.result.value?.map { it.file } })
        assertEquals(notes, onMain { again.fromPdf.notes.value })

        // Again, with the PDF's copy cleared from the cache meanwhile: the PDF goes, the result stays
        // with its notes.
        assertTrue(input.file.delete())
        val cleared = newViewModel(handle)
        waitUntil("the cleared PDF to go") { cleared.inputs.value.isEmpty() }
        runBlocking { cleared.awaitFileWork() }
        assertEquals(files.map { it.file }, onMain { cleared.result.value?.map { it.file } })
        assertEquals(notes, onMain { cleared.fromPdf.notes.value })

        // Pages with a drawing on them but no text, and nothing to read them with.
        val drawn = File(picked, "Drawn.pdf")
        PDDocument().use { doc ->
            repeat(2) {
                val page = PDPage(PDRectangle.A4).also(doc::addPage)
                PDPageContentStream(doc, page).use { content ->
                    content.addRect(100f, 100f, 200f, 200f)
                    content.fill()
                }
            }
            doc.save(drawn)
        }
        val text = newViewModel()
        add(text, picked(drawn))
        val blank = onMain { text.inputs.value.single() }
        onMain { text.runExportText(blank, "1-2", readScans = false, OcrLanguage.ENGLISH, "Drawn") }
        val said = runBlocking { withTimeout(60_000) { text.messages.first() } }
        assertEquals(strings.getString(if (BuildConfig.HAS_OCR) R.string.export_none_off else R.string.export_none_lite), said)
        waitUntil("the run to end") { !text.processing.value }
        assertNull("No result", onMain { text.result.value })
        assertNull(onMain { text.fromPdf.notes.value })

        // A page with text and a page with only a drawing: the text is made, and the notes say why
        // the other page has none, in the words of the edition and the switch.
        val mixed = File(picked.apply { mkdirs() }, "Mixed.pdf")
        PDDocument().use { doc ->
            writeText(doc, PDPage(PDRectangle.A4).also(doc::addPage), "MIXED")
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            PDPageContentStream(doc, page).use { content ->
                content.addRect(100f, 100f, 200f, 200f)
                content.fill()
            }
            doc.save(mixed)
        }
        val both = newViewModel()
        add(both, picked(mixed))
        onMain { both.runExportText(both.inputs.value.single(), "", readScans = false, OcrLanguage.ENGLISH, "Mixed") }
        waitUntil("the text to be made") { !both.processing.value }
        val made = onMain { both.result.value } ?: throw AssertionError("No result: ${runBlocking { withTimeoutOrNull(1_000) { both.messages.first() } }}")
        assertEquals(listOf("Mixed.txt"), made.map { it.name })
        assertEquals(
            ExportNotes(noText = NotedPages(1, "2"), noTextWhy = if (BuildConfig.HAS_OCR) ExportNotes.NO_TEXT_OFF else ExportNotes.NO_TEXT_LITE),
            onMain { both.fromPdf.notes.value },
        )
    }

    @Test fun theImageNameExampleIsTheNameOfAnImageMade() {
        // Too long for a file name: cut to leave room for the longest page number, "_p12" here.
        val long = "Minutes".repeat(40)
        val example = exampleImageName(long, "", 12)
        assertEquals("The third page's image, all 12 pages made", ExportImages.names(long, (0 until 12).toList())[2], example)
        assertNotEquals("Cut for page 12, not for page 3 alone", ExportImages.names(long, listOf(2)).single(), example)
        assertEquals("Cut as for the pages typed, the last typed first", ExportImages.names(long, listOf(2, 11)).first(), exampleImageName(long, "12,3", 12))
    }
}
