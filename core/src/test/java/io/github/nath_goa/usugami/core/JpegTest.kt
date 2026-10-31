package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.BufferedImage

class JpegTest {

    @Test
    fun parsesBaselineColour() {
        val bytes = TestImages.jpeg(TestImages.pattern(37, 21))
        val info = Jpeg.parse(bytes)
        assertEquals(37, info.width)
        assertEquals(21, info.height)
        assertEquals(3, info.components)
        assertEquals(8, info.precision)
        assertEquals(0xC0, info.frameMarker)
        assertTrue(info.canEmbedDirectly)
        assertFalse(info.isPlainRgb)
        assertTrue(Jpeg.isJpeg(bytes))
    }

    @Test
    fun parsesProgressiveAndGray() {
        val progressive = Jpeg.parse(TestImages.jpeg(TestImages.pattern(64, 48), progressive = true))
        assertEquals(0xC2, progressive.frameMarker)
        assertTrue(progressive.canEmbedDirectly)

        val gray = Jpeg.parse(TestImages.jpeg(TestImages.pattern(16, 16, BufferedImage.TYPE_BYTE_GRAY)))
        assertEquals(1, gray.components)
        assertTrue(gray.canEmbedDirectly)
    }

    @Test
    fun rejectsUnsupportedFrames() {
        val base = Jpeg.parse(TestImages.jpeg(TestImages.pattern(8, 8)))
        assertFalse(base.copy(components = 4).canEmbedDirectly)
        assertFalse(base.copy(precision = 12).canEmbedDirectly)
        assertFalse(base.copy(frameMarker = 0xC9).canEmbedDirectly) // arithmetic coding
        assertFalse(base.copy(frameMarker = 0xC3).canEmbedDirectly) // lossless
        assertFalse(base.copy(height = 0).canEmbedDirectly)          // height defined by DNL
        assertTrue(base.copy(componentIds = listOf(82, 71, 66)).isPlainRgb)
        assertFalse(base.copy(componentIds = listOf(82, 71, 66), hasAdobeMarker = true).isPlainRgb)
    }

    @Test
    fun detectsAdobeMarker() {
        val adobe = TestImages.segment(0xEE, "Adobe".toByteArray() + byteArrayOf(0, 100, 0, 0, 0, 0, 1))
        val info = Jpeg.parse(TestImages.withSegments(TestImages.jpeg(TestImages.pattern(8, 8)), adobe))
        assertTrue(info.hasAdobeMarker)
    }

    @Test
    fun stripRemovesMetadataAndTrailingDataButKeepsPixels() {
        for (progressive in listOf(false, true)) {
            val clean = TestImages.jpeg(TestImages.pattern(120, 80), progressive)
            val gps = "Exif\u0000\u0000GPS-SECRET-LOCATION".toByteArray()
            val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta>SECRET</x:xmpmeta>".toByteArray()
            val icc = "ICC_PROFILE\u0000\u0001\u0001fake-profile".toByteArray()
            val dirty = TestImages.withSegments(
                clean,
                TestImages.segment(0xE1, gps),
                TestImages.segment(0xE1, xmp),
                TestImages.segment(0xE2, "MPF\u0000index".toByteArray()),
                TestImages.segment(0xE2, icc),
                TestImages.segment(0xFE, "comment SECRET".toByteArray()),
                TestImages.segment(0xED, "Photoshop 3.0\u0000SECRET".toByteArray()),
            ) + "....ftypmp42 motion photo video SECRET".toByteArray() + byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

            val stripped = Jpeg.stripForEmbedding(dirty)
            assertEquals(-1, stripped.indexOf("SECRET".toByteArray()))
            assertEquals(-1, stripped.indexOf("MPF".toByteArray()))
            assertTrue("ICC profile kept", stripped.indexOf("ICC_PROFILE".toByteArray()) > 0)
            assertEquals(0xFF, stripped[stripped.size - 2].toInt() and 0xFF)
            assertEquals(0xD9, stripped[stripped.size - 1].toInt() and 0xFF)
            assertEquals(clean.size + 2 + icc.size + 2, stripped.size)

            assertArrayEquals(
                TestImages.pixelsOf(TestImages.decode(clean)),
                TestImages.pixelsOf(TestImages.decode(stripped)),
            )
            assertEquals(Jpeg.parse(clean).copy(), Jpeg.parse(stripped))
        }
    }

