package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.core.content.IntentCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.MarkSetting
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.RecentFiles
import io.github.nath_goa.usugami.data.SettingsStore
import io.github.nath_goa.usugami.ui.KeptResult
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.ToolMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Results that are not PDFs, through the view model: images and text files are left alone by the
 * Usugami watermark and handed over, saved and listed as what they are; a job may also end without
 * any file, and then no result shows.
 */
class NonPdfResultsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val models = ArrayList<PdfToolsViewModel>()
    private val recent = RecentFiles.of(app)

    /** What the tests put into Downloads, deleted afterwards. */
    private val made = ArrayList<Uri>()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!onMain(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    private fun newViewModel() = onMain { PdfToolsViewModel(app, SavedStateHandle()) }.also { models += it }

    private fun clearList() {
        recent.clear()
        runBlocking { recent.awaitWrites() }
    }

    @Before fun setUp() = clearList()

    @After fun tearDown() {
        for (vm in models) {
            onMain { vm.reset() }
            // Its cleanup would delete the files the next test makes.
            runBlocking { vm.awaitFileWork() }
        }
        made.forEach { app.contentResolver.delete(it, null, null) }
        clearList()
        // Later tests start with the watermark off, as it is at first.
        SettingsStore(app).setMark(MarkSetting())
    }

    /** The intent a share screen made by Usugami hands on to the app picked. */
    private fun sent(chooser: Intent): Intent = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!

    /**
     * Images and a text file a tool made, shown again after Android closed Usugami, are left byte for
     * byte as they were with the Usugami watermark on, and nothing is said about it; each opens and is
     * shared as what it is (any type when they differ), and saved into Downloads it keeps its name and
     * is listed in Recent files with its own type.
     */
    @Test fun imagesAndTextAreSavedSharedAndListedAsWhatTheyAre() {
        assumeTrue("Android 10 and newer save into Downloads", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        SettingsStore(app).setMark(MarkSetting(on = true))
        val dir = File(app.cacheDir, "output/tools").apply { mkdirs() }
        val jpg = File(dir, "Scan_page_1.jpg")
        jpg.outputStream().use { out ->
            Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }
        val txt = File(dir, "Scan.txt").apply { writeText("Page 1\nA line of text\n") }
        val jpgBytes = jpg.readBytes()
        val txtBytes = txt.readBytes()
        val files = listOf(PdfFile(jpg, 1, 0), PdfFile(txt, 1, 0))
        val vm = onMain { PdfToolsViewModel(app, SavedStateHandle(mapOf(KeptResult.KEY to KeptResult.pack(files)))) }.also { models += it }
        runBlocking { vm.awaitFileWork() }
        assertTrue("The watermark is on", onMain { vm.mark.value.on })
        assertEquals("The result is shown again", files.map { it.file }, onMain { vm.result.value?.map { it.file } })
        waitUntil("the watermark to settle") { !vm.marking.value }

        assertArrayEquals("The image as it was", jpgBytes, jpg.readBytes())
        assertArrayEquals("The text as it was", txtBytes, txt.readBytes())
        assertNull("Nothing said", runBlocking { withTimeoutOrNull(1_000) { vm.messages.first() } })

        assertEquals("image/jpeg", onMain { vm.viewIntent(files[0]) }.type)
        assertEquals("text/plain", onMain { vm.viewIntent(files[1]) }.type)
        assertEquals("image/jpeg", sent(onMain { vm.shareIntent(files.take(1)) }).type)
        assertEquals("*/*", sent(onMain { vm.shareIntent(files) }).type)

        onMain { vm.saveToDownloads() }
        assertEquals(app.getString(R.string.pdf_saved_downloads), runBlocking { withTimeout(20_000) { vm.messages.first() } })
        val listed = recent.files.value
        made += listed.map { Uri.parse(it.uri) }
        assertEquals(2, listed.size)
        val image = listed.single { it.type == "image/jpeg" }
        val text = listed.single { it.type == "text/plain" }
        assertTrue(image.name, image.name.startsWith("Scan_page_1") && image.name.endsWith(".jpg"))
        assertTrue(text.name, text.name.startsWith("Scan") && text.name.endsWith(".txt"))
        assertArrayEquals("The image in Downloads", jpgBytes, app.contentResolver.openInputStream(Uri.parse(image.uri))!!.use { it.readBytes() })
        assertArrayEquals("The text in Downloads", txtBytes, app.contentResolver.openInputStream(Uri.parse(text.uri))!!.use { it.readBytes() })
    }

    /**
     * A job that makes no file (text copied, say) shows no result, and what it gives to run after
     * runs; the progress it reported shows while it works and is gone once it is done. A job that
     * can't do what was asked says why, in its own words, and shows no result either.
     */
    @Test fun aJobWithoutFilesShowsNoResultAndSaysWhatItFound() {
        val vm = newViewModel()
        runBlocking { vm.awaitFileWork() }
        var ran = false
        val gate = CompletableDeferred<Unit>()
        onMain {
            vm.process("x", emptyList(), afterResult = { ran = true }) {
                vm.reportProgress(1, 2, "Page 1 of 2")
                // The job may suspend: it waits here until the progress has been seen.
                gate.await()
                emptyList()
            }
        }
        waitUntil("the job's progress") { vm.toolProgress.value?.text == "Page 1 of 2" }
        assertEquals(1 to 2, onMain { vm.toolProgress.value!!.let { it.done to it.total } })
        gate.complete(Unit)
        waitUntil("the job to end") { !vm.processing.value }
        assertTrue("What the job runs after ran", onMain { ran })
        assertNull("No result", onMain { vm.result.value })
        assertNull("No progress once done", onMain { vm.toolProgress.value })

        onMain { vm.process("x", emptyList()) { throw ToolMessage("Nothing to copy") } }
        assertEquals("Nothing to copy", runBlocking { withTimeout(10_000) { vm.messages.first() } })
        waitUntil("the job to end") { !vm.processing.value }
        assertNull("No result", onMain { vm.result.value })
    }
}
