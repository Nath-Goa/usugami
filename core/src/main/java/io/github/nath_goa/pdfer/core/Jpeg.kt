package io.github.nath_goa.pdfer.core

import java.io.ByteArrayOutputStream

/** Header facts about a JPEG file needed to embed it in a PDF unchanged. */
data class JpegInfo(
    val width: Int,
    val height: Int,
    val components: Int,
    val precision: Int,
    /** The SOFn marker byte, e.g. 0xC0 baseline, 0xC2 progressive. */
    val frameMarker: Int,
    val componentIds: List<Int>,
    val hasAdobeMarker: Boolean,
) {
    /**
     * Whether PDF's DCTDecode filter can show this image exactly as stored: 8-bit Huffman coded
     * (baseline, extended or progressive) grayscale or colour. Anything else (CMYK, 12-bit,
     * lossless, arithmetic coding) is decoded and re-encoded instead.
     */
    val canEmbedDirectly: Boolean
        get() = frameMarker in DIRECT_FRAMES && precision == 8 && (components == 1 || components == 3) &&
            width > 0 && height > 0

    /**
     * A 3-component JPEG whose components are labelled 'R','G','B' and that has no Adobe marker
     * holds plain RGB. PDF would otherwise assume YCbCr, so ColorTransform 0 must be given.
     */
    val isPlainRgb: Boolean
        get() = components == 3 && !hasAdobeMarker && componentIds == listOf('R'.code, 'G'.code, 'B'.code)

    private companion object {
        val DIRECT_FRAMES = setOf(0xC0, 0xC1, 0xC2)
    }
}

class InvalidJpegException(message: String) : Exception(message)

object Jpeg {

    fun isJpeg(header: ByteArray): Boolean =
        header.size >= 3 && header.u8(0) == 0xFF && header.u8(1) == 0xD8 && header.u8(2) == 0xFF

    /** Reads the frame header. Throws [InvalidJpegException] if there is none before the first scan. */
    fun parse(bytes: ByteArray): JpegInfo {
        if (bytes.size < 4 || bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) throw InvalidJpegException("Not a JPEG file")
        var frame: JpegInfo? = null
        var adobe = false
        forEachHeaderSegment(bytes) { marker, start, length ->
            val data = start + 4 // after FF xx LL LL
            val dataLength = length - 2
            when {
                isFrameMarker(marker) -> {
                    if (frame == null) {
                        if (dataLength < 6) throw InvalidJpegException("Truncated frame header")
                        val components = bytes.u8(data + 5)
                        if (dataLength < 6 + 3 * components) throw InvalidJpegException("Truncated frame header")
                        frame = JpegInfo(
                            width = bytes.u16(data + 3),
                            height = bytes.u16(data + 1),
                            components = components,
                            precision = bytes.u8(data),
                            frameMarker = marker,
                            componentIds = List(components) { bytes.u8(data + 6 + 3 * it) },
                            hasAdobeMarker = false,
                        )
                    }
                }
                marker == APP14 && dataLength >= 5 && bytes.startsWithAscii(data, "Adobe") -> adobe = true
            }
            true
        }
        val info = frame ?: throw InvalidJpegException("No frame header")
        return info.copy(hasAdobeMarker = adobe)
    }

    /**
     * Returns the image prepared for embedding: the primary image only, ending at its EOI marker
     * (dropping anything appended after it, such as the video of a "motion photo" or MPF
     * secondary images), with only the segments decoders need (see [writeIfNeeded]). Everything
     * else, such as EXIF and XMP with the GPS location, is left out. Image data is copied byte for
     * byte, so there is no quality loss.
     */
    fun stripForEmbedding(bytes: ByteArray): ByteArray {
        if (bytes.size < 4 || bytes.u8(0) != 0xFF || bytes.u8(1) != 0xD8) throw InvalidJpegException("Not a JPEG file")
        val out = ByteArrayOutputStream(bytes.size)
        out.write(0xFF)
        out.write(0xD8)
        var scanStart = -1
        forEachHeaderSegment(bytes) { marker, start, length ->
            if (marker == SOS) {
                scanStart = start
                false
            } else {
                writeIfNeeded(bytes, marker, start, length, out)
                true
            }
        }
        if (scanStart < 0) throw InvalidJpegException("No image data")
        copyScans(bytes, scanStart, out)
        return out.toByteArray()
    }

