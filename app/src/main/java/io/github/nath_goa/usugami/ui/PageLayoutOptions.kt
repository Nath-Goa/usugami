package io.github.nath_goa.usugami.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.pageLayout

/** Page layout's own state in the view model (see [ToolState]). */
internal class PageLayoutState(@Suppress("unused") private val handle: SavedStateHandle) : ToolState

/**
 * Page layout's choices, for [input]; its pages are drawn from [shown] (see
 * PdfToolsEngine.previewView). They tell [action] when the button is on and what it does.
 */
@Composable
internal fun PageLayoutOptions(input: PdfInput, shown: PdfInput, vm: PdfToolsViewModel, action: ToolAction) {
    action.Bind(ready = false) { fileName -> vm.runPageLayout(input, fileName) }
}

/** Saves [input] with its pages cropped, or several of them on each sheet, named after [fileName]. */
internal fun PdfToolsViewModel.runPageLayout(input: PdfInput, fileName: String) {
    // Not for a PDF put away between the screen's last drawing and the tap (see runFromPdf).
    if (inputs.value.singleOrNull()?.id != input.id) return
    process(fileName, listOf(input)) { check -> listOf(engine.pageLayout(input, fileName, check)) }
}
