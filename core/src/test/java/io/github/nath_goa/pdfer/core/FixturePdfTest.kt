package io.github.nath_goa.pdfer.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * Builds PDFs described by `manifest.tsv` in the directory given with
 * `-PpdferFixtures=<dir>`, so external tools can check them (see tools/validate_pdfs.py).
 * Skipped in normal test runs.
 *
 * Manifest lines (tab separated):
 *   pdf  <output file>  <PageSize>  <PageOrientation>  <Margin>
 *   img  <image file>   <exif orientation>  <rotation degrees>
 */
class FixturePdfTest {

    @Test
    fun buildFixturePdfs() {
        val dirName = System.getProperty("pdfer.fixtures").orEmpty()
        assumeTrue("no fixture directory given", dirName.isNotEmpty())
        val dir = File(dirName)

        var builder: ImagePdfBuilder? = null
        var stream: java.io.OutputStream? = null
        fun close() {
            builder?.finish()
            stream?.close()
        }
        File(dir, "manifest.tsv").readLines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t')
            when (parts[0]) {
                "pdf" -> {
                    close()
                    val settings = LayoutSettings(PageSize.valueOf(parts[2]), PageOrientation.valueOf(parts[3]), Margin.valueOf(parts[4]))
                    stream = File(dir, parts[1]).outputStream().buffered()
                    builder = ImagePdfBuilder(stream!!, settings, parts[1].removeSuffix(".pdf"), "PDFer")
                }
                "img" -> {
                    val file = File(dir, parts[1])
                    val bytes = file.readBytes()
                    val image = if (Jpeg.isJpeg(bytes)) {
                        val info = Jpeg.parse(bytes)
                        check(info.canEmbedDirectly) { "${file.name} needs re-encoding, which only the app does" }
                        PageImage.Jpeg(Jpeg.stripForEmbedding(bytes), info)
                    } else {
                        PageImage.Raster(TestImages.BufferedImageSource(ImageIO.read(file)))
                    }
                    builder!!.addPage(image, parts[2].toInt(), parts[3].toInt())
                }
                else -> error("bad manifest line: $line")
            }
        }
        close()
    }
}
