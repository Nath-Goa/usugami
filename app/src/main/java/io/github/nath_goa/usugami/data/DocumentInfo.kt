package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.filter.Filter
import com.tom_roush.pdfbox.filter.FilterFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.nath_goa.usugami.core.InfoChange
import io.github.nath_goa.usugami.core.InfoField
import io.github.nath_goa.usugami.core.InfoText
import io.github.nath_goa.usugami.core.PdfDates
import io.github.nath_goa.usugami.core.XmpInfo
import io.github.nath_goa.usugami.core.XmpPacket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/**
 * A date in the PDF's info: [millis] when it could be read as one, [raw] as the PDF has it (cleaned,
 * cut to [InfoText.MAX_SHOWN_RAW_DATE] characters).
 */
data class InfoDate(val millis: Long?, val raw: String)

/**
 * What Document info shows of a PDF. [fields] are as shown (cleaned, cut to [InfoText.MAX_FIELD]
 * characters), every field present and "" when the PDF has none; [cut] names those longer than that
 * in the PDF. [creator] and [producer] are cleaned and cut to [InfoText.MAX_SHOWN_APP] characters,
 * null when the PDF has none. [version] is the PDF version such as "1.7"; [signed] whether a digital
 * signature in it still holds.
 */
data class DocumentDetails(
    val inputId: Long,
    val fields: Map<InfoField, String>,
    val cut: Set<InfoField>,
    val created: InfoDate?,
    val modified: InfoDate?,
    val creator: String?,
    val producer: String?,
    val version: String,
    val sizeBytes: Long,
    val signed: Boolean,
)

/**
 * A PDF's info, read with PDFBox: the trailer's /Info first, the catalog's XMP packet for what /Info
 * lacks; and changed, in both, or removed with all the other info a PDF keeps of its making.
 */
internal object DocumentInfo {
    /**
     * What [doc], the PDF [input], says of itself. Each of the title, author, subject, keywords,
     * creator, producer and the two dates is /Info's when it has one (empty counts as none), else the
     * XMP packet's; a packet that can't be read counts as none. /Info is taken from the trailer as it
     * is: `doc.documentInformation` would put an empty one there.
     */
    fun read(doc: PDDocument, input: PdfInput, signed: Boolean): DocumentDetails {
        val info = doc.document.trailer.getDictionaryObject(COSName.INFO) as? COSDictionary
        // Read only when /Info lacks something.
        val xmp by lazy { xmpOf(doc) }
        fun value(key: COSName, fromXmp: (XmpInfo) -> String?): String? =
            info?.let { text(it.getDictionaryObject(key)) } ?: xmp?.let(fromXmp)
        val texts = mapOf(
            InfoField.TITLE to value(COSName.TITLE) { it.title },
            InfoField.AUTHOR to value(COSName.AUTHOR) { it.author },
            InfoField.SUBJECT to value(COSName.SUBJECT) { it.subject },
            InfoField.KEYWORDS to value(COSName.KEYWORDS) { it.keywords },
        )
        fun date(key: COSName, fromXmp: (XmpInfo) -> String?): InfoDate? {
            val raw = value(key, fromXmp) ?: return null
            return InfoDate(PdfDates.parse(raw, ZoneId.systemDefault()), InfoText.cut(raw, InfoText.MAX_SHOWN_RAW_DATE))
        }
        return DocumentDetails(
            inputId = input.id,
            fields = InfoField.entries.associateWith { InfoText.cut(texts[it] ?: "") },
            cut = InfoField.entries.filter { (texts[it]?.length ?: 0) > InfoText.MAX_FIELD }.toSet(),
            created = date(COSName.CREATION_DATE) { it.created },
            modified = date(COSName.MOD_DATE) { it.modified },
            creator = value(COSName.CREATOR) { it.creatorTool }?.let { InfoText.cut(it, InfoText.MAX_SHOWN_APP) },
            producer = value(COSName.PRODUCER) { it.producer }?.let { InfoText.cut(it, InfoText.MAX_SHOWN_APP) },
            version = String.format(Locale.ROOT, "%.1f", doc.version),
            sizeBytes = input.file.length(),
            signed = signed,
        )
    }