    /**
     * Copies the segment at [start] to [out] if decoders need it: every segment except
     * application data (APPn) and comments, plus three kinds of APPn that affect colours: the
     * JFIF header (APP0, its thumbnail removed, as it could show the photo before it was edited),
     * ICC profiles (APP2, which may span several segments) and the Adobe marker (APP14). Left out
     * are EXIF and XMP (APP1), MPF and FlashPix (APP2), content credentials (APP11),
     * Photoshop/IPTC (APP13), other vendor data and comments.
     */
    private fun writeIfNeeded(bytes: ByteArray, marker: Int, start: Int, length: Int, out: ByteArrayOutputStream) {
        fun hasId(id: String) = length - 2 >= id.length && bytes.startsWithAscii(start + 4, id)
        when {
            marker != COM && marker !in APP0..APP15 -> out.write(bytes, start, 2 + length)
            marker == APP0 && length >= JFIF_LENGTH && hasId("JFIF\u0000") -> {
                val jfif = bytes.copyOfRange(start, start + 2 + JFIF_LENGTH)
                jfif[2] = 0
                jfif[3] = JFIF_LENGTH.toByte()
                jfif[16] = 0 // thumbnail width
                jfif[17] = 0 // thumbnail height
                out.write(jfif)
            }
            (marker == APP2 && hasId("ICC_PROFILE\u0000")) || (marker == APP14 && hasId("Adobe")) ->
                out.write(bytes, start, 2 + length)
        }
    }

    /**
     * Calls [visit] with (marker, offset of its 0xFF, segment length) for each length-prefixed
     * segment from just after SOI up to and including the first SOS. Stops early when [visit]
     * returns false.
     */
    private inline fun forEachHeaderSegment(bytes: ByteArray, visit: (marker: Int, start: Int, length: Int) -> Boolean) {
        var pos = 2
        while (true) {
            // Tolerate stray bytes between segments (libjpeg does too), then skip fill bytes.
            while (pos < bytes.size && bytes.u8(pos) != 0xFF) pos++
            while (pos + 1 < bytes.size && bytes.u8(pos + 1) == 0xFF) pos++
            if (pos + 1 >= bytes.size) throw InvalidJpegException("No image data")
            val start = pos
            val marker = bytes.u8(pos + 1)
            pos += 2
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) continue // no length
            if (marker == 0xD9) throw InvalidJpegException("No image data")
            if (pos + 2 > bytes.size) throw InvalidJpegException("Truncated segment")
            val length = bytes.u16(pos)
            if (length < 2 || pos + length > bytes.size) throw InvalidJpegException("Truncated segment")
            if (!visit(marker, start, length)) return
            if (marker == SOS) return
            pos += length
        }
    }

    /**
     * Copies everything from the first SOS through the EOI marker: entropy-coded data and any
     * further segments (progressive scans, tables), filtered by [writeIfNeeded] like the header.
     * A truncated file is copied as far as it goes (up to a cut-off segment) and closed so
     * decoders show the partial image.
     */
    private fun copyScans(bytes: ByteArray, firstScan: Int, out: ByteArrayOutputStream) {
        var copied = firstScan // everything before this has been written or left out
        var end = bytes.size // where the copy stops if there is no EOI
        var pos = firstScan + 2 + bytes.u16(firstScan + 2)
        while (pos < bytes.size) {
            if (bytes.u8(pos) != 0xFF) {
                pos++
                continue
            }
            var next = pos + 1
            while (next < bytes.size && bytes.u8(next) == 0xFF) next++
            if (next >= bytes.size) break
            val marker = bytes.u8(next)
            when {
                marker == 0x00 || marker in 0xD0..0xD7 -> pos = next + 1 // stuffed byte or restart marker
                marker == 0xD9 -> {
                    out.write(bytes, copied, next + 1 - copied)
                    return
                }
                else -> {
                    val start = next - 1
                    val length = if (next + 3 <= bytes.size) bytes.u16(next + 1) else 0
                    if (length < 2 || start + 2 + length > bytes.size) {
                        end = start
                        break
                    }
                    out.write(bytes, copied, start - copied)
                    writeIfNeeded(bytes, marker, start, length, out)
                    copied = start + 2 + length
                    pos = copied
                }
            }
        }
        out.write(bytes, copied, end - copied)
        out.write(0xFF)
        out.write(0xD9)
    }

    private fun isFrameMarker(marker: Int) =
        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    private const val SOS = 0xDA
    private const val COM = 0xFE
    private const val APP0 = 0xE0
    private const val APP2 = 0xE2
    private const val APP14 = 0xEE
    private const val APP15 = 0xEF

    /** Length field of a JFIF segment without a thumbnail: itself (2) plus 14 bytes of header. */
    private const val JFIF_LENGTH = 16
}

private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF
private fun ByteArray.u16(index: Int) = (u8(index) shl 8) or u8(index + 1)
private fun ByteArray.startsWithAscii(index: Int, text: String): Boolean {
    if (index + text.length > size) return false
    return text.indices.all { u8(index + it) == text[it].code }
}
