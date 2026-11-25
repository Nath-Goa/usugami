package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import io.github.nath_goa.usugami.R

// Translate PDF is the standard edition's own: its texts are in this edition's strings only, and
// the screens shared by every edition ask for them here. Usugami Lite and Usugami OCR have the same
// declarations, saying there is no such tool. Its choices are in TranslateOptions.kt.

/** Whether this edition translates PDFs: the standard edition does, with Google ML Kit. */
internal val translationAvailable: Boolean = true

/** Translate PDF's name, on Home and at the top of its screen. */
@StringRes
internal fun translateTitle(): Int = R.string.translate_title

/** What Translate PDF does, under its name on Home. */
@StringRes
internal fun translateDetail(): Int = R.string.translate_detail

/** Translate PDF's hidden words for Home's search. */
@StringRes
internal fun translateKeywords(): Int = R.string.translate_keywords

/** Translate PDF's button. */
@StringRes
internal fun translateActionLabel(): Int = R.string.translate_action

/**
 * The notes on a result of Translate PDF, each a paragraph of its own: the pages with no text to
 * translate, those that look scanned but couldn't be read and why, and those where some text was
 * left as it was. Null when there is nothing to say.
 */
@Composable
internal fun translateNotesText(notes: TranslateNotes?): String? {
    if (notes == null) return null
    val paragraphs = ArrayList<String>()
    notes.noText?.let { paragraphs += pluralStringResource(R.plurals.translate_note_no_text, it.count, it.count, it.listed) }
    notes.unread?.let {
        val said = if (notes.unreadWhy == TranslateNotes.UNREAD_LANGUAGE) R.plurals.translate_note_unread_language else R.plurals.translate_note_unread_failed
        paragraphs += pluralStringResource(said, it.count, it.count, it.listed)
    }
    notes.partly?.let { paragraphs += pluralStringResource(R.plurals.translate_note_partly, it.count, it.count, it.listed) }
    return paragraphs.joinToString("\n\n").ifEmpty { null }
}