    /**
     * Changes [doc] in memory as [change] says, and returns whether an XMP packet that couldn't be
     * read was dropped.
     *
     * Without [InfoChange.removeAll], only the fields in [InfoChange.texts] change, in /Info and in
     * the catalog's XMP packet (patched by [XmpPacket.withTexts], so everything else in it stays):
     * a text sets its field, "" removes it. A PDF without XMP gets none; a packet that can't be read
     * is removed, since it couldn't be kept the same as /Info. Nothing else is touched (the
     * producer, the creator, the dates and the trailer's /ID stay as they are), but a PDF without a
     * usable /ID gets a random one: saved without, PDFBox would make one from the clock.
     *
     * With [InfoChange.removeAll], /Info, the catalog's /SpiderInfo and, wherever they are in the
     * PDF, every XMP /Metadata stream, /PieceInfo and /CreatorInfo go, with the /LastModified date
     * of pages and form XObjects; the /ID is replaced by a random one. The texts in
     * [InfoChange.texts] that aren't empty are then the PDF's only info, in a new /Info. [check] is
     * called as the PDF's objects are visited, so Cancel stops it.
     */
    fun apply(doc: PDDocument, change: InfoChange, check: () -> Unit): Boolean {
        val trailer = doc.document.trailer
        if (change.removeAll) {
            removeAll(doc, check)
            setTexts(trailer, change.texts.filterValues { it.isNotEmpty() })
            return false
        }
        if (!hasId(trailer)) trailer.setItem(COSName.ID, randomId())
        if (change.texts.isEmpty()) return false
        setTexts(trailer, change.texts)
        return patchXmp(doc, change.texts)
    }

    /**
     * Sets [texts] in the trailer's /Info, made when there is none (or it isn't a dictionary) and a
     * text isn't empty; "" removes the field, and an /Info left empty is taken out.
     */
    private fun setTexts(trailer: COSDictionary, texts: Map<InfoField, String>) {
        if (texts.isEmpty()) return
        var info = trailer.getDictionaryObject(COSName.INFO) as? COSDictionary
        if (info == null) {
            if (texts.values.all { it.isEmpty() }) return
            info = COSDictionary()
            trailer.setItem(COSName.INFO, info)
        }
        for ((field, text) in texts) {
            val key = keyOf(field)
            if (text.isEmpty()) info.removeItem(key) else info.setItem(key, COSString(InfoText.bytes(text)))
        }
        if (info.size() == 0) trailer.removeItem(COSName.INFO)
    }

    private fun keyOf(field: InfoField): COSName = when (field) {
        InfoField.TITLE -> COSName.TITLE
        InfoField.AUTHOR -> COSName.AUTHOR
        InfoField.SUBJECT -> COSName.SUBJECT
        InfoField.KEYWORDS -> COSName.KEYWORDS
    }

    /**
     * Writes [texts] into the catalog's XMP packet, in a new stream not compressed (as XMP is meant
     * to be kept), and returns false; a packet that can't be read is removed, and true returned. A
     * catalog without an XMP stream is left as it is.
     */
    private fun patchXmp(doc: PDDocument, texts: Map<InfoField, String>): Boolean {
        val catalog = doc.documentCatalog.cosObject
        if (catalog.getDictionaryObject(COSName.METADATA) !is COSStream) return false
        val patched = packetOf(catalog)?.let { XmpPacket.withTexts(it, texts) }
        if (patched == null) {
            catalog.removeItem(COSName.METADATA)
            return true
        }
        val stream = doc.document.createCOSStream()
        stream.setItem(COSName.TYPE, COSName.METADATA)
        stream.setItem(COSName.SUBTYPE, XML)
        stream.createOutputStream().use { it.write(patched) }
        catalog.setItem(COSName.METADATA, stream)
        return false
    }

