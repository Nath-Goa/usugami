package io.github.nath_goa.usugami.ui

import android.os.Bundle
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.core.MarkStyle
import io.github.nath_goa.usugami.data.PdfFile
import java.io.File

/**
 * Keeps a result's PDFs in the saved state, so that when Android stops Usugami in the background
 * the result screen shows again, rather than the PDFs being deleted without a word. The PDFs
 * themselves are in the cache: check they are still there before showing them again.
 */
internal class KeptResult(private val handle: SavedStateHandle) {

    /** The PDFs kept, or null when there was no result. */
    fun restore(): List<PdfFile>? {
        val kept = handle.get<Bundle>(KEY) ?: return null
        val folder = kept.getString("folder") ?: return null
        val files = kept.getByteArray("files")?.decodeToString()?.split(SEPARATOR) ?: return null
        val pages = kept.getIntArray("pages") ?: return null
        val originals = kept.getLongArray("originals") ?: return null
        if (pages.isEmpty() || files.size != pages.size || originals.size != pages.size) return null
        // Kept by Compress PDF; a result kept by an older version has none.
        val keptOriginals = kept.getBooleanArray("kept_originals")?.takeIf { it.size == pages.size }
        val marks = kept.getIntArray("marks")?.takeIf { it.size == pages.size }
        return files.indices.map { i ->
            val file = if ('/' in files[i]) File(files[i]) else File(folder, files[i])
            PdfFile(file, pages[i], originals[i], keptOriginal = keptOriginals?.get(i) == true, mark = marks?.get(i)?.let(::unpackMark))
        }
    }

    /** Keeps [files], the result showing now (null for none). */
    fun keep(files: List<PdfFile>?) {
        if (files.isNullOrEmpty()) handle.remove<Bundle>(KEY) else handle[KEY] = pack(files)
    }

    companion object {
        /** The saved-state key the result is kept under. */
        @VisibleForTesting
        internal const val KEY = "result"

        /** Can't be in a path, so it separates them. */
        private const val SEPARATOR = '\u0000'

        /**
         * [files] for the saved state, in a few bytes per PDF: a result can have one PDF per image of a
         * long list. They are all in one folder, so only their names are kept, after it.
         */
        @VisibleForTesting
        internal fun pack(files: List<PdfFile>): Bundle {
            val folder = files.first().file.parent.orEmpty()
            return bundleOf(
                "folder" to folder,
                "files" to files.joinToString(SEPARATOR.toString()) {
                    // Any PDF elsewhere keeps its whole path.
                    if (it.file.parent == folder) it.file.name else it.file.path
                }.encodeToByteArray(),
                "pages" to IntArray(files.size) { files[it].pageCount },
                "originals" to LongArray(files.size) { files[it].originalBytes },
                "kept_originals" to BooleanArray(files.size) { files[it].keptOriginal },
                "marks" to IntArray(files.size) { i -> files[i].mark?.let { it.size shl 8 or it.opacity } ?: NO_MARK },
            )
        }

        /** No Usugami mark, in [pack]'s "marks". */
        private const val NO_MARK = -1

        /** The mark [pack] kept as [packed]: its size and opacity in one number, or null for none. */
        private fun unpackMark(packed: Int): MarkStyle? = if (packed < 0) null else MarkStyle.of(packed shr 8, packed and 0xFF)
    }
}
