package io.github.nath_goa.usugami.core

/** Where a file opened in the reader was opened from, which is where Back leads. */
enum class ReadFrom { OUTSIDE, HOME, RECENT }

/** Telling what a file opened in the reader is. */
object OpenedFiles {
    /** The kinds the reader opens: PDFs as they are, the others made into PDFs first. */
    val READABLE: Set<SharedKind> = setOf(SharedKind.PDF, SharedKind.WORD, SharedKind.POWERPOINT, SharedKind.EPUB, SharedKind.EXCEL)

    /** How much of a file's start is looked at for "%PDF-", as on import (PdfToolsEngine.hasPdfHeader). */
    const val HEADER = 1024

    private val PDF_HEADER = "%PDF-".toByteArray(Charsets.US_ASCII)

    /** "PK\u0003\u0004": a ZIP file's first entry. */
    private val ZIP_HEADER = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /**
     * Whether the first [length] bytes of [start] (at most [HEADER]) have "%PDF-" in them: a byte
     * order mark or a blank line from the server that made the PDF may come first, as viewers allow.
     */
    fun isPdf(start: ByteArray, length: Int = start.size): Boolean {
        val end = minOf(length, start.size, HEADER)
        return (0..end - PDF_HEADER.size).any { at -> PDF_HEADER.indices.all { start[at + it] == PDF_HEADER[it] } }
    }

    /** Whether [start] begins a ZIP file, which .docx, .xlsx, .pptx and .epub files are. */
    fun isZip(start: ByteArray, length: Int = start.size): Boolean =
        minOf(length, start.size) >= ZIP_HEADER.size && ZIP_HEADER.indices.all { start[it] == ZIP_HEADER[it] }

    /**
     * What a file is taken as: [probed], what its content says, when it says anything; else
     * [declared], what its type and name say. Null when that is no kind the reader opens.
     * A file whose content doesn't tell keeps its declared kind, so that its converter, or the
     * PDF engine, says what is wrong with it (an old .doc, a damaged PDF).
     */
    fun kind(declared: SharedKind, probed: SharedKind?): SharedKind? = (probed ?: declared).takeIf { it in READABLE }
}