    /**
     * Takes out of [doc] everything Remove all info removes (see [apply]) but the texts: /Info, the
     * catalog's /SpiderInfo, and in every dictionary reachable from the trailer (streams' included)
     * an XMP /Metadata stream, /PieceInfo, /CreatorInfo, and a page's or form XObject's
     * /LastModified; the /ID becomes a random one. Each object is visited once, without recursion,
     * however deep the PDF; [check] is called every [CHECK_EVERY] objects.
     */
    private fun removeAll(doc: PDDocument, check: () -> Unit) {
        val trailer = doc.document.trailer
        trailer.removeItem(COSName.INFO)
        (trailer.getDictionaryObject(COSName.ROOT) as? COSDictionary)?.removeItem(SPIDER_INFO)
        val seen = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        val pending = ArrayDeque<COSBase>()
        seen.add(trailer)
        pending.push(trailer)
        var visited = 0
        while (pending.isNotEmpty()) {
            if (++visited % CHECK_EVERY == 0) check()
            when (val item = pending.pop()) {
                is COSDictionary -> {
                    strip(item)
                    for (value in item.values) push(value, seen, pending)
                }
                is COSArray -> for (value in item) push(value, seen, pending)
            }
        }
        trailer.setItem(COSName.ID, randomId())
    }

    /** Puts what [value] is (a reference followed) on [pending] when it is a dictionary or an array not yet [seen]. */
    private fun push(value: COSBase?, seen: MutableSet<COSBase>, pending: ArrayDeque<COSBase>) {
        val item = if (value is COSObject) value.getObject() else value
        if ((item is COSDictionary || item is COSArray) && seen.add(item)) pending.push(item)
    }

    /** Removes from [dict] the entries that say how and with what it was made (see [removeAll]). */
    private fun strip(dict: COSDictionary) {
        if (dict.getDictionaryObject(COSName.METADATA) is COSStream) dict.removeItem(COSName.METADATA)
        dict.removeItem(COSName.PIECE_INFO)
        dict.removeItem(CREATOR_INFO)
        if (dict.getCOSName(COSName.TYPE) == COSName.PAGE || dict.getCOSName(COSName.SUBTYPE) == COSName.FORM) {
            dict.removeItem(COSName.LAST_MODIFIED)
        }
    }

    /** Whether [trailer]'s /ID is what a PDF must have: an array of two strings. */
    internal fun hasId(trailer: COSDictionary): Boolean {
        val id = trailer.getDictionaryObject(COSName.ID) as? COSArray ?: return false
        return id.size() == 2 && id.getObject(0) is COSString && id.getObject(1) is COSString
    }

    /**
     * A new /ID: 16 random bytes, twice (both parts are the same in a PDF written for the first time).
     * Random, so it neither ties the PDF to the one it came from nor gives away when it was saved.
     */
    internal fun randomId(): COSArray {
        val id = COSString(ByteArray(ID_BYTES).also { SecureRandom().nextBytes(it) })
        id.setForceHexForm(true)
        return COSArray().apply {
            add(id)
            add(id)
        }
    }

    private val XML = COSName.getPDFName("XML")
    private val SPIDER_INFO = COSName.getPDFName("SpiderInfo")
    private val CREATOR_INFO = COSName.getPDFName("CreatorInfo")

    /** How many objects Remove all visits between two calls of its check. */
    private const val CHECK_EVERY = 1000

    /** The bytes of each half of a new /ID. */
    private const val ID_BYTES = 16

    /**
     * An info entry's text, cleaned, or null when it is empty or neither a string nor a name. A string
     * with a Unicode byte order mark is read by it (PDFBox doesn't know PDF 2.0's UTF-8 one), any other
     * by PDFBox, as PDFDocEncoding.
     */
    private fun text(value: COSBase?): String? {
        val raw = when (value) {
            is COSString -> InfoText.unicode(value.bytes) ?: value.string
            is COSName -> value.name
            else -> return null
        }
        return InfoText.clean(raw).ifEmpty { null }
    }

    /** What the catalog's XMP packet says, or null when there is none or it can't be read. */
    private fun xmpOf(doc: PDDocument): XmpInfo? =
        packetOf(doc.documentCatalog.cosObject)?.let { XmpPacket.read(it) }

