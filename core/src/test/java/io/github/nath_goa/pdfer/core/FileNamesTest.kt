package io.github.nath_goa.pdfer.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {

    @Test
    fun sanitize() {
        assertEquals("Holiday", FileNames.sanitize("  Holiday  ", "x"))
        assertEquals("Holiday", FileNames.sanitize("Holiday.pdf", "x"))
        assertEquals("Holiday", FileNames.sanitize("Holiday.PDF", "x"))
        assertEquals("a_b_c_d_e_f_g_h_i", FileNames.sanitize("a/b\\c:d*e?f\"g<h>i", "x"))
        assertEquals("tab_new", FileNames.sanitize("tab\tnew", "x"))
        assertEquals("x", FileNames.sanitize("   ", "x"))
        assertEquals("x", FileNames.sanitize("...", "x"))
        assertEquals("x", FileNames.sanitize(".pdf", "x"))
        assertEquals("Reçu été 📷", FileNames.sanitize("Reçu été 📷", "x"))
        assertEquals("name", FileNames.sanitize("name. ", "x"))
    }

    @Test
    fun sanitizeLimitsLengthWithoutSplittingCharacters() {
        val long = "📷".repeat(100) // 400 UTF-8 bytes
        val result = FileNames.sanitize(long, "x")
        assertTrue(result.toByteArray().size <= 200)
        assertEquals(50, result.codePointCount(0, result.length))
        assertEquals(200, FileNames.sanitize("a".repeat(300), "x").length)
    }

    @Test
    fun baseNameAndUnique() {
        assertEquals("IMG_1234", FileNames.baseName("IMG_1234.jpg"))
        assertEquals("archive.tar", FileNames.baseName("archive.tar.gz"))
        assertEquals(".hidden", FileNames.baseName(".hidden"))
        assertEquals("noext", FileNames.baseName("noext"))
        assertEquals("a", FileNames.unique("a", setOf("b")))
        assertEquals("a (2)", FileNames.unique("a", setOf("A")))
        assertEquals("a (3)", FileNames.unique("a", setOf("a", "a (2)")))
    }

    @Test
    fun naturalOrder() {
        val sorted = listOf("img10.jpg", "IMG2.jpg", "img1.jpg", "b.png", "A.png", "img02.jpg", "img2b.jpg")
            .sortedWith(NaturalOrder)
        assertEquals(listOf("A.png", "b.png", "img1.jpg", "IMG2.jpg", "img02.jpg", "img2b.jpg", "img10.jpg"), sorted)
        assertEquals(0, NaturalOrder.compare("same", "same"))
        assertTrue(NaturalOrder.compare("a", "ab") < 0)
        assertTrue(NaturalOrder.compare("photo 99999999999999999999", "photo 100000000000000000000") < 0)
    }
}
