package io.github.nath_goa.usugami.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.EditStyle
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.ui.kit.LinearProgress
import io.github.nath_goa.usugami.ui.kit.Space

/**
 * The reader's Annotate: what Edit PDF was until 2.0.0 (EditPdfOptions), on [shown], the reader's PDF
 * as it draws it (its view), then a line saying that saving makes a new PDF and the name it gets
 * ([fileName], [onFileName]). It scrolls as Edit PDF's screen did, and ends above the keyboard while
 * the name is typed. While a picture is copied in ([importing]), a moving bar shows at the top. The
 * page showing ([page], [onPage]) and the style of the next item ([style], [onStyle]) are kept by the
 * reader; [onPickPicture] opens the photo picker for a picture on the page given.
 */
@Composable
internal fun ReaderAnnotate(
    shown: PdfInput,
    vm: PdfToolsViewModel,
    importing: Boolean,
    page: Int,
    onPage: (Int) -> Unit,
    style: EditStyle,
    onStyle: (EditStyle) -> Unit,
    fileName: String,
    onFileName: (String) -> Unit,
    onPickPicture: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .imePadding()
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = Space.content)
                .fillMaxWidth(),
        ) {
            // Outside the spaced column, with its own gap, so the gap opens and closes with the bar (as in PdfToolScreen).
            AnimatedVisibility(visible = importing, enter = pdfSectionEnter(), exit = pdfSectionExit(), label = "importing") {
                LinearProgress(
                    Modifier
                        .padding(bottom = Space.gap)
                        .fillMaxWidth(),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.gap)) {
                EditPdfOptions(
                    input = shown,
                    vm = vm,
                    importing = importing,
                    pageShown = page,
                    onPage = onPage,
                    style = style,
                    onStyle = onStyle,
                    onPickPicture = onPickPicture,
                )
                Text(
                    stringResource(R.string.reader_save_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.ground),
                )
                FileNameField(fileName, onChange = onFileName)
            }
        }
    }
}
