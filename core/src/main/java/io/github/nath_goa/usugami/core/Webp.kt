package io.github.nath_goa.usugami.core

/** What a WebP file's header says about the picture in it. */
object Webp {
    /** Enough of the start of a file for [isLossyOpaque], even after a colour profile. */
    const val HEADER_BYTES = 64 * 1024

    /**
     * Whether [header], the start of a WebP file, holds a lossy picture (a photo, nearly always)
     * without transparency or animation: one that loses nothing more by being stored as JPEG.
     * Lossless, see-through and animated pictures, and anything that can't be read this far, are
     * false.
     */
    fun isLossyOpaque(header: ByteArray): Boolean {
        if (header.size < 16 || !header.ascii(0, "RIFF") || !header.ascii(8, "WEBP")) return false
        // The simple format: a single lossy ("VP8 ") or lossless ("VP8L") picture.
        if (header.ascii(12, "VP8 ")) return true
        if (!header.ascii(12, "VP8X") || header.size < 21) return false
        // The extended format: flags for alpha and animation, then chunks up to the picture's own.
        val flags = header[20].toInt()
        if (flags and (ALPHA or ANIMATION) != 0) return false
        var at = 12
        while (at + 8 <= header.size) {
            when {
                header.ascii(at, "VP8 ") -> return true
                header.ascii(at, "VP8L") || header.ascii(at, "ALPH") -> return false
            }
            val size = header.u32le(at + 4)
            // Chunks are padded to an even length.
            val next = at + 8L + size + (size and 1L)
            if (next > Int.MAX_VALUE) return false
            at = next.toInt()
        }
        return false
    }

    private const val ALPHA = 0x10
    private const val ANIMATION = 0x02

    private fun ByteArray.ascii(at: Int, text: String): Boolean =
        at >= 0 && at + text.length <= size && text.indices.all { this[at + it].toInt() == text[it].code }

    private fun ByteArray.u32le(at: Int): Long =
        (this[at].toLong() and 0xFF) or ((this[at + 1].toLong() and 0xFF) shl 8) or
            ((this[at + 2].toLong() and 0xFF) shl 16) or ((this[at + 3].toLong() and 0xFF) shl 24)
}
