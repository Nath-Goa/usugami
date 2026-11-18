package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.PdfInput
import io.github.nath_goa.usugami.ui.kit.PressIndication
import io.github.nath_goa.usugami.ui.kit.TileShape
import io.github.nath_goa.usugami.ui.kit.Usugami
import io.github.nath_goa.usugami.ui.kit.fadingEdges
import io.github.nath_goa.usugami.ui.kit.paper
import io.github.nath_goa.usugami.ui.kit.tile

/**
 * A row of [input]'s pages to tap, those [selected] (zero-based) outlined, as Split PDF's "Selected
 * pages" and PDF to images or text show them. A page tapped is handed to [onTap], which puts it in
 * or takes it out of what was typed, reading that as it is then: two pages tapped before the screen
 * redraws are both chosen.
 */
@Composable
internal fun PageRowPicker(input: PdfInput, selected: Set<Int>, modifier: Modifier = Modifier, onTap: (Int) -> Unit) {
    val state = rememberLazyListState()
    LazyRow(state = state, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fadingEdges(state)) {
        items(input.pageCount) { i ->
            val active = i in selected
            val pageLabel = stringResource(R.string.pdf_page_number, i + 1)
            // The press and the focus outline follow the tile's corners, the outline just inside its chosen edge.
            Column(
                Modifier
                    .width(105.dp)
                    .tile(active)
                    .clickable(interactionSource = null, indication = PressIndication.shaped(TileShape, focusInset = 2.dp)) { onTap(i) }
                    .semantics { this.selected = active },
            ) {
                PdfThumbnail(input, i, Modifier.padding(6.dp).fillMaxWidth().height(118.dp).paper())
                // A screen reader says "Page 3" rather than a bare "3".
                Text(
                    "${i + 1}",
                    style = Usugami.type.tabular(MaterialTheme.typography.labelMedium),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .semantics { contentDescription = pageLabel }
                        .padding(bottom = 6.dp),
                )
            }
        }
    }
}
