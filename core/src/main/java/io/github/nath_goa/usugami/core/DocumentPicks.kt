package io.github.nath_goa.usugami.core

/**
 * Sorting the files picked with Home's Documents to PDF: which converter opens, as for files
 * shared (see [SharedFiles]). The kind most of them are is taken, and the others are left out.
 */
object DocumentPicks {
    /** The kinds Documents to PDF takes. */
    val KINDS: Set<SharedKind> = setOf(SharedKind.WORD, SharedKind.POWERPOINT, SharedKind.EPUB, SharedKind.EXCEL)

    /** The type the "mimetype" entry of an EPUB file holds. */
    private const val EPUB_TYPE = "application/epub+zip"

    /** The entry every EPUB file has, which says where its book is. */
    private const val EPUB_CONTAINER = "META-INF/container.xml"

    /**
     * What a picked file is by its [type] and [name]: one of [KINDS], else [SharedKind.OTHER]
     * (also a PDF or an image, which this tile doesn't take).
     */
    fun kindOf(type: String?, name: String?): SharedKind =
        SharedFiles.kindOf(type, name).takeIf { it in KINDS } ?: SharedKind.OTHER

    /**
     * Whether neither [type] nor [name] says what the file is, so its content is worth a look
     * (see [kindOfEntries]).
     */
    fun untold(type: String?, name: String?): Boolean = SharedFiles.kindOf(type, name) == SharedKind.OTHER

    /**
     * What a ZIP file is by the [names] of its first entries, in order, and by the text of its
     * "mimetype" entry ([mimetype]) when it has one: EPUB when that text is "application/epub+zip"
     * or an entry is "META-INF/container.xml"; WORD, POWERPOINT or EXCEL by the first entry under
     * "word/", "ppt/" or "xl/". Null when they don't tell yet (or never will). Names are compared
     * whatever their case, as the Office formats do.
     */
    fun kindOfEntries(names: List<String>, mimetype: String?): SharedKind? {
        if (mimetype?.trim().equals(EPUB_TYPE, ignoreCase = true)) return SharedKind.EPUB
        if (names.any { it.equals(EPUB_CONTAINER, ignoreCase = true) }) return SharedKind.EPUB
        for (name in names) {
            when {
                name.startsWith("word/", ignoreCase = true) -> return SharedKind.WORD
                name.startsWith("ppt/", ignoreCase = true) -> return SharedKind.POWERPOINT
                name.startsWith("xl/", ignoreCase = true) -> return SharedKind.EXCEL
            }
        }
        return null
    }

    /**
     * The kind the picked files of [kinds] are taken as: [SharedFiles.chosen] over those in [KINDS]
     * (Word before PowerPoint before EPUB before Excel when as many are of two kinds). Null for none.
     */
    fun chosen(kinds: List<SharedKind>): SharedKind? = SharedFiles.chosen(kinds.filter { it in KINDS })
}
