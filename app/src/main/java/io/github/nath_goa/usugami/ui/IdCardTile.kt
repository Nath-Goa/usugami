package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nath_goa.usugami.R
import io.github.nath_goa.usugami.core.IdCardLayout
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.Thumbnails

/**
 * An ID card's page in the editor's grid, page [pageNumber]: the sheet as it will be in the PDF with
 * the card's [sides] on it (front first; one or two), its crop button (a menu of the two sides when
 * it has both), its name and its remove button, which removes the whole card; a card with its front
 * alone also has Add back side, which asks for the back. A card has no turn buttons: each side is
 * turned on its own crop screen. [cropping] are the images being cropped.
 */
@Composable
fun IdCardTile(
    pageNumber: Int,
    sides: List<ImageItem>,
    layout: LayoutSettings,
    thumbnails: Thumbnails,
    actions: EditorActions,
    cropping: Set<Long>,
) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.85f)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(12.dp),
        ) {
            IdCardPreview(sides, layout, thumbnails, Modifier.fillMaxSize())
            PageNumberBadge(pageNumber, Modifier.align(Alignment.TopStart))
            // A side being cropped can't be cropped again until it is done (its crop would start
            // from the page before), as on any page.
            Box(Modifier.align(Alignment.TopEnd)) {
                var menu by remember { mutableStateOf(false) }
                FilledTonalIconButton(
                    onClick = { if (sides.size == 1) actions.crop(sides[0].id) else menu = true },
                    enabled = sides.any { it.id !in cropping },
                ) {
                    Icon(Icons.Rounded.Crop, contentDescription = stringResource(R.string.crop_page, pageNumber))
                }
                DropdownMenu(expanded = menu && sides.size > 1, onDismissRequest = { menu = false }) {
                    sides.forEachIndexed { i, side ->
                        DropdownMenuItem(
                            text = { Text(stringResource(if (i == 0) R.string.card_crop_front else R.string.card_crop_back)) },
                            enabled = side.id !in cropping,
                            onClick = {
                                // The items can still be tapped while the menu fades out: only the first tap counts.
                                if (menu) {
                                    menu = false
                                    actions.crop(side.id)
                                }
                            },
                        )
                    }
                }
            }
            if (sides.any { it.id in cropping }) {
                val label = stringResource(R.string.cropping_page, pageNumber)
                CircularProgressIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = label })
            }
        }
        Text(
            stringResource(R.string.card_title),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sides.size == 1) {
                // A screen reader hears its page, as of the crop and remove buttons: "Add back side, Page 5".
                val label = stringResource(R.string.scan_pane_title, stringResource(R.string.card_add_back), stringResource(R.string.pdf_page_number, pageNumber))
                // On a narrow tile in large text it shrinks a little, as the editor's bottom buttons do, rather
                // than being cut off.
                TextButton(
                    onClick = { actions.addCardBack(sides[0].card) },
                    modifier = Modifier.weight(1f).semantics { contentDescription = label },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                ) {
                    val style = MaterialTheme.typography.labelLarge
                    Text(
                        stringResource(R.string.card_add_back),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            IconButton(onClick = { actions.remove(sides[0].id) }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_page, pageNumber))
            }
        }
    }
}

/**
 * An ID card's page as it will come out in the PDF: the page and where each of the card's [sides]
 * goes on it come from the same [IdCardLayout] the PDF writer uses, and each side is drawn as
 * [PagePreview] draws a page's image.
 */
@Composable
fun IdCardPreview(sides: List<ImageItem>, layout: LayoutSettings, thumbnails: Thumbnails, modifier: Modifier = Modifier) {
    val shown = sides.map { it.shownWidth to it.shownHeight }
    val places = remember(shown, layout) { IdCardLayout.compute(shown, layout) }
    // Each side keeps its own thumbnail while the other changes or goes.
    val images = sides.map { side -> key(side.id) { rememberThumbnail(side, thumbnails) } }
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val page = places[0]

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .aspectRatio((page.pageWidth / page.pageHeight).toFloat())
                .shadow(2.dp, RectangleShape)
                .border(0.5.dp, Color(0x22000000)),
        ) {
            drawRect(Color.White)
            val scale = size.width / page.pageWidth.toFloat()
            places.forEachIndexed { i, place ->
                drawPageImage(
                    images[i].image,
                    images[i].alpha,
                    sides[i].rotation,
                    placeholder,
                    left = place.imageX.toFloat() * scale,
                    top = place.imageTop.toFloat() * scale,
                    width = place.imageWidth.toFloat() * scale,
                    height = place.imageHeight.toFloat() * scale,
                )
            }
        }
    }
}
