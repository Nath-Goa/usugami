package io.github.nath_goa.usugami.ui

import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.core.PageChoice
import io.github.nath_goa.usugami.data.PdfPageRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reorganize PDF's selected pages, by their keys, so they stay selected wherever they are moved.
 * Kept in the saved state as their places ("2,5-7"), a few bytes even for every page of a long PDF.
 */
internal class ChosenPages(private val handle: SavedStateHandle, pages: List<PdfPageRef>) {
    private val _keys = MutableStateFlow(PageChoice.unpack(handle[KEY] ?: "", pages.map { it.key }))
    val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    /** Makes [keys] the selection, as far as they are pages of [pages]. */
    fun set(keys: Set<String>, pages: List<PdfPageRef>) {
        _keys.value = keys
        follow(pages)
    }

    /** The page list is now [pages]: pages no longer in it leave the selection, which is kept anew. */
    fun follow(pages: List<PdfPageRef>) {
        val chosen = _keys.value
        if (chosen.isEmpty()) {
            handle.remove<String>(KEY)
            return
        }
        val order = pages.map { it.key }
        val left = PageChoice.following(order, chosen)
        _keys.value = left
        if (left.isEmpty()) handle.remove<String>(KEY) else handle[KEY] = PageChoice.pack(order, left)
    }

    private companion object {
        const val KEY = "chosen_pages"
    }
}
