package io.github.nathgoa.pdfer.core

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
                marker == 0xEE && dataLength >= 5 && bytes.startsWithAscii(data, "Adobe") -> adobe = true
            }
            true
        }
        val info = frame ?: throw InvalidJpegException("No frame header")
        return info.copy(hasAdobeMarker = adobe)
    }

    /**
     * Returns the image prepared for embedding: the primary image only, ending at its EOI marker
     * (dropping anything appended after it, such as the video of a "motion photo" or MPF
     * secondary images), without EXIF/XMP (APP1), Photoshop/IPTC (APP13), comments and MPF
     * index (APP2 "MPF") segments, which hold metadata like GPS location. Image data is copied
     * byte for byte, so there is no quality loss.
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
                val data = start + 4
                val drop = marker == 0xE1 || marker == 0xED || marker == 0xFE ||
                    (marker == 0xE2 && length >= 6 && bytes.startsWithAscii(data, "MPF\u0000"))
                if (!drop) out.write(bytes, start, 2 + length)
                true
            }
        }
        if (scanStart < 0) throw InvalidJpegException("No image data")
        val end = findEndOfImage(bytes, scanStart)
        if (end > 0) {
            out.write(bytes, scanStart, end - scanStart)
        } else {
            // Truncated file: keep what there is and close it so decoders show the partial image.
            out.write(bytes, scanStart, bytes.size - scanStart)
            out.write(0xFF)
            out.write(0xD9)
        }
        return out.toByteArray()
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
     * Walks entropy-coded data and any further segments (progressive scans, tables) starting at
     * the first SOS, and returns the offset just past the EOI marker, or -1 if there is none.
     */
    private fun findEndOfImage(bytes: ByteArray, firstScan: Int): Int {
        var pos = firstScan + 2 + bytes.u16(firstScan + 2)
        while (pos < bytes.size) {
            if (bytes.u8(pos) != 0xFF) {
                pos++
                continue
            }
            var next = pos + 1
            while (next < bytes.size && bytes.u8(next) == 0xFF) next++
            if (next >= bytes.size) return -1
            val marker = bytes.u8(next)
            when {
                marker == 0x00 || marker in 0xD0..0xD7 -> pos = next + 1 // stuffed byte or restart marker
                marker == 0xD9 -> return next + 1
                else -> {
                    if (next + 3 > bytes.size) return -1
                    val length = bytes.u16(next + 1)
                    if (length < 2) return -1
                    pos = next + 1 + length
                }
            }
        }
        return -1
    }

    private fun isFrameMarker(marker: Int) =
        marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

    private const val SOS = 0xDA
}

private fun ByteArray.u8(index: Int) = this[index].toInt() and 0xFF
private fun ByteArray.u16(index: Int) = (u8(index) shl 8) or u8(index + 1)
private fun ByteArray.startsWithAscii(index: Int, text: String): Boolean {
    if (index + text.length > size) return false
    return text.indices.all { u8(index + it) == text[it].code }
}
