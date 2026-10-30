package io.github.nathgoa.pdfer.core

import java.io.OutputStream
import java.security.MessageDigest
import java.time.ZonedDateTime

/** An image ready to be placed on a page. */
sealed interface PageImage {
    val width: Int
    val height: Int

    /** JPEG data embedded as is (DCTDecode). Must satisfy [JpegInfo.canEmbedDirectly]. */
    class Jpeg(val data: ByteArray, val info: JpegInfo) : PageImage {
        init {
            require(info.canEmbedDirectly) { "JPEG cannot be embedded directly" }
        }

        override val width get() = info.width
        override val height get() = info.height
    }

    /** Decoded pixels, stored losslessly (FlateDecode). */
    class Raster(val pixels: PixelSource) : PageImage {
        override val width get() = pixels.width
        override val height get() = pixels.height
    }
}

/**
 * Writes a PDF with one image per page, streaming each page out as it is added so only one
 * image is in memory at a time. Call [addPage] for every image, then [finish].
 */
class ImagePdfBuilder(
    output: OutputStream,
    private val settings: LayoutSettings,
    private val title: String,
    private val producer: String,
    private val created: ZonedDateTime = ZonedDateTime.now(),
) {
    private val writer = PdfWriter(output)
    private val catalog = writer.reserve()
    private val pages = writer.reserve()
    private val info = writer.reserve()
    private val pageObjects = ArrayList<Int>()

    val pageCount: Int get() = pageObjects.size

    /**
     * Adds [image] as the next page. [exifOrientation] (1–8) is how the image is stored;
     * [rotationDegrees] is the extra clockwise rotation the user picked.
     */
    fun addPage(image: PageImage, exifOrientation: Int, rotationDegrees: Int, checkCancelled: () -> Unit = {}) {
        val transform = ImageTransform.of(exifOrientation, rotationDegrees)
        val shownWidth = if (transform.swapsAxes) image.height else image.width
        val shownHeight = if (transform.swapsAxes) image.width else image.height
        val page = PageLayout.compute(shownWidth, shownHeight, settings)

        val imageObject = writer.reserve()
        writeImage(imageObject, image, checkCancelled)

        val m = transform.pdfMatrix(page.imageX, page.imageY, page.imageWidth, page.imageHeight)
        val content = "q\n${m.joinToString(" ") { PdfSyntax.number(it) }} cm\n/Im0 Do\nQ\n"
        val contentObject = writer.reserve()
        writer.writeStream(contentObject, "", content.toByteArray(Charsets.ISO_8859_1))

        val pageObject = writer.reserve()
        writer.writeObject(
            pageObject,
            "<< /Type /Page /Parent $pages 0 R" +
                " /MediaBox [0 0 ${PdfSyntax.number(page.pageWidth)} ${PdfSyntax.number(page.pageHeight)}]" +
                " /Resources << /XObject << /Im0 $imageObject 0 R >> /ProcSet [/PDF /ImageB /ImageC] >>" +
                " /Contents $contentObject 0 R >>",
        )
        pageObjects.add(pageObject)
    }

    /** Writes the page tree, catalog, metadata and cross-reference table. */
    fun finish() {
        check(pageObjects.isNotEmpty()) { "A PDF needs at least one page" }
        writer.writeObject(
            pages,
            "<< /Type /Pages /Kids [${pageObjects.joinToString(" ") { "$it 0 R" }}] /Count ${pageObjects.size} >>",
        )
        writer.writeObject(catalog, "<< /Type /Catalog /Pages $pages 0 R >>")
        val date = PdfSyntax.textString(PdfSyntax.date(created))
        writer.writeObject(
            info,
            "<< /Title ${PdfSyntax.textString(title)} /Producer ${PdfSyntax.textString(producer)}" +
                " /Creator ${PdfSyntax.textString(producer)} /CreationDate $date /ModDate $date >>",
        )
        val id = MessageDigest.getInstance("MD5")
            .digest("$title|$created|${System.nanoTime()}|${pageObjects.size}".toByteArray())
        writer.finish(catalog, info, id)
    }

    private fun writeImage(number: Int, image: PageImage, checkCancelled: () -> Unit) {
        val common = "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} /BitsPerComponent 8"
        when (image) {
            is PageImage.Jpeg -> {
                val colorSpace = if (image.info.components == 1) "/DeviceGray" else "/DeviceRGB"
                val decodeParms = if (image.info.isPlainRgb) " /DecodeParms << /ColorTransform 0 >>" else ""
                writer.writeStream(number, "$common /ColorSpace $colorSpace /Filter /DCTDecode$decodeParms", image.data)
            }
            is PageImage.Raster -> {
                val gray = RasterEncoder.isGray(image.pixels, checkCancelled)
                val colors = if (gray) 1 else 3
                val colorSpace = if (gray) "/DeviceGray" else "/DeviceRGB"
                writer.writeStream(
                    number,
                    "$common /ColorSpace $colorSpace /Filter /FlateDecode" +
                        " /DecodeParms << /Predictor 15 /Colors $colors /BitsPerComponent 8 /Columns ${image.width} >>",
                ) { out -> RasterEncoder.writeFlate(image.pixels, gray, out, checkCancelled) }
            }
        }
    }
}
