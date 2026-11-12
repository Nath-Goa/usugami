package io.github.nath_goa.usugami.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.data.PdfInput

/**
 * A row of [input]'s pages to tap, those [selected] (zero-based) outlined, as Split PDF's "Selected
 * pages" and PDF to images or text show them. A page tapped is handed to [onTap], which puts it in
 * or takes it out of what was typed, reading that as it is then: two pages tapped before the screen
 * redraws are both chosen.
 */
@Composable
internal fun PageRowPicker(input: PdfInput, selected: Set<Int>, modifier: Modifier = Modifier, onTap: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        items(input.pageCount) { i ->
            val active = i in selected
            val borderWidth by animateDpAsState(if (active) 2.dp else 1.dp, Motion.short(), label = "page border width")
            val borderColor by animateColorAsState(
                if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                Motion.short(),
                label = "page border color",
            )
            val pageLabel = stringResource(R.string.pdf_page_number, i + 1)
            OutlinedCard(
                onClick = { onTap(i) },
                border = BorderStroke(borderWidth, borderColor),
                modifier = Modifier.width(105.dp).semantics { this.selected = active },
            ) {
                PdfThumbnail(input, i, Modifier.fillMaxWidth().height(130.dp))
                // A screen reader says "Page 3" rather than a bare "3".
                Text(
                    "${i + 1}",
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .semantics { contentDescription = pageLabel }
                        .padding(6.dp),
                )
            }
        }
    }
}
