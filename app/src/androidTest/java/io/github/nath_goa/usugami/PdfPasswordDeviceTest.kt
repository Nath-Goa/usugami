package io.github.nath_goa.usugami

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Picking PDFs that need a password, and leaving a tool while PDFs are still being added. */
class PdfPasswordDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private var vm: PdfToolsViewModel? = null

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

    /** A test PDF the way a picker gives it: a content:// URI with a display name. */
    private fun pdf(fixture: String, name: String = fixture): Uri {
        val file = File(app.cacheDir, "output/test-picked/$name").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()) = onMain { PdfToolsViewModel(app, handle) }.also { vm = it }

    private fun copies(): Set<String> = File(app.cacheDir, "pdf-inputs").list().orEmpty().toSet()

    @After fun tearDown() {
        vm?.let { onMain { it.reset() } }
        File(app.cacheDir, "output/test-picked").deleteRecursively()
    }

    @Test fun lockedPdfsAreAskedForOneAtATime() {
        val vm = newViewModel()
        onMain {
            vm.addPdfs(listOf(pdf("protected.pdf", "first.pdf"), pdf("alpha.pdf"), pdf("protected.pdf", "second.pdf")), replace = false)
        }
        waitUntil("the PDFs to be added") { !vm.importing.value }
        assertEquals(listOf("alpha.pdf"), onMain { vm.inputs.value.map { it.name } })
        assertEquals("Both locked PDFs wait, in the order picked", listOf("first.pdf", "second.pdf"), onMain { vm.locked.value.map { it.name } })

        val first = onMain { vm.locked.value.first() }
        onMain { vm.unlock(first, "wrong") }
        waitUntil("the password to be tried") { !vm.unlocking.value }
        assertTrue("A wrong password is reported", onMain { vm.wrongPassword.value })
        assertEquals(2, onMain { vm.locked.value.size })

        onMain { vm.unlock(first, "test123") }
        waitUntil("the first PDF to be opened") { vm.locked.value.size == 1 }
        assertFalse(onMain { vm.wrongPassword.value })
        assertEquals(listOf("alpha.pdf", "first.pdf"), onMain { vm.inputs.value.map { it.name } })
        assertEquals("Both PDFs' pages are listed", 6, onMain { vm.pages.value.size })

        // Cancelling skips the second one and frees its copy.
        val second = onMain { vm.locked.value.single() }
        assertEquals("second.pdf", second.name)
        onMain { vm.dismissPassword(second) }
        assertTrue(onMain { vm.locked.value.isEmpty() })
        waitUntil("the skipped copy to be deleted") { !second.file.exists() }
        assertEquals(2, onMain { vm.inputs.value.size })
    }

    /** The user may switch to another app to find the password, and the system may stop Usugami meanwhile. */
    @Test fun lockedPdfsSurviveTheProcessBeingRestarted() {
        val copy = File(app.cacheDir, "pdf-inputs/31.pdf").apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open("protected.pdf").use { input -> copy.outputStream().use(input::copyTo) }
        val leftover = File(app.cacheDir, "pdf-inputs/32.pdf").apply { writeText("from an earlier run") }
        // PdfToolsViewModel's saved-state keys.
        val handle = SavedStateHandle(mapOf("pdf_inputs" to arrayListOf<PdfInput>(), "pdf_locked" to arrayListOf(PdfInput(31, copy.path, "statement.pdf", 0))))

        val vm = newViewModel(handle)
        waitUntil("leftovers from an earlier run to be removed") { !leftover.exists() }
        assertTrue("The locked PDF's copy is kept", copy.isFile)
        val pending = onMain { vm.locked.value.single() }
        assertEquals("statement.pdf", pending.name)

        onMain { vm.unlock(pending, "test123") }
        waitUntil("the PDF to be opened") { vm.inputs.value.size == 1 }
        assertEquals(3, onMain { vm.inputs.value.single().pageCount })
    }

    /** Going back while a PDF is being added, then opening another tool. */
    @Test fun resetAbandonsAnImportAndTheNextToolCanImport() {
        val vm = newViewModel()
        onMain {
            vm.addPdfs(listOf(pdf("protected.pdf", "abandoned.pdf")), replace = true)
            vm.reset()
        }
        assertFalse("The next tool can import straight away", onMain { vm.importing.value })

        onMain { vm.addPdfs(listOf(pdf("alpha.pdf")), replace = true) }
        waitUntil("the new PDF to be added") { !vm.importing.value && vm.inputs.value.isNotEmpty() }
        Thread.sleep(500)
        assertEquals(listOf("alpha.pdf"), onMain { vm.inputs.value.map { it.name } })
        assertTrue("No password is asked for the abandoned PDF", onMain { vm.locked.value.isEmpty() })
        assertEquals("Only the new PDF's copy is left", setOf(File(onMain { vm.inputs.value.single().path }).name), copies())
    }
}
