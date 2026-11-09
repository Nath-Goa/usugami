package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfSaver
import io.github.nath_goa.usugami.ui.KeptCopies
import io.github.nath_goa.usugami.ui.KeptResult
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    /** The saved state of a view model showing [pdf] as its result, as when Android restarted Usugami. */
    private fun showing(pdf: File) = SavedStateHandle(mapOf(KeptResult.KEY to KeptResult.pack(listOf(PdfFile(pdf, 1, 0)))))

    /** In Usugami's language, which may not be English. */
    private fun string(res: Int) = AppLanguages.localized(app).resources.getString(res)

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

    /** A ZIP file saved over an existing document and cancelled partway leaves the document as it was. */
    @Test fun aCancelledSaveOverADocumentLeavesItAlone() {
        val pdf = File(dir, "zipped.pdf").apply { writeText("%PDF-1.4") }
        val target = File(dir, "existing.zip").apply { writeText("old document") }
        try {
            PdfSaver(app).writeTo(Uri.fromFile(target), listOf(pdf), "application/zip") { out ->
                out.write("half a ZIP file".toByteArray())
                throw CancellationException("Cancel pressed")
            }
            fail("The cancelled save went on")
        } catch (_: CancellationException) {
            // Expected.
        }
        assertEquals("old document", target.readText())
        assertTrue("Nothing is left in the cache", File(app.cacheDir, "saving").list().isNullOrEmpty())
    }

    /** Start over and Back wait while the PDF is being saved: they would delete or replace it mid-save. */
    @Test fun theResultStaysWhileItIsBeingSaved() {
        val pdf = File(app.cacheDir, "output/images/Saving.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF-1.4 saving") }
        // The document picked is a pipe: the save waits at it until the test reads what comes through,
        // so it is under way when Start over and Back are pressed. A few bytes saved into a file can be
        // saved before saveTo returns.
        val target = File(dir, "saving-target.pdf").apply { delete() }
        Os.mkfifo(target.path, "600".toInt(8))
        val vm = onMain { MainViewModel(app, showing(pdf)) }
        runBlocking { vm.awaitFileWork() }

        try {
            onMain {
                vm.saveTo(Uri.fromFile(target))
                vm.startOver()
                vm.backToEditing()
            }
            assertTrue("The result is still showing", onMain { vm.result.value != null })
            val saved = Executors.newSingleThreadExecutor().submit(Callable { target.readText() })
            assertEquals("%PDF-1.4 saving", saved.get(20, TimeUnit.SECONDS))
            assertEquals(string(R.string.msg_saved), firstMessage(vm.messages))
        } finally {
            target.delete()
        }

        // Once saved, Start over goes ahead.
        onMain { vm.startOver() }
        assertTrue(onMain { vm.result.value == null })
        runBlocking { vm.awaitFileWork() }
    }

    /** Images shared mid-save put the result away, but not its PDFs: a save that fails says so. */
    @Test fun aSaveThatFailsAfterImagesWereSharedSaysSo() {
        val pdf = File(app.cacheDir, "output/images/Failing.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF-1.4 failing") }
        val photo = File(dir, "shared.jpg")
        photo.outputStream().use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val vm = onMain { MainViewModel(app, showing(pdf)) }
        runBlocking { vm.awaitFileWork() }

        onMain {
            // Into a folder that isn't there, as when the storage runs full.
            vm.saveTo(Uri.fromFile(File(dir, "missing/target.pdf")))
            vm.addImages(listOf(Uri.fromFile(photo)))
        }
        assertEquals(string(R.string.msg_save_failed), firstMessage(vm.messages))
        onMain { vm.clearAll() }
        runBlocking { vm.awaitFileWork() }
    }

    /** Leaving a tool while its PDF is being saved (images were shared) frees the PDF only once it is saved. */
    @Test fun aToolsPdfIsFreedOnlyOnceItIsSaved() {
        val pdf = File(app.cacheDir, "output/tools/Saving.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF-1.4 tool") }
        val target = File(dir, "tool-target.pdf").apply { writeText("") }
        val vm = onMain { PdfToolsViewModel(app, showing(pdf)) }
        runBlocking { vm.awaitFileWork() }

        onMain {
            vm.saveTo(Uri.fromFile(target))
            vm.reset()
        }
        assertEquals(string(R.string.msg_saved), firstMessage(vm.messages))
        assertEquals("%PDF-1.4 tool", target.readText())
        runBlocking { vm.awaitFileWork() }
        assertFalse("Freed once saved", pdf.exists())
    }

    /** Saved to Downloads, then again once Android has restarted Usugami: the copy there is not made twice. */
    @Test fun savingToDownloadsAgainAfterARestartKeepsOneCopy() {
        val pdf = File(app.cacheDir, "output/images/Kept copy.pdf").apply { parentFile!!.mkdirs(); writeText("%PDF-1.4 kept") }
        val vm = onMain { MainViewModel(app, showing(pdf)) }
        assumeTrue(vm.canSaveToDownloads)
        runBlocking { vm.awaitFileWork() }
        try {
            onMain { vm.saveToDownloads() }
            val saved = firstMessage(vm.messages)

            val state = SavedStateHandle(mapOf(KeptResult.KEY to KeptResult.pack(listOf(PdfFile(pdf, 1, 0))), KeptCopies.KEY to onMain { vm.packCopies() }))
            val restored = onMain { MainViewModel(app, state) }
            runBlocking { restored.awaitFileWork() }
            onMain { restored.saveToDownloads() }
            assertEquals("Saved as the same copy", saved, firstMessage(restored.messages))
        } finally {
            app.contentResolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("Kept copy%"))
            pdf.delete()
        }
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
