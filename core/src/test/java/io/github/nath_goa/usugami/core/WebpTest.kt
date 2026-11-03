package io.github.nath_goa.usugami.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class WebpTest {
    private fun chunk(name: String, size: Int, first: Int = 0): ByteArray = ByteArrayOutputStream().apply {
        write(name.toByteArray(Charsets.US_ASCII))
        write(byteArrayOf(size.toByte(), (size shr 8).toByte(), (size shr 16).toByte(), (size shr 24).toByte()))
        // Padded to an even length, as WebP chunks are.
        write(ByteArray(size + (size and 1)).also { if (it.isNotEmpty()) it[0] = first.toByte() })
    }.toByteArray()

    private fun webp(vararg chunks: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write("RIFF".toByteArray(Charsets.US_ASCII))
        write(ByteArray(4))
        write("WEBP".toByteArray(Charsets.US_ASCII))
        chunks.forEach { write(it) }
    }.toByteArray()

    @Test
    fun lossyPhotosAreTold() {
        assertTrue(Webp.isLossyOpaque(webp(chunk("VP8 ", 30))))
        // Extended, with a colour profile (odd-sized, so padded) and EXIF data before the picture.
        assertTrue(Webp.isLossyOpaque(webp(chunk("VP8X", 10, first = 0x28), chunk("ICCP", 3001), chunk("VP8 ", 30), chunk("EXIF", 40))))
    }

    @Test
    fun losslessSeeThroughAndAnimatedAreNot() {
        assertFalse(Webp.isLossyOpaque(webp(chunk("VP8L", 30))))
        assertFalse("Alpha flag", Webp.isLossyOpaque(webp(chunk("VP8X", 10, first = 0x10), chunk("ALPH", 12), chunk("VP8 ", 30))))
        assertFalse("Alpha chunk", Webp.isLossyOpaque(webp(chunk("VP8X", 10), chunk("ALPH", 12), chunk("VP8 ", 30))))
        assertFalse("Animated", Webp.isLossyOpaque(webp(chunk("VP8X", 10, first = 0x02), chunk("ANIM", 6), chunk("ANMF", 40))))
        assertFalse(Webp.isLossyOpaque(webp(chunk("VP8X", 10), chunk("VP8L", 30))))
    }

    @Test
    fun otherOrCutShortFilesAreNot() {
        assertFalse(Webp.isLossyOpaque(ByteArray(0)))
        assertFalse(Webp.isLossyOpaque("RIFF\u0000\u0000\u0000\u0000WAVEfmt ".toByteArray(Charsets.ISO_8859_1)))
        // The picture's chunk lies beyond the bytes read.
        assertFalse(Webp.isLossyOpaque(webp(chunk("VP8X", 10), chunk("ICCP", 1_000_000).copyOf(400))))
        // A chunk size that runs past the end of any file.
        assertFalse(Webp.isLossyOpaque(webp(chunk("VP8X", 10)) + "ICCP".toByteArray(Charsets.US_ASCII) + byteArrayOf(-1, -1, -1, -1)))
    }
}
