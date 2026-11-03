package io.github.nath_goa.usugami.core

/**
 * Whether this failure (or one of its causes) is the storage running full, as Android reports it:
 * "write failed: ENOSPC (No space left on device)".
 */
fun Throwable.isOutOfSpace(): Boolean {
    val text = generateSequence(this) { it.cause }.joinToString(" ") { it.message.orEmpty() }
    return "ENOSPC" in text || "No space left" in text
}
