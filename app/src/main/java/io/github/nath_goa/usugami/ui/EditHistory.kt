package io.github.nath_goa.usugami.ui

import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.core.PageItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Edit PDF's items and the one chosen, kept in the saved state so they survive rotation and Android
 * closing Usugami in the background. Undo and Redo go back and forth over whole changes; they are
 * kept in memory only, as Reorganize PDF's Undo is.
 */
internal class EditHistory(private val handle: SavedStateHandle) {
    val items: StateFlow<List<PageItem>> = handle.getStateFlow(KEY_ITEMS, ArrayList<PageItem>())

    /** The chosen item's id, or null. */
    val selected: StateFlow<Long?> = handle.getStateFlow(KEY_SELECTED, null)

    private val undoStack = ArrayDeque<List<PageItem>>()
    private val redoStack = ArrayDeque<List<PageItem>>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo = _canRedo.asStateFlow()

    /** A change made of many steps, such as a drag, is under way: its steps are one change for Undo. */
    private var gesture = false

    private var nextId = (items.value.maxOfOrNull { it.id } ?: 0L) + 1

    fun newId(): Long = nextId++

    fun select(id: Long?) {
        if (selected.value != id) handle[KEY_SELECTED] = id
    }

    /** Makes [list] the items, as one change. */
    fun change(list: List<PageItem>) {
        gesture = false
        if (list == items.value) return
        push(items.value)
        set(list)
    }

    /** Starts a change made of many steps (a drag, or a slider moved); [step] makes each. */
    fun begin() {
        if (gesture) return
        push(items.value)
        gesture = true
    }

    fun step(list: List<PageItem>) {
        if (!gesture) begin()
        set(list)
    }

    /** The change [begin] started is over; one that changed nothing leaves nothing to undo. */
    fun end() {
        if (!gesture) return
        gesture = false
        if (undoStack.lastOrNull() == items.value) {
            undoStack.removeLast()
            updateFlags()
        }
    }

    /** Takes back the latest change. Returns the page it was on, or null when there is nothing to undo. */
    fun undo(): Int? {
        gesture = false
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(items.value)
        return travel(previous)
    }

    /** Makes again the change Undo last took back. Returns the page it is on, or null when there is nothing to redo. */
    fun redo(): Int? {
        gesture = false
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(items.value)
        return travel(next)
    }

    private fun travel(target: List<PageItem>): Int? {
        val current = items.value
        // The page of an item that differs: the change Undo or Redo is showing.
        val changed = target.firstOrNull { it !in current } ?: current.firstOrNull { it !in target }
        set(target)
        updateFlags()
        if (target.none { it.id == selected.value }) select(null)
        return changed?.page
    }

    /** Forgets every item and the history, as when the tool closes. */
    fun clear() {
        gesture = false
        undoStack.clear()
        redoStack.clear()
        set(emptyList())
        select(null)
        updateFlags()
    }

    private fun push(list: List<PageItem>) {
        undoStack.addLast(list)
        while (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        updateFlags()
    }

    private fun set(list: List<PageItem>) {
        handle[KEY_ITEMS] = ArrayList(list)
    }

    private fun updateFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    private companion object {
        const val KEY_ITEMS = "edit_items"
        const val KEY_SELECTED = "edit_selected"

        /** Changes Undo can take back, one after another. */
        const val MAX_UNDO = 50
    }
}
