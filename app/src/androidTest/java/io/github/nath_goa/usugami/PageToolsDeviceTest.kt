package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.nath_goa.usugami.data.NothingToFlatten
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Delete pages, Reorganize PDF and Flatten PDF, checked by reading the result back and by rendering it. */
class PageToolsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val engine = PdfToolsEngine(app)
    private val results = File(app.getExternalFilesDir(null), "test-results").apply { mkdirs() }
    private var nextId = 3001L
    private var vm: PdfToolsViewModel? = null

    @After fun tearDown() {
        vm?.let { onMain { it.reset() } }
        File(app.cacheDir, "output/test-picked").deleteRecursively()
    }

    private fun import(file: File): PdfInput = engine.import(Uri.fromFile(file), nextId++)

    private fun made(name: String) = File(app.cacheDir, name)

    private fun asset(name: String): File = made(name).also { target ->
        instrumentation.context.assets.open(name).use { input -> target.outputStream().use(input::copyTo) }
    }

    private fun save(file: File, name: String): File = file.copyTo(File(results, name), overwrite = true)

    private fun text(file: File): String = PDDocument.load(file).use { PDFTextStripper().getText(it) }

    /** Each page's text, without its line breaks. */
    private fun pageTexts(file: File): List<String> = PDDocument.load(file).use { doc ->
        (1..doc.numberOfPages).map { number ->
            PDFTextStripper().apply {
                startPage = number
                endPage = number
            }.getText(doc).trim()
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!onMain(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    /** A test PDF the way a picker gives it: a content:// URI with a display name. */
    private fun picked(file: File, name: String = file.name): Uri {
        val copy = File(app.cacheDir, "output/test-picked/$name").apply { parentFile!!.mkdirs() }
        file.copyTo(copy, overwrite = true)
        return FileProvider.getUriForFile(app, "${app.packageName}.files", copy)
    }

    @Test fun deletedPagesAreGoneFromTheFile() {
        val source = import(chapters(made("chapters.pdf")))
        assertTrue("The fixture holds the words checked below", everythingIn(source.file).contains("SECRET VALUE"))
        val result = engine.deletePages(source, listOf(3, 1), "chapters_pages_deleted")
        assertEquals("chapters_pages_deleted.pdf", result.file.name)
        val deleted = save(result.file, "pages_deleted.pdf")
        assertEquals(listOf("PAGE ONE", "PAGE THREE", "PAGE FIVE"), pageTexts(deleted))

        // Nothing of pages 2 and 4 is left anywhere in the file: not their text, not page 4's form field.
        val everything = everythingIn(deleted)
        for (gone in listOf("PAGE TWO", "PAGE FOUR", "SECRET VALUE", "Chapter TWO", "Chapter FOUR")) {
            assertFalse("\"$gone\" is still in the file", everything.contains(gone))
        }
        PDDocument.load(deleted).use { doc ->
            // Their bookmarks went; the others still lead to their pages.
            val outline = doc.documentCatalog.documentOutline
            val items = outline.children().toList()
            assertEquals(listOf("Chapter ONE", "Chapter THREE", "Chapter FIVE"), items.map { it.title })
            assertEquals(3, outline.cosObject.getInt(COSName.COUNT))
            for ((index, item) in items.withIndex()) assertSame(doc.getPage(index).cosObject, item.findDestinationPage(doc).cosObject)
            // The link to page 3 still works; the one to page 4 leads nowhere now.
            val links = doc.getPage(0).annotations.filterIsInstance<PDAnnotationLink>()
            assertEquals(2, links.size)
            assertSame(doc.getPage(1).cosObject, (links[0].destination as PDPageDestination).page.cosObject)
            assertNull((links[1].destination as PDPageDestination).page)
            // Page 4's field went with it; page 1's stays, filled in.
            val form = doc.documentCatalog.acroForm
            assertEquals(listOf("keep"), form.fields.map { it.fullyQualifiedName })
            assertEquals("KEEP VALUE", form.getField("keep").valueAsString)
        }
    }

    @Test fun mergePagesLeftOutAreGoneFromTheFile() {
        val chapters = import(chapters(made("merge_chapters.pdf")))
        val alpha = import(asset("alpha.pdf"))
        val pages = listOf(PdfPageRef(chapters.id, 0), PdfPageRef(alpha.id, 0), PdfPageRef(chapters.id, 2))
        val merged = save(engine.merge(listOf(chapters, alpha), pages, "merged").file, "merged_pages.pdf")
        assertEquals(3, pageTexts(merged).size)
        // The pages left out of the merge took their bookmarks and form fields with them.
        val everything = everythingIn(merged)
        for (gone in listOf("PAGE TWO", "PAGE FOUR", "PAGE FIVE", "SECRET VALUE", "Chapter FOUR", "ALPHA TWO")) {
            assertFalse("\"$gone\" is still in the file", everything.contains(gone))
        }
        assertTrue(everything.contains("KEEP VALUE"))
    }

    /**
     * Merge's page mode shares its page list and moves with Reorganize PDF (which inserts pages
     * and keeps Undo): pages of PDFs added to a merge still go at the end, the moves still land,
     * and no copy or scratch file is left once the merge is done and the tool is left.
     */
    @Test fun mergePageModeStillWorksAndLeavesNoWorkingFiles() {
        val inputDir = File(app.cacheDir, "pdf-inputs")
        val outputDir = File(app.cacheDir, "output/tools")
        val scratchDir = File(app.cacheDir, "pdfbox")
        val vm = onMain { PdfToolsViewModel(app, SavedStateHandle()) }.also { vm = it }
        onMain { vm.reset() }
        waitUntil("earlier copies to be cleared") { inputDir.list().isNullOrEmpty() }

        onMain { vm.addPdfs(listOf(picked(asset("alpha.pdf")), picked(asset("beta.pdf"))), replace = false) }
        waitUntil("both PDFs to be added") { vm.inputs.value.size == 2 && !vm.importing.value }
        val (alpha, beta) = onMain { vm.inputs.value }
        val a = (0..2).map { PdfPageRef(alpha.id, it) }
        val b = (0..1).map { PdfPageRef(beta.id, it) }
        assertEquals("Merge adds pages at the end", a + b, onMain { vm.pages.value })
        assertEquals(setOf("${alpha.id}.pdf", "${beta.id}.pdf"), inputDir.list()!!.toSet())

        // The buttons, a drag, a turn and a page left out.
        onMain {
            vm.movePageBy(b[0], -3)
            vm.removePage(a[1])
            vm.rotatePage(a[2], 90)
            vm.beginPageDrag()
            vm.movePage(b[1], a[0])
            vm.endPageDrag()
        }
        val order = listOf(b[0], b[1], a[0], a[2].copy(rotation = 90))
        assertEquals(order, onMain { vm.pages.value })

        onMain { vm.runMerge(pageMode = true, fileName = "merged_pages") }
        waitUntil("the merge") { vm.result.value != null && !vm.processing.value }
        val result = onMain { vm.result.value!!.single() }
        assertEquals("merged_pages.pdf", result.file.name)
        val merged = save(result.file, "merge_page_mode.pdf")
        val texts = pageTexts(merged).map { page -> page.filterNot(Char::isWhitespace) }
        assertEquals(4, texts.size)
        for ((index, expected) in listOf("BETAONE", "BETATWO", "ALPHAONE", "ALPHATHREE").withIndex()) {
            assertTrue("Page ${index + 1} says ${texts[index]}", texts[index].contains(expected))
        }
        val everything = everythingIn(merged)
        assertTrue("The words are findable in the file", everything.contains("ALPHA THREE"))
        assertFalse("The page left out is gone", everything.contains("ALPHA TWO"))
        PDDocument.load(merged).use { doc -> assertEquals(listOf(0, 0, 0, 90), doc.pages.map { it.rotation }) }

        // Only the result is in the output folder, and PDFBox left no scratch files.
        assertEquals(listOf("merged_pages.pdf"), outputDir.list()!!.toList())
        assertTrue("Scratch files: ${scratchDir.list()?.toList()}", scratchDir.list().isNullOrEmpty())

        // Back to editing, a PDF removed: its copy goes with its pages.
        onMain {
            vm.backToEditing()
            vm.removeInput(beta.id)
        }
        assertEquals(listOf(a[0], a[2].copy(rotation = 90)), onMain { vm.pages.value })
        waitUntil("beta's copy to be deleted") { inputDir.list()?.toSet() == setOf("${alpha.id}.pdf") }

        // Leaving the tool frees everything else.
        onMain { vm.reset() }
        waitUntil("the copies and the result to be deleted") { inputDir.list().isNullOrEmpty() && outputDir.list().isNullOrEmpty() }
        assertFalse(onMain { vm.canUndo.value })
        assertTrue(onMain { vm.pages.value.isEmpty() })
    }

    @Test fun deletingEveryPageIsRefused() {
        val source = import(asset("alpha.pdf"))
        try {
            engine.deletePages(source, listOf(0, 1, 2), "none")
            fail("A PDF must keep a page")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(File(app.cacheDir, "output/tools").listFiles().isNullOrEmpty())
    }

    @Test fun reorganizeKeepsOrderTurnsAddedAndBlankPages() {
        val main = import(nestedPages(made("nested.pdf")))
        val extra = import(asset("beta.pdf"))
        val pages = listOf(
            PdfPageRef(main.id, 2, 90),
            PdfPageRef(extra.id, 0),
            PdfPageRef(main.id, 0),
            PdfPageRef.blank(0, 300f, 400f),
            PdfPageRef(extra.id, 1, 180),
        )
        val reorganized = save(engine.reorganize(main, listOf(extra), pages, "nested_reorganized").file, "reorganized.pdf")
        // Without spaces and line breaks: text on a page turned sideways comes out a letter a line.
        val texts = pageTexts(reorganized).map { page -> page.filterNot(Char::isWhitespace) }
        assertEquals(5, texts.size)
        for ((index, expected) in listOf("MAINTHREE", "BETAONE", "MAINONE", "", "BETATWO").withIndex()) {
            if (expected.isEmpty()) assertEquals("Page ${index + 1} is blank", "", texts[index])
            else assertTrue("Page ${index + 1} says ${texts[index]}", texts[index].contains(expected))
        }
        assertFalse("The page left out is gone", everythingIn(reorganized).contains("MAIN TWO"))
        PDDocument.load(reorganized).use { doc ->
            val result = doc.pages.toList()
            // Turned on top of the turn they had: MAIN ONE keeps the quarter turn it inherited.
            assertEquals(listOf(90, 0, 90, 0, 180), result.map { it.rotation })
            // MAIN ONE keeps the US Letter size it inherited too.
            assertEquals(PDRectangle.LETTER.width, result[2].mediaBox.width, 0.01f)
            assertEquals(PDRectangle.LETTER.height, result[2].mediaBox.height, 0.01f)
            assertEquals(300f, result[3].mediaBox.width, 0.01f)
            assertEquals(400f, result[3].mediaBox.height, 0.01f)
            // MAIN ONE's link to MAIN THREE still leads there, now page 1.
            val link = result[2].annotations.filterIsInstance<PDAnnotationLink>().single()
            assertSame(result[0].cosObject, (link.destination as PDPageDestination).page.cosObject)
        }
        // Shown as a viewer shows them.
        val first = render(reorganized, 0)
        assertTrue("A page turned a quarter is shown sideways", first.width > first.height)
    }

    @Test fun reorganizeAddsPdfPagesWhereChosenAndUndoes() {
        val vm = onMain { PdfToolsViewModel(app, SavedStateHandle()) }.also { vm = it }
        onMain { vm.addPdfs(listOf(picked(asset("alpha.pdf"))), replace = true) }
        waitUntil("alpha to be added") { vm.inputs.value.size == 1 && !vm.importing.value }
        val alpha = onMain { vm.pages.value }
        assertFalse("Nothing to undo yet", onMain { vm.canUndo.value })

        // Pages of another PDF go after the chosen page.
        onMain {
            vm.prepareInsert(alpha[0])
            vm.addPdfs(listOf(picked(asset("beta.pdf"))), replace = false)
        }
        waitUntil("beta to be added") { vm.inputs.value.size == 2 && !vm.importing.value }
        val beta = onMain { vm.inputs.value[1] }
        assertEquals(listOf(alpha[0], PdfPageRef(beta.id, 0), PdfPageRef(beta.id, 1), alpha[1], alpha[2]), onMain { vm.pages.value })
        assertTrue(onMain { vm.canUndo.value })
        onMain { vm.undo() }
        assertEquals(alpha, onMain { vm.pages.value })
        assertFalse(onMain { vm.canUndo.value })

        // A PDF with a password goes through the password dialog, then in after the chosen page.
        onMain {
            vm.prepareInsert(alpha[1])
            vm.addPdfs(listOf(picked(asset("protected.pdf"))), replace = false)
        }
        waitUntil("the password to be asked") { vm.locked.value.size == 1 && !vm.importing.value }
        onMain { vm.unlock(vm.locked.value.single(), "test123") }
        waitUntil("the protected PDF to be added") { vm.inputs.value.size == 3 }
        val locked = onMain { vm.inputs.value[2] }
        assertEquals(listOf(alpha[0], alpha[1]) + (0..2).map { PdfPageRef(locked.id, it) } + alpha[2], onMain { vm.pages.value })

        // A blank page takes the size its neighbour shows: an A4 page, then the same turned sideways.
        onMain { vm.addBlankPage(alpha[0]) }
        waitUntil("the blank page") { vm.pages.value.getOrNull(1)?.isBlank == true }
        val blank = onMain { vm.pages.value[1] }
        val a4 = PDDocument.load(asset("alpha.pdf")).use { it.getPage(0).cropBox }
        assertEquals(a4.width, blank.width, 0.5f)
        assertEquals(a4.height, blank.height, 0.5f)
        onMain { vm.rotatePage(vm.pages.value.last(), 90) }
        onMain { vm.addBlankPage(null) }
        waitUntil("the blank page at the end") { vm.pages.value.last().isBlank }
        val sideways = onMain { vm.pages.value.last() }
        assertEquals(a4.height, sideways.width, 0.5f)
        assertEquals(a4.width, sideways.height, 0.5f)

        // A page dropped, then the lot saved.
        onMain { vm.removePage(vm.pages.value.first { it == alpha[1] }) }
        onMain { vm.runReorganize("together") }
        waitUntil("the result") { vm.result.value != null }
        assertTrue("Made from a PDF with a password", onMain { vm.passwordRemoved.value })
        val saved = save(onMain { vm.result.value!!.single().file }, "reorganized_added.pdf")
        // alpha 1, blank, the protected copy of alpha (1, 2, 3), alpha 3 turned, blank; alpha 2 removed.
        val texts = pageTexts(saved).map { page -> page.filterNot(Char::isWhitespace) }
        assertEquals(7, texts.size)
        for ((index, expected) in listOf("ALPHAONE", "", "ALPHAONE", "ALPHATWO", "ALPHATHREE", "ALPHATHREE", "").withIndex()) {
            if (expected.isEmpty()) assertEquals("Page ${index + 1} is blank", "", texts[index])
            else assertTrue("Page ${index + 1} says ${texts[index]}", texts[index].contains(expected))
        }
        PDDocument.load(saved).use { doc ->
            assertFalse(doc.isEncrypted)
            assertEquals(90, doc.getPage(5).rotation)
            assertEquals(a4.height, doc.getPage(6).mediaBox.width, 0.5f)
        }
    }

    /** Page [index] of [file] as shown, one pixel per point, on white. */
    private fun render(file: File, index: Int): Bitmap =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(index).use { page ->
                    Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    /** How many pixels of [bitmap] in the page's own rectangle ([left], [bottom], [right], [top], y up) [match]. */
    private fun count(bitmap: Bitmap, left: Int, bottom: Int, right: Int, top: Int, match: (Int) -> Boolean): Int {
        var found = 0
        for (y in bitmap.height - top until bitmap.height - bottom) for (x in left until right) if (match(bitmap.getPixel(x, y))) found++
        return found
    }

    private fun red(pixel: Int) = Color.red(pixel) > 200 && Color.green(pixel) < 80 && Color.blue(pixel) < 80
    private fun blue(pixel: Int) = Color.blue(pixel) > 200 && Color.red(pixel) < 80 && Color.green(pixel) < 80

    @Test fun flattenBakesFieldsAndCommentsIntoThePage() {
        val source = import(formPdf(made("form.pdf")))
        save(source.file, "form.pdf")
        // Field values aren't part of the page's own text until flattened.
        assertFalse(text(source.file).contains("Jane Doe"))
        val result = engine.flatten(source, "form_flattened")
        assertFalse(result.droppedDynamicXfa)
        val flat = save(result.file.file, "flattened.pdf")
        val content = text(flat)
        assertTrue("The filled-in name is part of the page: $content", content.contains("Jane Doe"))
        assertTrue("The field that had no appearance was drawn: $content", content.contains("Lisbon"))
        assertTrue(content.contains("FORM PAGE"))
        PDDocument.load(flat).use { doc ->
            val form = doc.documentCatalog.acroForm
            assertTrue("No fields left", form == null || form.fields.isEmpty())
            val annotations = doc.getPage(0).annotations
            assertTrue("No widgets left", annotations.none { it is PDAnnotationWidget })
            // Only the link stays, still going where it went.
            val link = annotations.single() as PDAnnotationLink
            assertEquals("https://example.org/form", (link.action as PDActionURI).uri)
        }
        // The comment and the ticked box are drawn on the page now.
        val bitmap = render(flat, 0)
        assertTrue("The red square", count(bitmap, 360, 410, 490, 490, ::red) > 5000)
        assertTrue("The ticked box", count(bitmap, 72, 520, 86, 534, ::blue) > 50)
    }

    @Test fun nothingToFlattenIsSaid() {
        val plain = import(asset("alpha.pdf"))
        try {
            engine.flatten(plain, "plain")
            fail("A PDF without fields or comments has nothing to flatten")
        } catch (e: NothingToFlatten) {
            assertFalse(e.dynamicXfa)
        }
        val xfaOnly = import(xfaOnlyPdf(made("xfa_only.pdf")))
        try {
            engine.flatten(xfaOnly, "xfa")
            fail("A dynamic XFA form alone can't be flattened")
        } catch (e: NothingToFlatten) {
            assertTrue(e.dynamicXfa)
        }
        assertTrue(File(app.cacheDir, "output/tools").listFiles().isNullOrEmpty())
    }

    @Test fun dynamicXfaIsDroppedAndTheRestFlattened() {
        val source = import(formPdf(made("xfa_form.pdf"), name = "Ana Lima", xfa = true))
        val result = engine.flatten(source, "xfa_flattened")
        assertTrue(result.droppedDynamicXfa)
        val flat = save(result.file.file, "flattened_xfa.pdf")
        assertTrue(text(flat).contains("Ana Lima"))
        PDDocument.load(flat).use { doc ->
            assertFalse(doc.documentCatalog.cosObject.containsKey(COSName.getPDFName("NeedsRendering")))
            val form = doc.documentCatalog.acroForm
            assertTrue(form == null || (!form.hasXFA() && form.fields.isEmpty()))
        }
    }

    @Test fun cancellingFlattenLeavesNoOutput() {
        class Stop : RuntimeException()
        val source = import(formPdf(made("form_cancel.pdf")))
        try {
            engine.flatten(source, "cancelled") { throw Stop() }
            fail("Flattening should have been stopped")
        } catch (_: Stop) {
        }
        assertTrue(File(app.cacheDir, "output/tools").listFiles().isNullOrEmpty())
    }
}
