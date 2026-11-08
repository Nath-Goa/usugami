package io.github.nath_goa.usugami

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.ImageStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HEIC, AVIF, WebP, BMP and GIF images are stored as a JPEG, or a PNG when they are see-through
 * or lossless, with their pixels as stored and their orientation kept. The samples are 128 × 64,
 * red in the stored top-left corner; the HEIC and AVIF ones are turned a quarter clockwise by a
 * property of their own, as libheif saves a photo, with EXIF data marked upright.
 */
class ImageTypesDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val store = ImageStore(app)
    private val added = ArrayList<ImageItem>()
    private val samples = File(app.cacheDir, "test-images").apply { mkdirs() }

    @After fun cleanUp() {
        added.forEach(store::delete)
        samples.deleteRecursively()
    }

    private fun sample(name: String): Uri {
        val file = File(samples, name)
        instrumentation.context.assets.open("images/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    private fun import(name: String, id: Long): ImageStore.ImportResult = store.import(sample(name), id)

    /** [name] imported: stored as [format], [orientation] kept and red still in the stored top-left corner. */
    private fun assertStored(name: String, id: Long, format: ImageFormat, orientation: Int = 1): ImageItem {
        val result = import(name, id)
        assertTrue("$name: $result", result is ImageStore.ImportResult.Added)
        val item = (result as ImageStore.ImportResult.Added).item.also { added += it }
        assertEquals(name, format, item.format)
        assertEquals(name, 128 to 64, item.width to item.height)
        assertEquals(name, orientation, item.exifOrientation)
        val pixels = BitmapFactory.decodeFile(item.path)
        assertTrue(name, Color.red(pixels.getPixel(4, 4)) > 180 && Color.green(pixels.getPixel(4, 4)) < 80)
        return item
    }

    @Test fun otherImageTypesAreStoredAsJpegOrPng() {
        assertStored("turned.heic", 31, ImageFormat.JPEG, orientation = 6)
        assertStored("photo.webp", 32, ImageFormat.JPEG)
        val clear = assertStored("clear.webp", 33, ImageFormat.PNG)
        assertEquals("Transparency kept", 0, Color.alpha(BitmapFactory.decodeFile(clear.path).getPixel(100, 50)))
        assertStored("drawing.gif", 34, ImageFormat.PNG)
        assertStored("drawing.bmp", 35, ImageFormat.PNG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertStored("turned.avif", 36, ImageFormat.JPEG, orientation = 6)
        } else {
            assertEquals(ImageStore.ImportResult.Unsupported, import("turned.avif", 36))
        }
        // A TIFF is none of them; nothing is left behind.
        val tiff = File(samples, "scan.tiff").apply { writeBytes(byteArrayOf(0x49, 0x49, 0x2A, 0) + ByteArray(60)) }
        assertEquals(ImageStore.ImportResult.Unsupported, store.import(Uri.fromFile(tiff), 37))
        assertEquals(added.map { File(it.path).name }.toSet(), File(app.cacheDir, "images").list().orEmpty().filter { it.startsWith("3") }.toSet())
    }
}
