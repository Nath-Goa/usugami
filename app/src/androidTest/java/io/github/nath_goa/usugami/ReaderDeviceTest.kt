package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import io.github.nath_goa.usugami.core.ReadFrom
import io.github.nath_goa.usugami.core.SharedKind
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.ReaderDocument
import io.github.nath_goa.usugami.data.ReaderFiles
import io.github.nath_goa.usugami.data.Redactor
import io.github.nath_goa.usugami.ui.Found
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfTool
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Files opened in the reader ("Open with", Home's Read and annotate), through the view models: what
 * a file is is told by its content, then by its type and name; it is copied in at once and waits for
 * the reader, also when Android restarts Usugami; the file opened last is the one wanted; a Word file
 * is made into the PDF the reader shows, which Save as PDF makes a result of; the copy handed to
 * another app (Share, Open with another app) is whole, named as a PDF, and one PDF's at a time. And the
 * reader's drawing: the sharp part of a page drawn when zoomed lines up with the whole page's picture,
 * as does what Find text finds; a long PDF is searched a few pages at a time from the page showing.
 */
class ReaderDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val models = ArrayList<PdfToolsViewModel>()
    private val picked = File(app.cacheDir, "output/test-picked")
    private val inbox = File(app.cacheDir, "shared")

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (!onMain(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    private fun main(handle: SavedStateHandle = SavedStateHandle()) = onMain { MainViewModel(app, handle) }.also { runBlocking { it.awaitShares() } }

    private fun tools(handle: SavedStateHandle = SavedStateHandle()) = onMain { PdfToolsViewModel(app, handle) }.also { models += it }

    /** A file the way another app has it: a content:// URI with a name, whose type the provider tells by the name. */
    private fun shared(name: String, fixture: String? = null, text: String = "not a PDF"): Uri {
        val file = File(picked, name).apply { parentFile!!.mkdirs() }
        if (fixture != null) instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) } else file.writeText(text)
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /** In Usugami's language, which may not be English. */
    private val strings get() = AppLanguages.localized(app).resources

    private fun next(messages: Flow<String>) = runBlocking { withTimeout(30_000) { messages.first() } }

    /** Opens [uri] in [vm] as [from] does, and waits until it is in, or has failed. */
    private fun open(vm: MainViewModel, uri: Uri?, type: String? = null, from: ReadFrom = ReadFrom.OUTSIDE) {
        onMain { vm.open(uri, type, from) }
        runBlocking { vm.awaitShares() }
    }

    @After fun tearDown() {
        for (vm in models) {
            onMain { vm.reset() }
            runBlocking { vm.awaitFileWork() }
        }
        picked.deleteRecursively()
        inbox.deleteRecursively()
    }

    @Test fun aFileOpenedIsCopiedInAndWaitsForTheReader() {
        val vm = main()
        open(vm, shared("Report.pdf", "alpha.pdf"), "application/pdf")
        assertEquals(ReadFrom.OUTSIDE.name, onMain { vm.reading.value })
        val waiting = onMain { vm.sharedFiles.value }.single()
        assertEquals(SharedKind.PDF, waiting.kind)
        assertEquals("Report.pdf", waiting.name)
        // The copy is Usugami's own: the app that opened the file may take it back.
        picked.deleteRecursively()
        assertTrue(waiting.file.isFile)

        // Android restarts Usugami while it waits: it is still there, still for the reader.
        val restored = main(SavedStateHandle(mapOf("shared_files" to arrayListOf(waiting), "reading" to ReadFrom.OUTSIDE.name)))
        assertEquals(listOf(waiting), onMain { restored.sharedFiles.value })
        assertEquals(ReadFrom.OUTSIDE.name, onMain { restored.reading.value })
        assertFalse(onMain { restored.takeOpenInterrupted() })

        val tools = tools()
        onMain { tools.addShared(vm.takeSharedFiles()) }
        waitUntil("the file opened to be taken in") { !tools.importing.value }
        val input = onMain { tools.inputs.value }.single()
        assertEquals("Report.pdf", input.name)
        assertEquals(3, input.pageCount)
        assertNull("Taken by the reader", onMain { vm.reading.value })

        // Restarted while the file was still being copied in, Usugami says so, and nothing waits.
        val cut = main(SavedStateHandle(mapOf("reading" to ReadFrom.HOME.name, "opening" to true)))
        assertNull(onMain { cut.reading.value })
        assertTrue(onMain { cut.takeOpenInterrupted() })
        assertFalse("Said once", onMain { cut.takeOpenInterrupted() })
    }

    @Test fun whatAFileIsIsToldByItsContent() {
        val vm = main()
        // A Word document without an ending, which the provider then calls binary data.
        open(vm, shared("report", "report.docx"))
        assertEquals(listOf("report" to SharedKind.WORD), onMain { vm.sharedFiles.value.map { it.name to it.kind } })
        // A PDF called a text file.
        open(vm, shared("notes.txt", "alpha.pdf"))
        assertEquals(listOf("notes.txt" to SharedKind.PDF), onMain { vm.sharedFiles.value.map { it.name to it.kind } })
        assertEquals(ReadFrom.OUTSIDE.name, onMain { vm.reading.value })

        // A text file is no kind the reader opens: said, naming it, and nothing waits.
        open(vm, shared("list.txt", text = "milk, eggs"))
        assertEquals(strings.getString(R.string.reader_cant_open, "list.txt"), next(vm.messages))
        assertTrue(onMain { vm.sharedFiles.value.isEmpty() })
        assertNull(onMain { vm.reading.value })
        assertEquals("The PDF opened before went", emptyList<String>(), inbox.list().orEmpty().toList())

        // An address Usugami can't read.
        open(vm, null)
        assertEquals(strings.getString(R.string.reader_unreadable), next(vm.messages))
        assertNull(onMain { vm.reading.value })

        // A PDF Usugami may not read, whose app gives it no name or type: its address's ending says
        // what it is, so it is a file that couldn't be read, not one of a kind Usugami doesn't open.
        open(vm, Uri.parse("content://${app.packageName}.nowhere/files/report.pdf"))
        assertEquals(strings.getString(R.string.reader_unreadable), next(vm.messages))
        assertNull(onMain { vm.reading.value })
    }

    @Test fun theLatestFileWins() {
        val vm = main()
        open(vm, shared("First.pdf", "alpha.pdf"))
        val first = onMain { vm.sharedFiles.value }.single()
        open(vm, shared("Second.pdf", "beta.pdf"), from = ReadFrom.HOME)
        assertEquals(listOf("Second.pdf"), onMain { vm.sharedFiles.value.map { it.name } })
        assertEquals(ReadFrom.HOME.name, onMain { vm.reading.value })
        assertFalse("The file it replaced is deleted", first.file.exists())

        // A PDF shared while the file opened waits takes its place, for Choose a tool.
        onMain { vm.share(listOf(shared("Shared.pdf", "alpha.pdf")), "application/pdf") }
        runBlocking { vm.awaitShares() }
        assertEquals(listOf("Shared.pdf" to SharedKind.PDF), onMain { vm.sharedFiles.value.map { it.name to it.kind } })
        assertNull(onMain { vm.reading.value })
        assertEquals(1, inbox.list()!!.size)
    }

    @Test fun aWordFileIsMadeIntoThePdfTheReaderShows() {
        val vm = main()
        open(vm, shared("report.docx", "report.docx"))
        val tools = tools()
        onMain { tools.addShared(vm.takeSharedFiles()) }
        waitUntil("the document opened to be taken in") { !tools.importing.value }
        assertEquals(listOf("report.docx"), onMain { tools.documents.value.map { it.name } })

        // Made into a PDF for the reader, which takes the document's place: not a result, nothing saved.
        onMain { tools.convertForReader() }
        waitUntil("the PDF to be made") { !tools.importing.value }
        val input = onMain { tools.inputs.value }.single()
        assertEquals("report.pdf", input.name)
        assertTrue(input.pageCount > 0)
        assertTrue(input.file.isFile)
        assertTrue("No document left", onMain { tools.documents.value.isEmpty() })
        assertEquals(input.id to "report.docx", onMain { tools.reader.source.value })
        assertNull("Nothing is saved yet", onMain { tools.result.value })
        // Handed to Choose a tool, it is still the document's PDF.
        onMain { tools.clearToolWork(PdfTool.READER) }
        assertEquals(listOf(input), onMain { tools.inputs.value })
        assertEquals(input.id to "report.docx", onMain { tools.reader.source.value })

        // Save as PDF: a result named after the document, and the reader's PDF stays.
        onMain { tools.runSaveCopy() }
        waitUntil("the PDF saved as a result") { tools.result.value != null && !tools.processing.value }
        val made = onMain { tools.result.value }!!.single()
        assertEquals("report.pdf", made.name)
        assertEquals(input.pageCount, made.pageCount)
        assertTrue(made.file.isFile)
        assertEquals(listOf(input), onMain { tools.inputs.value })
        assertTrue("Still there to read", input.file.isFile)

        // An old binary .doc: the converter's own message, and neither document nor PDF.
        val old = File(picked, "minutes.doc").apply { writeBytes(OLD_DOC + ByteArray(1024)) }
        open(vm, FileProvider.getUriForFile(app, "${app.packageName}.files", old))
        val other = tools()
        onMain { other.addShared(vm.takeSharedFiles()) }
        assertEquals(strings.getString(R.string.office_legacy_word), next(other.messages))
        waitUntil("the old document to be put away") { !other.importing.value }
        assertTrue(onMain { other.documents.value.isEmpty() && other.inputs.value.isEmpty() })
    }

    @Test fun aCopyForAnotherAppIsWholeNamedAsAPdfAndOnlyTheLastPdfs() {
        val bytes = ByteArray(200 * 1024) { it.toByte() }
        val file = File(picked, "opened").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val folder = File(app.cacheDir, "output/reader")
        try {
            // A PDF opened as "notes.txt" is handed over as a PDF, with the same bytes.
            val notes = PdfInput(41, file.path, "notes.txt", 1)
            val copy = ReaderFiles.shareCopy(app, notes)
            assertEquals("notes.txt.pdf", copy.name)
            assertTrue(copy.readBytes().contentEquals(bytes))
            // The same PDF again: the copy made before, not copied again.
            assertEquals(copy, ReaderFiles.shareCopy(app, notes) { error("Copied again") })

            // Another PDF, its copy stopped part of the way: the first PDF's copy has gone, and nothing is left of this one.
            val report = PdfInput(42, file.path, "Report.pdf", 1)
            var chunks = 0
            val stopped = runCatching { ReaderFiles.shareCopy(app, report) { if (++chunks == 2) throw IOException("Stopped") } }
            assertTrue(stopped.exceptionOrNull() is IOException)
            assertFalse(copy.parentFile!!.exists())
            assertEquals(emptyList<String>(), File(folder, "42").list()!!.toList())
            // Made whole, it keeps its name.
            assertEquals("Report.pdf", ReaderFiles.shareCopy(app, report).name)
        } finally {
            ReaderFiles.clear(app)
        }
        assertFalse(folder.exists())
    }

    /** The bounds of [bitmap]'s dark pixels within [area], or null when there are none. */
    private fun darkIn(bitmap: Bitmap, area: Rect): Rect? {
        val row = IntArray(area.width())
        var found: Rect? = null
        for (y in area.top until area.bottom) {
            bitmap.getPixels(row, 0, row.size, area.left, y, row.size, 1)
            for (i in row.indices) {
                if (Color.red(row[i]) >= 128) continue
                val x = area.left + i
                found = found?.apply { union(x, y, x + 1, y + 1) } ?: Rect(x, y, x + 1, y + 1)
            }
        }
        return found
    }

    @Test fun partsOfAPageAreDrawnWhereTheWholePageHasThem() {
        // A page turned a quarter and cropped to a box of no whole size (300.5 × 200.5 points as
        // shown), so that the two ways a renderer may take a transform (the page as it is, or squeezed
        // to whole points) draw it differently. In its lower right quarter as shown: "needle" and a
        // black square, the square near the far corner.
        PDFBoxResourceLoader.init(app)
        val file = File(picked, "turned.pdf").apply { parentFile!!.mkdirs() }
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(400f, 500f))
            page.cropBox = PDRectangle(50f, 100f, 200.5f, 300.5f)
            page.rotation = 90
            doc.addPage(page)
            PDPageContentStream(doc, page).use { content ->
                content.addRect(200f, 350f, 30f, 30f)
                content.fill()
            }
            writeText(doc, page, "needle", x = 160f, y = 270f)
            doc.save(file)
        }
        val sizes = tools().engine.shownPageSizes(PdfInput(1, file.path, "turned.pdf", 1))
        assertEquals(listOf(300.5f to 200.5f), sizes)
        val document = ReaderDocument.open(file, sizes) { PDDocument.load(file) }
        try {
            // The whole page at 8 pixels a point, and its lower right quarter at the same scale. (At 8,
            // the half point a renderer squeezes off a side would move the square's far edge by over 3
            // pixels; at 2, by less than the pixel the edges may differ by.)
            val whole = runBlocking { document.page(0, 2404, 1604) }!!
            val part = runBlocking { document.part(0, 8f, 1202, 802, 1202, 802) }!!
            val inWhole = darkIn(whole, Rect(1202, 802, 2404, 1604))
            val inPart = darkIn(part, Rect(0, 0, 1202, 802))
            // The word and the square are there, the word's corner and the square's far corner making the bounds.
            assertTrue("Nothing dark in the quarter: $inWhole", inWhole != null && inWhole.width() > 600 && inWhole.height() > 400)
            checkNotNull(inPart) { "Nothing dark in the part" }
            inWhole!!.offset(-1202, -802)
            for ((edge, pair) in listOf("left" to (inWhole.left to inPart.left), "top" to (inWhole.top to inPart.top), "right" to (inWhole.right to inPart.right), "bottom" to (inWhole.bottom to inPart.bottom))) {
                assertTrue("The $edge edge: $inWhole in the page, $inPart in the part", kotlin.math.abs(pair.first - pair.second) <= 1)
            }

            // The box Find text gives for "needle", searched on that page alone, covers the word in the
            // page's picture (left of the square, which is near the far corner) and nothing of the square.
            val box = PDDocument.load(file).use { Redactor.findOnPages(it, "needle", 0..0) {} }.matches.single().single()
            val boxed = Rect(floor(box.left * 2404).toInt(), floor(box.top * 1604).toInt(), ceil(box.right * 2404).toInt(), ceil(box.bottom * 1604).toInt())
            val word = checkNotNull(darkIn(whole, Rect(0, 0, 1800, 1604))) { "The word isn't drawn" }
            val square = checkNotNull(darkIn(whole, Rect(1800, 0, 2404, 1604))) { "The square isn't drawn" }
            assertTrue("The word $word in the box $boxed", boxed.left <= word.left + 1 && boxed.top <= word.top + 1 && boxed.right >= word.right - 1 && boxed.bottom >= word.bottom - 1)
            assertFalse("The square $square in the box $boxed", Rect.intersects(boxed, square))
            whole.recycle()
            part.recycle()
        } finally {
            document.close()
        }
    }

    @Test fun aLongPdfIsSearchedAFewPagesAtATime() {
        // 40 pages, each with "needle" on it.
        PDFBoxResourceLoader.init(app)
        val file = File(picked, "long.pdf").apply { parentFile!!.mkdirs() }
        PDDocument().use { doc ->
            repeat(40) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                writeText(doc, page, "needle")
            }
            doc.save(file)
        }
        val tools = tools()
        onMain { tools.addPdfs(listOf(FileProvider.getUriForFile(app, "${app.packageName}.files", file)), replace = true) }
        waitUntil("the PDF to be taken in") { tools.inputs.value.size == 1 && !tools.importing.value }
        onMain { tools.reader.show(tools.inputs.value.single()) }
        waitUntil("the PDF to be ready to show") { tools.reader.session.value != null }
        val session = onMain { tools.reader.session.value!! }

        // From page 31 on: its match is marked as soon as the first pages searched (31 to 40) are in.
        // What was found is watched on the main thread, where each lot of pages comes in on its own.
        val seen = ArrayList<Found>()
        val watching = MainScope().launch { session.found.collect { seen += it } }
        onMain { session.find("needle", from = 30) }
        waitUntil("the search to end") { session.found.value.let { it.query == "needle" && !it.searching } }
        watching.cancel()
        val first = onMain { seen.first { it.current >= 0 } }
        val marked = first.matches[first.current]
        assertEquals(30, marked.first().page)
        assertEquals("The pages from 31 on first", 10, first.pagesDone)
        assertTrue(first.searching)

        // All 40 in the end, in page order, the same match still marked.
        val done = onMain { session.found.value }
        assertEquals((0 until 40).toList(), done.matches.map { it.first().page })
        assertEquals(marked, done.matches[done.current])
        assertEquals(40, done.pagesDone)
        assertEquals(0, done.unread)
        // Followed once, when it was marked, not again as the pages before it came in.
        assertEquals(first.moves, done.moves)

        // Next, and round from the last to the first; Previous, back to the last. Each is followed.
        onMain { session.step(1) }
        assertEquals(31, onMain { session.found.value.let { it.matches[it.current].first().page } })
        assertEquals(done.moves + 1, onMain { session.found.value.moves })
        onMain { repeat(9) { session.step(1) } }
        assertEquals(0, onMain { session.found.value.let { it.matches[it.current].first().page } })
        onMain { session.step(-1) }
        assertEquals(39, onMain { session.found.value.let { it.matches[it.current].first().page } })

        // Stopped while it searches: nothing found, and nothing said, also once the text's thread has done
        // what it was given.
        onMain {
            session.find("needle", from = 0)
            session.stopFinding()
        }
        runBlocking { session.document.find("needle", 0..0) {} }
        Thread.sleep(500)
        assertEquals(Found(), onMain { session.found.value })
        assertNull(runBlocking { withTimeoutOrNull(1_000) { tools.messages.first() } })
    }

    private companion object {
        /** How an old binary Word file (.doc) starts: an OLE compound file. */
        val OLD_DOC = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
    }
}
