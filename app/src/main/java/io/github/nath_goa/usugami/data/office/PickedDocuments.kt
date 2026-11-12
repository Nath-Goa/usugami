package io.github.nath_goa.usugami.data.office

import android.content.Context
import android.net.Uri
import io.github.nath_goa.usugami.core.DocumentPicks
import io.github.nath_goa.usugami.core.SharedKind
import io.github.nath_goa.usugami.data.ShareInbox
import io.github.nath_goa.usugami.data.probeKind

/** The files picked with Home's Documents to PDF, sorted: the [kind] whose converter opens, and its files, in the order picked. */
class PickedDocuments(val kind: SharedKind, val uris: List<Uri>)

/** Sorts the files picked with Home's Documents to PDF by kind (see [DocumentPicks]). Blocking: call off the main thread. */
class DocumentSorter(private val context: Context) {
    private val inbox = ShareInbox(context)

    /**
     * The files sorted: [picked], those of the kind taken (null when none is of a kind the tile
     * takes); by name, [unsupported], those that are of none of its kinds, and [otherKind], those
     * of another of its kinds, which are left out.
     */
    class Sorted(val picked: PickedDocuments?, val unsupported: List<String>, val otherKind: List<String>)

    /**
     * Sorts [uris] by what their type and name say, or, when they say nothing, by what the start of
     * the file does (see [probeKind]). The files are read only for that, not copied: the
     * converter they open in takes them as files picked there.
     */
    fun sort(uris: List<Uri>): Sorted {
        val described = uris.map(inbox::describe)
        val kinds = uris.indices.map { i ->
            val (type, name) = described[i]
            val kind = DocumentPicks.kindOf(type, name)
            if (!DocumentPicks.untold(type, name)) kind else probeKind(context, uris[i])?.takeIf { it in DocumentPicks.KINDS } ?: kind
        }
        val kind = DocumentPicks.chosen(kinds)
        val names = uris.indices.map { i -> described[i].second ?: inbox.fallbackName(uris[i], kinds[i]) }
        return Sorted(
            picked = kind?.let { PickedDocuments(it, uris.filterIndexed { i, _ -> kinds[i] == it }) },
            unsupported = names.filterIndexed { i, _ -> kinds[i] == SharedKind.OTHER },
            otherKind = names.filterIndexed { i, _ -> kinds[i] != SharedKind.OTHER && kinds[i] != kind },
        )
    }

    companion object {
        /** What the picker offers: the types the four converters' own pickers offer, joined, each once. */
        val pickerTypes: Array<String> = OfficeKind.entries.flatMap { it.pickerTypes.asList() }.distinct().toTypedArray()
    }
}
