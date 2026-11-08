package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentFileListTest {

    private fun file(n: Int, grant: String? = null) =
        RecentFile("content://media/external/downloads/$n", "File $n.pdf", "application/pdf", 1000L + n, 1_700_000_000_000 + n, grant)

    @Test
    fun theLastSavedIsFirstAndAFileSavedAgainMovesUp() {
        val list = RecentFileList.add(emptyList(), listOf(file(1), file(2)))
        assertEquals(listOf(file(2), file(1)), list)
        // Saved again later, with a new time: it moves up and shows once.
        val again = file(1).copy(savedAt = 1_800_000_000_000)
        assertEquals(listOf(again, file(2)), RecentFileList.add(list, listOf(again)))
    }

    @Test
    fun theOldestGoPastTheMost() {
        val list = RecentFileList.add(emptyList(), (1..RecentFileList.MAX).map { file(it) })
        val more = RecentFileList.add(list, listOf(file(100), file(101)))
        assertEquals(RecentFileList.MAX, more.size)
        assertEquals(listOf(file(101), file(100), file(RecentFileList.MAX)), more.take(3))
        assertEquals(file(3), more.last())
    }

    @Test
    fun anAccessIsGivenBackOnlyOnceNoFileNeedsIt() {
        val folder = "content://docs/tree/primary%3ADocuments"
        val before = listOf(file(1, folder), file(2, folder), file(3, "content://docs/document/3"), file(4))
        assertEquals(emptySet<String>(), RecentFileList.unusedGrants(before, before.drop(1)))
        assertEquals(setOf(folder, "content://docs/document/3"), RecentFileList.unusedGrants(before, before.drop(3)))
    }

    @Test
    fun theListIsReadBackAsWritten() {
        val odd = RecentFile("content://docs/document/1", "Tab\there, line\nbreak \\ and \\t.pdf", "application/zip", -1, 5, "content://docs/document/1")
        val list = listOf(odd, file(2))
        assertEquals(list, RecentFileList.decode(RecentFileList.encode(list)))
        assertEquals(emptyList<RecentFile>(), RecentFileList.decode(RecentFileList.encode(emptyList())))
    }

    @Test
    fun damagedTextGivesTheFilesThatCanBeRead() {
        val good = RecentFileList.encode(listOf(file(1), file(2))).split('\n')
        val text = listOf(
            good[0],
            good[1],
            "content://a\tName.pdf\tapplication/pdf\tlots\t5\t",
            "content://b\tToo few fields",
            "file:///data/data/io.github.nath_goa.usugami/files/secret\tName.pdf\tapplication/pdf\t1\t5\t",
            "content://c\tBad \\q escape.pdf\tapplication/pdf\t1\t5\t",
            "content://d\t \tapplication/pdf\t1\t5\t",
            good[1],
            good[2],
        ).joinToString("\n")
        assertEquals(listOf(file(1), file(2)), RecentFileList.decode(text))
        assertEquals(emptyList<RecentFile>(), RecentFileList.decode(""))
        assertEquals(emptyList<RecentFile>(), RecentFileList.decode("{\"files\": []}\n" + good[1]))
    }
}