    @Test
    fun stripKeepsOnlySegmentsDecodersNeed() {
        for (progressive in listOf(false, true)) {
            val clean = TestImages.jpeg(TestImages.pattern(64, 40), progressive)
            // A real profile split over two segments, as encoders do with profiles over 64 KB.
            val profile = ICC_Profile.getInstance(ColorSpace.CS_sRGB).data
            val half = profile.size / 2
            val icc1 = TestImages.segment(0xE2, iccChunk(1, 2, profile.copyOfRange(0, half)))
            val icc2 = TestImages.segment(0xE2, iccChunk(2, 2, profile.copyOfRange(half, profile.size)))
            val adobe = TestImages.segment(0xEE, "Adobe".toByteArray() + byteArrayOf(0, 100, 0, 0, 0, 0, 1))
            val dirty = TestImages.withSegments(
                clean,
                TestImages.segment(0xEB, "JP\u0000\u0001\u0000\u0000\u0000\u0001jumbc2pa SECRET".toByteArray()),
                icc1,
                TestImages.segment(0xE3, "META camera serial SECRET".toByteArray()),
                icc2,
                TestImages.segment(0xE0, "JFXX\u0000\u0010SECRET thumbnail".toByteArray()),
                TestImages.segment(0xE2, "FPXR\u0000SECRET".toByteArray()),
                adobe,
                TestImages.segment(0xEC, "Ducky\u0000SECRET".toByteArray()),
                TestImages.segment(0xEF, "SECRET".toByteArray()),
            )

            val stripped = Jpeg.stripForEmbedding(dirty)
            assertEquals(-1, stripped.indexOf("SECRET".toByteArray()))
            assertArrayEquals(TestImages.withSegments(clean, icc1, icc2, adobe), stripped)
            val info = Jpeg.parse(stripped)
            assertEquals(Jpeg.parse(clean).copy(hasAdobeMarker = true), info)
            val decoded = TestImages.decode(stripped)
            assertEquals(64, decoded.width)
            assertEquals(40, decoded.height)
        }
    }

    @Test
    fun stripRemovesJfifThumbnail() {
        val clean = TestImages.jpeg(TestImages.pattern(40, 30))
        // ImageIO writes SOI, then a JFIF APP0 segment of length 16: 14 header bytes, no thumbnail.
        assertEquals(0xE0, clean[3].toInt() and 0xFF)
        assertEquals(16, clean[5].toInt())
        val header = clean.copyOfRange(6, 20)
        header[12] = 2 // thumbnail width
        header[13] = 1 // thumbnail height; 2 × 1 RGB pixels follow
        val withThumbnail = clean.copyOf(2) + TestImages.segment(0xE0, header + "SECRET".toByteArray()) +
            clean.copyOfRange(20, clean.size)
        assertTrue(Jpeg.parse(withThumbnail).canEmbedDirectly)
        assertArrayEquals(clean, Jpeg.stripForEmbedding(withThumbnail))
    }

    @Test
    fun stripRemovesMetadataBetweenScans() {
        val clean = TestImages.jpeg(TestImages.pattern(64, 48), progressive = true)
        // Entropy-coded data never contains FF DA, so the second match starts the second scan.
        val sos = byteArrayOf(0xFF.toByte(), 0xDA.toByte())
        val secondScan = clean.indexOf(sos, clean.indexOf(sos) + 2)
        assertTrue(secondScan > 0)
        val metadata = TestImages.segment(0xE1, "Exif\u0000\u0000SECRET".toByteArray()) +
            TestImages.segment(0xFE, "SECRET".toByteArray())
        val dirty = clean.copyOf(secondScan) + metadata + clean.copyOfRange(secondScan, clean.size)
        assertArrayEquals(clean, Jpeg.stripForEmbedding(dirty))

        // Cut off inside the EXIF segment: the scans before it are kept, the partial segment is not.
        val truncated = dirty.copyOf(secondScan + 14)
        val eoi = byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertArrayEquals(clean.copyOf(secondScan) + eoi, Jpeg.stripForEmbedding(truncated))
    }

    @Test
    fun stripIsIdentityForPlainFiles() {
        // ImageIO writes SOI, APP0 JFIF, tables, SOF, SOS, data, EOI: nothing to remove.
        val clean = TestImages.jpeg(TestImages.pattern(33, 17))
        assertArrayEquals(clean, Jpeg.stripForEmbedding(clean))
    }

    @Test
    fun truncatedFileGetsClosed() {
        val clean = TestImages.jpeg(TestImages.pattern(64, 64))
        val truncated = clean.copyOf(clean.size - 40)
        val stripped = Jpeg.stripForEmbedding(truncated)
        assertEquals(truncated.size + 2, stripped.size)
        assertEquals(0xD9, stripped.last().toInt() and 0xFF)
    }

    @Test(expected = InvalidJpegException::class)
    fun rejectsNonJpeg() {
        Jpeg.parse(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 0))
    }

    @Test(expected = InvalidJpegException::class)
    fun rejectsHeaderOnly() {
        Jpeg.parse(TestImages.jpeg(TestImages.pattern(8, 8)).copyOf(20))
    }

    private fun iccChunk(sequence: Int, count: Int, part: ByteArray): ByteArray =
        "ICC_PROFILE\u0000".toByteArray() + byteArrayOf(sequence.toByte(), count.toByte()) + part
}
