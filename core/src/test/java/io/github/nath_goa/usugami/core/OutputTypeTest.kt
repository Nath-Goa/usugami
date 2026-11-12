package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputTypeTest {

    @Test
    fun aFilesTypeIsReadFromItsEnding() {
        // A name whose ending says nothing else is a PDF's, as every file Usugami made before 2.0.0 is.
        for (name in listOf("Report.pdf", "REPORT.PDF", "scan", "notes.docx")) assertEquals(name, OutputType.PDF, OutputType.ofName(name))
        assertEquals(OutputType.JPEG, OutputType.ofName("p.jpg"))
        assertEquals(OutputType.JPEG, OutputType.ofName("p.JPEG"))
        assertEquals(OutputType.PNG, OutputType.ofName("p.png"))
        assertEquals(OutputType.TEXT, OutputType.ofName("Report.txt"))
        // Only the last ending counts.
        assertEquals(OutputType.TEXT, OutputType.ofName("a.b.txt"))
    }

    @Test
    fun aTypeIsShownAsAnImageTextOrPdf() {
        assertEquals(OutputType.PNG, OutputType.ofMime("image/png"))
        assertEquals(OutputType.JPEG, OutputType.ofMime("image/jpeg"))
        // Any other image is shown as one.
        assertEquals(OutputType.JPEG, OutputType.ofMime("image/webp"))
        assertEquals(OutputType.TEXT, OutputType.ofMime("text/plain; charset=utf-8"))
        assertEquals(OutputType.PDF, OutputType.ofMime("application/pdf"))
        assertEquals(OutputType.PDF, OutputType.ofMime("application/zip"))

        assertEquals("application/pdf" to "pdf", OutputType.PDF.mime to OutputType.PDF.extension)
        assertEquals("image/jpeg" to "jpg", OutputType.JPEG.mime to OutputType.JPEG.extension)
        assertEquals("image/png" to "png", OutputType.PNG.mime to OutputType.PNG.extension)
        assertEquals("text/plain" to "txt", OutputType.TEXT.mime to OutputType.TEXT.extension)
        assertTrue(OutputType.JPEG.isImage && OutputType.PNG.isImage)
        assertFalse(OutputType.PDF.isImage || OutputType.TEXT.isImage)
    }
}
