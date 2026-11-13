package io.github.nath_goa.usugami

import android.app.Application
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import io.github.nath_goa.usugami.core.Compressor
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.GrayOutcome
import io.github.nath_goa.usugami.data.PdfCompression
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.ui.KeptResult
import io.github.nath_goa.usugami.ui.PdfTool
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Compress PDF with several PDFs, through the view model: each PDF's level and name, kept through
 * the process being restarted, the PDFs compressed in turn (one that fails named, the others made),
 * PDFs with a password asked for in turn, each PDF of the result saved on its own, and Black and
 * white for all of them.
 */
class BatchCompressDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val models = ArrayList<PdfToolsViewModel>()

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

    private fun newViewModel(handle: SavedStateHandle = SavedStateHandle()) = onMain { PdfToolsViewModel(app, handle) }.also { models += it }

    private val picked = File(app.cacheDir, "output/test-picked")

    /** A test PDF the way a picker gives it: a content:// URI with a display name. */
    private fun pdf(fixture: String, name: String = fixture): Uri {
        val file = File(picked, name).apply { parentFile!!.mkdirs() }
        instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /** A one-page "scan", a photo of noise stored at high quality, which compressing shrinks a lot. */
    private fun scan(name: String): Uri {
        val file = File(picked, name).apply { parentFile!!.mkdirs() }
        val random = java.util.Random(7)
        val bitmap = Bitmap.createBitmap(IntArray(1200 * 1500) { Color.BLACK or random.nextInt(0x1000000) }, 1200, 1500, Bitmap.Config.ARGB_8888)
            .apply { setHasAlpha(false) }
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { it.drawImage(JPEGFactory.createFromImage(doc, bitmap, 0.95f), 50f, 70f, 495f, 650f) }
            doc.save(file)
        }
        bitmap.recycle()
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /** A PDF that can't get smaller (see [compactPdf]), the way a picker gives it. */
    private fun compact(name: String): Uri {
        val file = compactPdf(File(picked, name).apply { parentFile!!.mkdirs() }, "ALREADY COMPACT")
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /**
     * A one-page PDF of red text, the way a picker gives it: forty lines of it, so that the colour is
     * a few bytes of a page that Black and white can't make 1% smaller.
     */
    private fun redText(name: String): Uri {
        val file = File(picked, name).apply { parentFile!!.mkdirs() }
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            PDPageContentStream(doc, page).use { content ->
                content.beginText()
                content.setNonStrokingColor(0.8f, 0.1f, 0.1f)
                content.setFont(PDType1Font.HELVETICA, 12f)
                content.setLeading(16f)
                content.newLineAtOffset(72f, 780f)
                for (line in 1..40) {
                    content.showText("Note $line: ${line * 7919} red words, ${line * 104729 % 9973} of them about ${"abcdefghij".repeat(line % 4 + 1)}")
                    content.newLine()
                }
                content.endText()
            }
            doc.save(file)
        }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /** A one-page PDF of a gradient Black and white can't turn gray ([addUnconvertibleMeshPage]), the way a picker gives it. */
    private fun mesh(name: String): Uri {
        val file = File(picked, name).apply { parentFile!!.mkdirs() }
        PDDocument().use { doc ->
            addUnconvertibleMeshPage(doc)
            doc.save(file)
        }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /** A file that isn't a PDF at all, picked as one. */
    private fun broken(name: String): Uri {
        val file = File(picked, name).apply { parentFile!!.mkdirs(); writeText("not a PDF") }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /** In Usugami's language, which may not be English. */
    private val strings get() = AppLanguages.localized(app).resources

    private fun nextMessage(vm: PdfToolsViewModel) = runBlocking { withTimeout(30_000) { vm.messages.first() } }

    private fun add(vm: PdfToolsViewModel, vararg uris: Uri) {
        onMain { vm.addPdfs(uris.toList(), replace = false) }
        waitUntil("the PDFs to be added") { !vm.importing.value }
    }

    private fun compressAll(vm: PdfToolsViewModel) {
        onMain { vm.runCompressAll() }
        waitUntil("the PDFs to be compressed") { !vm.processing.value }
    }

    @After fun tearDown() {
        for (vm in models) {
            onMain { vm.reset() }
            // Its cleanup would delete the copies the next test makes.
            runBlocking { vm.awaitFileWork() }
        }
        picked.deleteRecursively()
    }

    /** PDFs picked together that can't be opened are named, in one message, rather than one "that PDF" for them all. */
    @Test fun pdfsPickedTogetherThatCantBeOpenedAreNamed() {
        val vm = newViewModel()
        add(vm, pdf("alpha.pdf"), broken("first broken.pdf"), broken("second broken.pdf"))
        assertEquals(listOf("alpha.pdf"), onMain { vm.inputs.value.map { it.name } })
        val expected = strings.getString(R.string.file_problem, "first broken.pdf, second broken.pdf", strings.getString(R.string.pdf_import_failed_many))
        assertEquals(expected, nextMessage(vm))
    }

    @Test fun levelsAndNamesFollowThePdfsAndSurviveTheProcessBeingRestarted() {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        add(vm, pdf("alpha.pdf"), pdf("beta.pdf"))
        val (alpha, beta) = onMain { vm.inputs.value }
        assertEquals("Each PDF is named after itself", listOf("alpha_compressed", "beta_compressed"), onMain { vm.inputs.value.map { vm.outputName(PdfTool.COMPRESS, it) } })
        assertEquals(listOf(PdfCompression.RECOMMENDED, PdfCompression.RECOMMENDED), onMain { vm.inputs.value.map(vm::compressionOf) })

        onMain {
            vm.setBatchChoiceForAll(PdfCompression.EXTREME.ordinal)
            vm.setBatchChoice(beta.id, PdfCompression.LESS.ordinal)
            vm.setBatchName(alpha.id, "Alpha, smaller")
        }
        assertEquals(PdfCompression.EXTREME, onMain { vm.compressionOf(alpha) })
        assertEquals("A PDF changed after the level for all keeps its own", PdfCompression.LESS, onMain { vm.compressionOf(beta) })
        assertNull("The levels differ", onMain { vm.batch.value.common })

        // A PDF added later starts with the level chosen for all.
        add(vm, pdf("alpha.pdf", "gamma.pdf"))
        val gamma = onMain { vm.inputs.value.last() }
        assertEquals(PdfCompression.EXTREME, onMain { vm.compressionOf(gamma) })
        onMain { vm.setBatchName(gamma.id, "Gamma") }

        // Removed, it is forgotten; the others keep theirs.
        onMain { vm.removeInput(gamma.id) }
        assertEquals(listOf(alpha.id, beta.id), onMain { vm.batch.value.files.map { it.id } })

        // Android stops Usugami in the background and starts it again from the saved state.
        val again = newViewModel(handle)
        assertEquals(listOf("alpha.pdf", "beta.pdf"), onMain { again.inputs.value.map { it.name } })
        assertEquals("Alpha, smaller", onMain { again.outputName(PdfTool.COMPRESS, alpha) })
        assertEquals("beta_compressed", onMain { again.outputName(PdfTool.COMPRESS, beta) })
        assertEquals(PdfCompression.EXTREME, onMain { again.compressionOf(alpha) })
        assertEquals(PdfCompression.LESS, onMain { again.compressionOf(beta) })

        // The level for all sets every PDF again, the one changed alone too.
        onMain { again.setBatchChoiceForAll(PdfCompression.RECOMMENDED.ordinal) }
        assertEquals(PdfCompression.RECOMMENDED.ordinal, onMain { again.batch.value.common })

        // The next tool starts afresh.
        onMain { again.reset() }
        assertTrue(onMain { again.batch.value.files.isEmpty() })
        assertEquals(PdfCompression.RECOMMENDED.ordinal, onMain { again.batch.value.all })
    }

    /** With one PDF, Compress PDF works as it always has: its name, and a message when it can't shrink. */
    @Test fun onePdfIsCompressedAsBefore() {
        val vm = newViewModel()
        add(vm, compact("notes.pdf"))
        compressAll(vm)
        val file = onMain { vm.result.value!!.single() }
        assertEquals("notes_compressed.pdf", file.name)
        assertTrue(file.keptOriginal)
        assertEquals(strings.getString(R.string.pdf_no_reduction), nextMessage(vm))
    }

    @Test fun eachPdfIsCompressedInTurnAndOneThatFailsIsNamed() {
        val vm = newViewModel()
        add(vm, compact("alpha.pdf"), scan("scan.pdf"), pdf("beta.pdf"))
        val (alpha, scan, beta) = onMain { vm.inputs.value }
        // Damaged once picked: it can't be read any more.
        beta.file.writeText("%PDF-1.4 nothing else")
        onMain {
            // Named as alpha's will be: the second is told apart.
            vm.setBatchName(scan.id, "alpha_compressed")
            vm.setBatchChoice(scan.id, PdfCompression.EXTREME.ordinal)
        }
        compressAll(vm)

        assertEquals(strings.getQuantityString(R.plurals.batch_failed, 1, 1, "beta.pdf"), nextMessage(vm))
        val files = onMain { vm.result.value!! }
        assertEquals("The others are made", listOf("alpha_compressed.pdf", "alpha_compressed (2).pdf"), files.map { it.name })
        assertTrue("An already compact PDF can't get smaller: the original is kept", files[0].keptOriginal)
        assertArrayEquals(alpha.file.readBytes(), files[0].file.readBytes())
        assertFalse(files[1].keptOriginal)
        assertTrue("The scan shrank", files[1].sizeBytes < scan.file.length() / 2)
        assertNull("No progress once done", onMain { vm.batchProgress.value })
        assertFalse(onMain { vm.passwordRemoved.value })

        // The result, and which PDFs kept their original, come back after Android restarted Usugami.
        val kept = KeptResult.pack(files)
        val restored = newViewModel(SavedStateHandle(mapOf(KeptResult.KEY to kept)))
        assertEquals(listOf(true, false), onMain { restored.result.value!!.map { it.keptOriginal } })
    }

    @Test fun withNoPdfMadeThereIsNoResultAndEachIsNamed() {
        val vm = newViewModel()
        add(vm, pdf("alpha.pdf", "first.pdf"), pdf("beta.pdf", "second.pdf"))
        onMain { vm.inputs.value }.forEach { it.file.writeText("not a PDF any more") }
        compressAll(vm)
        assertEquals(strings.getQuantityString(R.plurals.batch_failed, 2, 2, "first.pdf, second.pdf"), nextMessage(vm))
        assertNull(onMain { vm.result.value })
    }

    @Test fun pdfsWithAPasswordJoinOnceOpened() {
        val vm = newViewModel()
        add(vm, pdf("protected.pdf", "statement.pdf"), pdf("alpha.pdf"))
        assertEquals(listOf("statement.pdf"), onMain { vm.locked.value.map { it.name } })
        onMain { vm.unlock(vm.locked.value.single(), "test123") }
        waitUntil("the PDF to be opened") { vm.inputs.value.size == 2 }
        compressAll(vm)
        val files = onMain { vm.result.value!! }
        assertEquals(listOf("alpha_compressed.pdf", "statement_compressed.pdf"), files.map { it.name })
        assertTrue("The note says the new PDFs have no password", onMain { vm.passwordRemoved.value })
        PDDocument.load(files[1].file).use { assertFalse(it.isEncrypted) }
    }

    /**
     * Black and white for all the PDFs: the one with a part it couldn't turn gray is named, and so is
     * one made gray that came out no smaller; what each kept in colour comes back with the result after
     * Android restarted Usugami, and a result kept by 1.6.5, without it, comes back with none.
     */
    @Test fun blackAndWhiteSaysWhatStayedInColorAndIsKeptWithTheResult() {
        val vm = newViewModel()
        add(vm, redText("red.pdf"), mesh("mesh.pdf"))
        onMain { vm.runCompressAll(gray = true) }
        waitUntil("the PDFs to be compressed") { !vm.processing.value }

        val files = onMain { vm.result.value!! }
        assertEquals(listOf("red_compressed.pdf", "mesh_compressed.pdf"), files.map { it.name })
        assertEquals("The red text is gray", GrayOutcome(changed = true, colorPages = emptyList()), files[0].gray)
        assertEquals("The mesh's page is still partly in color", listOf(0), files[1].gray?.colorPages)
        assertEquals(strings.getQuantityString(R.plurals.batch_gray_partial, 1, 1, "mesh.pdf"), nextMessage(vm))
        assertNull("The red text is no smaller", Compressor.savedPercent(files[0].originalBytes, files[0].sizeBytes))
        assertEquals(strings.getQuantityString(R.plurals.batch_gray_not_smaller, 1, 1, "red.pdf"), nextMessage(vm))

        // Android restarts Usugami while the result shows.
        val kept = KeptResult.pack(files)
        val restored = newViewModel(SavedStateHandle(mapOf(KeptResult.KEY to kept)))
        assertEquals(files.map { it.gray }, onMain { restored.result.value!!.map { it.gray } })
        // As 1.6.5 kept a result.
        val older = newViewModel(SavedStateHandle(mapOf(KeptResult.KEY to Bundle(kept).apply { remove("gray") })))
        assertEquals(files.map { it.name }, onMain { older.result.value!!.map { it.name } })
        assertTrue(onMain { older.result.value!!.all { it.gray == null } })
    }

    /** Each PDF of the result has its own Save: into Downloads, or into a document "Save as" made. */
    @Test fun eachPdfOfTheResultIsSavedOnItsOwn() {
        val stamp = "Batch_${System.currentTimeMillis()}"
        val folder = File(app.cacheDir, "output/tools").apply { mkdirs() }
        val one = File(folder, "${stamp}_one.pdf").apply { writeText("%PDF-1.4 one") }
        val two = File(folder, "${stamp}_two.pdf").apply { writeText("%PDF-1.4 two") }
        val handle = SavedStateHandle(mapOf(KeptResult.KEY to KeptResult.pack(listOf(PdfFile(one, 1, 0), PdfFile(two, 1, 0)))))
        val vm = newViewModel(handle)
        runBlocking { vm.awaitFileWork() }
        val (first, second) = onMain { vm.result.value!! }
        val target = File(app.cacheDir, "test-saving/$stamp.pdf").apply { parentFile!!.mkdirs(); writeText("") }
        try {
            // "Save as" for the second; Android restarts Usugami while its screen is open.
            onMain { vm.prepareSaveAs(second) }
            val again = newViewModel(handle)
            runBlocking { again.awaitFileWork() }
            onMain { again.saveTo(Uri.fromFile(target)) }
            assertEquals(strings.getString(R.string.msg_saved), nextMessage(again))
            assertEquals("%PDF-1.4 two", target.readText())
            assertEquals(setOf(second.name), onMain { again.savedFiles.value })
            assertFalse("Not all are saved yet", onMain { again.saved.value })
            // Kept in the saved state as a flag for each PDF, and restored with the result.
            assertArrayEquals(booleanArrayOf(false, true), onMain { handle.get<BooleanArray>("saved_files") })
            val restored = newViewModel(handle)
            runBlocking { restored.awaitFileWork() }
            assertEquals(setOf(second.name), onMain { restored.savedFiles.value })

            assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            onMain { again.saveOneToDownloads(first) }
            assertEquals(strings.getString(R.string.msg_saved_downloads, first.name), nextMessage(again))
            assertEquals(setOf(first.name, second.name), onMain { again.savedFiles.value })
            assertTrue("Each has been saved", onMain { again.saved.value })
            // Saved again, alone or with the others, it isn't copied twice.
            onMain { again.saveOneToDownloads(first) }
            nextMessage(again)
            onMain { again.saveToDownloads() }
            assertEquals(strings.getString(R.string.pdf_saved_downloads), nextMessage(again))
            assertEquals(listOf(first.name, second.name), downloads(stamp).map { it.second }.sorted())
        } finally {
            downloads(stamp).forEach { app.contentResolver.delete(it.first, null, null) }
            target.delete()
        }
    }

    /**
     * Save to Downloads that fails partway (here a PDF that can't be read; the storage running out, as
     * it happens) keeps the PDFs it copied before: they are marked saved, and saving again doesn't
     * copy them twice.
     */
    @Test fun aSaveToDownloadsThatFailsPartwayKeepsWhatItCopied() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val stamp = "Partway_${System.currentTimeMillis()}"
        val folder = File(app.cacheDir, "output/tools").apply { mkdirs() }
        val pdfs = listOf("a", "b", "c").map { File(folder, "${stamp}_$it.pdf").apply { writeText("%PDF-1.4 $it") } }
        val handle = SavedStateHandle(mapOf(KeptResult.KEY to KeptResult.pack(pdfs.map { PdfFile(it, 1, 0) })))
        val vm = newViewModel(handle)
        runBlocking { vm.awaitFileWork() }
        val files = onMain { vm.result.value!! }
        try {
            assertTrue(pdfs[1].setReadable(false, false))
            onMain { vm.saveToDownloads() }
            assertEquals(strings.getString(R.string.msg_save_failed), nextMessage(vm))
            assertEquals("The PDF copied before the failure is saved", setOf(files[0].name), onMain { vm.savedFiles.value })
            assertFalse(onMain { vm.saved.value })
            assertEquals(listOf(files[0].name), downloads(stamp).map { it.second })

            pdfs[1].setReadable(true, false)
            onMain { vm.saveToDownloads() }
            assertEquals(strings.getString(R.string.pdf_saved_downloads), nextMessage(vm))
            assertTrue(onMain { vm.saved.value })
            assertEquals("None copied twice", files.map { it.name }, downloads(stamp).map { it.second }.sorted())
        } finally {
            pdfs[1].setReadable(true, false)
            downloads(stamp).forEach { app.contentResolver.delete(it.first, null, null) }
        }
    }

    /** Usugami's PDFs in Downloads whose names start with [prefix]: their entries and names. */
    private fun downloads(prefix: String): List<Pair<Uri, String>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val columns = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
        return app.contentResolver.query(collection, columns, "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("$prefix%"), null)!!.use { c ->
            buildList { while (c.moveToNext()) add(ContentUris.withAppendedId(collection, c.getLong(0)) to c.getString(1)) }
        }
    }
}
