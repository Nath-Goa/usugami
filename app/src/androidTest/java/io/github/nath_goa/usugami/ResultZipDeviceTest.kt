package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.SplitMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * "Share or save as ZIP" on the result screen of a run that made several PDFs: the ZIP file holds
 * exactly that run's PDFs, byte for byte and stored as they are, it is shared as application/zip
 * through Usugami's file provider or written into the document picked, and cancelling or a result
 * that has gone leaves nothing behind.
 */
class ResultZipDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val picked = File(app.cacheDir, "output/test-zip")

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

    private fun <T> first(flow: Flow<T>): T = runBlocking { withTimeout(20_000) { flow.first() } }

    @After fun tearDown() {
        picked.deleteRecursively()
        File(app.cacheDir, "test-zip-photos").deleteRecursively()
    }

    /** A file the way a picker gives it: a content:// URI from Usugami's file provider. */
    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)

    /** A PDF from the test assets, picked. */
    private fun pdf(fixture: String): Uri {
        val file = File(picked, fixture).apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) }
        return uriFor(file)
    }

    /** An empty document, as the "Save as" screen makes one. */
    private fun newDocument(name: String): Pair<File, Uri> {
        val file = File(picked, name).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(0)) }
        return file to uriFor(file)
    }

    /** alpha.pdf (three pages) split into one PDF per page, named "alpha_split". */
    private fun splitEveryPage(): Pair<PdfToolsViewModel, List<PdfFile>> {
        val vm = onMain { PdfToolsViewModel(app, SavedStateHandle()) }
        onMain { vm.addPdfs(listOf(pdf("alpha.pdf")), replace = true) }
        waitUntil("the PDF to be added") { vm.inputs.value.size == 1 }
        onMain { vm.runSplit(SplitMode.EVERY_PAGE, "", combine = false, fileName = "alpha_split") }
        waitUntil("the split PDFs") { vm.result.value != null }
        val files = onMain { vm.result.value!! }
        assertEquals(3, files.size)
        return vm to files
    }

    /** Each entry of a ZIP file: its name and bytes, in order. Every one must be stored as it is. */
    private fun entries(input: InputStream): List<Pair<String, ByteArray>> = ZipInputStream(input).use { zip ->
        generateSequence { zip.nextEntry }.map { entry ->
            assertEquals("${entry.name} is stored, not compressed again", ZipEntry.STORED, entry.method)
            entry.name to zip.readBytes()
        }.toList()
    }

    /** The ZIP file holds exactly [files], in order, byte for byte. */
    private fun assertHolds(files: List<PdfFile>, zip: List<Pair<String, ByteArray>>) {
        assertEquals(files.map { it.name }, zip.map { it.first })
        for ((file, entry) in files.zip(zip)) assertArrayEquals(file.name, file.file.readBytes(), entry.second)
    }

    @Test fun splitPdfsAreSharedAsOneZip() {
        val (vm, files) = splitEveryPage()
        onMain { vm.shareZip() }
        val chooser = first(vm.zipShares)

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("application/zip", send.type)
        assertTrue("The app shared with may read it", (send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.files", uri.authority)
        assertEquals("Named after the run", "alpha_split.zip", uri.lastPathSegment)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertHolds(files, app.contentResolver.openInputStream(uri)!!.use(::entries))

        // The ZIP file is a working file like the PDFs: starting over clears it.
        val zip = File(app.cacheDir, "output/tools/zip/alpha_split.zip")
        assertTrue(zip.isFile)
        onMain { vm.startOver() }
        waitUntil("the ZIP file to be cleared") { !zip.exists() }
    }

    @Test fun pdfsMadeOnePerImageAreSavedAsOneZip() {
        // Two pictures whose names differ only in case: their PDFs get different names, and so do
        // the ZIP file's entries.
        val photos = listOf("Beach.jpg", "beach.png", "Mountain.jpg").map { name ->
            val file = File(app.cacheDir, "test-zip-photos/$name").apply { parentFile!!.mkdirs() }
            val format = if (name.endsWith(".png")) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            file.outputStream().use { Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }.compress(format, 90, it) }
            Uri.fromFile(file)
        }
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        onMain { vm.addImages(photos) }
        waitUntil("the pictures to be added") { vm.items.value.size == 3 && vm.pendingImports.value == 0 }
        onMain { vm.convert(merge = false, fileName = "Holiday", compression = Compression.RECOMMENDED) }
        waitUntil("the PDFs") { vm.result.value != null }
        val files = onMain { vm.result.value!! }
        assertEquals(listOf("Beach.pdf", "beach (2).pdf", "Mountain.pdf"), files.map { it.name })
        assertEquals("Holiday.zip", onMain { vm.zipFileName() })
        assertFalse(vm.saved.value)

        val (document, uri) = newDocument("chosen.zip")
        onMain { vm.saveZipTo(uri) }
        assertEquals(app.getString(R.string.msg_saved), first(vm.messages))
        assertTrue("Saving it counts as saving the PDFs", vm.saved.value)
        assertHolds(files, document.inputStream().use(::entries))
        onMain { vm.startOver() }
    }

    @Test fun savingOverADocumentReplacesAllOfIt() {
        val (vm, files) = splitEveryPage()
        val (document, uri) = newDocument("old.zip")
        // An existing document, longer than the new ZIP file.
        document.writeBytes(ByteArray(2_000_000) { 7 })
        onMain { vm.saveZipTo(uri) }
        assertEquals(app.getString(R.string.msg_saved), first(vm.messages))
        assertHolds(files, document.inputStream().use(::entries))
        assertTrue("Nothing of the old document is left at the end", document.length() < 2_000_000)
    }

    @Test fun cancellingLeavesNothingBehind() {
        val (vm, _) = splitEveryPage()
        // Cancelled straight away, before the first chunk is read.
        onMain {
            vm.shareZip()
            vm.cancelZip()
        }
        waitUntil("the ZIP file to stop") { vm.zipProgress.value == null }
        assertNull("Nothing is shared", runBlocking { withTimeoutOrNull(1_000) { vm.zipShares.first() } })
        assertTrue(File(app.cacheDir, "output/tools/zip").list().isNullOrEmpty())

        val (_, uri) = newDocument("cancelled.zip")
        onMain {
            vm.saveZipTo(uri)
            vm.cancelZip()
        }
        waitUntil("the ZIP file to stop") { vm.zipProgress.value == null }
        assertFalse("Nothing was saved", vm.saved.value)
        assertNull("No message", runBlocking { withTimeoutOrNull(1_000) { vm.messages.first() } })
        // The PDFs are still there to share or save.
        assertNotNull(vm.result.value)
    }

    @Test fun aResultClearedFromTheCacheSaysSo() {
        val (vm, files) = splitEveryPage()
        files.forEach { it.file.delete() }
        onMain { vm.shareZip() }
        val message = first(vm.messages)
        assertEquals(app.resources.getQuantityString(R.plurals.msg_result_cleared, 3), message)
        assertNull("Back to the tool", vm.result.value)
        assertNull("Nothing is shared", runBlocking { withTimeoutOrNull(1_000) { vm.zipShares.first() } })
    }

    /** The system restarted Usugami while "Save as" was open: the PDFs have gone, so say so. */
    @Test fun savingAfterTheResultHasGoneSaysSo() {
        val (_, uri) = newDocument("late.zip")
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        onMain { vm.saveZipTo(uri) }
        assertEquals(app.resources.getQuantityString(R.plurals.msg_result_cleared, 2), first(vm.messages))

        val pdfVm = onMain { PdfToolsViewModel(app, SavedStateHandle()) }
        onMain { pdfVm.saveZipTo(uri) }
        assertEquals(app.resources.getQuantityString(R.plurals.msg_result_cleared, 2), first(pdfVm.messages))
    }
}
