package io.github.nath_goa.usugami.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.nath_goa.usugami.data.PdfInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Page [index] drawn as it is shown, and its shown size in points. */
class RenderedPage(val index: Int, val image: ImageBitmap, val width: Float, val height: Float)

/**
 * Page [pageIndex] of [input] as it is shown (its crop box, turned by its rotation), on white, with
 * its longer side [longSide] pixels. Null if it can't be rendered. Reads the file: call it off the
 * main thread.
 */
fun renderShownPage(input: PdfInput, pageIndex: Int, longSide: Int): RenderedPage? = try {
    ParcelFileDescriptor.open(input.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            renderer.openPage(pageIndex).use { page ->
                val scale = longSide.toFloat() / maxOf(page.width, page.height)
                val bitmap = Bitmap.createBitmap(maxOf(1, (page.width * scale).toInt()), maxOf(1, (page.height * scale).toInt()), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                RenderedPage(pageIndex, bitmap.asImageBitmap(), page.width.toFloat(), page.height.toFloat())
            }
        }
    }
} catch (_: Exception) { null }

/** Like [renderShownPage], the image only. */
fun renderPdfPage(input: PdfInput, pageIndex: Int, longSide: Int): ImageBitmap? = renderShownPage(input, pageIndex, longSide)?.image

@Composable
fun PdfThumbnail(input: PdfInput, pageIndex: Int, modifier: Modifier = Modifier) {
    val preview by produceState<ImageBitmap?>(null, input.path, pageIndex) {
        value = withContext(Dispatchers.IO) { renderPdfPage(input, pageIndex, 150) }
    }
    // The page fades in over the placeholder once it has been rendered, instead of popping in.
    Crossfade(
        targetState = preview,
        modifier = modifier.background(androidx.compose.ui.graphics.Color.White),
        animationSpec = Motion.short(),
        label = "pdf thumbnail",
    ) { bitmap ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (bitmap != null) Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            else Icon(Icons.Rounded.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
