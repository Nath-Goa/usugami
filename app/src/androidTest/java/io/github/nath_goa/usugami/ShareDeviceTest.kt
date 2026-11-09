package io.github.nath_goa.usugami

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.core.SharedKind
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.office.OfficeKind
import io.github.nath_goa.usugami.ui.MainViewModel
import io.github.nath_goa.usugami.ui.PdfTool
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.SplitMode
import io.github.nath_goa.usugami.ui.officeKind
import io.github.nath_goa.usugami.ui.takesShared
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Files shared to Usugami from another app, through the view models: sorted by kind (the kind most
 * of them are taken, the others and those Usugami can't use named), copied in at once, kept while
 * they wait and when the process is restarted, and taken by the tool they open in, PDFs with a
 * password asked for and files that can't be used named.
 */
class ShareDeviceTest {
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

    /** A file the way another app shares it: a content:// URI with a display name and a type. */
    private fun shared(name: String, fixture: String? = null, text: String = "not a PDF"): Uri {
        val file = File(picked, name).apply { parentFile!!.mkdirs() }
        if (fixture != null) instrumentation.context.assets.open(fixture).use { input -> file.outputStream().use(input::copyTo) } else file.writeText(text)
        return FileProvider.getUriForFile(app, "${app.packageName}.files", file)
    }

    /** In Usugami's language, which may not be English. */
    private val strings get() = AppLanguages.localized(app).resources

    private fun next(messages: Flow<String>) = runBlocking { withTimeout(30_000) { messages.first() } }

    private fun share(vm: MainViewModel, vararg uris: Uri, sharedAs: String? = null) {
        onMain { vm.share(uris.toList(), sharedAs) }
        runBlocking { vm.awaitShares() }
    }

    /** Gives [vm]'s waiting files to [tools], as the screen they open in does, and waits for them to be in. */
    private fun take(vm: MainViewModel, tools: PdfToolsViewModel) {
        onMain { tools.addShared(vm.takeSharedFiles()) }
        waitUntil("the shared files to be taken in") { !tools.importing.value }
    }

    @After fun tearDown() {
        for (vm in models) {
            onMain { vm.reset() }
            runBlocking { vm.awaitFileWork() }
        }
        picked.deleteRecursively()
        inbox.deleteRecursively()
    }

    /**
     * Images shared while a tool makes its PDF, then that PDF discarded: the next tool opened doesn't
     * show the run as its own while it ends, and makes its own PDF.
     */
    @Test fun aRunLeftForSharedImagesIsNotTheNextTools() {
        val tools = tools()
        onMain { tools.addPdfs(listOf(shared("Report.pdf", "alpha.pdf")), replace = true) }
        waitUntil("the PDF to be added") { tools.inputs.value.size == 1 }
        val processing = onMain {
            tools.runSplit(SplitMode.EVERY_PAGE, "", combine = false, fileName = "Left")
            tools.reset()
            tools.processing.value
        }
        assertFalse(processing)

        onMain { tools.addPdfs(listOf(shared("Next.pdf", "alpha.pdf")), replace = true) }
        waitUntil("the next PDF to be added") { tools.inputs.value.size == 1 && !tools.importing.value }
        onMain { tools.runSplit(SplitMode.EVERY_PAGE, "", combine = false, fileName = "Next") }
        waitUntil("the next tool's PDFs") { tools.result.value != null && !tools.processing.value }
        assertTrue(onMain { tools.result.value!!.all { it.name.startsWith("Next") } })
    }

    @Test fun aSharedPdfIsCopiedInAtOnceAndTheToolTakesItWithItsName() {
        val vm = main()
        share(vm, shared("Report.pdf", "alpha.pdf"))
        val waiting = onMain { vm.sharedFiles.value }.single()
        assertEquals(SharedKind.PDF, waiting.kind)
        assertEquals("Report.pdf", waiting.name)
        // The copy is Usugami's own: the app that shared the file may take it back.
        picked.deleteRecursively()
        assertTrue(waiting.file.isFile)

        val tools = tools()
        take(vm, tools)
        val input = onMain { tools.inputs.value }.single()
        assertEquals("Report.pdf", input.name)
        assertEquals(3, input.pageCount)
        assertTrue(onMain { vm.sharedFiles.value.isEmpty() })
        assertEquals("Moved into the tool's copies, not copied again", emptyList<String>(), inbox.list().orEmpty().toList())

        // The process is restarted while Choose a tool shows: the PDF is still there.
        val restored = tools(SavedStateHandle(mapOf("pdf_inputs" to arrayListOf(input))))
        runBlocking { restored.awaitFileWork() }
        assertEquals(listOf(input), onMain { restored.inputs.value })
        assertTrue(input.file.isFile)
    }

    @Test fun filesOfAnotherKindAndFilesUsugamiCantUseAreLeftOutByName() {
        val vm = main()
        share(vm, shared("one.pdf", "alpha.pdf"), shared("Report.docx", "report.docx"), shared("two.pdf", "beta.pdf"), shared("notes.txt"))
        assertEquals(strings.getQuantityString(R.plurals.share_unsupported, 1, 1, "notes.txt"), next(vm.messages))
        assertEquals(strings.getQuantityString(R.plurals.share_other_kind, 1, 1, "Report.docx"), next(vm.messages))
        assertEquals(listOf("one.pdf", "two.pdf"), onMain { vm.sharedFiles.value.map { it.name } })
        assertEquals("Only the PDFs are copied in", 2, inbox.list()!!.size)
    }

    @Test fun theKindMostOfTheFilesAreIsTaken() {
        val vm = main()
        share(vm, shared("a.docx", "report.docx"), shared("alpha.pdf", "alpha.pdf"), shared("b.docx", "report.docx"))
        assertEquals(strings.getQuantityString(R.plurals.share_other_kind, 1, 1, "alpha.pdf"), next(vm.messages))
        val tools = tools()
        take(vm, tools)
        val documents = onMain { tools.documents.value }
        assertEquals(listOf("a.docx", "b.docx"), documents.map { it.name })
        assertTrue(documents.all { it.kind == OfficeKind.WORD && it.file.isFile })
    }

    @Test fun aFileWithoutTypeOrExtensionIsWhatTheShareSays() {
        val vm = main()
        share(vm, shared("download", "alpha.pdf"), sharedAs = "application/pdf")
        assertEquals(SharedKind.PDF, onMain { vm.sharedFiles.value }.single().kind)
        // Shared as anything at all, it can't be used.
        share(vm, shared("download2", "alpha.pdf"), sharedAs = "*/*")
        assertEquals(strings.getQuantityString(R.plurals.share_unsupported, 1, 1, "download2"), next(vm.messages))
        assertEquals(1, onMain { vm.sharedFiles.value.size })
    }

    @Test fun aSharedPdfWithAPasswordIsAskedFor() {
        val vm = main()
        share(vm, shared("statement.pdf", "protected.pdf"), shared("alpha.pdf", "alpha.pdf"))
        val tools = tools()
        take(vm, tools)
        assertEquals(listOf("statement.pdf"), onMain { tools.locked.value.map { it.name } })
        onMain { tools.unlock(tools.locked.value.single(), "test123") }
        waitUntil("the PDF to be opened") { tools.inputs.value.size == 2 }
        assertEquals(listOf("alpha.pdf", "statement.pdf"), onMain { tools.inputs.value.map { it.name } })
    }

    @Test fun sharedFilesThatCantBeOpenedAreNamed() {
        val vm = main()
        share(vm, shared("broken.pdf"), shared("alpha.pdf", "alpha.pdf"), shared("damaged.pdf"))
        val tools = tools()
        take(vm, tools)
        // Together, in one message worded for both.
        assertEquals(
            strings.getString(R.string.file_problem, "broken.pdf, damaged.pdf", strings.getString(R.string.pdf_import_failed_many)),
            next(tools.messages),
        )
        assertEquals(listOf("alpha.pdf"), onMain { tools.inputs.value.map { it.name } })
        assertEquals("Nothing is left behind", emptyList<String>(), inbox.list().orEmpty().toList())

        // One file shared is told about as one picked is.
        share(vm, shared("Old.docx", text = "not a Word file"))
        take(vm, tools)
        assertEquals(strings.getString(R.string.office_not_word), next(tools.messages))
    }

    @Test fun filesWaitingAreKeptWhenTheProcessIsRestarted() {
        val vm = main()
        share(vm, shared("Report.pdf", "alpha.pdf"), shared("Notes.pdf", "beta.pdf"))
        val waiting = onMain { vm.sharedFiles.value }
        val stale = File(inbox, "12345").apply { writeText("left from an earlier session") }
        waiting[1].file.delete()

        val restored = main(SavedStateHandle(mapOf("shared_files" to ArrayList(waiting))))
        waitUntil("the copy cleared from the cache to be dropped") { restored.sharedFiles.value.size == 1 }
        assertEquals(listOf(waiting[0]), onMain { restored.sharedFiles.value })
        assertEquals(strings.getQuantityString(R.plurals.msg_files_cleared, 1, 1), next(restored.messages))
        assertFalse("Leftovers go", stale.exists())
        assertTrue(waiting[0].file.isFile)
    }

    @Test fun filesOfAnotherKindSharedWhileOthersWaitAreLeftOutWhenTaken() {
        val vm = main()
        share(vm, shared("alpha.pdf", "alpha.pdf"))
        share(vm, shared("Talk.pptx", "deck.pptx"))
        assertEquals(listOf(SharedKind.PDF, SharedKind.POWERPOINT), onMain { vm.sharedFiles.value.map { it.kind } })
        val taken = onMain { vm.takeSharedFiles() }
        assertEquals(listOf("alpha.pdf"), taken.map { it.name })
        assertEquals(strings.getQuantityString(R.plurals.share_other_kind, 1, 1, "Talk.pptx"), next(vm.messages))
        runBlocking { vm.awaitShares() }
        assertEquals(listOf(taken.single().file.name), inbox.list()!!.toList())
    }

    /** Files that couldn't be read before they reached Usugami are told about as shared files, images as images. */
    @Test fun filesThatCouldntBeReadAreToldAboutByKind() {
        val vm = main()
        onMain { vm.share(emptyList(), "application/pdf", refused = 1) }
        assertEquals(strings.getQuantityString(R.plurals.msg_share_unreadable, 1, 1), next(vm.messages))
        onMain { vm.share(emptyList(), "image/jpeg", refused = 2) }
        assertEquals(strings.getQuantityString(R.plurals.msg_failed_import, 2, 2), next(vm.messages))
    }

    /** A PDF made by a tool, shared, isn't offered to Usugami, which would refuse to read it. */
    @Test fun aPdfMadeIsNotSharedToUsugamiItself() {
        val made = PdfFile(File(app.cacheDir, "output/tools/Made.pdf"), 1, 0)
        val tools = tools()
        val intent = onMain { tools.shareIntent(listOf(made)) }
        val excluded = IntentCompat.getParcelableArrayExtra(intent, Intent.EXTRA_EXCLUDE_COMPONENTS, ComponentName::class.java)
        assertEquals(listOf(ComponentName(app, MainActivity::class.java)), excluded?.toList())
    }

    /**
     * Leaving the screen files are coming in for stops those files only: a share still waiting its turn
     * behind them, which doesn't show as coming in yet, comes in.
     */
    @Test fun droppingTheFilesComingLeavesTheSharesBehindThem() {
        val vm = main()
        val pdf = shared("alpha.pdf", "alpha.pdf")
        val report = shared("Report.docx", "report.docx")
        onMain {
            vm.share(listOf(pdf), null)
            vm.share(listOf(report), null)
            vm.dropShares()
        }
        runBlocking { vm.awaitShares() }
        assertEquals(listOf("alpha.pdf", "Report.docx"), onMain { vm.sharedFiles.value.map { it.name } })
    }

    /**
     * Files being copied in, or taken in by a tool, and images picked are marked in the saved state
     * until they are in: when Android closes Usugami meanwhile, they are gone, and the restored view
     * models say so, once.
     */
    @Test fun filesLostWhenAndroidClosesUsugamiAreToldAbout() {
        val handle = SavedStateHandle()
        val vm = main(handle)
        val pdf = shared("alpha.pdf", "alpha.pdf")
        // Read before the share can go on: it goes on on the main thread.
        assertEquals(1, onMain { vm.share(listOf(pdf), null).let { handle.get<Int>("shares_under_way") } })
        runBlocking { vm.awaitShares() }
        assertNull("Copied in", onMain { handle.get<Int>("shares_under_way") })
        assertFalse(onMain { vm.takeShareInterrupted() })

        val toolsHandle = SavedStateHandle()
        val tools = tools(toolsHandle)
        assertNotNull(onMain { tools.addShared(vm.takeSharedFiles()).let { toolsHandle.get<Int>("taking_shared") } })
        waitUntil("the shared files to be taken in") { !tools.importing.value }
        assertNull("Taken in", onMain { toolsHandle.get<Int>("taking_shared") })
        assertEquals(listOf("alpha.pdf"), onMain { tools.inputs.value.map { it.name } })
        assertFalse(onMain { tools.takeShareInterrupted() })

        val restored = main(SavedStateHandle(mapOf("shares_under_way" to 1)))
        assertTrue(onMain { restored.takeShareInterrupted() })
        assertFalse("Said once", onMain { restored.takeShareInterrupted() })
        val restoredTools = tools(SavedStateHandle(mapOf("taking_shared" to 1)))
        assertTrue(onMain { restoredTools.takeShareInterrupted() })
        assertFalse("Said once", onMain { restoredTools.takeShareInterrupted() })
        // Images picked that weren't all added yet.
        val restoredPicks = main(SavedStateHandle(mapOf("picks_under_way" to 2)))
        assertEquals(strings.getString(R.string.msg_picks_interrupted), next(restoredPicks.messages))
    }

    @Test fun droppedFilesLeaveNothingBehind() {
        val vm = main()
        share(vm, shared("alpha.pdf", "alpha.pdf"), shared("beta.pdf", "beta.pdf"))
        onMain { vm.dropShares() }
        runBlocking { vm.awaitShares() }
        assertTrue(onMain { vm.sharedFiles.value.isEmpty() && vm.sharing.value == null })
        assertEquals(emptyList<String>(), inbox.list().orEmpty().toList())
        // A tool put away while it takes them in leaves none of them behind either.
        share(vm, shared("alpha.pdf", "alpha.pdf"), shared("beta.pdf", "beta.pdf"))
        val tools = tools()
        onMain {
            tools.addShared(vm.takeSharedFiles())
            tools.reset()
        }
        runBlocking { tools.awaitFileWork() }
        waitUntil("the copies not taken in to be deleted") { inbox.list().orEmpty().isEmpty() }
        assertEquals(emptyList<String>(), File(app.cacheDir, "pdf-inputs").list().orEmpty().toList())
    }

    @Test fun anotherToolTakesThePdfsWithoutWhatTheLastOneDid() {
        val vm = main()
        share(vm, shared("alpha.pdf", "alpha.pdf"), shared("beta.pdf", "beta.pdf"))
        val tools = tools()
        take(vm, tools)
        val (alpha, beta) = onMain { tools.inputs.value }
        onMain {
            tools.setBatchName(alpha.id, "Smaller")
            tools.rotatePage(tools.pages.value.first(), 90)
        }
        assertTrue(onMain { tools.hasUnsavedWork(PdfTool.MERGE) })
        onMain { tools.clearToolWork(PdfTool.MERGE) }
        assertEquals(listOf(alpha, beta), onMain { tools.inputs.value })
        assertFalse(onMain { tools.hasUnsavedWork(PdfTool.MERGE) })
        assertEquals("alpha_watermarked", onMain { tools.outputName(PdfTool.WATERMARK, alpha) })
        assertFalse(onMain { tools.canUndo.value })
    }

    /**
     * Back from a tool for one PDF to Choose a tool, the PDFs that tool added go with what it did: the
     * PDFs Reorganize PDF's "Add pages" added, one waiting for its password too, and their copies, are gone.
     */
    @Test fun aPdfAddedToReorganizeGoesWhenItGoesBack() {
        val vm = main()
        share(vm, shared("alpha.pdf", "alpha.pdf"))
        val tools = tools()
        take(vm, tools)
        val alpha = onMain { tools.inputs.value }.single()
        onMain {
            tools.prepareInsert(tools.pages.value.first())
            tools.addPdfs(listOf(shared("beta.pdf", "beta.pdf")), replace = false)
        }
        waitUntil("the pages to be added") { !tools.importing.value }
        val beta = onMain { tools.inputs.value }.last()
        assertTrue(onMain { tools.hasUnsavedWork(PdfTool.REORGANIZE) })
        // And one more, still waiting for its password.
        onMain { tools.addPdfs(listOf(shared("statement.pdf", "protected.pdf")), replace = false) }
        waitUntil("the PDF to wait for its password") { !tools.importing.value }
        val statement = onMain { tools.locked.value }.single()

        onMain { tools.clearToolWork(PdfTool.REORGANIZE) }
        assertEquals(listOf(alpha), onMain { tools.inputs.value })
        assertTrue(onMain { tools.locked.value.isEmpty() })
        assertEquals(alpha.pageCount, onMain { tools.pages.value.size })
        assertFalse(onMain { tools.hasUnsavedWork(PdfTool.REORGANIZE) })
        runBlocking { tools.awaitFileWork() }
        assertFalse("Its copy is deleted", beta.file.exists())
        assertFalse(statement.file.exists())
        assertTrue(alpha.file.isFile)
    }

    @Test fun choosingAToolOffersThoseThatTakeTheSharedPdfs() {
        val pdfTools = PdfTool.entries.filter { it.officeKind == null }
        assertEquals("One PDF goes to any PDF tool", pdfTools, PdfTool.entries.filter { it.takesShared(1) })
        assertEquals(
            listOf(PdfTool.MERGE, PdfTool.COMPRESS, PdfTool.PAGE_NUMBERS, PdfTool.WATERMARK, PdfTool.FLATTEN),
            PdfTool.entries.filter { it.takesShared(3) },
        )
    }
}
