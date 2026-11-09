package io.github.nath_goa.usugami.core

/**
 * Whether this failure (or one of its causes) is the storage running full, as Android reports it:
 * "write failed: ENOSPC (No space left on device)". So does PDFBox running out of the room Usugami
 * gives its scratch files, which leaves the phone some storage free.
 */
fun Throwable.isOutOfSpace(): Boolean {
    val text = generateSequence(this) { it.cause }.joinToString(" ") { it.message.orEmpty() }
    return "ENOSPC" in text || "No space left" in text || SCRATCH_FULL in text
}

/** What PDFBox says when a document's scratch files reach their limit. */
private const val SCRATCH_FULL = "Maximum allowed scratch file memory exceeded"
