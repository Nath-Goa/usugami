package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Segments share the row's width equally, so on narrow phones or with a large font size a label
// like "Recommended" would be cut off. The app's segmented buttons therefore have no check icon
// (the selected segment is still filled and announced as selected), narrower padding, and a label
// that shrinks a little when it still doesn't fit.

/** Content padding for segmented buttons: 6 dp instead of the default 12 dp on each side. */
val SegmentPadding = PaddingValues(horizontal = 6.dp)

/** Label for a segmented button that shrinks to fit instead of being cut off. */
@Composable
fun SegmentLabel(text: String) {
    val size = MaterialTheme.typography.labelLarge.fontSize
    Text(
        text,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = size, stepSize = 0.5.sp),
    )
}
