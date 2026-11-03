package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Segments share the row's width equally, so on narrow phones or with a large font size a label
// like "Recommended" would be cut off. The app's segmented buttons therefore have no check icon
// (the selected segment is still filled and announced as selected), narrower padding, and a label
// that shrinks when it still doesn't fit. Top bar titles shrink the same way.

/** Content padding for segmented buttons: 6 dp instead of the default 12 dp on each side. */
val SegmentPadding = PaddingValues(horizontal = 6.dp)

/**
 * Label for a segmented button that shrinks to fit instead of being cut off. Only a label too long
 * even at the smallest size (a very large font size on a narrow phone) ends in "…".
 */
@Composable
fun SegmentLabel(text: String) {
    val size = MaterialTheme.typography.labelLarge.fontSize
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = size, stepSize = 0.5.sp),
    )
}

/**
 * A top bar's title. The bar is one line tall: a long title (a translation, a large font size)
 * shrinks a little and then ends in "…", rather than wrapping onto a second line the bar cuts off.
 */
@Composable
fun BarTitle(text: String) {
    Text(
        text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = 16.sp, maxFontSize = MaterialTheme.typography.titleLarge.fontSize, stepSize = 0.5.sp),
    )
}
