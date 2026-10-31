package io.github.nath_goa.usugami

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.PdfSaver
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/** Saving a finished PDF into a document the user picked. */
class SavingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val dir = File(app.cacheDir, "test-saving").apply { mkdirs() }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun firstMessage(messages: Flow<String>) = runBlocking { withTimeout(10_000) { messages.first() } }

    @Test fun savingCopiesThePdf() {
        val pdf = File(dir, "made.pdf").apply { writeText("%PDF-1.4 new") }
        val target = File(dir, "chosen.pdf").apply { writeText("old document, longer than the new one") }
        PdfSaver(app).saveTo(pdf, Uri.fromFile(target))
        assertEquals("%PDF-1.4 new", target.readText())
    }

    @Test fun aPdfThatCantBeReadLeavesTheChosenDocumentAlone() {
        val pdf = File(dir, "unreadable.pdf").apply { writeText("%PDF-1.4") }
        val target = File(dir, "existing.pdf").apply { writeText("old document") }
        assertTrue(pdf.setReadable(false, false))
        try {
            PdfSaver(app).saveTo(pdf, Uri.fromFile(target))
            fail("The unreadable PDF was saved")
        } catch (_: IOException) {
            // Expected.
        } finally {
            pdf.setReadable(true, false)
        }
        assertEquals("The document isn't emptied before the PDF is opened", "old document", target.readText())
    }

    /** The system restarted Usugami while "Save as" was open: the PDF has gone, so say so. */
    @Test fun saveAsAfterTheResultHasGoneSaysSo() {
        val target = Uri.fromFile(File(dir, "picked.pdf").apply { writeText("") })

        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        onMain { vm.saveTo(target) }
        val message = firstMessage(vm.messages)
        assertTrue(message, message.contains("create it again"))

        val pdfVm = onMain { PdfToolsViewModel(app, SavedStateHandle()) }
        onMain { pdfVm.saveTo(target) }
        val pdfMessage = firstMessage(pdfVm.messages)
        assertTrue(pdfMessage, pdfMessage.contains("create it again"))
    }
}
