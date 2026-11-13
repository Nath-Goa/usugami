package io.github.nath_goa.usugami

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup
import com.tom_roush.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import io.github.nath_goa.usugami.core.InfoChange
import io.github.nath_goa.usugami.core.InfoField
import io.github.nath_goa.usugami.core.XmpPacket
import io.github.nath_goa.usugami.data.AppLanguages
import io.github.nath_goa.usugami.data.InfoDate
import io.github.nath_goa.usugami.data.PageMark
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.documentDetails
import io.github.nath_goa.usugami.data.setDocumentInfo
import io.github.nath_goa.usugami.ui.DocumentInfoState
import io.github.nath_goa.usugami.ui.PdfTool
import io.github.nath_goa.usugami.ui.PdfToolsViewModel
import io.github.nath_goa.usugami.ui.infoTyped
import io.github.nath_goa.usugami.ui.loadDocumentInfo
import io.github.nath_goa.usugami.ui.runDocumentInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Calendar
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Document info's engine: what it reads of a PDF (its /Info first, its XMP packet for what /Info
 * lacks, whether a signature in it still holds), and what it saves (only the fields changed, in /Info
 * and XMP; after Remove all info, none of the info a PDF keeps of its making); and its form, in the
 * view model and on the screen.
 */
class DocumentInfoDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as Application
    private val engine = PdfToolsEngine(app)
    private val marks = PageMark(app)
    private val dir = File(app.cacheDir, "test-docinfo").apply { mkdirs() }
    private val imported = ArrayList<PdfInput>()
    private var nextId = 9101L
    /** The view models tests 4 and 5 drive, put away after each test. */
    private val viewModels = ArrayList<PdfToolsViewModel>()
    private var activity: MainActivity? = null

    @After fun tearDown() {
        activity?.let { onMain { it.finish() } }
        for (vm in viewModels.asReversed()) {
            onMain { vm.reset() }
            // Its cleanup would delete the copies the next test makes.
            runBlocking { vm.awaitFileWork() }
        }
        imported.forEach(engine::delete)
        engine.clearOutput()
        dir.deleteRecursively()
        File(app.cacheDir, "output/test-picked").deleteRecursively()
    }

    private fun import(file: File, password: String = ""): PdfInput =
        engine.import(Uri.fromFile(file), nextId++, password).also { imported += it }

    /**
     * Two pages (A4, and Letter turned a quarter with a crop box smaller than the page), a bookmark and
     * a link to page 2, and info wherever a PDF keeps it:
     * - /Info: Title "SecretTitle", Author "SecretAuthor Renée" (PDFDocEncoding), Subject, Keywords,
     *   Creator "SecretTool", Producer, both dates, /Trapped /False and a key of its own, /Department
     * - the catalog's XMP packet, not compressed: dc:title "SecretXmpTitle", dc:creator
     *   "SecretXmpAuthor", dc:rights "SecretRights" and pdfaid:part 1
     * - an XMP packet on page 1 ("SecretPageXmp") and on a picture drawn there ("SecretImageXmp")
     * - page 1's /PieceInfo ("SecretPiece") and /LastModified
     * - a layer whose /Usage names the app that made it, "SecretLayerTool", in /CreatorInfo
     * - a trailer /ID of [ID_BYTES] twice; PDF version 1.7.
     */
    private fun infoPdf(file: File): File = file.also {
        PDFBoxResourceLoader.init(app)
        PDDocument().use { doc ->
            val first = PDPage(PDRectangle.A4)
            val second = PDPage(PDRectangle.LETTER).apply {
                rotation = 90
                cropBox = PDRectangle(50f, 60f, 500f, 650f)
            }
            doc.addPage(first)
            doc.addPage(second)
            writeText(doc, first, "PAGE ONE")
            writeText(doc, second, "PAGE TWO", x = 100f, y = 400f)

            val picture = LosslessFactory.createFromImage(doc, Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) })
            picture.cosObject.setItem(COSName.METADATA, xmp(doc, packet("SecretImageXmp")))
            PDPageContentStream(doc, first, PDPageContentStream.AppendMode.APPEND, true).use { it.drawImage(picture, 72f, 500f, 40f, 40f) }
            first.cosObject.setItem(COSName.METADATA, xmp(doc, packet("SecretPageXmp")))
            first.cosObject.setItem(COSName.PIECE_INFO, COSDictionary().apply {
                setItem(COSName.getPDFName("UsugamiTest"), COSDictionary().apply {
                    setString(COSName.getPDFName("Private"), "SecretPiece")
                    setString(COSName.LAST_MODIFIED, "D:20260101000000Z")
                })
            })
            first.cosObject.setString(COSName.LAST_MODIFIED, "D:20260101000000Z")

            val outline = PDDocumentOutline()
            doc.documentCatalog.documentOutline = outline
            outline.addLast(PDOutlineItem().apply {
                title = "Chapter TWO"
                destination = PDPageFitDestination().apply { setPage(second) }
            })
            first.annotations = listOf<PDAnnotation>(PDAnnotationLink().apply {
                rectangle = PDRectangle(72f, 600f, 200f, 20f)
                destination = PDPageFitDestination().apply { setPage(second) }
            })

            val layer = PDOptionalContentGroup("Artwork")
            layer.cosObject.setItem(COSName.getPDFName("Usage"), COSDictionary().apply {
                setItem(COSName.getPDFName("CreatorInfo"), COSDictionary().apply {
                    setString(COSName.CREATOR, "SecretLayerTool")
                    setItem(COSName.SUBTYPE, COSName.getPDFName("Artwork"))
                })
            })
            doc.documentCatalog.ocProperties = PDOptionalContentProperties().apply { addGroup(layer) }
            doc.documentCatalog.cosObject.setItem(COSName.METADATA, xmp(doc, CATALOG_XMP))

            doc.document.trailer.setItem(COSName.INFO, COSDictionary().apply {
                setString(COSName.TITLE, "SecretTitle")
                setItem(COSName.AUTHOR, COSString("SecretAuthor Renée".toByteArray(Charsets.ISO_8859_1)))
                setString(COSName.SUBJECT, "SecretSubject")
                setString(COSName.KEYWORDS, "SecretKeyword, report")
                setString(COSName.CREATOR, "SecretTool")
                setString(COSName.PRODUCER, "SecretProducer")
                setString(COSName.CREATION_DATE, "D:20261112153012+05'30'")
                setString(COSName.MOD_DATE, "D:20261113090000Z")
                setItem(COSName.TRAPPED, COSName.getPDFName("False"))
                setString(COSName.getPDFName("Department"), "SecretDept")
            })
            doc.document.trailer.setItem(COSName.ID, COSArray().apply { repeat(2) { add(COSString(ID_BYTES)) } })
            doc.version = 1.7f
            doc.save(file)
        }
    }

    /** An XMP stream as PDFs have them, not compressed, or compressed with [filter]. */
    private fun xmp(doc: PDDocument, packet: String, filter: COSName? = null): COSStream = doc.document.createCOSStream().apply {
        setItem(COSName.TYPE, COSName.METADATA)
        setItem(COSName.SUBTYPE, COSName.getPDFName("XML"))
        (filter?.let { createOutputStream(it) } ?: createOutputStream()).use { it.write(packet.toByteArray(Charsets.UTF_8)) }
    }

    /** A small XMP packet whose description is [text]. */
    private fun packet(text: String) = """
        <?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
        <x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
        <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
        <dc:description><rdf:Alt><rdf:li xml:lang="x-default">$text</rdf:li></rdf:Alt></dc:description>
        </rdf:Description></rdf:RDF></x:xmpmeta>
        <?xpacket end="w"?>
    """.trimIndent()

    /** [source] opened with PDFBox, changed by [change] and saved as [name]. */
    private fun changed(source: File, name: String, change: (PDDocument) -> Unit): File = File(dir, name).also { file ->
        PDDocument.load(source).use { doc ->
            change(doc)
            doc.save(file)
        }
    }

    /**
     * Signs [file] as a signing app does: an update appended with a signature field whose value has a
     * /ByteRange over the whole file but its /Contents, which holds a placeholder rather than a real
     * signature (PageMarkDeviceTest's, without the certification).
     */
    private fun sign(file: File) {
        PDDocument.load(file.readBytes()).use { doc ->
            val signature = PDSignature().apply {
                setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                name = "Test"
                signDate = Calendar.getInstance()
            }
            // Bytes a real signature has too, which keep it written in hex when the PDF is saved anew.
            doc.addSignature(signature) { ByteArray(64) { 0xA7.toByte() } }
            file.outputStream().use { doc.saveIncremental(it) }
        }
    }

    private fun at(instant: String) = Instant.parse(instant).toEpochMilli()

    /**
     * 1. What the PDF has: /Info's four fields, dates, creator and producer, the version, unsigned.
     * Where /Info has nothing (an empty title, no author, no /Info at all), the XMP packet's values
     * show; signed, the PDF says so.
     */
    @Test fun readsWhatThePdfHas() {
        val source = infoPdf(File(dir, "info.pdf"))
        // The fixture's file holds each secret where a byte search finds it, but the author: with its é,
        // PDFBox writes that string in hex.
        val bytes = String(source.readBytes(), Charsets.ISO_8859_1)
        for (secret in SECRETS - "SecretAuthor") assertTrue(secret, secret in bytes)
        val authorHex = "SecretAuthor".toByteArray(Charsets.US_ASCII).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        assertFalse("SecretAuthor" in bytes)
        assertTrue(authorHex in bytes.lowercase())
        val input = import(source)
        val details = engine.documentDetails(input, marks)
        assertEquals(input.id, details.inputId)
        assertEquals(
            mapOf(
                InfoField.TITLE to "SecretTitle",
                InfoField.AUTHOR to "SecretAuthor Renée",
                InfoField.SUBJECT to "SecretSubject",
                InfoField.KEYWORDS to "SecretKeyword, report",
            ),
            details.fields,
        )
        assertEquals(emptySet<InfoField>(), details.cut)
        assertEquals(InfoDate(at("2026-11-12T10:00:12Z"), "D:20261112153012+05'30'"), details.created)
        assertEquals(InfoDate(at("2026-11-13T09:00:00Z"), "D:20261113090000Z"), details.modified)
        assertEquals("SecretTool", details.creator)
        assertEquals("SecretProducer", details.producer)
        assertEquals("1.7", details.version)
        assertEquals(source.length(), details.sizeBytes)
        assertFalse(details.signed)

        // An empty title and no author: XMP's, field by field; the subject is still /Info's.
        val gaps = import(changed(source, "gaps.pdf") { doc ->
            val info = doc.document.trailer.getDictionaryObject(COSName.INFO) as COSDictionary
            info.setString(COSName.TITLE, "")
            info.removeItem(COSName.AUTHOR)
        })
        engine.documentDetails(gaps, marks).fields.let { fields ->
            assertEquals("SecretXmpTitle", fields[InfoField.TITLE])
            assertEquals("SecretXmpAuthor", fields[InfoField.AUTHOR])
            assertEquals("SecretSubject", fields[InfoField.SUBJECT])
        }

        // No /Info: the title and author from XMP, nothing else.
        val noInfo = engine.documentDetails(import(changed(source, "no_info.pdf") { it.document.trailer.removeItem(COSName.INFO) }), marks)
        assertEquals(
            mapOf(
                InfoField.TITLE to "SecretXmpTitle",
                InfoField.AUTHOR to "SecretXmpAuthor",
                InfoField.SUBJECT to "",
                InfoField.KEYWORDS to "",
            ),
            noInfo.fields,
        )
        assertNull(noInfo.created)
        assertNull(noInfo.modified)
        assertNull(noInfo.creator)
        assertNull(noInfo.producer)

        // No /Info and the packet compressed, in a PDF with a password: read through its filter, decrypted.
        val compressed = import(
            changed(source, "compressed.pdf") { doc ->
                doc.document.trailer.removeItem(COSName.INFO)
                doc.documentCatalog.cosObject.setItem(COSName.METADATA, xmp(doc, CATALOG_XMP, COSName.FLATE_DECODE))
                doc.protect(StandardProtectionPolicy("owner", "user", AccessPermission()))
            },
            password = "user",
        )
        assertEquals("SecretXmpTitle", engine.documentDetails(compressed, marks).fields[InfoField.TITLE])
        // A compressed packet longer than a packet may be, whole within the limit: not read, not cut there.
        val padded = CATALOG_XMP.replace("<?xpacket end", " ".repeat(3 * 1024 * 1024) + "\n<?xpacket end")
        val long = import(changed(source, "long_xmp.pdf") { doc ->
            doc.document.trailer.removeItem(COSName.INFO)
            doc.documentCatalog.cosObject.setItem(COSName.METADATA, xmp(doc, padded, COSName.FLATE_DECODE))
        })
        assertEquals("", engine.documentDetails(long, marks).fields[InfoField.TITLE])

        // Signed: its signature holds in the copy read.
        val signedFile = source.copyTo(File(dir, "signed.pdf"))
        sign(signedFile)
        val signed = engine.documentDetails(import(signedFile), marks)
        assertTrue(signed.signed)
        assertEquals("SecretTitle", signed.fields[InfoField.TITLE])
    }

    /**
     * 2. Saving changes only what was changed: from a PDF with a password, a title in three scripts and
     * an emoji and an emptied author are saved in /Info and in XMP, and every other info entry, the
     * /ID, XMP's PDF/A identification and rights, the pages, the link and the bookmark are as they
     * were, in a PDF without a password. A packet that can't be read is dropped and the result says so;
     * an /ID that isn't two strings becomes a random one.
     */
    @Test fun savingChangesOnlyWhatWasChanged() {
        val source = infoPdf(File(dir, "info.pdf"))
        val locked = changed(source, "locked.pdf") { it.protect(StandardProtectionPolicy("owner", "user", AccessPermission())) }
        val title = "नमस्ते 世界 😀"
        val result = engine.setDocumentInfo(
            import(locked, password = "user"),
            InfoChange(mapOf(InfoField.TITLE to title, InfoField.AUTHOR to "")),
            "changed",
        )
        assertFalse(result.droppedXmp)
        PDDocument.load(source).use { original ->
            PDDocument.load(result.file.file).use { doc ->
                assertFalse(doc.isEncrypted)
                val before = info(original)!!
                val after = info(doc)!!
                assertEquals(title, after.getString(COSName.TITLE))
                assertEquals(before.keySet() - COSName.AUTHOR, after.keySet())
                for (key in after.keySet() - COSName.TITLE) {
                    val was = before.getDictionaryObject(key)
                    val now = after.getDictionaryObject(key)
                    if (was is COSString) assertArrayEquals(key.name, was.bytes, (now as COSString).bytes) else assertEquals(key.name, was, now)
                }
                assertEquals(listOf(ID_BYTES.toList(), ID_BYTES.toList()), idOf(doc).map { it.toList() })

                val stream = doc.documentCatalog.cosObject.getDictionaryObject(COSName.METADATA) as COSStream
                assertNull(stream.filters)
                val packet = stream.createInputStream().use { it.readBytes() }
                val xmp = XmpPacket.read(packet)!!
                assertEquals(title, xmp.title)
                assertNull(xmp.author)
                val text = String(packet, Charsets.UTF_8)
                assertTrue("pdfaid:part" in text)
                assertTrue("SecretRights" in text)
                assertLayoutKept(doc)
            }
        }

        // A packet that can't be read, and an /ID of two numbers.
        val broken = changed(source, "broken.pdf") { doc ->
            doc.documentCatalog.cosObject.setItem(COSName.METADATA, xmp(doc, "<!DOCTYPE x><x/>"))
            doc.document.trailer.setItem(COSName.ID, COSArray().apply {
                add(COSInteger.ONE)
                add(COSInteger.TWO)
            })
        }
        val dropped = engine.setDocumentInfo(import(broken), InfoChange(mapOf(InfoField.TITLE to "New")), "dropped")
        assertTrue(dropped.droppedXmp)
        PDDocument.load(dropped.file.file).use { doc ->
            assertNull(doc.documentCatalog.cosObject.getDictionaryObject(COSName.METADATA))
            assertEquals("New", info(doc)!!.getString(COSName.TITLE))
            assertRandomId(doc)
        }
    }

    /**
     * 3. Remove all info leaves nothing: no /Info, no XMP, /PieceInfo or /CreatorInfo anywhere, no
     * /LastModified on the page, a random /ID, none of the fixture's secrets in the file's bytes; the
     * pages, the link and the bookmark as they were. A title typed as well is then the only info.
     */
    @Test fun removeAllLeavesNothing() {
        val source = infoPdf(File(dir, "info.pdf"))
        // The search below finds each of them in the fixture.
        PDDocument.load(source).use { assertEquals(setOf("Metadata", "PieceInfo", "CreatorInfo"), leftovers(it).toSet()) }
        val input = import(source)
        val result = engine.setDocumentInfo(input, InfoChange(removeAll = true), "removed")
        assertFalse(result.droppedXmp)
        val bytes = String(result.file.file.readBytes(), Charsets.ISO_8859_1)
        for (secret in SECRETS + "SecretXmpTitle" + "SecretXmpAuthor") {
            assertFalse(secret, secret in bytes)
            assertFalse(secret, String(secret.toByteArray(Charsets.UTF_16BE), Charsets.ISO_8859_1) in bytes)
        }
        // The author with its é, which PDFBox writes in hex.
        assertFalse(hex("SecretAuthor") in bytes.lowercase())
        PDDocument.load(result.file.file).use { doc ->
            assertNull(doc.document.trailer.getDictionaryObject(COSName.INFO))
            assertEquals(emptyList<String>(), leftovers(doc))
            assertNull(doc.getPage(0).cosObject.getDictionaryObject(COSName.LAST_MODIFIED))
            assertFalse(assertRandomId(doc).contentEquals(ID_BYTES))
            assertLayoutKept(doc)
        }

        // A title typed after Remove all info: the only info, still no XMP.
        val titled = engine.setDocumentInfo(input, InfoChange(mapOf(InfoField.TITLE to "New", InfoField.AUTHOR to ""), removeAll = true), "titled")
        PDDocument.load(titled.file.file).use { doc ->
            val info = info(doc)!!
            assertEquals(setOf(COSName.TITLE), info.keySet())
            assertEquals("New", info.getString(COSName.TITLE))
            assertEquals(emptyList<String>(), leftovers(doc))
        }
    }

    /**
     * 4. The form in the view model, as the screen drives it: a PDF whose copy was cleared from the
     * cache is put away and that said; nothing is changed while the fields hold the PDF's info; a text
     * typed is a change until it is the PDF's again; a line break typed is one space; Undo after Remove all info
     * brings back what was typed; what was typed and Remove all info come back in a view model made
     * anew from the saved state, as after Android closed Usugami, the PDF's info read again; the PDF
     * saved is <name>_info.pdf with that info only; and the form goes with its PDF.
     */
    @Test fun theFormKeepsWhatWasTypedAndSavesIt() {
        val handle = SavedStateHandle()
        val vm = newViewModel(handle)
        val state = vm.documentInfo
        // Its copy cleared from the cache while Usugami runs, as the system may do: the PDF is put away
        // and that said, rather than its info being read for ever. Then it is added again.
        onMain { vm.addPdfs(listOf(picked()), replace = true) }
        waitUntil("the PDF to be added") { !vm.importing.value && vm.inputs.value.size == 1 }
        val gone = onMain { vm.inputs.value.single() }
        assertTrue(gone.file.delete())
        onMain { vm.loadDocumentInfo(gone) }
        waitUntil("the PDF to be put away") { vm.inputs.value.isEmpty() }
        val cleared = onMain { vm.strings.getQuantityString(R.plurals.msg_files_cleared, 1, 1) }
        runBlocking { withTimeout(5_000) { vm.messages.first { it == cleared } } }
        assertNull(onMain { state.details.value })
        onMain { vm.addPdfs(listOf(picked()), replace = true) }
        waitUntil("the PDF to be added again") { !vm.importing.value && vm.inputs.value.size == 1 }
        val input = onMain { vm.inputs.value.single() }
        onMain { vm.loadDocumentInfo(input) }
        waitUntil("its info to be read") { state.details.value != null }
        fun shown(field: InfoField) = onMain { state.shown(field) }
        fun changed() = onMain { state.changed.value }
        fun unsaved() = onMain { vm.hasUnsavedWork(PdfTool.DOCUMENT_INFO) }
        assertEquals("SecretTitle", shown(InfoField.TITLE))
        assertEquals("SecretAuthor Renée", shown(InfoField.AUTHOR))
        assertFalse(changed())
        assertFalse(unsaved())

        onMain { state.setText(InfoField.TITLE, "X") }
        assertTrue(changed())
        assertTrue(unsaved())
        // The PDF's own title again (spaces and a line break around it don't count): nothing changed.
        onMain { state.setText(InfoField.TITLE, " SecretTitle\n") }
        assertFalse(changed())
        assertFalse(unsaved())
        // A line break typed or pasted is one space in the field, a Windows one and an empty line too.
        assertEquals("Annual report 2026", infoTyped("Annual report\r\n2026"))
        assertEquals("a b c ", infoTyped("a\n\nb\rc\n"))

        onMain { state.setText(InfoField.AUTHOR, "Someone") }
        onMain { state.removeAll() }
        assertEquals(listOf("", "", "", ""), InfoField.entries.map(::shown))
        assertTrue(changed())
        onMain { state.undoRemoveAll() }
        assertFalse(onMain { state.removeAll.value })
        assertEquals("Someone", shown(InfoField.AUTHOR))
        assertEquals("SecretTitle", shown(InfoField.TITLE))

        // A title longer than the field takes, cut where a space follows: changed and put back as it
        // showed, it is no change (saved, the title would have been cut).
        onMain {
            val long = DocumentInfoState(SavedStateHandle())
            long.begin(input)
            val details = state.details.value!!
            long.loaded(details.copy(fields = details.fields + (InfoField.TITLE to "a".repeat(1999) + " "), cut = setOf(InfoField.TITLE)))
            val cut = long.shown(InfoField.TITLE)
            long.setText(InfoField.TITLE, cut.dropLast(2))
            assertTrue(long.changed.value)
            long.setText(InfoField.TITLE, cut)
            assertFalse(long.changed.value)
        }

        onMain {
            state.removeAll()
            state.setText(InfoField.TITLE, "New")
        }
        // Android closed Usugami: a view model made anew from the saved state.
        val again = newViewModel(handle)
        val restored = again.documentInfo
        assertEquals(input.id, onMain { again.inputs.value.single().id })
        assertTrue("Leaving asks first, also before the info is read again", onMain { again.hasUnsavedWork(PdfTool.DOCUMENT_INFO) })
        onMain { again.loadDocumentInfo(again.inputs.value.single()) }
        waitUntil("the info to be read again") { restored.details.value != null }
        assertTrue(onMain { restored.removeAll.value })
        assertEquals("New", onMain { restored.shown(InfoField.TITLE) })
        assertEquals("", onMain { restored.shown(InfoField.AUTHOR) })
        assertTrue(onMain { again.hasUnsavedWork(PdfTool.DOCUMENT_INFO) })

        onMain { again.runDocumentInfo(input, again.defaultName(PdfTool.DOCUMENT_INFO, input)) }
        waitUntil("the PDF to be saved") { again.result.value != null }
        val made = onMain { again.result.value!!.single() }
        assertEquals("report_info.pdf", made.name)
        PDDocument.load(made.file).use { doc ->
            assertEquals(mapOf("Title" to "New"), info(doc)!!.keySet().associate { it.name to info(doc)!!.getString(it) })
            assertNull(doc.documentCatalog.cosObject.getDictionaryObject(COSName.METADATA))
        }

        // Typed, then the PDF removed: nothing is left to lose.
        onMain { restored.setText(InfoField.SUBJECT, "Y") }
        assertTrue(onMain { again.hasUnsavedWork(PdfTool.DOCUMENT_INFO) })
        onMain { again.removeInput(input.id) }
        assertFalse(onMain { again.hasUnsavedWork(PdfTool.DOCUMENT_INFO) })
        assertNull(onMain { restored.change() })
    }

    /**
     * 5. The screen, run as the app runs it: Document info opened from its card on Home (the last of
     * Optimize and protect, scrolled to) shows the PDF's title in its field with Save PDF off; Remove
     * all info empties the field, says "Will be removed" and turns Save PDF on, which saves the PDF and
     * shows the result.
     */
    @Test fun removeAllInfoOnTheScreenSavesThePdf() {
        if (AppLanguages.reset(app)) Thread.sleep(1_000)
        AppLanguages.skipChoice(app)
        val intent = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val main = (instrumentation.startActivitySync(intent) as MainActivity).also { activity = it }
        val tool = app.getString(R.string.tool_document_info)
        waitUntil("Home") { shows(app.getString(R.string.settings)) }
        // Below View, Create PDF, Organize and Edit and sign: scrolled to, as a screen reader would.
        var scrolls = 0
        while (onMain { button(tool) } == null && scrolls++ < 10) {
            accessibilityNodes().firstOrNull { AccessibilityAction.ACTION_SCROLL_DOWN in it.actionList }
                ?.performAction(AccessibilityAction.ACTION_SCROLL_DOWN.id)
            Thread.sleep(500)
        }
        press(tool)
        waitUntil("Home to slide away") { !shows(app.getString(R.string.settings)) }
        val vm = onMain { ViewModelProvider(main)[PdfToolsViewModel::class.java] }
        onMain { vm.addPdfs(listOf(picked()), replace = true) }

        val save = app.getString(R.string.pdf_document_info_action)
        waitUntil("the PDF's title in its field") { "SecretTitle" in fieldTexts() }
        assertFalse("Nothing to save before anything is changed", onMain { enabled(save) })
        press(app.getString(R.string.docinfo_remove_all))
        waitUntil("the fields to be emptied") { fieldTexts().none { it.startsWith("Secret") } }
        assertTrue(onMain { shows(app.getString(R.string.docinfo_will_be_removed)) })
        waitUntil("Save PDF to be on") { enabled(save) }
        press(save)
        waitUntil("the result screen") { shows(app.getString(R.string.result_heading_one)) }
        assertEquals("report_info.pdf", onMain { vm.result.value?.single()?.name })
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /** Waits until [condition], asked on the main thread, holds. */
    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!onMain(condition)) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    private fun newViewModel(handle: SavedStateHandle) = onMain { PdfToolsViewModel(app, handle) }.also { viewModels += it }

    /** The fixture as a file picker gives it: a content:// URI of a PDF named report.pdf. */
    private fun picked(): Uri {
        val file = File(app.cacheDir, "output/test-picked/report.pdf").apply { parentFile!!.mkdirs() }
        return FileProvider.getUriForFile(app, "${app.packageName}.files", infoPdf(file))
    }

    /** What a screen reader is told of the screen, for scrolling it. */
    private fun accessibilityNodes(): List<AccessibilityNodeInfo> {
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo) {
            all += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        instrumentation.uiAutomation.rootInActiveWindow?.let(::walk)
        return all
    }

    /** The screen's content as Compose has it now, merged as a screen reader hears it. On the main thread. */
    private fun semantics(): List<SemanticsNode> {
        fun find(view: View): ViewRootForTest? = when (view) {
            is ViewRootForTest -> view
            is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) }
            else -> null
        }
        fun SemanticsNode.all(): List<SemanticsNode> = listOf(this) + children.flatMap { it.all() }
        return checkNotNull(find(activity!!.window.decorView)) { "No Compose content" }.semanticsOwner.rootSemanticsNode.all()
    }

    private fun SemanticsNode.says(text: String) =
        config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true ||
            config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true

    /** Whether anything on the screen says [text]. On the main thread. */
    private fun shows(text: String) = semantics().any { it.says(text) }

    /** The button saying [text], if Compose has it. On the main thread. */
    private fun button(text: String): SemanticsNode? = semantics().firstOrNull { it.says(text) && SemanticsActions.OnClick in it.config }

    /** What the text fields on the screen hold. On the main thread. */
    private fun fieldTexts(): List<String> = semantics().mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }

    /** Whether the button saying [text] is there and can be pressed. On the main thread. */
    private fun enabled(text: String) = button(text)?.let { SemanticsProperties.Disabled !in it.config } == true

    private fun press(text: String) = onMain {
        val click = checkNotNull(button(text)?.config?.getOrNull(SemanticsActions.OnClick)?.action) { "No \"$text\"" }
        click()
    }

    private fun info(doc: PDDocument) = doc.document.trailer.getDictionaryObject(COSName.INFO) as? COSDictionary

    /** The bytes of each part of [doc]'s trailer /ID. */
    private fun idOf(doc: PDDocument): List<ByteArray> = (doc.document.trailer.getDictionaryObject(COSName.ID) as COSArray).let { id ->
        List(id.size()) { (id.getObject(it) as COSString).bytes }
    }

    /** Checks [doc]'s /ID is a new one as Document info makes it (two equal parts of 16 bytes), and returns a part. */
    private fun assertRandomId(doc: PDDocument): ByteArray {
        val id = idOf(doc)
        assertEquals(2, id.size)
        assertEquals(16, id[0].size)
        assertArrayEquals(id[0], id[1])
        return id[0]
    }

    /** Checks [doc] has the fixture's two pages, the second turned and cropped, and its link and bookmark to it. */
    private fun assertLayoutKept(doc: PDDocument) {
        assertEquals(2, doc.numberOfPages)
        val second = doc.getPage(1)
        assertEquals(90, second.rotation)
        second.cropBox.let { assertEquals(listOf(50f, 60f, 550f, 710f), listOf(it.lowerLeftX, it.lowerLeftY, it.upperRightX, it.upperRightY)) }
        val link = doc.getPage(0).annotations.single() as PDAnnotationLink
        assertEquals(1, (link.destination as PDPageDestination).retrievePageNumber())
        val bookmark = doc.documentCatalog.documentOutline.firstChild
        assertEquals("Chapter TWO", bookmark.title)
        assertEquals(1, (bookmark.destination as PDPageDestination).retrievePageNumber())
    }

    /** The names of the /Metadata, /PieceInfo and /CreatorInfo entries of every dictionary reachable from [doc]'s trailer. */
    private fun leftovers(doc: PDDocument): List<String> {
        val keys = listOf(COSName.METADATA, COSName.PIECE_INFO, COSName.getPDFName("CreatorInfo"))
        val found = ArrayList<String>()
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val pending = ArrayDeque<COSBase?>().apply { add(doc.document.trailer) }
        while (pending.isNotEmpty()) {
            val item = pending.removeLast().let { if (it is COSObject) it.getObject() else it }
            if ((item !is COSDictionary && item !is COSArray) || !seen.add(item)) continue
            if (item is COSDictionary) {
                keys.filter { item.containsKey(it) }.mapTo(found) { it.name }
                pending.addAll(item.values)
            } else {
                (item as COSArray).forEach { pending.add(it) }
            }
        }
        return found
    }

    private fun hex(text: String) = text.toByteArray(Charsets.US_ASCII).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        /** The trailer /ID's two parts. */
        val ID_BYTES = ByteArray(16) { (it * 17 + 3).toByte() }

        /** What the fixture says of its maker, in /Info, XMP, /PieceInfo and a layer: none of it left after Remove all. */
        val SECRETS = listOf(
            "SecretTitle", "SecretAuthor", "SecretTool", "SecretDept", "SecretRights", "SecretPageXmp",
            "SecretImageXmp", "SecretPiece", "SecretLayerTool",
        )

        /** The catalog's packet: a PDF/A-1 file's, with a title, an author and rights. */
        val CATALOG_XMP = """
            <?xpacket begin="${'﻿'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
            <x:xmpmeta xmlns:x="adobe:ns:meta/">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/"
                xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/">
               <dc:title><rdf:Alt><rdf:li xml:lang="x-default">SecretXmpTitle</rdf:li></rdf:Alt></dc:title>
               <dc:creator><rdf:Seq><rdf:li>SecretXmpAuthor</rdf:li></rdf:Seq></dc:creator>
               <dc:rights><rdf:Alt><rdf:li xml:lang="x-default">SecretRights</rdf:li></rdf:Alt></dc:rights>
               <pdfaid:part>1</pdfaid:part>
              </rdf:Description>
             </rdf:RDF>
            </x:xmpmeta>
            <?xpacket end="w"?>
        """.trimIndent()
    }
}
