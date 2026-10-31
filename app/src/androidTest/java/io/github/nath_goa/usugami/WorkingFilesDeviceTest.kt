package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.ImageStore
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.office.OfficeInput
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Working files live in the cache, which the system may clear while Usugami is in the background.
 * After the process is restored, items whose files are gone must be dropped (with a message).
 * The copies (images, PDFs, Word and PowerPoint files) must outlive the activity being recreated,
 * and go when the user leaves or discards them.
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

    /** A small picture outside Usugami's working folders, to import. */
    private fun photo(name: String): Uri {
        val file = File(app.cacheDir, "test-photos/$name").apply { parentFile!!.mkdirs() }
        file.outputStream().use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        return Uri.fromFile(file)
    }

    private fun imageCopies(): Set<String> = File(app.cacheDir, "images").list().orEmpty().toSet()

    /** Creates the view model in [store], the way the activity does, so clearing the store clears it. */
    private inline fun <reified T : ViewModel> inStore(store: ViewModelStore, crossinline create: () -> T): T =
        onMain {
            ViewModelProvider(store, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
            })[T::class.java]
        }

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

    @Test fun restoredDocumentsAreKeptOrDroppedAndResetFreesThem() {
        val kept = file("office-inputs/21.docx")
        val leftover = file("office-inputs/20.pptx")
        val work = file("office-work/part-1.pdf")
        // "document" is PdfToolsViewModel's saved-state key for the Word or PowerPoint file.
        fun restore(input: OfficeInput) = onMain { PdfToolsViewModel(app, SavedStateHandle(mapOf("document" to input))) }

        val vm = restore(OfficeInput(21, kept.path, "notes.docx", OfficeKind.WORD))
        waitUntil("an earlier session's files to be removed") { !leftover.exists() && !work.exists() }
        assertTrue(kept.isFile)
        assertEquals(21L, vm.document.value?.id)
        // Leaving the tool frees the copy.
        onMain { vm.reset() }
        waitUntil("the copy to be deleted") { !kept.exists() }
        assertNull(vm.document.value)

        // The system cleared the cache while Usugami was away: the document is dropped, with a message.
        val gone = File(app.cacheDir, "office-inputs/22.docx").apply { delete() }
        val restored = restore(OfficeInput(22, gone.path, "gone.docx", OfficeKind.WORD))
        waitUntil("the missing document to be dropped") { restored.document.value == null }
        val message = runBlocking { withTimeout(10_000) { restored.messages.first() } }
        assertTrue(message, message.contains("cleared"))
    }

    @Test fun copiesSurviveTheActivityBeingDestroyedWithoutFinishing() {
        val image = file("images/11.jpg")
        val pdf = file("pdf-inputs/12.pdf")
        fun images() = SavedStateHandle(mapOf("items" to arrayListOf(ImageItem(11, image.path, "a.jpg", ImageFormat.JPEG, 10, 10, 1))))
        fun pdfs() = SavedStateHandle(mapOf("pdf_inputs" to arrayListOf(PdfInput(12, pdf.path, "a.pdf", 1))))

        // "Don't keep activities", or the system reclaiming the activity: the view models are cleared,
        // but the saved state still lists the copies for the next activity.
        ViewModelStore().also { store ->
            inStore(store) { MainViewModel(app, images()) }
            inStore(store) { PdfToolsViewModel(app, pdfs()) }
            onMain { store.clear() }
        }
        Thread.sleep(500)
        assertTrue("The image copy is kept", image.isFile)
        assertTrue("The PDF copy is kept", pdf.isFile)

        // Leaving for good frees them.
        ViewModelStore().also { store ->
            val vm = inStore(store) { MainViewModel(app, images()) }
            val pdfVm = inStore(store) { PdfToolsViewModel(app, pdfs()) }
            onMain {
                vm.activityFinishing()
                pdfVm.activityFinishing()
                store.clear()
            }
        }
        waitUntil("the copies to be deleted") { !image.exists() && !pdf.exists() }
    }

    @Test fun finishingTheActivityFreesTheCopies() {
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity
        val vm = onMain { ViewModelProvider(activity)[MainViewModel::class.java] }
        val photo = photo("finish.jpg")
        onMain { vm.addImages(listOf(photo)) }
        waitUntil("the image to be imported") { vm.items.value.size == 1 }
        val copy = File(vm.items.value.single().path)
        assertTrue(copy.isFile)

        onMain { activity.finish() }
        waitUntil("the copy to be deleted") { !copy.exists() }
    }

    @Test fun imagesStillImportingWhenDiscardedDontComeBack() {
        val vm = onMain { MainViewModel(app, SavedStateHandle()) }
        val photos = listOf(photo("1.jpg"), photo("2.jpg"), photo("3.jpg"))
        onMain {
            vm.addImages(photos)
            // The first image is being copied now.
            vm.clearAll()
        }
        assertEquals(0, onMain { vm.pendingImports.value })

        val later = photo("4.jpg")
        onMain { vm.addImages(listOf(later)) }
        waitUntil("only the later image to be there") {
            vm.items.value.size == 1 && imageCopies() == setOf(File(vm.items.value.single().path).name)
        }
        Thread.sleep(500)
        assertEquals("The discarded images stay discarded", 1, onMain { vm.items.value.size })
        assertEquals(0, onMain { vm.pendingImports.value })
    }

    @Test fun pngWithAHugeExifChunkStillImports() {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        // Before IEND (the last 12 bytes), an EXIF chunk claiming to be 2 GB: ExifInterface allocates
        // that much before reading it, and runs out of memory.
        val chunk = byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xF0.toByte()) + "eXIfMM".toByteArray()
        val file = File(app.cacheDir, "test-photos/huge-exif.png").apply { parentFile!!.mkdirs() }
        file.writeBytes(png.copyOf(png.size - 12) + chunk + png.copyOfRange(png.size - 12, png.size))

        val result = ImageStore(app).import(Uri.fromFile(file), 21)
        assertTrue("$result", result is ImageStore.ImportResult.Added)
        val item = (result as ImageStore.ImportResult.Added).item
        assertEquals(8, item.width)
        assertEquals(1, item.exifOrientation)
        File(item.path).delete()
    }
}
