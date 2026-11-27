package io.github.nath_goa.usugami.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.Thumbnails

/**
 * What the Photos screen's buttons do: [takeAnother] opens the camera for the next page, [done]
 * goes on to cropping, [remove] takes a photo (by its id) out of the scan, and [discard] ends the
 * scan without its photos.
 */
class ScanPhotosActions(
    val takeAnother: () -> Unit,
    val done: () -> Unit,
    val remove: (Long) -> Unit,
    val discard: () -> Unit,
)

/**
 * A scan's first stage: the [photos] taken so far, in the order taken, as they were taken, each
 * with its number and a button that removes it, and Take another and Done at the foot. [adding]
 * photos have been taken and are still being added. Leaving with photos asks first whether to
 * discard them. [active] is false while the screen animates away: it then takes no Back.
 */
@Composable
fun ScanPhotosScreen(
    photos: List<ImageItem>,
    adding: Int,
    thumbnails: Thumbnails,
    active: Boolean,
    snackbar: SnackbarHostState,
    actions: ScanPhotosActions,
) {
}
