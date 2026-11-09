package io.github.nath_goa.usugami.ui

import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle

/**
 * Keeps where a result's PDFs were saved in the saved state, beside the result itself (see
 * [KeptResult]): their copies in Downloads and in the folder saved into last. Saving the result again
 * after Android stopped Usugami in the background then copies only what has gone, as it does
 * otherwise, rather than adding "Name (1).pdf" beside each copy.
 */
internal object KeptCopies {
    /** The saved-state key the copies are kept under. */
    const val KEY = "result_copies"

    /**
     * Past this many bytes a list of copies isn't kept: the saved state holds little, and the result
     * takes some of it already. Thousands of PDFs saved into a folder with long names could pass it.
     */
    private const val MAX_BYTES = 64 * 1024

    /** Can't be in a URI, so it separates them. */
    private const val SEPARATOR = '\u0000'

    /**
     * The copies for the saved state: [downloads] in Downloads and [inFolder] in the folder [tree],
     * each at the place of its PDF in the result, null for one not copied there.
     */
    fun pack(downloads: List<Uri?>?, tree: Uri?, inFolder: List<Uri?>?): Bundle {
        val kept = Bundle()
        downloads?.let(::packUris)?.let { kept.putBundle("downloads", it) }
        if (tree != null) {
            inFolder?.let(::packUris)?.let {
                kept.putBundle("folder", it)
                kept.putString("tree", tree.toString())
            }
        }
        return kept
    }

    /** The copies in Downloads [pack] kept for a result of [count] PDFs, if any. */
    fun downloads(handle: SavedStateHandle, count: Int): List<Uri?>? =
        handle.get<Bundle>(KEY)?.getBundle("downloads")?.let { unpackUris(it, count) }

    /** The folder saved into last and the copies in it that [pack] kept for a result of [count] PDFs, if any. */
    fun folder(handle: SavedStateHandle, count: Int): Pair<Uri, List<Uri?>>? {
        val kept = handle.get<Bundle>(KEY) ?: return null
        val tree = kept.getString("tree")?.toUri() ?: return null
        val copies = kept.getBundle("folder")?.let { unpackUris(it, count) } ?: return null
        return tree to copies
    }

    /**
     * [uris] in a few bytes each, or null for none or too many: copies made in one place differ only
     * after the last "/" (a number in Downloads), so what comes before it is kept once.
     */
    private fun packUris(uris: List<Uri?>): Bundle? {
        val first = uris.firstNotNullOfOrNull { it }?.toString() ?: return null
        val base = first.substringBeforeLast('/') + '/'
        val parts = uris.joinToString(SEPARATOR.toString()) { uri ->
            val whole = uri?.toString().orEmpty()
            val end = whole.removePrefix(base)
            // One that differs sooner is kept whole: it has a "/", which an end never has.
            if (end != whole && end.isNotEmpty() && '/' !in end) end else whole
        }.encodeToByteArray()
        return if (parts.size > MAX_BYTES) null else bundleOf("base" to base, "parts" to parts)
    }

    /** The URIs [packUris] packed, or null when they aren't for [count] PDFs. */
    private fun unpackUris(packed: Bundle, count: Int): List<Uri?>? {
        val base = packed.getString("base") ?: return null
        val parts = packed.getByteArray("parts")?.decodeToString()?.split(SEPARATOR) ?: return null
        if (parts.size != count) return null
        return parts.map { part ->
            when {
                part.isEmpty() -> null
                '/' in part -> part.toUri()
                else -> (base + part).toUri()
            }
        }
    }
}
