package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageFileTypeTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    /** The box a HEIF, AVIF or MP4 file starts with, listing [major] and then [compatible]. */
    private fun ftyp(major: String, vararg compatible: String): ByteArray {
        val size = 16 + 4 * compatible.size
        return byteArrayOf(0, 0, 0, size.toByte()) + bytes("ftyp$major") + ByteArray(4) + bytes(compatible.joinToString("")) + ByteArray(8)
    }

    @Test
    fun picturesAreToldByTheirFirstBytes() {
        assertEquals(ImageFileType.JPEG, ImageFileType.of(byteArrayOf(-1, -40, -1, -32)))
        assertEquals(ImageFileType.PNG, ImageFileType.of(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0)))
        assertEquals(ImageFileType.WEBP, ImageFileType.of(bytes("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals(ImageFileType.GIF, ImageFileType.of(bytes("GIF89a\u0001\u0000")))
        assertEquals(ImageFileType.BMP, ImageFileType.of(bytes("BM\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u00006\u0000\u0000\u0000(\u0000\u0000\u0000")))
        // As an iPhone saves a photo, as a HEIF file of HEIF's brand only, and as AVIF files list theirs.
        assertEquals(ImageFileType.HEIF, ImageFileType.of(ftyp("heic", "mif1", "heic")))
        assertEquals(ImageFileType.HEIF, ImageFileType.of(ftyp("mif1", "mif1")))
        assertEquals(ImageFileType.AVIF, ImageFileType.of(ftyp("avif", "avif", "mif1", "miaf", "MA1B")))
        assertEquals(ImageFileType.AVIF, ImageFileType.of(ftyp("mif1", "mif1", "avif")))
    }

    @Test
    fun otherFilesAreNone() {
        assertNull(ImageFileType.of(ByteArray(0)))
        assertNull("A video", ImageFileType.of(ftyp("isom", "isom", "iso2", "mp41")))
        assertNull("A sound", ImageFileType.of(bytes("RIFF\u0000\u0000\u0000\u0000WAVEfmt ")))
        assertNull("Text starting BM", ImageFileType.of(bytes("BMW cars and other things")))
        assertNull("A TIFF", ImageFileType.of(bytes("II*\u0000\u0008\u0000\u0000\u0000")))
        // A box size that runs past the bytes read lists only the brands within them.
        assertNull(ImageFileType.of(byteArrayOf(0x7F, -1, -1, -1) + bytes("ftypisom")))
    }
}
