package io.github.nath_goa.usugami.data

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.filter.Filter
import com.tom_roush.pdfbox.filter.FilterFactory
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.nath_goa.usugami.core.InfoField
import io.github.nath_goa.usugami.core.InfoText
import io.github.nath_goa.usugami.core.PdfDates
import io.github.nath_goa.usugami.core.XmpInfo
import io.github.nath_goa.usugami.core.XmpPacket
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
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

/** A PDF's info, read with PDFBox: the trailer's /Info first, the catalog's XMP packet for what /Info lacks. */
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

/** Saves [input] with its title, author and the like changed or removed. The lane fills it in: they are saved as they are. */
internal fun PdfToolsEngine.setDocumentInfo(input: PdfInput, fileName: String, check: () -> Unit = {}): PdfFile =
    edit(input, fileName, "Document_info", check) { }
