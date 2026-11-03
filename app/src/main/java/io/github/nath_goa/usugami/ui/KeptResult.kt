package io.github.nath_goa.usugami.ui

import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.data.PdfFile
import java.io.File

/**
 * Keeps a result's PDFs in the saved state, so that when Android stops Usugami in the background
 * the result screen shows again, rather than the PDFs being deleted without a word. The PDFs
 * themselves are in the cache: check they are still there before showing them again.
 */
internal class KeptResult(private val handle: SavedStateHandle) {

    /** The PDFs kept, or null when there was no result (or too many PDFs to keep, see [count]). */
    fun restore(): List<PdfFile>? {
        val paths = handle.get<Array<String>>(KEY_PATHS) ?: return null
        val pages = handle.get<IntArray>(KEY_PAGES) ?: return null
        val originals = handle.get<LongArray>(KEY_ORIGINALS) ?: return null
        if (paths.isEmpty() || pages.size != paths.size || originals.size != paths.size) return null
        return paths.indices.map { PdfFile(File(paths[it]), pages[it], originals[it]) }
    }

    /** How many PDFs the kept result had, also when there were too many to keep; 0 for none. */
    val count: Int get() = handle[KEY_COUNT] ?: 0

    /** Keeps [files], the result showing now (null for none). */
    fun keep(files: List<PdfFile>?) {
        if (files == null) {
            for (key in listOf(KEY_COUNT, KEY_PATHS, KEY_PAGES, KEY_ORIGINALS)) handle.remove<Any>(key)
            return
        }
        handle[KEY_COUNT] = files.size
        if (files.size > MAX_FILES) {
            // One per image of a long list: too much for the saved state, so they will only be said to be gone.
            for (key in listOf(KEY_PATHS, KEY_PAGES, KEY_ORIGINALS)) handle.remove<Any>(key)
            return
        }
        handle[KEY_PATHS] = Array(files.size) { files[it].file.path }
        handle[KEY_PAGES] = IntArray(files.size) { files[it].pageCount }
        handle[KEY_ORIGINALS] = LongArray(files.size) { files[it].originalBytes }
    }

    private companion object {
        const val KEY_COUNT = "result_count"
        const val KEY_PATHS = "result_paths"
        const val KEY_PAGES = "result_pages"
        const val KEY_ORIGINALS = "result_originals"
        const val MAX_FILES = 100
    }
}
