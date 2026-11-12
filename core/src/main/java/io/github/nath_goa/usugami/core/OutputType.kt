package io.github.nath_goa.usugami.core

import java.util.Locale

/** What a file Usugami makes is: its type as other apps are told it ([mime]) and how its name ends ([extension]). */
enum class OutputType(val mime: String, val extension: String) {
    PDF("application/pdf", "pdf"),
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    TEXT("text/plain", "txt");

    val isImage: Boolean get() = this == JPEG || this == PNG

    companion object {
        /** What a file named [name] is, by its ending, whatever its case; a PDF when the ending says nothing else. */
        fun ofName(name: String): OutputType = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> JPEG
            "png" -> PNG
            "txt" -> TEXT
            else -> PDF
        }

        /** What a file of the type [mime] is shown as: an image, a text file, or else a PDF. */
        fun ofMime(mime: String): OutputType {
            val type = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
            return when {
                type == PNG.mime -> PNG
                type.startsWith("image/") -> JPEG
                type.startsWith("text/") -> TEXT
                else -> PDF
            }
        }
    }
}
