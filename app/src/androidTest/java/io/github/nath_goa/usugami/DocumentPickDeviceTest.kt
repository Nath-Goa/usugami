package io.github.nath_goa.usugami

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.core.SharedKind
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.office.PickedDocuments
import io.github.nath_goa.usugami.ui.MainViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The files picked with Home's Documents to PDF, sorted through the view model: the kind most of
 * them are is taken, the others and those Usugami can't use are named, and a file whose type and
 * name say nothing is known by its content.
 */
class DocumentPickDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val picked = File(app.cacheDir, "output/test-picked")

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun main() = onMain { MainViewModel(app, SavedStateHandle()) }.also { runBlocking { it.awaitShares() } }

    /** A file the way a picker gives it: a content:// URI with a display name, and a type by its extension. */
    private fun file(name: String, fixture: String? = null, bytes: ByteArray = "not a document".toByteArray()): Uri {
        val file = File(picked, name).apply { parentFile!!.mkdirs() }
        if (fixture != null) instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) } else file.writeBytes(bytes)
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /** In Usugami's language, which may not be English. */
    private val strings get() = AppLanguages.localized(app).resources

    private fun next(messages: Flow<String>) = runBlocking { withTimeout(30_000) { messages.first() } }

    /** Picks [uris] and returns what is offered once they are sorted. */
    private fun pick(vm: MainViewModel, vararg uris: Uri): PickedDocuments {
        onMain { vm.pickDocuments(uris.toList()) }
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (true) {
            onMain { vm.takePickedDocuments() }?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for the files to be sorted" }
            Thread.sleep(50)
        }
    }

    @After fun tearDown() {
        picked.deleteRecursively()
    }

    @Test fun theKindMostOfTheFilesAreIsTakenAndTheOthersNamed() {
        val vm = main()
        val deck = file("deck.pptx", "deck.pptx")
        val deck2 = file("Deck 2.pptx", "deck.pptx")
        onMain { vm.pickDocuments(listOf(file("report.docx", "report.docx"), deck, deck2, file("notes.txt"))) }
        // Said in the order a share says them, before the converter opens.
        assertEquals(strings.getQuantityString(R.plurals.share_unsupported, 1, 1, "notes.txt"), next(vm.messages))
        assertEquals(strings.getQuantityString(R.plurals.share_other_kind, 1, 1, "report.docx"), next(vm.messages))
        val offered = onMain { vm.takePickedDocuments() }!!
        assertEquals(SharedKind.POWERPOINT, offered.kind)
        assertEquals(listOf(deck, deck2), offered.uris)
        assertNull("Taken once", onMain { vm.takePickedDocuments() })
    }

    @Test fun aFileWhoseTypeAndNameSayNothingIsKnownByItsContent() {
        val vm = main()
        // Without an extension, the test's file provider calls each of them just binary data.
        val report = file("report", "report.docx")
        assertEquals(SharedKind.WORD, pick(vm, report).kind)

        val book = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("mimetype"))
                zip.write("application/epub+zip".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val epub = file("book", bytes = book)
        val offered = pick(vm, epub)
        assertEquals(SharedKind.EPUB, offered.kind)
        assertEquals(listOf(epub), offered.uris)

        // A text file: nothing is offered, and it is named.
        onMain { vm.pickDocuments(listOf(file("readme"))) }
        assertEquals(strings.getQuantityString(R.plurals.share_unsupported, 1, 1, "readme"), next(vm.messages))
        assertNull(onMain { vm.takePickedDocuments() })
    }
}
