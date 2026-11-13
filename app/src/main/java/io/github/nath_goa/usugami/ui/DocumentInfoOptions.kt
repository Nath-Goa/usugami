package io.github.nath_goa.usugami.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import io.github.nath_goa.usugami.core.InfoChange
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.data.setDocumentInfo

/** Document info's own state in the view model (see [ToolState]). */
internal class DocumentInfoState(@Suppress("unused") private val handle: SavedStateHandle) : ToolState

/**
 * Document info's choices, for [input]; its first page is drawn from [shown]. They tell [action]
 * when the button is on and what it does.
 */
@Composable
internal fun DocumentInfoOptions(input: PdfInput, shown: PdfInput, vm: PdfToolsViewModel, action: ToolAction) {
    action.Bind(ready = false) { fileName -> vm.runDocumentInfo(input, fileName) }
}

/** Saves [input] with its title, author and the like as chosen, named after [fileName]. */
internal fun PdfToolsViewModel.runDocumentInfo(input: PdfInput, fileName: String) {
    // Not for a PDF put away between the screen's last drawing and the tap (see runFromPdf).
    if (inputs.value.singleOrNull()?.id != input.id) return
    process(fileName, listOf(input)) { check -> listOf(engine.setDocumentInfo(input, InfoChange(), fileName, check).file) }
}
