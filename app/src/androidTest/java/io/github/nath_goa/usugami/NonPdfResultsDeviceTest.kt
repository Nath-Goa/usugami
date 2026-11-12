package io.github.nath_goa.usugami

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.ToolMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Results that are not PDFs, through the view model: a job may also end without any file, and then
 * no result shows.
 */
class NonPdfResultsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val models = ArrayList<PdfToolsViewModel>()

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

    @After fun tearDown() {
        for (vm in models) {
            onMain { vm.reset() }
            // Its cleanup would delete the files the next test makes.
            runBlocking { vm.awaitFileWork() }
        }
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
