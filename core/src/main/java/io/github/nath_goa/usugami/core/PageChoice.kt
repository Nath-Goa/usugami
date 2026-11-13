package io.github.nath_goa.usugami.core

/**
 * Reorganize PDF's selected pages. Pages are known by keys [K] that stay with them wherever they
 * go in the list; what the user types and reads are their places in the list as it is now ("2,5-7").
 */
object PageChoice {
    /** The places (zero-based, going up) of the pages of [order] that are in [chosen]. */
    fun <K> places(order: List<K>, chosen: Set<K>): List<Int> =
        if (chosen.isEmpty()) emptyList() else order.indices.filter { order[it] in chosen }

    /** [chosen] without the pages no longer in [order]; [chosen] itself when all still are. */
    fun <K> following(order: List<K>, chosen: Set<K>): Set<K> {
        if (chosen.isEmpty()) return chosen
        val left = order.filterTo(HashSet()) { it in chosen }
        return if (left.size == chosen.size) chosen else left
    }

    /**
     * What the field shows: [typed] as it is while it names just the pages at [places], or can't
     * be read and nothing is selected; otherwise [places] written as ranges.
     */
    fun fieldText(typed: String, places: List<Int>, pageCount: Int): String {
        val read = PdfPageSelection.read(typed, pageCount)
        val asTyped = if (read == null) places.isEmpty() else read.toSet() == places.toSet()
        return if (asTyped) typed else PdfPageSelection.format(places)
    }

    /** The selection for the saved state: the pages' places as ranges, a few bytes however many. */
    fun <K> pack(order: List<K>, chosen: Set<K>): String = PdfPageSelection.format(places(order, chosen))

    /** The selection [pack] kept, for the same [order]; none if it doesn't fit it. */
    fun <K> unpack(text: String, order: List<K>): Set<K> =
        if (order.isEmpty()) emptySet() else PdfPageSelection.read(text, order.size).orEmpty().mapTo(HashSet()) { order[it] }
}
