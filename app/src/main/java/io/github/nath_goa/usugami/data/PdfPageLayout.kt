package io.github.nath_goa.usugami.data

/** Saves [input] with its pages cropped, or several of them on each sheet. The lane fills it in: the pages are saved as they are. */
internal fun PdfToolsEngine.pageLayout(input: PdfInput, fileName: String, check: () -> Unit = {}): PdfFile =
    edit(input, fileName, "Page_layout", check) { }
