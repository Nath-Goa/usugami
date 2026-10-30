package io.github.nathgoa.pdfer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ImagePdfBuilderTest {

    private fun build(settings: LayoutSettings = LayoutSettings(), title: String = "Test"): ByteArray {
        val jpegBytes = TestImages.jpeg(TestImages.pattern(40, 30))
        val out = ByteArrayOutputStream()
        val builder = ImagePdfBuilder(out, settings, title, "PDFer", ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneOffset.UTC))
        builder.addPage(PageImage.Jpeg(Jpeg.stripForEmbedding(jpegBytes), Jpeg.parse(jpegBytes)), exifOrientation = 6, rotationDegrees = 0)
        builder.addPage(PageImage.Raster(TestImages.BufferedImageSource(TestImages.pattern(25, 50))), 1, 90)
        builder.finish()
        assertEquals(2, builder.pageCount)
        return out.toByteArray()
    }

    @Test
    fun crossReferenceTablePointsAtEveryObject() {
        val pdf = build()
        val text = String(pdf, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4\n%"))
        assertTrue(text.endsWith("%%EOF\n"))

        val startxref = Regex("startxref\n(\\d+)\n%%EOF\n$").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("xref\n", startxref))
        val header = Regex("xref\n0 (\\d+)\n").find(text, startxref)!!
        val count = header.groupValues[1].toInt()
        var pos = header.range.last + 1
        assertEquals("0000000000 65535 f \n", text.substring(pos, pos + 20))
        for (obj in 1 until count) {
            pos += 20
            val entry = text.substring(pos, pos + 20)
            assertTrue(entry, entry.matches(Regex("\\d{10} 00000 n \n")))
            val offset = entry.substring(0, 10).toInt()
            assertTrue("object $obj at $offset", text.startsWith("$obj 0 obj\n", offset))
        }
        assertTrue(text.contains("/Size $count /Root 1 0 R /Info 3 0 R /ID [<"))
    }

    @Test
    fun streamLengthsMatchData() {
        val text = String(build(), Charsets.ISO_8859_1)
        // Direct lengths.
        Regex("/Length (\\d+) >>\nstream\n").findAll(text).forEach { m ->
            val start = m.range.last + 1
            val length = m.groupValues[1].toInt()
            assertTrue(text.startsWith("\nendstream\nendobj\n", start + length))
        }
        // Indirect length (the losslessly stored image).
        val indirect = Regex("/Length (\\d+) 0 R >>\nstream\n").find(text)!!
        val lengthObject = indirect.groupValues[1]
        val length = Regex("\n$lengthObject 0 obj\n(\\d+)\nendobj\n").find(text)!!.groupValues[1].toInt()
        assertTrue(text.startsWith("\nendstream\nendobj\n", indirect.range.last + 1 + length))
    }

    @Test
    fun pagesAndPlacement() {
        val text = String(build(), Charsets.ISO_8859_1)
        assertTrue(text.contains("/Type /Pages /Kids ["))
        assertTrue(text.contains("/Count 2"))
        // EXIF 6 on a 40x30 JPEG shows 30x40, scaled to A4 portrait height-limited? width 595.2756 -> height 793.7
        assertTrue(text.contains("/MediaBox [0 0 595.2756 841.8898]"))
        assertTrue(text.contains("/Filter /DCTDecode /Length"))
        assertTrue(text.contains("/DecodeParms << /Predictor 15 /Colors 3 /BitsPerComponent 8 /Columns 25 >>"))
        assertTrue(text.contains("/CreationDate (D:20261014120000+00'00')"))
    }

    @Test
    fun unicodeTitle() {
        val text = String(build(title = "Reçu (été)"), Charsets.ISO_8859_1)
        assertTrue(text.contains("/Title <FEFF"))
    }
}
