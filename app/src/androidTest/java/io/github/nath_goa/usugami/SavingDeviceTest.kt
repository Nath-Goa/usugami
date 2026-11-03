package io.github.nath_goa.usugami

import android.app.Application
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.AppLanguages
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

    /** Start over and Back wait while the PDF is being saved: they would delete or replace it mid-save. */
    @Test fun theResultStaysWhileItIsBeingSaved() {
        val pdf = File(app.cacheDir, "output/images/Saving.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF-1.4 saving") }
        val target = File(dir, "saving-target.pdf").apply { writeText("") }
        // MainViewModel's saved-state keys for a result showing (see KeptResult).
        val state = mapOf("result_count" to 1, "result_paths" to arrayOf(pdf.path), "result_pages" to intArrayOf(1), "result_originals" to longArrayOf(0))
        val vm = onMain { MainViewModel(app, SavedStateHandle(state)) }
        runBlocking { vm.awaitFileWork() }

        onMain {
            vm.saveTo(Uri.fromFile(target))
            vm.startOver()
            vm.backToEditing()
        }
        assertTrue("The result is still showing", onMain { vm.result.value != null })
        assertEquals(AppLanguages.localized(app).resources.getString(R.string.msg_saved), firstMessage(vm.messages))
        assertEquals("%PDF-1.4 saving", target.readText())

        // Once saved, Start over goes ahead.
        onMain { vm.startOver() }
        assertTrue(onMain { vm.result.value == null })
        runBlocking { vm.awaitFileWork() }
    }

    /** The system restarted Usugami while "Save as" was open: the PDF has gone, so say so. */
    @Test fun saveAsAfterTheResultHasGoneSaysSo() {
        val target = Uri.fromFile(File(dir, "picked.pdf").apply { writeText("") })
        // In Usugami's language, which may not be English.
        val cleared = AppLanguages.localized(app).resources.getQuantityString(R.plurals.msg_result_cleared, 1)

        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        onMain { vm.saveTo(target) }
        assertEquals(cleared, firstMessage(vm.messages))

        val pdfVm = onMain { PdfToolsViewModel(app, SavedStateHandle()) }
        onMain { pdfVm.saveTo(target) }
        assertEquals(cleared, firstMessage(pdfVm.messages))
        // Their cleanup of an earlier session's files would delete what the next test makes.
        runBlocking {
            vm.awaitFileWork()
            pdfVm.awaitFileWork()
        }
    }
}
