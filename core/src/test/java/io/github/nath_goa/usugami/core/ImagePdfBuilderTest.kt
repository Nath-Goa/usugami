package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.time.ZonedDateTime

class ImagePdfBuilderTest {

    private fun build(settings: LayoutSettings = LayoutSettings(), title: String = "Test"): ByteArray {
        val jpegBytes = TestImages.jpeg(TestImages.pattern(40, 30))
        val out = ByteArrayOutputStream()
        val builder = ImagePdfBuilder(out, settings, title, "Usugami", ZonedDateTime.of(2026, 10, 14, 12, 0, 0, 0, ZoneOffset.UTC))
        builder.addPage(PageImage.Jpeg(Jpeg.stripForEmbedding(jpegBytes), Jpeg.parse(jpegBytes)), exifOrientation = 6, rotationDegrees = 0)
        builder.addPage(PageImage.Raster(TestImages.BufferedImageSource(TestImages.pattern(25, 50))), 1, 90)
        builder.finish()
        assertEquals(2, builder.pageCount)
        return out.toByteArray()
    }

    @Test
    fun crossReferenceTablePointsAtEveryObject() = assertCrossReferenceTablePointsAtEveryObject(build())

    private fun assertCrossReferenceTablePointsAtEveryObject(pdf: ByteArray) {
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
    fun streamLengthsMatchData() = assertStreamLengthsMatchData(build())

    private fun assertStreamLengthsMatchData(pdf: ByteArray) {
        val text = String(pdf, Charsets.ISO_8859_1)
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
    fun blackAndWhitePagesAreOneBitAndGreyPagesOneComponent() {
        // A page of print (G4 wins) and one of noise (Flate wins), then a grey JPEG.
        val rendered = java.awt.image.BufferedImage(400, 200, java.awt.image.BufferedImage.TYPE_BYTE_BINARY).apply {
            val g = createGraphics()
            g.color = java.awt.Color.WHITE
            g.fillRect(0, 0, 400, 200)
            g.color = java.awt.Color.BLACK
            g.font = java.awt.Font(java.awt.Font.SERIF, java.awt.Font.PLAIN, 28)
            // Lines that differ (Flate would find lines repeated).
            listOf("Usugami makes PDFs", "of scanned pages,", "black and white", "in a few kilobytes", "with CCITT G4.")
                .forEachIndexed { line, words -> g.drawString(words, 8 + line * 13, 34 + line * 36) }
            g.dispose()
        }
        val print = BitImage(400, 200).apply {
            data.fill(0xFF.toByte())
            for (y in 0 until 200) for (x in 0 until 400) {
                if (rendered.getRGB(x, y) and 0xFF < 128) data[y * rowBytes + x / 8] = (data[y * rowBytes + x / 8].toInt() and (0x80 ushr (x % 8)).inv()).toByte()
            }
        }
        val random = java.util.Random(1)
        val noise = BitImage(64, 64).apply { random.nextBytes(data) }
        val gray = GrayImage(30, 20, ByteArray(600) { i -> (i % 30 * 8).toByte() })
        val grayJpeg = GrayJpeg.encode(gray, 75)
        val out = ByteArrayOutputStream()
        val builder = ImagePdfBuilder(out, LayoutSettings(), "Scan", "Usugami")
        builder.addPage(PageImage.Bilevel(print), 1, 0)
        builder.addPage(PageImage.Bilevel(noise), 1, 90)
        builder.addPage(PageImage.Jpeg(grayJpeg, Jpeg.parse(grayJpeg)), 1, 0)
        builder.finish()
        val text = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertTrue(
            text.contains(
                "/Type /XObject /Subtype /Image /Width 400 /Height 200 /BitsPerComponent 1 /ColorSpace /DeviceGray" +
                    " /Filter /CCITTFaxDecode /DecodeParms << /K -1 /Columns 400 /Rows 200 >>",
            ),
        )
        val g4 = CcittG4.encode(print)
        assertTrue("G4 ${g4.size}, Flate ${RasterEncoder.flateBits(print).size}", g4.size < RasterEncoder.flateBits(print).size)
        assertTrue(text.contains("/Length ${g4.size} >>\nstream\n" + String(g4, Charsets.ISO_8859_1) + "\nendstream"))
        assertTrue(
            text.contains(
                "/Width 64 /Height 64 /BitsPerComponent 1 /ColorSpace /DeviceGray /Filter /FlateDecode" +
                    " /DecodeParms << /Predictor 15 /Colors 1 /BitsPerComponent 1 /Columns 64 >>",
            ),
        )
        assertTrue(text.contains("/Width 30 /Height 20 /BitsPerComponent 8 /ColorSpace /DeviceGray /Filter /DCTDecode"))
    }

    @Test
    fun aPageOfTwoImagesDrawsBothWithOneContentStream() {
        // A card's front stored losslessly and turned upside down by the user; its back a JPEG
        // stored sideways (EXIF 6). Both show 50 x 32, laid out on Letter while the builder's own
        // settings say A4: a page of several images takes its box from them.
        val front = PageImage.Raster(TestImages.BufferedImageSource(TestImages.pattern(50, 32)))
        val jpegBytes = TestImages.jpeg(TestImages.pattern(32, 50))
        val back = PageImage.Jpeg(Jpeg.stripForEmbedding(jpegBytes), Jpeg.parse(jpegBytes))
        val (frontBox, backBox) = IdCardLayout.compute(listOf(50 to 32, 50 to 32), LayoutSettings(PageSize.LETTER))
        val out = ByteArrayOutputStream()
        val builder = ImagePdfBuilder(out, LayoutSettings(PageSize.A4), "Card", "Usugami")
        // An ordinary page first, as a scan mixes them: a JPEG, so that the first length written as
        // an object of its own is the front's, between the card's two images.
        val pageBytes = TestImages.jpeg(TestImages.pattern(40, 30))
        builder.addPage(PageImage.Jpeg(Jpeg.stripForEmbedding(pageBytes), Jpeg.parse(pageBytes)), 1, 0)
        val placements = builder.addPage(listOf(PageDrawing(front, 1, 180, frontBox), PageDrawing(back, 6, 0, backBox)))
        builder.finish()
        assertEquals(2, builder.pageCount)
        val pdf = out.toByteArray()
        val text = String(pdf, Charsets.ISO_8859_1)

        assertEquals(listOf(frontBox, backBox), placements.map { it.geometry })
        assertEquals(listOf(ImageTransform.of(1, 180), ImageTransform.of(6, 0)), placements.map { it.transform })

        // The page: Letter's box, both images among its resources.
        val page = Regex(
            "<< /Type /Page /Parent \\d+ 0 R /MediaBox \\[0 0 612 792] /Resources << /XObject << /Im0 (\\d+) 0 R /Im1 (\\d+) 0 R >>" +
                " /ProcSet \\[/PDF /ImageB /ImageC] >> /Contents (\\d+) 0 R >>",
        ).find(text)
        assertTrue(text, page != null)
        val (im0, im1, contents) = page!!.destructured
        assertTrue(text.contains("\n$im0 0 obj\n<< /Type /XObject /Subtype /Image /Width 50 /Height 32 /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /FlateDecode"))
        assertTrue(text.contains("\n$im1 0 obj\n<< /Type /XObject /Subtype /Image /Width 32 /Height 50 /BitsPerComponent 8 /ColorSpace /DeviceRGB /Filter /DCTDecode"))

        // One content stream drawing each image with the matrix its placement gives.
        val stream = Regex("\n$contents 0 obj\n<<  /Length (\\d+) >>\nstream\n").find(text)!!
        val content = text.substring(stream.range.last + 1, stream.range.last + 1 + stream.groupValues[1].toInt())
        val drawn = placements.withIndex().joinToString("") { (i, placement) ->
            "q\n${placement.matrix().joinToString(" ") { PdfSyntax.number(it) }} cm\n/Im$i Do\nQ\n"
        }
        assertEquals(drawn, content)
        assertEquals(2, Regex(" Do\n").findAll(content).count())

        // A second image on a page is where an offset or a length would slip.
        assertCrossReferenceTablePointsAtEveryObject(pdf)
        assertStreamLengthsMatchData(pdf)
    }

    @Test
    fun theImagesOfAPageShareItsBox() {
        val builder = ImagePdfBuilder(ByteArrayOutputStream(), LayoutSettings(), "Card", "Usugami")
        val image = PageImage.Raster(TestImages.BufferedImageSource(TestImages.pattern(50, 32)))
        val a4 = IdCardLayout.compute(listOf(50 to 32), LayoutSettings(PageSize.A4)).single()
        val letter = IdCardLayout.compute(listOf(50 to 32), LayoutSettings(PageSize.LETTER)).single()
        assertThrows(IllegalArgumentException::class.java) { builder.addPage(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            builder.addPage(listOf(PageDrawing(image, 1, 0, a4), PageDrawing(image, 1, 0, letter)))
        }
        assertEquals(0, builder.pageCount)
    }

    @Test
    fun unicodeTitle() {
        val text = String(build(title = "Reçu (été)"), Charsets.ISO_8859_1)
        assertTrue(text.contains("/Title <FEFF"))
    }
}
