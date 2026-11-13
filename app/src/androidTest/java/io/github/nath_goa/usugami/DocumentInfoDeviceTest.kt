package io.github.nath_goa.usugami

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
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
import io.github.nath_goa.usugami.data.InfoDate
import io.github.nath_goa.usugami.data.PageMark
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.PdfToolsEngine
import io.github.nath_goa.usugami.data.documentDetails
import io.github.nath_goa.usugami.data.setDocumentInfo
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
 * and XMP; after Remove all info, none of the info a PDF keeps of its making).
 */
class DocumentInfoDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val engine = PdfToolsEngine(app)
    private val marks = PageMark(app)
    private val dir = File(app.cacheDir, "test-docinfo").apply { mkdirs() }
    private val imported = ArrayList<PdfInput>()
    private var nextId = 9101L

    @After fun tearDown() {
        imported.forEach(engine::delete)
        engine.clearOutput()
        dir.deleteRecursively()
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