    /**
     * The bytes of [catalog]'s XMP packet, or null when it has none, it is longer than
     * [XmpPacket.MAX_BYTES], or its stream can't be read. A packet not compressed is read up to one
     * byte more than that and not on. A compressed one is decoded here, filter by filter, with PDFBox's
     * own filters but into memory, each stopped as soon as it passes its limit: [XmpPacket.MAX_BYTES]
     * for the last, [STAGE_BYTES] for one before it. PDFBox's `createInputStream` would decode the whole
     * stream into its scratch file first, so a packet of a few megabytes that inflates to gigabytes
     * would fill the phone's storage before it was refused. Only the filters text is written with are
     * decoded, each once (an image filter allocates its picture before it writes a byte); any other
     * filter, a filter named twice or one that fails gives null.
     */
    private fun packetOf(catalog: COSDictionary): ByteArray? {
        val stream = catalog.getDictionaryObject(COSName.METADATA) as? COSStream ?: return null
        return try {
            val filters = filtersOf(stream) ?: return null
            if (filters.isEmpty()) {
                val bytes = stream.createInputStream().use { readAtMost(it, XmpPacket.MAX_BYTES + 1) }
                return bytes.takeIf { it.size <= XmpPacket.MAX_BYTES }
            }
            stream.createRawInputStream().use { raw ->
                var input: InputStream = raw
                var output = Capped(0)
                for ((index, filter) in filters.withIndex()) {
                    output = Capped(if (index == filters.lastIndex) XmpPacket.MAX_BYTES else STAGE_BYTES)
                    filter.decode(input, output, stream, index)
                    input = output.inputStream()
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [stream]'s filters as PDFBox reads /Filter (a name, an array of names, anything else none), or
     * null when one isn't a name, isn't one of [TEXT_FILTERS] or comes twice.
     */
    private fun filtersOf(stream: COSStream): List<Filter>? {
        val names = when (val filter = stream.filters) {
            is COSName -> listOf(filter)
            is COSArray -> List(filter.size()) { filter.getObject(it) as? COSName ?: return null }
            else -> emptyList()
        }
        if (names.any { it !in TEXT_FILTERS }) return null
        // A name and its abbreviation give the same filter.
        val filters = names.map { FilterFactory.INSTANCE.getFilter(it) }
        return filters.takeIf { it.toSet().size == it.size }
    }

    /** The filters a packet of text can be written with. */
    private val TEXT_FILTERS = setOf(
        COSName.FLATE_DECODE, COSName.FLATE_DECODE_ABBREVIATION, COSName.LZW_DECODE, COSName.LZW_DECODE_ABBREVIATION,
        COSName.ASCII85_DECODE, COSName.ASCII85_DECODE_ABBREVIATION, COSName.ASCII_HEX_DECODE,
        COSName.ASCII_HEX_DECODE_ABBREVIATION, COSName.RUN_LENGTH_DECODE, COSName.RUN_LENGTH_DECODE_ABBREVIATION,
        COSName.CRYPT,
    )

    /** What a filter before the last may give: room for a packet written in hex before it was compressed. */
    private const val STAGE_BYTES = 4 * XmpPacket.MAX_BYTES

    /** Bytes in memory, up to [limit]: a write past it throws (not an EOFException, which LZWDecode takes as the end). */
    private class Capped(private val limit: Int) : ByteArrayOutputStream() {
        override fun write(b: Int) {
            if (count >= limit) throw TooLong()
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len > limit - count) throw TooLong()
            super.write(b, off, len)
        }

        fun inputStream(): InputStream = ByteArrayInputStream(buf, 0, count)
    }

    private class TooLong : IOException("Longer than an XMP packet may be")

    private fun readAtMost(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

/** What Document info shows of [input]; [marks] tells whether it has a signature that still holds. */
internal fun PdfToolsEngine.documentDetails(input: PdfInput, marks: PageMark): DocumentDetails =
    load(input.file, input.password).use { doc -> DocumentInfo.read(doc, input, signed = marks.isSigned(doc, input.file)) }

/**
 * Saves [input] with its info changed as [change] says (see [DocumentInfo.apply]), as [fileName],
 * without a password. The result says whether an XMP packet that couldn't be read was dropped.
 */
internal fun PdfToolsEngine.setDocumentInfo(input: PdfInput, change: InfoChange, fileName: String, check: () -> Unit = {}): InfoResult {
    var dropped = false
    val file = edit(input, fileName, "Document_info", check) { doc -> dropped = DocumentInfo.apply(doc, change, check) }
    return InfoResult(file, dropped)
}

/** A PDF Document info made: [file], and whether the XMP packet of the PDF it came from was dropped, as it couldn't be read. */
internal class InfoResult(val file: PdfFile, val droppedXmp: Boolean)
