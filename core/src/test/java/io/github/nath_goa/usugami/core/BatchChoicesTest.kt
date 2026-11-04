package io.github.nath_goa.usugami.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class BatchChoicesTest {
    private val start = BatchChoices(all = 1).following(listOf(10, 20, 30))

    @Test
    fun filesAddedStartWithTheChoiceForAllAndTheToolsName() {
        assertEquals(listOf(10L, 20L, 30L), start.files.map { it.id })
        assertEquals(listOf(1, 1, 1), start.files.map { it.choice })
        assertEquals(listOf<String?>(null, null, null), start.files.map { it.name })
        assertEquals(1, start.common)
        // Files the list doesn't know yet have the choice for all.
        assertEquals(1, start.choiceOf(99))
        assertNull(start.nameOf(99))
    }

    @Test
    fun theChoiceForAllSetsEveryFileAndOneChangedAfterKeepsItsOwn() {
        val extreme = start.withAll(2)
        assertEquals(listOf(2, 2, 2), extreme.files.map { it.choice })
        assertEquals(2, extreme.common)

        val oneLess = extreme.withChoice(20, 0)
        assertEquals(listOf(2, 0, 2), oneLess.files.map { it.choice })
        assertNull("The files differ", oneLess.common)
        // A file added now starts with the choice made for all, not with the one changed alone.
        val added = oneLess.following(listOf(10, 20, 30, 40))
        assertEquals(2, added.choiceOf(40))
        assertEquals(0, added.choiceOf(20))

        // Choosing for all again sets the file changed alone too.
        assertEquals(listOf(1, 1, 1, 1), added.withAll(1).files.map { it.choice })
        // Choosing for a file that isn't listed changes nothing.
        assertEquals(oneLess, oneLess.withChoice(99, 1))
    }

    @Test
    fun aFileAddedStartsWithTheChoiceTheFilesKeptAllHave() {
        // Each file set to 2 on its own: the screen shows 2 as the choice for all, and a file added gets it.
        val each = start.withChoice(10, 2).withChoice(20, 2).withChoice(30, 2)
        assertEquals(2, each.common)
        val added = each.following(listOf(10, 20, 30, 40))
        assertEquals(2, added.choiceOf(40))
        assertEquals(2, added.common)
        // One file set on its own and the others removed: the one left has the choice for all.
        val left = start.withChoice(10, 0).following(listOf(10))
        assertEquals(0, left.all)
        assertEquals(0, left.following(listOf(10, 50)).choiceOf(50))
    }

    @Test
    fun followingKeepsWhatFilesHaveInTheNewOrderAndForgetsFilesRemoved() {
        val chosen = start.withName(30, "Thirty").withChoice(30, 0)
        val next = chosen.following(listOf(30, 10))
        assertEquals(listOf(30L, 10L), next.files.map { it.id })
        assertEquals("Thirty", next.nameOf(30))
        assertEquals(0, next.choiceOf(30))
        assertNull(next.nameOf(20))
        // A file removed and added again starts afresh.
        assertNull(next.following(listOf(30, 10, 20)).nameOf(20))
        assertSame("Nothing changed, nothing new", next, next.following(listOf(30, 10, 10)))
        assertEquals(BatchChoices(all = 1), chosen.following(emptyList()))
    }

    @Test
    fun namesAreKeptAsTypedAndCanGoBackToTheTools() {
        val named = start.withName(10, " my report.pdf ")
        assertEquals(" my report.pdf ", named.nameOf(10))
        assertNull(named.withName(10, null).nameOf(10))
        assertEquals(named, named.withName(99, "x"))
    }

    @Test
    fun packedAndUnpacked() {
        val choices = start.withAll(2).withChoice(20, 0).withName(10, "Été / notes").withName(30, "")
        val packed = choices.pack()
        assertArrayEquals(longArrayOf(10, 20, 30), packed.ids)
        assertArrayEquals(intArrayOf(2, 0, 2), packed.choices)
        assertEquals(choices, BatchChoices.unpack(packed.ids, packed.names, packed.choices, packed.all))
        assertEquals(BatchChoices(all = 2), BatchChoices.unpack(null, null, null, 2))
        assertEquals("Arrays that don't match give no files", BatchChoices(all = 1), BatchChoices.unpack(longArrayOf(1, 2), arrayOf("a"), intArrayOf(0, 0), 1))
    }
}
