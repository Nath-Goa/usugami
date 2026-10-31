package io.github.nath_goa.usugami

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.nath_goa.usugami.core.Compression
import io.github.nath_goa.usugami.core.Jpeg
import io.github.nath_goa.usugami.core.LayoutSettings
import io.github.nath_goa.usugami.data.ImageFormat
import io.github.nath_goa.usugami.data.ImageItem
import io.github.nath_goa.usugami.data.PdfExporter
import io.github.nath_goa.usugami.data.PdfFile
import io.github.nath_goa.usugami.data.PdfOcr
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** PDFs made from photos hold the pictures only: no EXIF, vendor data or hidden extra images. */
class ImageExportDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "test-images").apply { mkdirs() }

    private fun noise(width: Int, height: Int): Bitmap {
        val random = Random(1)
        val pixels = IntArray(width * height) { 0xFF000000.toInt() or random.nextInt(0x1000000) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun export(file: File, width: Int, height: Int): PdfFile {
        val item = ImageItem(1, file.path, file.name, ImageFormat.JPEG, width, height, ExifInterface.ORIENTATION_NORMAL)
        return runBlocking {
            PdfExporter(context).export(listOf(item), LayoutSettings(), Compression.RECOMMENDED, true, "Notes", "Usugami") {}
        }.single()
    }

    private fun exportPdf(file: File, width: Int, height: Int): ByteArray = export(file, width, height).file.readBytes()

    /** Only what the app sets: no author, device or file path, and no XMP. */
    private fun assertGenericDetails(pdf: File) = PDDocument.load(pdf).use { doc ->
        val info = doc.documentInformation
        assertEquals(setOf("Title", "Producer", "Creator", "CreationDate", "ModDate"), info.metadataKeys)
        assertEquals(listOf("Notes", "Usugami", "Usugami"), listOf(info.title, info.producer, info.creator))
        assertNull(doc.documentCatalog.metadata)
    }

    @Test fun exifIsLeftOutWhenTheOriginalIsKept() {
        val file = File(dir, "exif.jpg")
        // Low quality, so the original is smaller than a re-encoded copy and goes into the PDF as is.
        file.outputStream().use { noise(400, 300).compress(Bitmap.CompressFormat.JPEG, 20, it) }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_MAKE, "SecretCam")
            setLatLong(15.4909, 73.8278)
            saveAttributes()
        }
        assertTrue(file.readBytes().indexOf("SecretCam") >= 0)

        val pdf = exportPdf(file, 400, 300)
        assertTrue("Original image kept", pdf.indexOf(Jpeg.stripForEmbedding(file.readBytes())) >= 0)
        assertEquals(-1, pdf.indexOf("SecretCam"))
        assertEquals(-1, pdf.indexOf("Exif"))
    }

    /** PDF viewers read embedded JPEGs as sRGB, so a Display P3 original would show dull colours. */
    @Test fun wideGamutOriginalIsConvertedToSrgb() {
        val file = File(dir, "p3.jpg")
        val p3 = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888, false, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        Canvas(p3).drawBitmap(noise(400, 300), 0f, 0f, null)
        // Low quality, so the original would be smaller than a re-encoded copy.
        file.outputStream().use { p3.compress(Bitmap.CompressFormat.JPEG, 20, it) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        assumeTrue("This device did not tag the JPEG as Display P3", bounds.outColorSpace?.isSrgb == false)

        val pdf = exportPdf(file, 400, 300)
        assertEquals("Original image left out", -1, pdf.indexOf(Jpeg.stripForEmbedding(file.readBytes())))
    }

    @Test fun pdfDetailsAreGenericAfterTextRecognitionToo() {
        val ocr = PdfOcr(context) // also sets up PDFBox
        val file = File(dir, "photo.jpg")
        file.outputStream().use { noise(200, 150).compress(Bitmap.CompressFormat.JPEG, 80, it) }
        val pdf = export(file, 200, 150)
        assertGenericDetails(pdf.file)
        val searchable = runBlocking { ocr.embed(pdf, listOf(PdfOcr.Line(0, "Hello", Rect(10, 10, 120, 40), 200, 150))) }
        assertGenericDetails(searchable.file)
    }

    /** Recent phones save Ultra HDR photos: XMP, an MPF index and a gain map image after the photo. */
    @SdkSuppress(minSdkVersion = 34)
    @Test fun ultraHdrGainMapIsLeftOutWhenReEncoded() {
        val file = File(dir, "ultrahdr.jpg")
        val photo = noise(400, 300).apply { gainmap = Gainmap(Bitmap.createBitmap(100, 75, Bitmap.Config.ARGB_8888)) }
        file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        assumeTrue("This device did not write an Ultra HDR JPEG", file.readBytes().indexOf("hdrgm") >= 0)

        val pdf = exportPdf(file, 400, 300)
        assertEquals(-1, pdf.indexOf("hdrgm"))
        assertEquals(-1, pdf.indexOf("MPF"))
    }

    private fun ByteArray.indexOf(text: String): Int = indexOf(text.toByteArray())

    private fun ByteArray.indexOf(pattern: ByteArray): Int {
        outer@ for (i in 0..size - pattern.size) {
            for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }
}
