package io.github.nath_goa.usugami

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.nath_goa.usugami.core.PageNumberPosition
import io.github.nath_goa.usugami.core.PageNumberSize
import io.github.nath_goa.usugami.core.PageSize
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.WatermarkColor
import io.github.nath_goa.usugami.data.WatermarkOpacity
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.ui.PdfTool
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Add watermark, Add page numbers, Flatten PDF and Word, PowerPoint and EPUB to PDF with several
 * files, through the view model: the same choices for all, each file's own name, the files made into
 * PDFs in turn, and a file that can't be made named, saying why, while the others are made.
 */
class BatchToolsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val models = ArrayList<PdfToolsViewModel>()

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 60_000
        while (!onMain(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()) = onMain { PdfToolsViewModel(app, handle) }.also { models += it }

    private val picked = File(app.cacheDir, "output/test-picked")

    /** A file the way a picker gives it: a content:// URI with a display name. */
    private fun uri(file: File): Uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)

    /** A test file from the assets, picked as [name]. */
    private fun asset(fixture: String, name: String = fixture): Uri {
        val file = File(picked, name).apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) }
        return uri(file)
    }

    /** A file made by [make], picked as [name]. */
    private fun made(name: String, make: (File) -> File): Uri = uri(make(File(picked, name).apply { parentFile!!.mkdirs() }))

    /** In Usugami's language, which may not be English. */
    private val strings get() = AppLanguages.localized(app).resources

    private fun nextMessage(vm: PdfToolsViewModel) = runBlocking { withTimeout(30_000) { vm.messages.first() } }

    private fun add(vm: PdfToolsViewModel, vararg uris: Uri) {
        onMain { vm.addPdfs(uris.toList(), replace = false) }
        waitUntil("the PDFs to be added") { !vm.importing.value }
    }

    private fun addDocuments(vm: PdfToolsViewModel, kind: OfficeKind, vararg uris: Uri) {
        onMain { vm.addDocuments(uris.toList(), kind) }
        waitUntil("the files to be added") { !vm.importing.value }
    }

    /** Runs [run] and waits until it is done. */
    private fun process(vm: PdfToolsViewModel, run: PdfToolsViewModel.() -> Unit) {
        onMain { vm.run() }
        waitUntil("the PDFs to be made") { !vm.processing.value }
    }

    private fun text(file: File): String = PDDocument.load(file).use { PDFTextStripper().getText(it) }

    /** Each page's digits in its bottom-right corner, where page numbers go there (A4 pages). */
    private fun cornerNumbers(file: File): List<String> = PDDocument.load(file).use { doc ->
        (1..doc.numberOfPages).map { number ->
            val glyphs = ArrayList<TextPosition>()
            object : PDFTextStripper() {
                override fun processTextPosition(text: TextPosition) {
                    glyphs += text
                }
            }.apply {
                startPage = number
                endPage = number
            }.getText(doc)
            glyphs.filter { it.unicode.single().isDigit() && it.x > 595 - 100 && it.y > 842 - 72 }.joinToString("") { it.unicode }
        }
    }

    @After fun tearDown() {
        for (vm in models) {
            onMain { vm.reset() }
            // Its cleanup would delete the copies the next test makes.
            runBlocking { vm.awaitFileWork() }
        }
        picked.deleteRecursively()
    }

    @Test fun everyPdfGetsTheWatermarkUnderItsOwnName() {
        val vm = newViewModel()
        add(vm, asset("alpha.pdf"), asset("beta.pdf"), asset("protected.pdf", "statement.pdf"))
        onMain { vm.unlock(vm.locked.value.single(), "test123") }
        waitUntil("the PDF with a password to be opened") { vm.inputs.value.size == 3 }
        val (alpha, beta, statement) = onMain { vm.inputs.value }
        assertEquals(
            "Each PDF is named after itself",
            listOf("alpha_watermarked", "beta_watermarked", "statement_watermarked"),
            onMain { vm.inputs.value.map { vm.outputName(PdfTool.WATERMARK, it) } },
        )

        // Text no font can draw is refused at once, for all of them.
        process(vm) { runWatermarkAll("͸͹", WatermarkColor.GRAY, WatermarkOpacity.TRANSLUCENT) }
        assertEquals(strings.getString(R.string.watermark_no_font), nextMessage(vm))
        assertNull(onMain { vm.result.value })

        onMain { vm.setBatchName(statement.id, "Statement, marked") }
        // Damaged once picked: it can't be read any more.
        beta.file.writeText("%PDF-1.4 nothing else")
        process(vm) { runWatermarkAll("Draft", WatermarkColor.RED, WatermarkOpacity.OPAQUE) }
        assertEquals(strings.getQuantityString(R.plurals.batch_failed, 1, 1, "beta.pdf"), nextMessage(vm))
        val files = onMain { vm.result.value!! }
        assertEquals("The others are made", listOf("alpha_watermarked.pdf", "Statement, marked.pdf"), files.map { it.name })
        assertEquals(listOf(alpha.pageCount, statement.pageCount), files.map { it.pageCount })
        // Text running across the page comes out in pieces: without the spaces and line breaks between them.
        for (file in files) assertEquals("On every page of ${file.name}", file.pageCount, Regex("Draft").findAll(text(file.file).filterNot(Char::isWhitespace)).count())
        assertTrue("A PDF with a password was picked", onMain { vm.passwordRemoved.value })
        assertNull("No progress once done", onMain { vm.batchProgress.value })
        assertEquals("Watermarked_PDFs.zip", onMain { vm.zipFileName() })
    }

    @Test fun pagesToSkipGoForEveryPdfThatHasThem() {
        val vm = newViewModel()
        // Three pages, two pages, and one page.
        add(vm, asset("alpha.pdf"), asset("beta.pdf"), made("cover.pdf") { compactPdf(it, "COVER") })

        // Pages to skip are read against the longest PDF.
        process(vm) { runPageNumbersAll(PageNumberPosition.BOTTOM_RIGHT, PageNumberSize.MEDIUM, "4") }
        assertEquals(strings.getString(R.string.pdf_pages_range, 3), nextMessage(vm))
        process(vm) { runPageNumbersAll(PageNumberPosition.BOTTOM_RIGHT, PageNumberSize.MEDIUM, "1-3") }
        assertEquals("Every page of every PDF skipped", strings.getString(R.string.page_numbers_all_skipped), nextMessage(vm))
        assertNull(onMain { vm.result.value })

        // A skipped cover: the one-page PDF has nothing left to number, and is left out, saying so.
        process(vm) { runPageNumbersAll(PageNumberPosition.BOTTOM_RIGHT, PageNumberSize.MEDIUM, "1, 3") }
        assertEquals(strings.getQuantityString(R.plurals.batch_nothing_to_number, 1, 1, "cover.pdf"), nextMessage(vm))
        val files = onMain { vm.result.value!! }
        assertEquals(listOf("alpha_numbered.pdf", "beta_numbered.pdf"), files.map { it.name })
        assertEquals("Each page its own number, the skipped ones none", listOf("", "2", ""), cornerNumbers(files[0].file))
        assertEquals(listOf("", "2"), cornerNumbers(files[1].file))
    }

    @Test fun pdfsWithNothingToFlattenAreLeftOutAndNamed() {
        val vm = newViewModel()
        add(vm, made("form.pdf") { formPdf(it) }, asset("alpha.pdf"), made("amounts.pdf") { unencodableFormPdf(it) })
        process(vm) { runFlattenAll() }
        // First the PDF left out, then what happened to another.
        assertEquals(strings.getQuantityString(R.plurals.batch_nothing_to_flatten, 1, 1, "alpha.pdf"), nextMessage(vm))
        assertEquals(
            strings.getString(R.string.file_problem, "amounts.pdf", strings.getQuantityString(R.plurals.flatten_fields_kept, 2, 2)),
            nextMessage(vm),
        )
        val files = onMain { vm.result.value!! }
        assertEquals(listOf("form_flattened.pdf", "amounts_flattened.pdf"), files.map { it.name })
        assertTrue(text(files[0].file).contains("Jane Doe"))
        PDDocument.load(files[0].file).use { doc -> assertTrue(doc.documentCatalog.acroForm?.fields.isNullOrEmpty()) }

        // With nothing to flatten in any of them, there is no result.
        val plain = newViewModel()
        add(plain, asset("alpha.pdf"), asset("beta.pdf"))
        process(plain) { runFlattenAll() }
        assertEquals(strings.getQuantityString(R.plurals.batch_nothing_to_flatten, 2, 2, "alpha.pdf, beta.pdf"), nextMessage(plain))
        assertNull(onMain { plain.result.value })
    }

    /** One PDF works as it always has, with its own messages. */
    @Test fun onePdfIsWorkedOnAsBefore() {
        val vm = newViewModel()
        add(vm, asset("alpha.pdf"))
        process(vm) { runPageNumbersAll(PageNumberPosition.BOTTOM_RIGHT, PageNumberSize.MEDIUM, "1-3") }
        assertEquals(strings.getString(R.string.page_numbers_all_skipped), nextMessage(vm))
        process(vm) { runFlattenAll() }
        assertEquals(strings.getString(R.string.flatten_nothing), nextMessage(vm))
        process(vm) { runWatermarkAll("Copy", WatermarkColor.BLUE, WatermarkOpacity.SEMI) }
        assertEquals(listOf("alpha_watermarked.pdf"), onMain { vm.result.value!!.map { it.name } })
    }

    @Test fun severalWordFilesAreConvertedEachIntoItsOwnPdf() {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        // An old binary .doc among them is left out, named, and the others are added.
        val old = made("minutes.doc") { it.apply { writeBytes(OLD_DOC + ByteArray(1024)) } }
        addDocuments(vm, OfficeKind.WORD, asset("report.docx", "Garden report.docx"), old, asset("report.docx", "Second.docx"), asset("report.docx", "Third.docx"))
        assertEquals(strings.getString(R.string.file_problem, "minutes.doc", strings.getString(R.string.office_legacy_word)), nextMessage(vm))
        assertEquals(listOf("Garden report.docx", "Second.docx", "Third.docx"), onMain { vm.documents.value.map { it.name } })
        val (garden, second, third) = onMain { vm.documents.value }
        assertEquals(listOf("Garden report", "Second", "Third"), onMain { vm.documents.value.map(vm::outputName) })

        // Named as the first will be: the second is told apart. One removed is forgotten.
        onMain {
            vm.setBatchName(second.id, "Garden report")
            vm.removeDocument(third.id)
        }
        waitUntil("the copy to be deleted") { !third.file.exists() }
        assertEquals(listOf(garden.id, second.id), onMain { vm.batch.value.files.map { it.id } })

        // The files and their names survive Android stopping Usugami in the background.
        val again = newViewModel(handle)
        runBlocking { again.awaitFileWork() }
        assertEquals(listOf("Garden report.docx", "Second.docx"), onMain { again.documents.value.map { it.name } })
        assertEquals("Garden report", onMain { again.outputName(second) })

        process(again) { runConvertAll() }
        val files = onMain { again.result.value!! }
        assertEquals(listOf("Garden report.pdf", "Garden report (2).pdf"), files.map { it.name })
        for (file in files) {
            assertEquals(5, file.pageCount)
            assertTrue(text(file.file).contains("Riverside Community Garden"))
        }
        assertNull(onMain { again.batchProgress.value })
        assertNull(onMain { again.convertProgress.value })
        assertFalse(onMain { again.passwordRemoved.value })
        assertTrue("Working files removed", !File(app.cacheDir, "office-work").exists())
    }

    @Test fun aFileThatCantBeConvertedIsNamedAndTheOthersAreMade() {
        val vm = newViewModel()
        addDocuments(vm, OfficeKind.POWERPOINT, asset("deck.pptx"), asset("deck.pptx", "broken.pptx"))
        // Damaged once picked.
        onMain { vm.documents.value[1] }.file.writeText("not a presentation any more")
        process(vm) { runConvertAll() }
        assertEquals(strings.getQuantityString(R.plurals.batch_failed, 1, 1, "broken.pptx"), nextMessage(vm))
        assertEquals(listOf("deck.pdf"), onMain { vm.result.value!!.map { it.name } })
    }

    @Test fun booksShareThePageSizeChosen() {
        val vm = newViewModel()
        addDocuments(vm, OfficeKind.EPUB, made("Tiny Tales.epub") { book(it, "Tiny Tales") }, made("Short Stories.epub") { book(it, "Short Stories") })
        process(vm) { runConvertAll(PageSize.A4) }
        val files = onMain { vm.result.value!! }
        assertEquals(listOf("Tiny Tales.pdf", "Short Stories.pdf"), files.map { it.name })
        for (file in files) {
            PDDocument.load(file.file).use { doc ->
                val box = doc.getPage(0).mediaBox
                assertEquals(595f, box.width, 1.5f)
                assertEquals(842f, box.height, 1.5f)
            }
        }
        assertTrue(text(files[1].file).contains("Short Stories"))
    }

    /** A small EPUB 3 book called [title], with one chapter. */
    private fun book(file: File, title: String): File = file.also {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="book.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("book.opf", """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>$title</dc:title><dc:identifier id="id">x</dc:identifier></metadata><manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/></manifest><spine><itemref idref="c1"/></spine></package>""")
            put("c1.xhtml", """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>1</title></head><body><h1>$title</h1><p>Once upon a time.</p></body></html>""")
        }
        file.writeBytes(out.toByteArray())
    }

    private companion object {
        /** How an old binary Office file (.doc, .ppt) starts. */
        val OLD_DOC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    }
}
