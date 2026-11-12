package io.github.nath_goa.usugami.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.fromPdf

/** PDF to images or text's own state in the view model (see [ToolState]). */
internal class FromPdfState(@Suppress("unused") private val handle: SavedStateHandle) : ToolState

/**
 * PDF to images or text's choices, for [input]; its pages are drawn from [shown] (see
 * PdfToolsEngine.previewView). They tell [action] when the button is on and what it does.
 */
@Composable
internal fun FromPdfOptions(input: PdfInput, shown: PdfInput, vm: PdfToolsViewModel, action: ToolAction) {
    // Its files aren't PDFs: the options show how they are named.
    action.Bind(ready = false, suffix = null) { fileName -> vm.runFromPdf(input, fileName) }
}

/** Makes [input]'s pages into images, or its text into a file, named after [fileName]. */
internal fun PdfToolsViewModel.runFromPdf(input: PdfInput, fileName: String) {
    // As the tools in the view model do (they read inputs again): not for a PDF put away between
    // the screen's last drawing and the tap, which would be said to have been cleared.
    if (inputs.value.singleOrNull()?.id != input.id) return
    process(fileName, listOf(input)) { check -> engine.fromPdf(input, fileName, check) }
}
