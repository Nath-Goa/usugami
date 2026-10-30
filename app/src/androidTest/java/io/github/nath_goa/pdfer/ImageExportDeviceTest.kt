package io.github.nath_goa.pdfer

import android.graphics.Bitmap
import android.graphics.Gainmap
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import io.github.nath_goa.pdfer.core.Compression
import io.github.nath_goa.pdfer.core.Jpeg
import io.github.nath_goa.pdfer.core.LayoutSettings
import io.github.nath_goa.pdfer.data.ImageFormat
import io.github.nath_goa.pdfer.data.ImageItem
import io.github.nath_goa.pdfer.data.PdfExporter
import io.github.nath_goa.pdfer.data.PdfFile
import io.github.nath_goa.pdfer.data.PdfOcr
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
            PdfExporter(context).export(listOf(item), LayoutSettings(), Compression.RECOMMENDED, true, "Notes", "PDFer") {}
        }.single()
    }

    private fun exportPdf(file: File, width: Int, height: Int): ByteArray = export(file, width, height).file.readBytes()

    /** Only what the app sets: no author, device or file path, and no XMP. */
    private fun assertGenericDetails(pdf: File) = PDDocument.load(pdf).use { doc ->
        val info = doc.documentInformation
        assertEquals(setOf("Title", "Producer", "Creator", "CreationDate", "ModDate"), info.metadataKeys)
        assertEquals(listOf("Notes", "PDFer", "PDFer"), listOf(info.title, info.producer, info.creator))
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
