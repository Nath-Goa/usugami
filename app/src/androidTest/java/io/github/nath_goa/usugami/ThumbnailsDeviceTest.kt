package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.Thumbnails
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The small pictures of the pages in the editor. */
class ThumbnailsDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * A page shrunk to a size that isn't a whole number of pixels, as a straightened page often is,
     * has no grey line along its edge: on white paper it would show against the white page around it.
     */
    @Test fun aWhitePageHasNoGreyEdge() = runBlocking {
        val file = File(app.cacheDir, "thumbnail-test.jpg")
        try {
            val white = Bitmap.createBitmap(1000, 1403, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            file.outputStream().use { white.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            for (orientation in listOf(1, 6)) {
                val item = ImageItem(1, file.path, "page.jpg", ImageFormat.JPEG, 1000, 1403, orientation)
                val thumbnail = Thumbnails().load(item)!!
                for (x in listOf(0, thumbnail.width - 1)) {
                    for (y in listOf(0, thumbnail.height / 2, thumbnail.height - 1)) {
                        val red = Color.red(thumbnail.getPixel(x, y))
                        assertTrue("Orientation $orientation, pixel ($x, $y) of ${thumbnail.width} x ${thumbnail.height}: $red", red >= 250)
                    }
                }
            }
        } finally {
            file.delete()
        }
    }
}
