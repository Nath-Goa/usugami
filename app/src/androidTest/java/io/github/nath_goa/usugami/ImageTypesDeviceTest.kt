package io.github.nath_goa.usugami

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorSpace
import android.net.Uri
import android.os.Build
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nath_goa.usugami.data.HeifTiles
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.ImageStore
import io.github.nath_goa.usugami.data.PagePictures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * HEIC, AVIF, WebP, BMP and GIF images are stored as a JPEG, or a PNG when they are see-through
 * or lossless, with their pixels as stored and their orientation kept. The samples are 128 × 64,
 * red in the stored top-left corner; the HEIC and AVIF ones are turned a quarter clockwise by a
 * property of their own. The HEIC one's EXIF data is marked upright, as libheif saves a photo, so
 * it shows that the property wins over EXIF; the AVIF one has no EXIF data.
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            assertStored("turned.heic", 31, ImageFormat.JPEG, orientation = 6)
        } else {
            assertEquals(ImageStore.ImportResult.Unsupported, import("turned.heic", 31))
        }
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

    /**
     * A 200-megapixel HEIC in 512-pixel tiles, as phones save one, but of two colours, so a small
     * file: blue in its top-left eighth, red elsewhere. It is read a tile at a time, straight to the
     * size it is stored at, without the 400 MB and more Android's decoder takes to make it whole.
     */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
    @Test fun aHugeHeicIsReadATileAtATime() {
        val uri = sample("huge.heic")
        Runtime.getRuntime().gc()
        val before = residentKb()
        var peak = before
        val sampler = thread {
            try {
                while (true) {
                    peak = maxOf(peak, residentKb())
                    Thread.sleep(5)
                }
            } catch (e: InterruptedException) {
                // Done.
            }
        }
        val result = try {
            store.import(uri, 39)
        } finally {
            sampler.interrupt()
            sampler.join()
        }
        assertTrue("$result", result is ImageStore.ImportResult.Added)
        val item = (result as ImageStore.ImportResult.Added).item.also { added += it }
        assertEquals(ImageFormat.JPEG, item.format)
        assertEquals(4080 to 3060, item.width to item.height)
        val pixels = BitmapFactory.decodeFile(item.path, BitmapFactory.Options().apply { inSampleSize = 8 })
        assertTrue("Blue corner", Color.blue(pixels.getPixel(10, 10)) > 180 && Color.red(pixels.getPixel(10, 10)) < 80)
        assertTrue("Red elsewhere", Color.red(pixels.getPixel(400, 300)) > 180 && Color.blue(pixels.getPixel(400, 300)) < 80)
        assertTrue("Took ${(peak - before) / 1024} MB", peak - before < 200 * 1024)
        // Annotate reads it the same way, for a picture put on a page.
        val picture = checkNotNull(PagePictures.decode(checkNotNull(uri.path), 1, 1024))
        assertEquals(1024 to 768, picture.width to picture.height)
        assertTrue("Blue corner", Color.blue(picture.getPixel(10, 10)) > 180 && Color.red(picture.getPixel(10, 10)) < 80)
    }

    /** How much memory Usugami's process has in use, in kB. */
    private fun residentKb(): Long =
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.filter(Char::isDigit).toLong()

    /**
     * Read a tile at a time, a picture has the colours Android's own decoder gives it. tiles.heic is
     * 1024 × 512 in two tiles, a gradient, in Display P3, which is brought to sRGB: there a colour
     * beyond sRGB can differ more, as a level more or less in a dark channel shows more.
     */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
    @Test fun tilesGiveTheColoursAndroidGives() {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(File(checkNotNull(sample("tiles.heic").path)).path, bounds)
        assertFalse("Not sRGB", bounds.outColorSpace!!.isSrgb)
        assertSameAsAndroid("tiles.heic")
        assertSameAsAndroid("turned.heic")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) assertSameAsAndroid("turned.avif")
    }

    private fun assertSameAsAndroid(name: String) {
        val path = checkNotNull(sample(name).path)
        val tiles = checkNotNull(HeifTiles.decode(path, 2)) { name }
        val android = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = 2
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            },
        )
        assertEquals(name, android.width to android.height, tiles.width to tiles.height)
        var worst = 0
        var total = 0L
        for (y in 0 until tiles.height) for (x in 0 until tiles.width) {
            val a = android.getPixel(x, y)
            val b = tiles.getPixel(x, y)
            val off = maxOf(abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
            worst = maxOf(worst, off)
            total += off
        }
        val average = total.toDouble() / (tiles.width * tiles.height)
        assertTrue("$name: off by $average on average, $worst at most", average <= 1 && worst <= 24)
    }

    /** A picture put on a page in Annotate is turned the way Images to PDF turns the same photo. */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.P)
    @Test fun editPdfTurnsHeicPicturesTheSameWay() {
        val pictures = PagePictures(app)
        try {
            val picture = checkNotNull(pictures.import(sample("turned.heic"), 38))
            assertEquals(6, picture.orientation)
            assertEquals("Upright size", 64 to 128, picture.width to picture.height)
        } finally {
            pictures.deleteAllExcept(emptyList())
        }
    }
}
