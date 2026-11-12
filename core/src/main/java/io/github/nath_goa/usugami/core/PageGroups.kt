package io.github.nath_goa.usugami.core

/**
 * How the images of a scan make pages: one image a page, except the sides of an ID card, which
 * share one. An image's card number is 0 when it is not a card's side; the sides of one card carry
 * the same number and lie next to each other, the front first.
 */
object PageGroups {
    /**
     * The pages a list of images makes, in order: an image with card number 0 is a page of its
     * own; images next to each other with the same non-zero number are one page, two at most
     * (a third starts a new page).
     */
    fun <T> pages(items: List<T>, card: (T) -> Long): List<List<T>> {
        val pages = ArrayList<List<T>>()
        var i = 0
        while (i < items.size) {
            val number = card(items[i])
            val pairs = number != 0L && i + 1 < items.size && card(items[i + 1]) == number
            if (pairs) {
                pages.add(listOf(items[i], items[i + 1]))
                i += 2
            } else {
                pages.add(listOf(items[i]))
                i += 1
            }
        }
        return pages
    }

    /**
     * [items] with [added] put right after the last image of its card, or at the end when it has
     * no card or its card has no image yet.
     */
    fun <T> withAdded(items: List<T>, added: T, card: (T) -> Long): List<T> {
        val number = card(added)
        val last = if (number == 0L) -1 else items.indexOfLast { card(it) == number }
        if (last < 0) return items + added
        return items.subList(0, last + 1) + added + items.subList(last + 1, items.size)
    }
}
