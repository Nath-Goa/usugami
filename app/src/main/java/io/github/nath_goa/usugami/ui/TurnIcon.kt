package io.github.nath_goa.usugami.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateLeft
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * The icon of a quarter turn to the left or, [clockwise], to the right, named [description]: the two
 * are drawn alike. A turn to the left is to the left whichever way the text runs, so neither is
 * mirrored for right-to-left text.
 */
@Composable
fun TurnIcon(clockwise: Boolean, description: String) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Icon(if (clockwise) Icons.AutoMirrored.Rounded.RotateRight else Icons.AutoMirrored.Rounded.RotateLeft, contentDescription = description)
    }
}
