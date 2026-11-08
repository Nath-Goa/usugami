package io.github.nath_goa.usugami.core

/**
 * The kinds of image file Images to PDF takes in, told apart by their first bytes rather than by
 * a name or a type another app gives, which can be wrong.
 */
enum class ImageFileType {
    JPEG,
    PNG,
    WEBP,
    BMP,
    GIF,

    /** HEIC and other HEIF files, as phones save photos. */
    HEIF,
    AVIF,
    ;

    companion object {
        /** Enough of the start of a file for [of], with the brands of a HEIF or AVIF file. */
        const val HEADER_BYTES = 64

        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

        /** The sizes the header of a BMP file's picture can have, one per version of the format. */
        private val BMP_HEADER_SIZES = setOf(12L, 16L, 40L, 52L, 56L, 64L, 108L, 124L)

        private val AVIF_BRANDS = setOf("avif", "avis")
        private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1")

        /** The type of the file starting with [header]; null when it is none of these. */
        fun of(header: ByteArray): ImageFileType? = when {
            Jpeg.isJpeg(header) -> JPEG
            header.size >= PNG_SIGNATURE.size && header.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) -> PNG
            header.ascii(0, "RIFF") && header.ascii(8, "WEBP") -> WEBP
            header.ascii(0, "GIF87a") || header.ascii(0, "GIF89a") -> GIF
            header.ascii(0, "BM") && header.size >= 18 && header.u32le(14) in BMP_HEADER_SIZES -> BMP
            header.ascii(4, "ftyp") -> isoType(header)
            else -> null
        }

        /**
         * A HEIF or AVIF file starts with a box listing the brands it follows: its main one, then
         * those it is compatible with. An AVIF file also lists HEIF's own ("mif1"), so AVIF is
         * looked for first.
         */
        private fun isoType(header: ByteArray): ImageFileType? {
            val boxEnd = minOf(header.u32be(0), header.size.toLong()).toInt()
            val brands = buildList {
                if (boxEnd >= 12) add(header.text(8, 4))
                var at = 16
                while (at + 4 <= boxEnd) {
                    add(header.text(at, 4))
                    at += 4
                }
            }
            return when {
                brands.any { it in AVIF_BRANDS } -> AVIF
                brands.any { it in HEIF_BRANDS } -> HEIF
                else -> null
            }
        }

        private fun ByteArray.ascii(at: Int, text: String): Boolean =
            at + text.length <= size && text.indices.all { this[at + it].toInt() == text[it].code }

        private fun ByteArray.text(at: Int, length: Int): String = String(this, at, length, Charsets.ISO_8859_1)

        private fun ByteArray.u32le(at: Int): Long =
            (this[at].toLong() and 0xFF) or ((this[at + 1].toLong() and 0xFF) shl 8) or
                ((this[at + 2].toLong() and 0xFF) shl 16) or ((this[at + 3].toLong() and 0xFF) shl 24)

        private fun ByteArray.u32be(at: Int): Long =
            if (size < at + 4) 0L
            else ((this[at].toLong() and 0xFF) shl 24) or ((this[at + 1].toLong() and 0xFF) shl 16) or
                ((this[at + 2].toLong() and 0xFF) shl 8) or (this[at + 3].toLong() and 0xFF)
    }
}
