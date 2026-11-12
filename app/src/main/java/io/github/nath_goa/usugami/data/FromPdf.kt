package io.github.nath_goa.usugami.data

/** [input]'s pages as images, or its text as a file, in place of the files made before. The lane fills it in: nothing is made yet. */
internal fun PdfToolsEngine.fromPdf(input: PdfInput, fileName: String, check: () -> Unit = {}): List<PdfFile> = newOutput { emptyList() }
