package io.github.nath_goa.usugami.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import io.github.nath_goa.usugami.core.LookAdjustments
import io.github.nath_goa.usugami.core.PageLook
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.Thumbnails

/**
 * What the Filters screen does: [select] shows a page (by its id) large, [look] and [adjust] change
 * the page shown, or every page of the scan while the same filter is kept for all, [sameForAll]
 * turns that on or off, [save] ends the scan, and [back] goes back to cropping.
 */
class ScanLooksActions(
    val select: (Long) -> Unit,
    val look: (PageLook) -> Unit,
    val adjust: (LookAdjustments) -> Unit,
    val sameForAll: (Boolean) -> Unit,
    val save: () -> Unit,
    val back: () -> Unit,
)

/**
 * A scan's last stage: the page [selected] among the scan's [pages] (in order; the first when it
 * isn't among them) large, in its look; under it all the pages in a row, to choose from; under
 * that the looks, each shown on that page, its brightness and contrast, and whether the same filter
 * goes to every page ([sameForAll]; not offered for one page); Save at the foot. A page in
 * [cropping] is still being made. [active] is false while the screen animates away: it then takes
 * no Back.
 */
@Composable
fun ScanLooksScreen(
    pages: List<ImageItem>,
    selected: Long,
    sameForAll: Boolean,
    cropping: Set<Long>,
    thumbnails: Thumbnails,
    active: Boolean,
    snackbar: SnackbarHostState,
    actions: ScanLooksActions,
) {
}
