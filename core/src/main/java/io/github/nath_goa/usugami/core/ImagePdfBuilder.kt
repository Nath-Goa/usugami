package io.github.nath_goa.usugami.core

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

    /**
     * Black and white, stored a bit a pixel: CCITT Group 4 (as fax machines and scanners store
     * pages), or Flate when that is smaller (as for a speckled picture).
     */
    class Bilevel(val image: BitImage) : PageImage {
        override val width get() = image.width
        override val height get() = image.height
    }
}

/**
 * Where [ImagePdfBuilder.addPage] put an image: the page and the rectangle the image fills
 * ([geometry]), drawn turned by [transform] (EXIF orientation, then the user's rotation).
 */
data class ImagePlacement(val geometry: PageGeometry, val transform: ImageTransform) {
    /** The `cm` matrix the page draws the image with. */
    fun matrix(): DoubleArray = transform.pdfMatrix(geometry.imageX, geometry.imageY, geometry.imageWidth, geometry.imageHeight)

    /**
     * Where the page shows the point ([s], [t]) of the image as it appears there (upright and turned
     * as the user chose; [s] across and [t] down, each 0 to 1), in points from the page's bottom-left
     * corner. The point goes back to the image as stored and through [matrix], so it lands exactly
     * where the PDF draws it.
     */
    fun toPage(s: Double, t: Double): Pair<Double, Double> {
        val stored = transform.inverse()
        val u = stored.a * s + stored.b * t + stored.c
        // An image XObject fills the unit square with its first row at the top.
        val v = 1 - (stored.d * s + stored.e * t + stored.f)
        val m = matrix()
        return Pair(m[0] * u + m[2] * v + m[4], m[1] * u + m[3] * v + m[5])
    }
}

/**
 * One image of a page that holds several: stored with [exifOrientation] (1–8), turned
 * [rotationDegrees] clockwise by the user, drawn in the rectangle [geometry] gives it on the page
 * [geometry] describes.
 */
class PageDrawing(val image: PageImage, val exifOrientation: Int, val rotationDegrees: Int, val geometry: PageGeometry)

/**
 * Writes a PDF of images, streaming each page out as it is added. A page holds one image laid out
 * by the [settings], so only one image is in memory at a time; or, for an ID card, its two sides,
 * small and laid out by the caller, which are held together while their page is written. Call
 * [addPage] for every page, then [finish].
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
     * [rotationDegrees] is the extra clockwise rotation the user picked. Returns where the image went.
     */
    fun addPage(image: PageImage, exifOrientation: Int, rotationDegrees: Int, checkCancelled: () -> Unit = {}): ImagePlacement {
        val transform = ImageTransform.of(exifOrientation, rotationDegrees)
        val shownWidth = if (transform.swapsAxes) image.height else image.width
        val shownHeight = if (transform.swapsAxes) image.width else image.height
        val placement = ImagePlacement(PageLayout.compute(shownWidth, shownHeight, settings), transform)
        val page = placement.geometry

        val imageObject = writer.reserve()
        writeImage(imageObject, image, checkCancelled)

        val m = placement.matrix()
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
        return placement
    }

    /**
     * Adds a page with [drawings] on it (all with the same page box), the first drawn first.
     * Returns where each went, in the same order.
     */
    fun addPage(drawings: List<PageDrawing>, checkCancelled: () -> Unit = {}): List<ImagePlacement> {
        require(drawings.isNotEmpty()) { "A page needs at least one image" }
        val box = drawings.first().geometry
        require(drawings.all { it.geometry.pageWidth == box.pageWidth && it.geometry.pageHeight == box.pageHeight }) {
            "The images of one page must share its page box"
        }
        val placements = drawings.map { ImagePlacement(it.geometry, ImageTransform.of(it.exifOrientation, it.rotationDegrees)) }

        val imageObjects = drawings.map { drawing ->
            val imageObject = writer.reserve()
            writeImage(imageObject, drawing.image, checkCancelled)
            imageObject
        }

        val content = StringBuilder()
        placements.forEachIndexed { i, placement ->
            val m = placement.matrix()
            content.append("q\n${m.joinToString(" ") { PdfSyntax.number(it) }} cm\n/Im$i Do\nQ\n")
        }
        val contentObject = writer.reserve()
        writer.writeStream(contentObject, "", content.toString().toByteArray(Charsets.ISO_8859_1))

        val xObjects = imageObjects.withIndex().joinToString(" ") { (i, number) -> "/Im$i $number 0 R" }
        val pageObject = writer.reserve()
        writer.writeObject(
            pageObject,
            "<< /Type /Page /Parent $pages 0 R" +
                " /MediaBox [0 0 ${PdfSyntax.number(box.pageWidth)} ${PdfSyntax.number(box.pageHeight)}]" +
                " /Resources << /XObject << $xObjects >> /ProcSet [/PDF /ImageB /ImageC] >>" +
                " /Contents $contentObject 0 R >>",
        )
        pageObjects.add(pageObject)
        return placements
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
        val size = "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height}"
        val common = "$size /BitsPerComponent 8"
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
            is PageImage.Bilevel -> {
                val bilevel = "$size /BitsPerComponent 1 /ColorSpace /DeviceGray"
                val g4 = CcittG4.encode(image.image, checkCancelled)
                val flate = RasterEncoder.flateBits(image.image, checkCancelled)
                if (g4.size <= flate.size) {
                    // 0 is black, as in DeviceGray (/BlackIs1 false).
                    writer.writeStream(
                        number,
                        "$bilevel /Filter /CCITTFaxDecode /DecodeParms << /K -1 /Columns ${image.width} /Rows ${image.height} >>",
                        g4,
                    )
                } else {
                    writer.writeStream(
                        number,
                        "$bilevel /Filter /FlateDecode /DecodeParms << /Predictor 15 /Colors 1 /BitsPerComponent 1 /Columns ${image.width} >>",
                        flate,
                    )
                }
            }
        }
    }
}
