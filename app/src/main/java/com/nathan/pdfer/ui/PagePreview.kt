package com.nathan.pdfer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nathan.pdfer.core.LayoutSettings
import com.nathan.pdfer.core.PageLayout
import com.nathan.pdfer.data.ImageItem
import com.nathan.pdfer.data.Thumbnails
import kotlin.math.roundToInt

/**
 * The page as it will come out in the PDF: page shape, margins and image placement come from
 * the same [PageLayout] the PDF writer uses.
 */
@Composable
fun PagePreview(item: ImageItem, layout: LayoutSettings, thumbnails: Thumbnails, modifier: Modifier = Modifier) {
    val geometry = remember(item.shownWidth, item.shownHeight, layout) {
        PageLayout.compute(item.shownWidth, item.shownHeight, layout)
    }
    val thumbnail by produceState<ImageBitmap?>(thumbnails.cached(item.id)?.asImageBitmap(), item.id) {
        if (value == null) value = thumbnails.load(item)?.asImageBitmap()
    }
    // A thumbnail that has to be decoded fades in over the placeholder; a cached one shows at once.
    val thumbnailAlpha by animateFloatAsState(
        targetValue = if (thumbnail != null) 1f else 0f,
        animationSpec = Motion.short(),
        label = "thumbnail fade",
    )
    val placeholder = MaterialTheme.colorScheme.surfaceVariant
    val rotation = item.rotation

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .aspectRatio((geometry.pageWidth / geometry.pageHeight).toFloat())
                .shadow(2.dp, RectangleShape)
                .border(0.5.dp, Color(0x22000000)),
        ) {
            drawRect(Color.White)
            val scale = size.width / geometry.pageWidth.toFloat()
            val left = geometry.imageX.toFloat() * scale
            val top = geometry.imageTop.toFloat() * scale
            val width = geometry.imageWidth.toFloat() * scale
            val height = geometry.imageHeight.toFloat() * scale
            val image = thumbnail
            val imageAlpha = thumbnailAlpha
            if (image == null || imageAlpha < 1f) {
                drawRect(placeholder, Offset(left, top), Size(width, height))
            }
            if (image == null) return@Canvas
            // The thumbnail is upright; turn it by the user's rotation around the image centre.
            val sideways = rotation % 180 != 0
            val drawW = if (sideways) height else width
            val drawH = if (sideways) width else height
            val center = Offset(left + width / 2, top + height / 2)
            rotate(rotation.toFloat(), pivot = center) {
                drawImage(
                    image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset((center.x - drawW / 2).roundToInt(), (center.y - drawH / 2).roundToInt()),
                    dstSize = IntSize(drawW.roundToInt().coerceAtLeast(1), drawH.roundToInt().coerceAtLeast(1)),
                    alpha = imageAlpha,
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}
