package io.github.nath_goa.usugami.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.OcrLanguage

/**
 * The text language as a chip ("Text language: German") that opens a menu of every language, the
 * chosen one checked. Wherever text recognition asks for a language, so that each place looks the same.
 */
@Composable
internal fun OcrLanguageChip(selected: OcrLanguage, onSelect: (OcrLanguage) -> Unit, enabled: Boolean = true) {
    OptionChip(
        label = stringResource(R.string.ocr_language),
        value = stringResource(selected.label),
        options = OcrLanguage.entries,
        selected = selected,
        optionLabel = { stringResource(it.label) },
        onSelect = onSelect,
        enabled = enabled,
    )
}
