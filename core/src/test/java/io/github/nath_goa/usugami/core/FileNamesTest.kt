package io.github.nath_goa.usugami.core

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
        assertEquals("Report", FileNames.sanitize("Report.pdf.", "x"))
        assertEquals("Report", FileNames.sanitize("Report.pdf . ", "x"))
    }

    @Test
    fun sanitizeLimitsLengthWithoutSplittingCharacters() {
        val long = "📷".repeat(100) // 400 UTF-8 bytes
        val result = FileNames.sanitize(long, "x")
        assertTrue(result.toByteArray().size <= 200)
        assertEquals(50, result.codePointCount(0, result.length))
        assertEquals(200, FileNames.sanitize("a".repeat(300), "x").length)
        // Nor a letter from its accent: "é" written as an e and a combining accent is 3 bytes.
        assertEquals("é".repeat(66), FileNames.sanitize("é".repeat(67), "x"))
    }

    @Test
    fun aLongNameIsCutBeforeItsSuffix() {
        assertEquals("Report_redacted", FileNames.withSuffix("Report.pdf", "_redacted", "x"))
        // 67 Devanagari letters are 201 bytes: the base gives way, the ending stays whole.
        val long = FileNames.withSuffix("क".repeat(67), "_redacted", "x")
        assertEquals("क".repeat(63) + "_redacted", long)
        assertTrue(long.toByteArray().size <= 200)
        assertEquals("x_split", FileNames.withSuffix(" ... ", "_split", "x"))
    }

    @Test
    fun filesMadeTogetherFromALongNameAllBeginAlike() {
        // Split's parts: the name is cut once, for the longest ending, not once for each.
        val parts = FileNames.withSuffixes("क".repeat(67), listOf("_1_1-9", "_10_91-100"), "x")
        assertEquals(listOf("क".repeat(63) + "_1_1-9", "क".repeat(63) + "_10_91-100"), parts)
        assertTrue(parts.all { it.toByteArray().size <= 200 })
        // Room left for the parts' endings keeps "_split" whole after them.
        val split = FileNames.withSuffix("a".repeat(200), "_split", "x", spare = 10)
        assertEquals("a".repeat(184) + "_split", split)
        assertEquals(listOf(split + "_1_1-9", split + "_10_91-100"), FileNames.withSuffixes(split, listOf("_1_1-9", "_10_91-100"), "x"))
    }

    @Test
    fun splitPartsAreNamedByTheirPages() {
        // Every page, pages a few at a time, and ranges, with the last part a page alone.
        assertEquals(listOf("Report_p1", "Report_p2", "Report_p3"), FileNames.pageParts("Report", listOf(listOf(0), listOf(1), listOf(2)), "x"))
        assertEquals(listOf("Report_p1-5", "Report_p6-10", "Report_p11"), FileNames.pageParts("Report.pdf", listOf((0..4).toList(), (5..9).toList(), listOf(10)), "x"))
        // Pages picked and put together, in the order they go in.
        assertEquals(listOf("Report_p10,1-3,5"), FileNames.pageParts("Report", listOf(listOf(9, 0, 1, 2, 4)), "x"))
        // The same pages twice are told apart.
        assertEquals(listOf("Report_p1-3", "Report_p1-3 (2)"), FileNames.pageParts("Report", listOf(listOf(0, 1, 2), listOf(0, 1, 2)), "x"))
        // A long list says its first pages and its last.
        val scattered = FileNames.pageParts("Report", listOf((0 until 200 step 2).toList()), "x").single()
        assertEquals("Report_p1,3,5,7,9,11,13,15,17,19,21,23,25,…,199", scattered)
        // A long name is cut alike for all, the pages kept whole.
        val long = FileNames.pageParts("क".repeat(67), listOf(listOf(0), (98..99).toList()), "x")
        assertEquals(listOf("क".repeat(64) + "_p1", "क".repeat(64) + "_p99-100"), long)
        assertEquals("x_p1", FileNames.pageParts(" ", listOf(listOf(0)), "x").single())
    }

    @Test
    fun aMessageListsAFewNamesAndCountsTheRest() {
        assertEquals(emptyList<String>() to 0, FileNames.listed(emptyList()))
        assertEquals(listOf("a", "b", "c", "d") to 0, FileNames.listed(listOf("a", "b", "c", "d")))
        val many = (1..250).map { "File $it.txt" }
        assertEquals(listOf("File 1.txt", "File 2.txt", "File 3.txt") to 247, FileNames.listed(many))
    }

    @Test
    fun windowsDeviceNamesGetAnUnderscore() {
        assertEquals("CON_", FileNames.sanitize("CON", "x"))
        assertEquals("nul_", FileNames.sanitize("nul.pdf", "x"))
        assertEquals("Aux_.notes", FileNames.sanitize("Aux.notes", "x"))
        assertEquals("com1_", FileNames.sanitize("com1", "x"))
        assertEquals("LPT9_ .old", FileNames.sanitize("LPT9 .old", "x"))
        for (fine in listOf("Console", "COM10", "nullable", "CON-report", "My CON")) assertEquals(fine, FileNames.sanitize(fine, "x"))
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
    fun uniqueNamesAreCleanedAndTellFilesApartIgnoringCase() {
        assertEquals(
            listOf("Report", "Report (2)", "report (3)", "Notes", "Compressed", "Compressed (2)", "a_b"),
            FileNames.uniqueNames(listOf("Report", "Report.pdf", " report ", "Notes", "", "...", "a/b"), "Compressed"),
        )
        // A name typed like one made unique isn't taken twice.
        assertEquals(listOf("a", "a (2)", "a (3)"), FileNames.uniqueNames(listOf("a", "a (2)", "a"), "x"))
        assertEquals(listOf("Été", "été (2)"), FileNames.uniqueNames(listOf("Été", "été"), "x"))
        assertEquals(emptyList<String>(), FileNames.uniqueNames(emptyList(), "x"))
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
