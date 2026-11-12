package io.github.nath_goa.usugami.data

/** Saves [input] with its title, author and the like changed or removed. The lane fills it in: they are saved as they are. */
internal fun PdfToolsEngine.setDocumentInfo(input: PdfInput, fileName: String, check: () -> Unit = {}): PdfFile =
    edit(input, fileName, "Document_info", check) { }
