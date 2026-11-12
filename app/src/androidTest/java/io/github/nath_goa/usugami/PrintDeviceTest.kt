package io.github.nath_goa.usugami

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import io.github.nath_goa.usugami.core.RecentFile
import io.github.nath_goa.usugami.data.AppLanguage
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.PdfPrinting
import io.github.nath_goa.usugami.data.PdfSaver
import io.github.nath_goa.usugami.data.RecentFiles
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException

/**
 * Print: the PDF is handed to Android's print system byte for byte, and not at all once it is
 * cancelled; a listed file is looked at before printing, and one deleted can't be read; and the print
 * screen opens from the activity as the screens' Print opens it, also with a language picked, where
 * below Android 13 the activity works on a copy of its context that Android won't print for.
 */
class PrintDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext
    private val dir = File(app.cacheDir, "test-print").apply { mkdirs() }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    /** The app whose window is in front, as far as the screen reader's service can tell. */
    private fun front(): String? = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()

    // The test PDFs are written with PDFBox's own fonts, which it reads from Usugami's resources.
    @Before fun setUp() = PDFBoxResourceLoader.init(app)

    @After fun tearDown() {
        // The language picked outlives the activity: forgotten, as the other tests do.
        if (AppLanguages.reset(app)) Thread.sleep(1_000)
        dir.deleteRecursively()
    }

    @Test fun thePdfIsCopiedAsItIsUntilCancelled() {
        assertEquals(app.packageManager.hasSystemFeature(PackageManager.FEATURE_PRINTING), PdfPrinting.available(app))
        val pdf = compactPdf(File(dir, "Copied.pdf"), "COPIED")
        val out = ByteArrayOutputStream()
        assertTrue(PdfPrinting.copy({ FileInputStream(pdf) }, out) { false })
        assertArrayEquals(pdf.readBytes(), out.toByteArray())
        // Cancelled before anything is read: nothing is written.
        val cancelled = ByteArrayOutputStream()
        assertFalse(PdfPrinting.copy({ FileInputStream(pdf) }, cancelled) { true })
        assertEquals(0, cancelled.size())
        // A file that has gone is a failure, for the print screen to tell of.
        assertThrows(FileNotFoundException::class.java) {
            PdfPrinting.copy({ FileInputStream(File(dir, "Missing.pdf")) }, ByteArrayOutputStream()) { false }
        }
    }

    @Test fun aListedPdfCanBeReadUntilItIsDeleted() {
        assumeTrue("Android 10 and newer save into Downloads", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val recent = RecentFiles.of(app)
        val pdf = compactPdf(File(dir, "Print test ${System.currentTimeMillis()}.pdf"), "LISTED")
        val copy = PdfSaver(app).saveToDownloads(listOf(pdf)).single()
        var row: RecentFile? = null
        try {
            val listed = recent.files.value.first { it.uri == copy.uri.toString() }.also { row = it }
            assertTrue(recent.canRead(listed))
            // What is printed is the PDF as it was saved.
            assertArrayEquals(pdf.readBytes(), recent.open(listed).use { it.readBytes() })
            app.contentResolver.delete(copy.uri, null, null)
            assertFalse("Deleted in another app", recent.canRead(listed))
        } finally {
            app.contentResolver.delete(copy.uri, null, null)
            row?.let(recent::remove)
            runBlocking { recent.awaitWrites() }
        }
    }

    @Test fun printingStartsFromTheActivity() {
        assumeTrue("The phone prints", PdfPrinting.available(app))
        val pdf = compactPdf(File(dir, "Test.pdf"), "PRINTED")
        // With a language picked, as every user has once past the first screen: below Android 13 the
        // activity then works on a copy of its context, which Android won't print for (without one, it
        // keeps Android's own and this would pass for the wrong reason). From Android 13 Android applies
        // the language itself and the activity keeps its own context, so none is picked there: one set
        // now would reach the activity a moment after it starts and recreate it under this test.
        AppLanguages.skipChoice(app, AppLanguage.ENGLISH.takeIf { Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU })
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        var printScreen: String? = null
        try {
            waitUntil("Usugami to show") { front() == app.packageName }
            // The function the screens' Print goes through.
            assertTrue("Printing starts", onMain { activity.print("Test.pdf") { FileInputStream(pdf) } })
            waitUntil("the print screen to cover Usugami") {
                printScreen = front()?.takeIf { it != app.packageName }
                printScreen != null
            }
        } finally {
            onMain { activity.finish() }
            waitUntil("the activity to be gone") { activity.isDestroyed }
        }
        // Android closes a print screen whose app screen has gone.
        waitUntil("the print screen to close") { front() != printScreen }
    }
}
