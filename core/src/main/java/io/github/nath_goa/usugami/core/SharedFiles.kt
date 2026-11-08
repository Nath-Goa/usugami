package io.github.nath_goa.usugami.core

/**
 * What a file shared to Usugami from another app is, as far as Usugami goes: which of its tools
 * takes it. [OTHER] is a file none of them takes.
 */
enum class SharedKind { PDF, IMAGE, WORD, POWERPOINT, EPUB, EXCEL, OTHER }

/**
 * Sorting the files shared to Usugami together. A file manager shares whatever was selected, so a
 * share can hold files of several kinds; Usugami opens one tool with them, so it takes the files of
 * one kind and says which it left out.
 */
object SharedFiles {

    /**
     * What a shared file is: from [type], the type the app that has the file gives it (such as
     * "application/pdf"), else from its [name]'s extension, else from [sharedAs], the type the
     * whole share was sent as (any image, say). Types that say nothing about the file, such as
     * "application/octet-stream" or any file at all, are passed over.
     */
    fun kindOf(type: String?, name: String?, sharedAs: String? = null): SharedKind =
        kindOfType(type) ?: kindOfName(name) ?: kindOfType(sharedAs) ?: SharedKind.OTHER

    /**
     * The kind a share of files of [kinds] is taken as: the kind most of them are, and when as many
     * are of two kinds, PDFs before images, and images before Word, PowerPoint, EPUB and Excel
     * files, in that order. Null when none is of a kind Usugami takes.
     */
    fun chosen(kinds: List<SharedKind>): SharedKind? =
        kinds.filter { it != SharedKind.OTHER }
            .groupingBy { it }
            .eachCount()
            .maxWithOrNull(compareBy<Map.Entry<SharedKind, Int>> { it.value }.thenByDescending { it.key.ordinal })
            ?.key

    private fun kindOfType(type: String?): SharedKind? {
        val t = type?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when {
            t == "application/pdf" || t == "application/x-pdf" -> SharedKind.PDF
            t.startsWith("image/") -> SharedKind.IMAGE
            t in WORD_TYPES -> SharedKind.WORD
            t in POWERPOINT_TYPES -> SharedKind.POWERPOINT
            t == "application/epub+zip" -> SharedKind.EPUB
            t in EXCEL_TYPES -> SharedKind.EXCEL
            else -> null
        }
    }

    private fun kindOfName(name: String?): SharedKind? {
        val dot = name?.lastIndexOf('.') ?: return null
        if (dot < 0) return null
        return when (name.substring(dot + 1).trim().lowercase()) {
            "pdf" -> SharedKind.PDF
            "jpg", "jpeg", "jpe", "png", "webp", "gif", "heic", "heif", "bmp", "avif" -> SharedKind.IMAGE
            "docx", "docm", "dotx", "dotm", "doc" -> SharedKind.WORD
            "pptx", "pptm", "ppsx", "ppsm", "potx", "ppt", "pps" -> SharedKind.POWERPOINT
            "epub" -> SharedKind.EPUB
            "xlsx", "xlsm", "xltx", "xltm", "xls" -> SharedKind.EXCEL
            else -> null
        }
    }

    // The old binary formats are taken too: the converter then says how to make them into ones it reads.
    private val WORD_TYPES = setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        "application/vnd.ms-word.document.macroenabled.12",
        "application/vnd.ms-word.template.macroenabled.12",
        "application/msword",
    )

    private val POWERPOINT_TYPES = setOf(
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
        "application/vnd.openxmlformats-officedocument.presentationml.template",
        "application/vnd.ms-powerpoint.presentation.macroenabled.12",
        "application/vnd.ms-powerpoint.slideshow.macroenabled.12",
        "application/vnd.ms-powerpoint",
    )

    private val EXCEL_TYPES = setOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
        "application/vnd.ms-excel.sheet.macroenabled.12",
        "application/vnd.ms-excel.template.macroenabled.12",
        "application/vnd.ms-excel",
    )
}
