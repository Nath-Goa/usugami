package io.github.nath_goa.usugami.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.PdfInput

// Usugami Lite has no Translate PDF. Home, its search and Choose a tool leave the tool out (see
// translationAvailable), so nothing below is ever shown: the texts stand in for the standard
// edition's own, which this edition doesn't carry.

/** Whether this edition translates PDFs: Usugami Lite doesn't. */
internal val translationAvailable: Boolean = false

/** Translate PDF's name. Never shown in this edition. */
@StringRes
internal fun translateTitle(): Int = R.string.app_label

/** What Translate PDF does. Never shown in this edition. */
@StringRes
internal fun translateDetail(): Int = R.string.app_label

/** Translate PDF's hidden search words. Never searched in this edition. */
@StringRes
internal fun translateKeywords(): Int = R.string.app_label

/** Translate PDF's button. Never shown in this edition. */
@StringRes
internal fun translateActionLabel(): Int = R.string.app_label

/** Translate PDF's choices: none in this edition, which never opens the tool. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun TranslateOptions(input: PdfInput, shown: PdfInput, vm: PdfToolsViewModel, action: ToolAction) = Unit

/** The notes on a result of Translate PDF: none in this edition, which has no such results. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun translateNotesText(notes: TranslateNotes?): String? = null
