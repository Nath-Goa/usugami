package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.random.Random

class ZipArchiveTest {
    @get:Rule val folder = TemporaryFolder()

    private fun file(name: String, bytes: ByteArray): File = File(folder.newFolder(), name).apply { writeBytes(bytes) }

    private fun zipOf(files: List<File>, onProgress: (ZipProgress) -> Unit = {}): File {
        val zip = File(folder.root, "out-${Random.nextInt(1_000_000)}.zip")
        zip.outputStream().use { ZipArchive.write(files, it, onProgress = onProgress) }
        return zip
    }

    @Test
    fun filesGoInAsTheyAreInTheirOrder() {
        val first = file("Report_split_1_1-1.pdf", Random(1).nextBytes(200_000))
        val second = file("Report_split_2_2-3.pdf", "%PDF-1.7 small".toByteArray())
        val empty = file("empty.pdf", ByteArray(0))
        ZipFile(zipOf(listOf(first, second, empty))).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf(first.name, second.name, empty.name), entries.map { it.name })
            for ((entry, source) in entries.zip(listOf(first, second, empty))) {
                assertEquals("Stored, not compressed again", ZipEntry.STORED, entry.method)
                assertEquals(source.length(), entry.size)
                assertEquals(source.length(), entry.compressedSize)
                assertEquals(CRC32().apply { update(source.readBytes()) }.value, entry.crc)
                assertArrayEquals(source.readBytes(), zip.getInputStream(entry).readBytes())
                assertTrue(!entry.isDirectory)
            }
        }
    }

    @Test
    fun namesInOtherScriptsSurvive() {
        val hindi = file("रिपोर्ट_1.pdf", byteArrayOf(1, 2, 3))
        val french = file("Reçu été.pdf", byteArrayOf(4))
        ZipFile(zipOf(listOf(hindi, french))).use { zip ->
            assertEquals(listOf(hindi.name, french.name), zip.entries().toList().map { it.name })
        }
    }

    @Test
    fun namesAreMadeUniqueIgnoringCase() {
        assertEquals(
            listOf("a.pdf", "A (2).pdf", "b.pdf", "a (3).pdf", "a (2) (2).pdf"),
            ZipArchive.entryNames(listOf("a.pdf", "A.pdf", "b.pdf", "a.pdf", "a (2).pdf")),
        )
        // The extension stays at the end; a name without one gets the number at the end.
        assertEquals(listOf("notes", "Notes (2)"), ZipArchive.entryNames(listOf("notes", "Notes")))
        // No folders inside the archive, so nothing is unpacked outside the folder chosen.
        assertEquals(listOf(".._.._x.pdf", "a_b.pdf", "file"), ZipArchive.entryNames(listOf("../../x.pdf", "a\\b.pdf", "  ")))

        val one = file("Scan.pdf", byteArrayOf(1))
        val other = file("scan.pdf", byteArrayOf(2))
        ZipFile(zipOf(listOf(one, other))).use { zip ->
            val entries = zip.entries().toList()
            assertEquals(listOf("Scan.pdf", "scan (2).pdf"), entries.map { it.name })
            assertArrayEquals(byteArrayOf(2), zip.getInputStream(entries[1]).readBytes())
        }
    }

    @Test
    fun progressCountsEveryByteTwiceAndEveryFile() {
        val files = listOf(file("1.pdf", ByteArray(150_000)), file("2.pdf", ByteArray(10)), file("3.pdf", ByteArray(70_000)))
        val total = files.sumOf { it.length() } * 2
        val seen = ArrayList<ZipProgress>()
        zipOf(files) { seen += it }
        assertTrue(seen.zipWithNext().all { (a, b) -> b.bytes >= a.bytes && b.files >= a.files })
        assertEquals(ZipProgress(3, 3, total, total), seen.last())
        assertEquals(1f, seen.last().fraction)
        assertEquals(listOf(1, 2, 3), seen.map { it.files }.distinct().drop(1))
        assertEquals(0f, ZipProgress(0, 0, 0, 0).fraction)
    }

    @Test
    fun checkStopsItPartWay() {
        val files = listOf(file("big.pdf", ByteArray(1_000_000)), file("next.pdf", ByteArray(1_000_000)))
        var chunks = 0
        val written = ByteArrayOutputStream()
        var closed = false
        val out = object : OutputStream() {
            override fun write(b: Int) = written.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = written.write(b, off, len)
            override fun close() { closed = true }
        }
        try {
            ZipArchive.write(files, out, check = { if (++chunks > 20) throw IllegalStateException("cancelled") })
            fail("It wasn't stopped")
        } catch (e: IllegalStateException) {
            assertEquals("cancelled", e.message)
        }
        assertTrue("It stopped in the first file", written.size() < 1_000_000)
        assertTrue("The stream is closed", closed)
    }

    @Test
    fun aMissingFileFails() {
        val gone = File(folder.root, "gone.pdf")
        try {
            zipOf(listOf(file("here.pdf", byteArrayOf(1)), gone))
            fail("A missing file was zipped")
        } catch (_: java.io.IOException) {
            // Expected: the result was cleared from the cache.
        }
    }
}
