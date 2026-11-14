package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.text.PDFTextStripper
import io.github.nath_goa.usugami.data.Flattening
import io.github.nath_goa.usugami.data.NothingToFlatten
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfPageRef
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.ToolJob
import io.github.nath_goa.usugami.ui.PdfTool
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.SplitMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Reorganize PDF and Flatten PDF, checked by reading the result back and by rendering it. */
class PageToolsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val engine = PdfToolsEngine(app)
    private val results = File(app.getExternalFilesDir(null), "test-results").apply { mkdirs() }
    private var nextId = 3001L
    private var vm: PdfToolsViewModel? = null

    @After fun tearDown() {
        vm?.let { vm ->
            onMain { vm.reset() }
            // Its cleanup would delete the copies the next test makes.
            runBlocking { vm.awaitFileWork() }
        }
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

    private fun waitUntil(what: String, timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
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
        val result = engine.reorganize(source, emptyList(), pagesOf(source, 0, 2, 4), "chapters_reorganized")
        assertEquals("chapters_reorganized.pdf", result.file.name)
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

    /**
     * A part of a split holds only its own pages, though their links, comments and form fields
     * point at the others: nothing of those (their text, a form field's value) comes along.
     */
    @Test fun splitPartsHoldOnlyTheirOwnPages() {
        val source = import(chapters(made("split_chapters.pdf")))
        val part = save(engine.split(source, listOf(listOf(0)), "part").single().file, "split_page_one.pdf")
        assertEquals(listOf("PAGE ONE"), pageTexts(part))
        val everything = everythingIn(part)
        for (gone in listOf("PAGE TWO", "PAGE THREE", "PAGE FOUR", "PAGE FIVE", "SECRET VALUE")) {
            assertFalse("\"$gone\" is in the part", everything.contains(gone))
        }
        assertTrue("Page 1's own field stays", everything.contains("KEEP VALUE"))

        val parts = engine.split(source, PAGE_WORDS.indices.map { listOf(it) }, "each")
        for ((index, file) in parts.withIndex()) {
            val all = everythingIn(file.file)
            for ((other, word) in PAGE_WORDS.withIndex()) {
                assertEquals("Part ${index + 1} has \"PAGE $word\"", index == other, all.contains("PAGE $word"))
            }
            assertEquals("Only page 4's part has its field", index == 3, all.contains("SECRET VALUE"))
        }

        // Fields that nest by page share their parents: each part still has only its page's values.
        val nested = import(nestedFormPdf(made("split_nested.pdf")))
        for ((index, half) in engine.split(nested, listOf(listOf(0), listOf(1)), "nested").withIndex()) {
            val all = everythingIn(half.file)
            assertEquals("Part ${index + 1} has page 1's value", index == 0, all.contains("NEAR VALUE"))
            assertEquals("Part ${index + 1} has page 2's value", index == 1, all.contains("FAR VALUE"))
        }
    }

    /**
     * Split's PDFs are named by their pages after the name typed. A single one is named as typed,
     * unless that is the PDF's own name, which it would take. Their ZIP file is named as typed, or
     * after the PDF's own name with "_split".
     */
    @Test fun splitPdfsAreNamedByTheirPages() {
        val vm = onMain { PdfToolsViewModel(app, SavedStateHandle()) }.also { vm = it }
        onMain { vm.addPdfs(listOf(picked(asset("alpha.pdf"))), replace = true) }
        waitUntil("the PDF to be added") { vm.inputs.value.size == 1 && !vm.importing.value }
        fun split(mode: SplitMode, text: String, combine: Boolean, name: String): List<String> {
            onMain { vm.runSplit(mode, text, combine, name) }
            waitUntil("the PDFs") { vm.result.value != null && !vm.processing.value }
            return onMain { vm.result.value!!.map { it.name } }
        }
        val own = onMain { vm.defaultName(PdfTool.SPLIT) }
        assertEquals("alpha", own)
        assertEquals(listOf("alpha_p1-2.pdf", "alpha_p3.pdf"), split(SplitMode.CUSTOM, "1-2;3", combine = false, own))
        assertEquals(listOf("alpha_p3,1.pdf"), split(SplitMode.SELECTED, "3,1", combine = true, own))
        assertEquals(listOf("Chosen.pdf"), split(SplitMode.SELECTED, "3,1", combine = true, "Chosen"))
        assertEquals("A ZIP file named as typed", "Chosen.zip", onMain { vm.zipFileName() })
        // Typed with ".pdf", the PDF's own name is still its own.
        assertEquals(listOf("alpha_p1.pdf", "alpha_p2.pdf", "alpha_p3.pdf"), split(SplitMode.EVERY_PAGE, "", combine = false, "alpha.pdf"))
        assertEquals("Their ZIP file", "alpha_split.zip", onMain { vm.zipFileName() })
    }

    /**
     * Page 1's links to page 3, itself and by name, still work in a part that has both, also after
     * an earlier part left page 3 out; its link to page 4, left out, leads nowhere. A link to a
     * damaged name doesn't stop the split.
     */
    @Test fun linksBetweenPagesOfAPartStillWork() {
        val file = made("split_links.pdf")
        PDDocument.load(chapters(made("split_chapters_links.pdf"))).use { doc ->
            fun leaf(name: String, value: COSBase) = COSDictionary().apply {
                setItem(COSName.NAMES, COSArray().apply { add(COSString(name)); add(value) })
                setItem(COSName.LIMITS, COSArray().apply { add(COSString(name)); add(COSString(name)) })
            }
            val names = COSArray().apply {
                add(leaf("bad", COSString("not a place")))
                add(leaf("three", PDPageFitDestination().apply { setPage(doc.getPage(2)) }.cosObject))
            }
            doc.documentCatalog.cosObject.setItem(COSName.NAMES, COSDictionary().apply { setItem(COSName.DESTS, COSDictionary().apply { setItem(COSName.KIDS, names) }) })
            val first = doc.getPage(0)
            first.annotations = first.annotations + listOf("three", "bad").map { name ->
                PDAnnotationLink().apply {
                    rectangle = PDRectangle(72f, 520f, 200f, 20f)
                    destination = PDNamedDestination(name)
                }
            }
            doc.save(file)
        }
        val source = import(file)
        val part = save(engine.split(source, listOf(listOf(0, 1), listOf(0, 2)), "links")[1].file, "split_links.pdf")
        PDDocument.load(part).use { doc ->
            val (toThree, toFour, byName) = doc.getPage(0).annotations.filterIsInstance<PDAnnotationLink>().take(3).map { it.destination as PDPageDestination }
            assertSame("The link leads to page 2 of the part", doc.getPage(1).cosObject, toThree.page?.cosObject)
            assertNull(toFour.page)
            assertSame("The link by name leads to page 2 of the part", doc.getPage(1).cosObject, byName.page?.cosObject)
        }
        assertFalse(everythingIn(part).contains("PAGE FOUR"))
    }

    /**
     * Pages that share one list of resources (also inherited from the page tree) each draw their own
     * image: a page deleted, left out or split off takes its image with it, and the pages that stay
     * still show theirs.
     */
    @Test fun picturesOfRemovedPagesGoThoughPagesShareResources() {
        val alpha = import(asset("alpha.pdf"))
        for (inherited in listOf(false, true)) {
            val source = import(sharedResources(made("shared_$inherited.pdf"), inherited))
            assertTrue("The fixture holds every image", IMAGE_MARKERS.all { everythingIn(source.file).contains(it) })
            fun assertImages(file: File, kept: List<Int>, what: String) {
                val everything = everythingIn(file)
                for ((index, marker) in IMAGE_MARKERS.withIndex()) {
                    assertEquals("$what (inherited: $inherited) has image ${index + 1}", index in kept, everything.contains(marker))
                }
            }
            fun assertDrawn(file: File, pages: Int) {
                for (index in 0 until pages) {
                    val bitmap = render(file, index)
                    assertTrue("Page ${index + 1} shows its image", count(bitmap, 110, 310, 490, 490) { Color.red(it) < 120 } > 30_000)
                }
            }

            val deleted = engine.reorganize(source, emptyList(), pagesOf(source, 0, 2), "deleted").file
            assertEquals(listOf("PAGE ONE", "PAGE THREE"), pageTexts(deleted))
            assertImages(deleted, listOf(0, 2), "Pages removed")
            assertDrawn(deleted, 2)

            val reorganized = engine.reorganize(source, emptyList(), listOf(PdfPageRef(source.id, 2), PdfPageRef(source.id, 0)), "reorganized").file
            assertImages(reorganized, listOf(2, 0), "Reorganize")
            assertDrawn(reorganized, 2)

            val pages = listOf(PdfPageRef(source.id, 0), PdfPageRef(alpha.id, 0), PdfPageRef(source.id, 2))
            val merged = engine.merge(listOf(source, alpha), pages, "merged").file
            assertImages(merged, listOf(0, 2), "Merge")

            val parts = engine.split(source, listOf(listOf(0), listOf(1, 2)), "split")
            assertImages(parts[0].file, listOf(0), "Split's first part")
            assertImages(parts[1].file, listOf(1, 2), "Split's second part")
            assertDrawn(parts[1].file, 2)
        }
    }

    /**
     * A JPEG 2000 image, which Usugami can't decode, still counts as drawn by its page: the page's
     * part of a split keeps it, though the pages share their resources, and the other part doesn't.
     */
    @Test fun splitKeepsJpeg2000Images() {
        val file = made("split_jpx.pdf")
        PDDocument().use { doc ->
            // Not a real JPEG 2000 image: nothing reads it, as nothing in Usugami reads a real one.
            val image = doc.document.createCOSStream().apply {
                createRawOutputStream().use { it.write("NOT REALLY JPEG 2000".toByteArray()) }
                setItem(COSName.TYPE, COSName.XOBJECT)
                setItem(COSName.SUBTYPE, COSName.IMAGE)
                setItem(COSName.FILTER, COSName.JPX_DECODE)
                setInt(COSName.WIDTH, 16)
                setInt(COSName.HEIGHT, 16)
                // No /ColorSpace: a JPEG 2000 image may leave it to the image data.
            }
            val shared = PDResources().apply { cosObject.setItem(COSName.XOBJECT, COSDictionary().apply { setItem(COSName.getPDFName("Im0"), image) }) }
            for (content in listOf("q 400 0 0 400 100 300 cm /Im0 Do Q", "")) {
                val page = PDPage(PDRectangle.A4).also(doc::addPage)
                page.resources = shared
                page.setContents(PDStream(doc, content.byteInputStream()))
            }
            doc.save(file)
        }
        val parts = engine.split(import(file), listOf(listOf(0), listOf(1)), "jpx")
        fun filterOfImage(part: File) = PDDocument.load(part).use { doc ->
            (doc.getPage(0).resources.cosObject.getCOSDictionary(COSName.XOBJECT)?.getDictionaryObject(COSName.getPDFName("Im0")) as? COSStream)?.getCOSName(COSName.FILTER)
        }
        assertEquals("The page that draws it keeps it", COSName.JPX_DECODE, filterOfImage(parts[0].file))
        assertNull("The other page leaves it out", filterOfImage(parts[1].file))
    }

    /**
     * A damaged outline whose bookmarks hold the first bookmark again, a loop, is read once
     * through: deleting a page doesn't walk it without end.
     */
    @Test(timeout = 60_000) fun aLoopingOutlineDoesntHoldUpDeletingPages() {
        val file = made("looping_outline.pdf")
        PDDocument().use { doc ->
            val pages = (0..1).map { PDPage(PDRectangle.A4).also(doc::addPage) }
            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            val items = (0..3).map { index ->
                PDOutlineItem().apply {
                    title = "Chapter $index"
                    destination = PDPageFitDestination().apply { page = pages[index % 2] }
                    outline.addLast(this)
                }
            }
            for (item in items) item.cosObject.setItem(COSName.FIRST, items[0].cosObject)
            doc.save(file)
        }
        val input = import(file)
        val deleted = engine.reorganize(input, emptyList(), pagesOf(input, 0), "loop_deleted")
        assertEquals(1, deleted.pageCount)
    }

    /** The tags of a deleted page's content go, with their alternate text; the others stay. */
    @Test fun tagsOfDeletedPagesGo() {
        val source = import(taggedPages(made("tagged.pdf")))
        assertTrue(everythingIn(source.file).contains("SECRET ALT 2"))
        for ((what, result) in listOf(
            "Pages removed" to { engine.reorganize(source, emptyList(), pagesOf(source, 0, 2), "tagged_deleted").file },
            "Pages moved" to { engine.reorganize(source, emptyList(), listOf(PdfPageRef(source.id, 2), PdfPageRef(source.id, 0)), "tagged_reorganized").file },
        )) {
            val file = save(result(), "tagged_${what.replace(' ', '_')}.pdf")
            val everything = everythingIn(file)
            assertFalse("$what: page 2's alternate text is gone", everything.contains("SECRET ALT 2"))
            for (kept in listOf("SECRET ALT 1", "SECRET ALT 3", "SPANS PAGES")) assertTrue("$what keeps \"$kept\"", everything.contains(kept))
            PDDocument.load(file).use { doc ->
                val root = doc.documentCatalog.structureTreeRoot
                val elements = root.kids.filterIsInstance<PDStructureElement>().single().kids.filterIsInstance<PDStructureElement>()
                assertEquals("$what: the figures left and the paragraph", listOf("Figure", "Figure", "P"), elements.map { it.structureType })
                // The index of IDs keeps the figures left, each with its element, and says so in its limits.
                val leaves = root.idTree.kids
                assertEquals("$what: the IDs left", listOf("fig1", "fig3"), leaves.flatMap { it.names.keys })
                assertTrue(leaves.flatMap { it.names.values }.all { it.cosObject != null })
                assertEquals("fig1", leaves.first().upperLimit)
            }
        }
    }

    /** Pages keep the numbers viewers show for them (a book's i, ii, iii, then 1, 2, 3) wherever they go. */
    @Test fun pagesKeepTheirLabels() {
        val book = import(labelledPages(made("book.pdf")))
        assertEquals(listOf("i", "ii", "iii", "1", "2", "3"), pageLabels(book.file))
        assertEquals(listOf("ii", "iii", "1", "2", "3"), pageLabels(engine.reorganize(book, emptyList(), pagesOf(book, 1, 2, 3, 4, 5), "no_cover").file))
        assertEquals(listOf("i", "iii", "2", "3"), pageLabels(engine.reorganize(book, emptyList(), pagesOf(book, 0, 2, 4, 5), "fewer").file))
        // Reversed, with a blank page at the end, which goes on from the page before it.
        val reversed = (5 downTo 0).map { PdfPageRef(book.id, it) } + PdfPageRef.blank(0, 300f, 400f)
        assertEquals(listOf("3", "2", "1", "iii", "ii", "i", "ii"), pageLabels(engine.reorganize(book, emptyList(), reversed, "reversed").file))
        // A PDF without labels has none to keep, even once joined to one with labels: its pages go
        // on from the page before them, wherever they go.
        val alpha = import(asset("alpha.pdf"))
        val mixed = listOf(PdfPageRef(alpha.id, 2), PdfPageRef(alpha.id, 0), PdfPageRef(book.id, 4), PdfPageRef(alpha.id, 1), PdfPageRef(book.id, 1))
        assertEquals(listOf("1", "2", "2", "3", "ii"), pageLabels(engine.merge(listOf(alpha, book), mixed, "merged").file))
        val added = listOf(PdfPageRef(book.id, 3), PdfPageRef(alpha.id, 1), PdfPageRef(alpha.id, 0), PdfPageRef(book.id, 0))
        assertEquals(listOf("1", "2", "3", "i"), pageLabels(engine.reorganize(book, listOf(alpha), added, "added").file))
        // Joined whole, the one without labels first: its pages are 1, 2, 3, in labels from the first page on.
        val joined = engine.merge(listOf(alpha, book), null, "joined").file
        assertEquals(List(alpha.pageCount) { "${it + 1}" } + listOf("i", "ii", "iii", "1", "2", "3"), pageLabels(joined))
        PDDocument.load(joined).use { doc ->
            val nums = (doc.documentCatalog.cosObject.getDictionaryObject(COSName.PAGE_LABELS) as COSDictionary).getCOSArray(COSName.NUMS)
            assertEquals(0, nums.getInt(0))
        }

        // Labels that are just the pages' places follow the new places, as if there were none.
        val plain = import(labelledPages(made("plain.pdf"), plain = true))
        assertEquals(listOf("1", "2", "3", "4", "5"), pageLabels(engine.reorganize(plain, emptyList(), pagesOf(plain, 0, 1, 3, 4, 5), "plain_deleted").file))
    }

    @Test fun aPdfWithNoPagesLeftIsRefused() {
        val source = import(asset("alpha.pdf"))
        try {
            engine.reorganize(source, emptyList(), emptyList(), "none")
            fail("A PDF must keep a page")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(File(app.cacheDir, "output/tools").listFiles().isNullOrEmpty())
    }

    /**
     * Pages only turned, or turned and removed, are saved in place, as pages only turned or removed
     * were saved before 2.0.0: the page tree stays as it was, nested, and the pages keep what they
     * inherit from it.
     */
    @Test fun pagesOnlyTurnedOrRemovedLeaveTheRestOfTheFileAsItWas() {
        val main = import(nestedPages(made("nested_in_place.pdf")))
        fun kids(doc: PDDocument) = (doc.pages.cosObject.getDictionaryObject(COSName.KIDS) as COSArray).size()
        PDDocument.load(main.file).use { doc ->
            assertEquals("The fixture's root holds the node and page 3", 2, kids(doc))
            for (page in doc.pages.take(2)) assertFalse(page.cosObject.containsKey(COSName.MEDIA_BOX) || page.cosObject.containsKey(COSName.ROTATE))
        }

        // Page 3 turned, nothing else.
        val turned = save(engine.reorganize(main, emptyList(), pagesOf(main, 0, 1) + PdfPageRef(main.id, 2, 90), "nested_turned").file, "nested_turned.pdf")
        PDDocument.load(turned).use { doc ->
            assertEquals("The page tree is still nested", 2, kids(doc))
            val pages = doc.pages.toList()
            for ((index, page) in pages.take(2).withIndex()) {
                assertFalse("Page ${index + 1} has no size of its own", page.cosObject.containsKey(COSName.MEDIA_BOX))
                assertFalse("Page ${index + 1} has no turn of its own", page.cosObject.containsKey(COSName.ROTATE))
                // Still shown as it inherits it: US Letter, a quarter turn.
                assertEquals(90, page.rotation)
                assertEquals(PDRectangle.LETTER.width, page.mediaBox.width, 0.01f)
                assertEquals(PDRectangle.LETTER.height, page.mediaBox.height, 0.01f)
            }
            assertEquals(90, pages[2].rotation)
            val link = pages[0].annotations.filterIsInstance<PDAnnotationLink>().single()
            assertSame(pages[2].cosObject, (link.destination as PDPageDestination).page.cosObject)
        }

        // Page 2 left out and page 1 turned, in one save.
        val fewer = save(engine.reorganize(main, emptyList(), listOf(PdfPageRef(main.id, 0, 90), PdfPageRef(main.id, 2)), "nested_fewer").file, "nested_fewer.pdf")
        PDDocument.load(fewer).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val first = doc.getPage(0)
            assertEquals("The quarter turn it inherited, and one more", 180, first.rotation)
            assertFalse(first.cosObject.containsKey(COSName.MEDIA_BOX))
            assertEquals(PDRectangle.LETTER.width, first.mediaBox.width, 0.01f)
        }
        assertFalse("The page left out is gone", everythingIn(fewer).contains("MAIN TWO"))
    }

    /**
     * A damaged PDF whose page tree lists one page twice shows it twice. Taking out the second one
     * keeps the pages in their order: PDFBox would take out the first.
     */
    @Test fun aPageListedTwiceIsRemovedWhereChosen() {
        val twice = made("listed_twice.pdf")
        PDDocument().use { doc ->
            val pages = listOf("ONE", "TWO").map { word -> PDPage(PDRectangle.A4).also { doc.addPage(it); writeText(doc, it, "PAGE $word") } }
            doc.pages.cosObject.setItem(COSName.KIDS, COSArray().apply { add(pages[0].cosObject); add(pages[1].cosObject); add(pages[0].cosObject) })
            doc.pages.cosObject.setInt(COSName.COUNT, 3)
            doc.save(twice)
        }
        val main = import(twice)
        assertEquals(3, main.pageCount)
        val left = engine.reorganize(main, emptyList(), pagesOf(main, 0, 1), "listed_twice").file
        assertEquals(listOf("PAGE ONE", "PAGE TWO"), pageTexts(left))
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

    /**
     * Reorganize PDF's selection: the pages selected are turned or removed together, each time as one
     * change for Undo, but never every page; it follows a page moved and comes back with the saved state.
     */
    @Test fun theSelectedPagesAreTurnedAndRemovedTogetherAndOneIsKept() {
        val handle = SavedStateHandle()
        val vm = onMain { PdfToolsViewModel(app, handle) }.also { vm = it }
        onMain { vm.addPdfs(listOf(picked(asset("alpha.pdf"))), replace = true) }
        waitUntil("alpha to be added") { vm.inputs.value.size == 1 && !vm.importing.value }
        val alpha = onMain { vm.pages.value }
        assertEquals(3, alpha.size)

        onMain { vm.choosePages(alpha.mapTo(HashSet()) { it.key }) }
        onMain { vm.rotateChosen(-90) }
        assertEquals(listOf(270, 270, 270), onMain { vm.pages.value.map { it.rotation } })
        onMain { vm.undo() }
        assertEquals(listOf(0, 0, 0), onMain { vm.pages.value.map { it.rotation } })
        assertFalse("Every page selected: none is removed", onMain { vm.removeChosen() })
        assertEquals(alpha, onMain { vm.pages.value })

        // Two pages tapped before the screen redraws are both selected.
        onMain {
            vm.choosePages(emptySet())
            vm.toggleChosenPage(alpha[1])
            vm.toggleChosenPage(alpha[2])
        }
        assertEquals(setOf(alpha[1].key, alpha[2].key), onMain { vm.chosenPages.value })
        assertTrue(onMain { vm.removeChosen() })
        assertEquals(listOf(alpha[0]), onMain { vm.pages.value })
        assertTrue("The pages removed leave the selection", onMain { vm.chosenPages.value.isEmpty() })
        onMain { vm.undo() }
        assertEquals(alpha, onMain { vm.pages.value })

        onMain { vm.choosePages(setOf(alpha[2].key)) }
        onMain { vm.movePageBy(alpha[2], -1) }
        assertEquals(listOf(alpha[0], alpha[2], alpha[1]), onMain { vm.pages.value })
        assertEquals("The page moved stays selected", setOf(alpha[2].key), onMain { vm.chosenPages.value })

        // Android brings Usugami back with the same saved state.
        val again = onMain { PdfToolsViewModel(app, handle) }
        assertEquals(setOf(alpha[2].key), onMain { again.chosenPages.value })
        // The cleanup it started with would delete what the next test makes; tearDown waits for vm's only.
        runBlocking { again.awaitFileWork() }
    }

    /**
     * Merge's pages follow the files' order when it changes, each page keeping its turn, until pages
     * of different files are mixed. Nothing done to the pages, there is nothing to lose by leaving.
     */
    @Test fun mergePagesFollowTheFilesOrder() {
        val vm = onMain { PdfToolsViewModel(app, SavedStateHandle()) }.also { vm = it }
        onMain { vm.addPdfs(listOf(picked(asset("alpha.pdf")), picked(asset("beta.pdf"))), replace = false) }
        waitUntil("both PDFs to be added") { vm.inputs.value.size == 2 && !vm.importing.value }
        val (alpha, beta) = onMain { vm.inputs.value }
        assertFalse("Nothing to lose yet", onMain { vm.hasUnsavedWork(PdfTool.MERGE) })
        assertTrue("Reorganize counts the PDF added", onMain { vm.hasUnsavedWork(PdfTool.REORGANIZE) })

        onMain { vm.moveInput(beta.id, -1) }
        val a = (0..2).map { PdfPageRef(alpha.id, it) }
        val b = (0..1).map { PdfPageRef(beta.id, it) }
        assertEquals(b + a, onMain { vm.pages.value })
        assertFalse("Moving files loses nothing", onMain { vm.hasUnsavedWork(PdfTool.MERGE) })

        onMain { vm.rotatePage(a[0], 90) }
        assertTrue(onMain { vm.hasUnsavedWork(PdfTool.MERGE) })
        val turned = listOf(a[0].copy(rotation = 90)) + a.drop(1)
        onMain { vm.sortInputs() }
        assertEquals("A to Z: alpha first", turned + b, onMain { vm.pages.value })
        onMain { vm.sortInputs(reverse = true) }
        assertEquals("Z to A: beta first", b + turned, onMain { vm.pages.value })

        onMain { vm.runMerge(pageMode = true, fileName = "ordered") }
        waitUntil("the merge") { vm.result.value != null && !vm.processing.value }
        val texts = pageTexts(onMain { vm.result.value!!.single().file }).map { page -> page.filterNot(Char::isWhitespace) }
        assertEquals(5, texts.size)
        for ((index, expected) in listOf("BETAONE", "BETATWO", "ALPHAONE", "ALPHATWO", "ALPHATHREE").withIndex()) {
            assertTrue("Page ${index + 1} says ${texts[index]}", texts[index].contains(expected))
        }

        // Once pages of both files are mixed, that order stays.
        onMain { vm.backToEditing() }
        onMain { vm.movePageBy(turned[0], -1) }
        val mixed = onMain { vm.pages.value }
        assertEquals(listOf(b[0], turned[0], b[1]) + turned.drop(1), mixed)
        onMain { vm.moveInput(alpha.id, -1) }
        assertEquals(listOf(alpha.id, beta.id), onMain { vm.inputs.value.map { it.id } })
        assertEquals(mixed, onMain { vm.pages.value })
    }

    /** Removing the PDF while another PDF, or a picture, is still being added abandons that too. */
    @Test fun startingOverAbandonsWhatIsBeingAdded() {
        val inputDir = File(app.cacheDir, "pdf-inputs")
        val vm = onMain { PdfToolsViewModel(app, SavedStateHandle()) }.also { vm = it }
        onMain { vm.addPdfs(listOf(picked(asset("alpha.pdf"))), replace = true) }
        waitUntil("alpha to be added") { vm.inputs.value.size == 1 && !vm.importing.value }
        // The import has started, and waits for its copy, when the PDF is removed.
        onMain {
            vm.addPdfs(listOf(picked(asset("beta.pdf"))), replace = false)
            vm.startOver()
        }
        Thread.sleep(1500)
        assertTrue("Beta didn't become the tool's PDF: ${onMain { vm.inputs.value }}", onMain { vm.inputs.value.isEmpty() })
        assertTrue(onMain { vm.pages.value.isEmpty() && vm.locked.value.isEmpty() && !vm.importing.value })
        waitUntil("the copies to be deleted") { inputDir.list().isNullOrEmpty() }

        // Annotate's picture.
        onMain { vm.addPdfs(listOf(picked(asset("alpha.pdf"))), replace = true) }
        waitUntil("alpha to be added again") { vm.inputs.value.size == 1 && !vm.importing.value }
        val picture = File(app.cacheDir, "output/test-picked/picture.png").apply {
            parentFile!!.mkdirs()
            outputStream().use { Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        onMain {
            vm.addPicture(FileProvider.getUriForFile(app, "${app.packageName}.files", picture))
            vm.startOver()
        }
        Thread.sleep(1500)
        assertTrue("No picture on a PDF that has gone", onMain { vm.editItems.value.isEmpty() })
        assertFalse(onMain { vm.importing.value })
    }

    /**
     * The page list goes in the saved state, which Android keeps small: a long PDF's untouched pages
     * take no room, and changed ones a few bytes each. They come back as they were.
     */
    @Test fun aLongPdfsPagesFitTheSavedState() {
        val long = made("long.pdf").also { file ->
            PDDocument().use { doc ->
                repeat(LONG_PAGES) { doc.addPage(PDPage(PDRectangle.A4)) }
                doc.save(file)
            }
        }
        val handle = SavedStateHandle()
        val vm = onMain { PdfToolsViewModel(app, handle) }.also { vm = it }
        onMain { vm.addPdfs(listOf(picked(long)), replace = true) }
        waitUntil("the long PDF to be added", 60_000) { vm.inputs.value.size == 1 && !vm.importing.value }
        assertEquals(LONG_PAGES, onMain { vm.pages.value.size })
        // As SavedStateHandle writes its values into the activity's saved state.
        fun savedBytes(): Int = onMain {
            val parcel = Parcel.obtain()
            try {
                parcel.writeValue(ArrayList(handle.keys().map { handle.get<Any?>(it) }))
                parcel.dataSize()
            } finally {
                parcel.recycle()
            }
        }
        assertTrue("Untouched pages: ${savedBytes()} bytes", savedBytes() < 10_000)

        val pages = onMain { vm.pages.value }
        onMain { vm.rotatePage(pages[5], 90) }
        onMain { vm.removePage(pages[9]) }
        onMain { vm.addBlankPage(pages[0]) }
        waitUntil("the blank page") { vm.pages.value[1].isBlank }
        assertTrue("Changed pages: ${savedBytes()} bytes", savedBytes() < 100_000)
        // Reorganize PDF's Select all: every page selected is kept as its places.
        onMain { vm.choosePages(vm.pages.value.mapTo(HashSet()) { it.key }) }
        assertTrue("Every page selected: ${savedBytes()} bytes", savedBytes() < 100_000)

        // Android brings Usugami back with the same saved state.
        val changed = onMain { vm.pages.value }
        val again = onMain { PdfToolsViewModel(app, handle) }
        assertEquals(changed, onMain { again.pages.value })
        assertEquals(90, onMain { again.pages.value[6].rotation })
        assertTrue(onMain { again.pages.value[1].let { it.isBlank && it.width > 0f && it.height > 0f } })
        assertEquals(changed.size, onMain { again.chosenPages.value.size })
        // The cleanup it started with would delete what the next test makes; tearDown waits for vm's only.
        runBlocking { again.awaitFileWork() }
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

    private companion object {
        /** A PDF long enough that its pages as objects would crowd the saved state. */
        const val LONG_PAGES = 5000
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

    /**
     * Fields PDFBox can't draw stay fields for viewers to draw, rather than come out blank or with
     * the look they had before their value was set, which their form says is out of date.
     */
    @Test fun fieldsPdfBoxCantDrawStayFields() {
        val source = import(unencodableFormPdf(made("unencodable_form.pdf")))
        // The preview shows the amount as viewers do, not the out-of-date look.
        val view = text(engine.previewView(source).file)
        assertTrue(view, view.contains("5,000") && !view.contains("OLD AMOUNT"))
        val result = engine.flatten(source, "unencodable_flattened")
        assertEquals("Both stay fields", 2, result.keptFields)
        val flat = save(result.file.file, "unencodable_flattened.pdf")
        assertFalse("The out-of-date look isn't part of the page", text(flat).contains("OLD AMOUNT"))
        PDDocument.load(flat).use { doc ->
            val form = doc.documentCatalog.acroForm
            assertEquals(mapOf("amount" to "₹ 5,000", "name" to "नाम"), form.fields.associate { it.partialName to it.valueAsString })
            assertTrue(form.needAppearances)
            assertEquals(2, doc.getPage(0).annotations.size)
        }
    }

    /** A drop-down list whose choices pair a code with a name shows the name, as viewers do, not the code it keeps or another choice's name. */
    @Test fun aDropDownShowsTheNameOfItsChoice() {
        val file = made("country_form.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4).also(doc::addPage)
            val form = PDAcroForm(doc).also { doc.documentCatalog.acroForm = it }
            form.defaultResources = PDResources().apply { put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA) }
            form.needAppearances = true
            val country = PDComboBox(form).apply {
                partialName = "country"
                defaultAppearance = "/Helv 12 Tf 0 g"
                cosObject.setItem(COSName.OPT, COSArray().apply {
                    // A name kept elsewhere in the file, and a choice with no name, before the one chosen.
                    add(COSArray().apply {
                        add(COSString("code-at"))
                        add(COSObject(COSString("Austria")))
                    })
                    add(COSArray().apply { add(COSString("code-be")) })
                    for ((code, name) in listOf("code-pt" to "Portugal", "code-es" to "Spain")) add(COSArray().apply {
                        add(COSString(code))
                        add(COSString(name))
                    })
                })
                cosObject.setString(COSName.V, "code-pt")
            }
            // Saving writes the name kept elsewhere into the list itself, so that one is checked here.
            assertEquals("Austria", Flattening.shownChoice(country, "code-at"))
            assertEquals("code-be", Flattening.shownChoice(country, "code-be"))
            val widget = country.widgets.single().apply {
                rectangle = PDRectangle(72f, 600f, 200f, 24f)
                this.page = page
                isPrinted = true
            }
            form.fields.add(country)
            page.annotations = listOf(widget)
            doc.save(file)
        }
        val flat = save(engine.flatten(import(file), "country_flattened").file.file, "country_flattened.pdf")
        val content = text(flat)
        assertTrue("The choice's name is part of the page: $content", content.contains("Portugal") && !content.contains("code-pt") && !content.contains("Spain"))
    }

    /**
     * Fields kept stay fields, widgets and all, though a field flattened has a widget that doesn't
     * say which page it is on: PDFBox then looks for widgets on every page.
     */
    @Test fun fieldsKeptStayThoughAWidgetFlattenedNamesNoPage() {
        val source = import(unencodableFormPdf(made("unplaced_form.pdf"), unplacedField = true))
        val result = engine.flatten(source, "unplaced_flattened")
        assertEquals(2, result.keptFields)
        val flat = save(result.file.file, "unplaced_flattened.pdf")
        assertTrue("The field PDFBox can draw is part of the page", text(flat).contains("Lisbon"))
        PDDocument.load(flat).use { doc ->
            assertEquals(setOf("amount", "name"), doc.documentCatalog.acroForm.fields.map { it.partialName }.toSet())
            val widgets = doc.getPage(0).annotations.filterIsInstance<PDAnnotationWidget>()
            assertEquals("The kept fields' widgets stay on the page", 2, widgets.size)
            assertTrue("The amount keeps its look", widgets.single { it.cosObject.getString(COSName.T) == "amount" }.normalAppearanceStream != null)
        }
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

    /**
     * Several PDFs at once: each flattened into a PDF of its own, named as asked (told apart), with
     * what flattening it says (fields kept, a dynamic XFA form dropped); one with nothing to flatten
     * fails on its own, saying so, and cancelling leaves nothing behind.
     */
    @Test fun flattenEachFlattensEveryPdfOnItsOwn() {
        val form = import(formPdf(made("each_form.pdf")))
        val plain = import(asset("alpha.pdf"))
        val xfa = import(formPdf(made("each_xfa.pdf"), name = "Ana Lima", xfa = true))
        val amounts = import(unencodableFormPdf(made("each_amounts.pdf")))
        val output = File(app.cacheDir, "output/tools")
        val started = ArrayList<Int>()
        val outcomes = engine.flattenEach(
            listOf(ToolJob(form, "Form"), ToolJob(plain, "Plain"), ToolJob(xfa, "form"), ToolJob(amounts, "Amounts")),
            onNext = { started += it },
        )
        assertEquals("Each PDF in turn", listOf(0, 1, 2, 3), started)
        val nothing = outcomes[1].exceptionOrNull()
        assertTrue("Nothing to flatten: $nothing", nothing is NothingToFlatten && !nothing.dynamicXfa)
        val (flatForm, flatXfa, flatAmounts) = listOf(0, 2, 3).map { outcomes[it].getOrThrow() }
        assertEquals(listOf("Form.pdf", "form (2).pdf", "Amounts.pdf"), listOf(flatForm, flatXfa, flatAmounts).map { it.file.name })
        assertEquals("Only those made", setOf("Form.pdf", "form (2).pdf", "Amounts.pdf"), output.list()!!.toSet())
        assertTrue(text(flatForm.file.file).contains("Jane Doe"))
        assertFalse(flatForm.droppedDynamicXfa)
        assertTrue(flatXfa.droppedDynamicXfa)
        assertTrue(text(flatXfa.file.file).contains("Ana Lima"))
        assertEquals(2, flatAmounts.keptFields)

        // Cancelled during the second PDF: none is left, the first's neither.
        try {
            engine.flattenEach(listOf(ToolJob(form, "one"), ToolJob(xfa, "two")), check = { if (started.size > 5) throw CancellationException() }, onNext = { started += it })
            fail("Flattening should have been stopped")
        } catch (_: CancellationException) {
        }
        assertEquals(listOf(0, 1, 2, 3, 0, 1), started)
        assertTrue("Nothing left behind", output.listFiles().isNullOrEmpty())
        listOf(form, plain, xfa, amounts).forEach(engine::delete)
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
