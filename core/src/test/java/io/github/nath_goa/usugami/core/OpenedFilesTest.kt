package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenedFilesTest {

    private fun bytes(prefix: ByteArray, text: String = "", size: Int = OpenedFiles.HEADER): ByteArray =
        (prefix + text.toByteArray(Charsets.US_ASCII)).copyOf(maxOf(size, prefix.size + text.length))

    @Test
    fun aPdfIsToldByItsHeaderInTheFirstKilobyte() {
        assertTrue(OpenedFiles.isPdf("%PDF-1.7\n".toByteArray()))
        // After a byte order mark and a blank line, as some servers send it.
        assertTrue(OpenedFiles.isPdf(bytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), "\r\n%PDF-1.4")))
        // Ending at byte 1024, the last that is looked at.
        assertTrue(OpenedFiles.isPdf(bytes(ByteArray(1019) { ' '.code.toByte() }, "%PDF-", size = 2048)))
        // Starting at byte 1021, it doesn't fit in the first kilobyte.
        assertFalse(OpenedFiles.isPdf(bytes(ByteArray(1021) { ' '.code.toByte() }, "%PDF-", size = 2048)))
        assertFalse(OpenedFiles.isPdf("%PD".toByteArray()))
        // Only the bytes read count.
        assertFalse(OpenedFiles.isPdf("%PDF-1.7".toByteArray(), length = 4))
        assertFalse(OpenedFiles.isPdf(ByteArray(0)))
    }

    @Test
    fun aZipIsToldByItsFirstBytes() {
        assertTrue(OpenedFiles.isZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
        assertFalse(OpenedFiles.isZip(byteArrayOf(0x50, 0x4B, 0x03)))
        assertFalse(OpenedFiles.isZip(byteArrayOf(0x50, 0x4B, 0x03, 0x04), length = 3))
        assertFalse(OpenedFiles.isZip("%PDF-1.7".toByteArray()))
        // An old Word file (.doc), not a ZIP file.
        assertFalse(OpenedFiles.isZip(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())))
    }

    @Test
    fun theContentDecidesAndTheDeclaredKindStandsWhenItDoesNot() {
        assertEquals(SharedKind.PDF, OpenedFiles.kind(declared = SharedKind.WORD, probed = SharedKind.PDF))
        assertEquals(SharedKind.WORD, OpenedFiles.kind(declared = SharedKind.OTHER, probed = SharedKind.WORD))
        assertEquals(SharedKind.EXCEL, OpenedFiles.kind(declared = SharedKind.EXCEL, probed = null))
        assertEquals(SharedKind.PDF, OpenedFiles.kind(declared = SharedKind.PDF, probed = null))
        assertNull(OpenedFiles.kind(declared = SharedKind.IMAGE, probed = null))
        assertNull(OpenedFiles.kind(declared = SharedKind.OTHER, probed = null))
    }
}
