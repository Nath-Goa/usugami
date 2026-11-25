package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
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
