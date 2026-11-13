package io.github.nath_goa.usugami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcutRulesTest {
    private fun scene(
        place: ShortcutPlace,
        working: Boolean = false,
        waiting: Boolean = false,
        unsaved: Boolean = false,
        editorIdle: Boolean = false,
    ) = ShortcutScene(place, working, waiting, unsaved, editorIdle)

    /** What every shortcut does in [scene], in the order the shortcuts show. */
    private fun steps(scene: ShortcutScene) = ShortcutTarget.entries.map { ShortcutRules.step(it, scene) }

    private fun all(step: ShortcutStep) = List(ShortcutTarget.entries.size) { step }

    @Test
    fun whatAShortcutDoesDependsOnWhereUsugamiIs() {
        for (place in ShortcutPlace.entries) {
            for (unsaved in listOf(false, true)) for (idle in listOf(false, true)) {
                // A job under way refuses every shortcut, whatever else.
                for (waiting in listOf(false, true)) {
                    assertEquals("$place working", all(ShortcutStep.BUSY), steps(scene(place, working = true, waiting = waiting, unsaved = unsaved, editorIdle = idle)))
                }
                // Files waiting or a question showing: Usugami only comes to the front.
                assertEquals("$place waiting", all(ShortcutStep.STAY), steps(scene(place, waiting = true, unsaved = unsaved, editorIdle = idle)))
            }
        }
        // A PDF tool asks first over unsaved work, and opens the shortcut's tool over saved work.
        assertEquals(all(ShortcutStep.ASK), steps(scene(ShortcutPlace.TOOL, unsaved = true)))
        assertEquals(all(ShortcutStep.OPEN), steps(scene(ShortcutPlace.TOOL)))
        // Images to PDF: Images stays; Scan adds a photo only on the plain list; Merge and Compress
        // open over the images.
        assertEquals(
            listOf(ShortcutStep.ADD_PHOTO, ShortcutStep.STAY, ShortcutStep.OPEN, ShortcutStep.OPEN),
            steps(scene(ShortcutPlace.EDITOR, editorIdle = true)),
        )
        assertEquals(
            listOf(ShortcutStep.STAY, ShortcutStep.STAY, ShortcutStep.OPEN, ShortcutStep.OPEN),
            steps(scene(ShortcutPlace.EDITOR)),
        )
        // Home and Choose a tool open the tool, also with stray flags that only a tool or the editor reads.
        for (place in listOf(ShortcutPlace.HOME, ShortcutPlace.CHOOSE_TOOL)) {
            assertEquals("$place", all(ShortcutStep.OPEN), steps(scene(place)))
            assertEquals("$place", all(ShortcutStep.OPEN), steps(scene(place, unsaved = true, editorIdle = true)))
        }
    }

    @Test
    fun aShortcutIsFoundByItsIdAndNothingElse() {
        assertEquals(listOf("scan", "images", "merge", "compress"), ShortcutTarget.entries.map { it.id })
        for (target in ShortcutTarget.entries) assertEquals(target, ShortcutTarget.of(target.id))
        for (id in listOf(null, "", "SCAN", "Merge", " merge", "merge ", "reader", "MERGE", "scan,merge")) assertNull(id, ShortcutTarget.of(id))
    }
}
