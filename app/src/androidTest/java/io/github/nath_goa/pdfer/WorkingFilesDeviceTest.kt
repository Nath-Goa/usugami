package io.github.nath_goa.pdfer

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.pdfer.data.ImageFormat
import io.github.nath_goa.pdfer.data.ImageItem
import io.github.nath_goa.pdfer.data.PdfInput
import io.github.nath_goa.pdfer.data.PdfPageRef
import io.github.nath_goa.pdfer.ui.MainViewModel
import io.github.nath_goa.pdfer.ui.PdfToolsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Working files live in the cache, which the system may clear while PDFer is in the background.
 * After the process is restored, items whose files are gone must be dropped (with a message).
 */
class WorkingFilesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!onMain(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    private fun file(path: String) = File(app.cacheDir, path).apply { parentFile!!.mkdirs(); writeText("x") }

    @Test fun restoredImagesWhoseCopiesWereClearedAreDropped() {
        val kept = file("images/1.jpg")
        val gone = File(app.cacheDir, "images/2.jpg").apply { delete() }
        fun item(id: Long, file: File) = ImageItem(id, file.path, file.name, ImageFormat.JPEG, 10, 10, 1)
        // "items" is MainViewModel's saved-state key.
        val handle = SavedStateHandle(mapOf("items" to arrayListOf(item(1, kept), item(2, gone))))

        val vm = onMain { MainViewModel(app, handle) }

        waitUntil("the missing image to be dropped") { vm.items.value.map { it.id } == listOf(1L) }
        val message = runBlocking { withTimeout(10_000) { vm.messages.first() } }
        assertTrue(message, message.contains("cleared"))
        assertTrue("A copy that is still there is kept", kept.isFile)
    }

    @Test fun restoredPdfsWhoseCopiesWereClearedAreDroppedAndResetFreesTheRest() {
        val kept = file("pdf-inputs/1.pdf")
        val gone = File(app.cacheDir, "pdf-inputs/2.pdf").apply { delete() }
        val scratch = file("pdfbox/left-over.tmp")
        val inputs = arrayListOf(PdfInput(1, kept.path, "kept.pdf", 2), PdfInput(2, gone.path, "gone.pdf", 1))
        val pages = arrayListOf(PdfPageRef(1, 0), PdfPageRef(2, 0), PdfPageRef(1, 1))
        // PdfToolsViewModel's saved-state keys.
        val handle = SavedStateHandle(mapOf("pdf_inputs" to inputs, "pdf_pages" to pages))

        val vm = onMain { PdfToolsViewModel(app, handle) }

        waitUntil("the missing PDF to be dropped") { vm.inputs.value.map { it.id } == listOf(1L) }
        assertEquals(listOf(PdfPageRef(1, 0), PdfPageRef(1, 1)), vm.pages.value)
        val message = runBlocking { withTimeout(10_000) { vm.messages.first() } }
        assertTrue(message, message.contains("cleared"))
        assertTrue(kept.isFile)
        waitUntil("PDFBox scratch files from an earlier run to be removed") { !scratch.exists() }

        // Leaving the tools frees the copies.
        onMain { vm.reset() }
        waitUntil("the input copies to be deleted") { File(app.cacheDir, "pdf-inputs").list().isNullOrEmpty() }
        assertFalse(kept.exists())
        assertTrue(vm.inputs.value.isEmpty())
    }
}
