package io.github.nath_goa.usugami.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import io.github.nath_goa.usugami.core.FileNames
import io.github.nath_goa.usugami.core.PageFrame
import io.github.nath_goa.usugami.core.PageNumberPosition
import io.github.nath_goa.usugami.core.PageNumberSize
import io.github.nath_goa.usugami.core.PageNumbers
import io.github.nath_goa.usugami.core.RedactBox
import io.github.nath_goa.usugami.core.SignatureLayout
import io.github.nath_goa.usugami.core.SignaturePlacement
import io.github.nath_goa.usugami.core.WatermarkLayout
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.parcelize.Parcelize

@Parcelize
data class PdfInput(val id: Long, val path: String, val name: String, val pageCount: Int, val password: String = "") : Parcelable {
    val file: File get() = File(path)
}

@Parcelize
data class PdfPageRef(val inputId: Long, val page: Int, val rotation: Int = 0) : Parcelable

enum class PdfCompression(val dpi: Int, val quality: Float) {
    LESS(200, 0.85f), RECOMMENDED(150, 0.68f), EXTREME(100, 0.48f)
}

class PdfPasswordRequired(val input: PdfInput) : IOException("Password required")

/** The password given to [PdfToolsEngine.unlock] didn't open the PDF. */
class PdfWrongPassword : IOException("Wrong password")

/** The PDF is encrypted for a certificate (digital ID) rather than with a password; Usugami can't open those. */
class PdfCertificateEncrypted(cause: Throwable) : IOException("Certificate-encrypted PDFs aren't supported", cause)

/**
 * Split, merge, compress, rotate, page numbers, watermarks, signatures and redaction. Input copies, results and PDFBox's scratch files all live in the cache,
 * so the system can reclaim them (and "Clear cache" removes them); callers must cope with a file
 * that has gone.
 */
class PdfToolsEngine(private val context: Context) {
    private val inputDir = File(context.cacheDir, "pdf-inputs")
    private val outputDir = File(context.cacheDir, "output/tools")
    private val scratchDir = File(context.cacheDir, "pdfbox")

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    // The folder is created each time: the system may have cleared the cache since the last run.
    private fun memory() = MemoryUsageSetting.setupMixed(24L * 1024 * 1024).setTempDir(scratchDir.apply { mkdirs() })

    fun clearOutput() { outputDir.deleteRecursively() }

    /** Where a ZIP file of the results is made, beside them, so it goes when they do. */
    val zipDir get() = File(outputDir, "zip")

    /** Scratch files are deleted when PDFBox closes a document, but not when the app is killed mid-run. */
    fun clearScratch() { scratchDir.deleteRecursively() }

    fun delete(input: PdfInput) {
        input.file.delete()
        viewFile(input).delete()
    }

    fun deleteAllExcept(keep: Collection<PdfInput>) {
        val names = keep.mapTo(HashSet()) { it.file.name }
        inputDir.listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    /**
     * A local copy keeps access after the system document picker's grant expires. [check] is called
     * between chunks of the copy and throws to stop it: a large PDF from a cloud provider can take a while.
     */
    fun import(uri: Uri, id: Long, password: String = "", check: () -> Unit = {}): PdfInput {
        inputDir.mkdirs()
        val file = File(inputDir, "$id.pdf")
        val inputInfo = PdfInput(id, file.path, displayName(uri) ?: "Document.pdf", 0, password)
        // A view made for an earlier copy with this number would show the wrong PDF.
        viewFile(inputInfo).delete()
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open PDF")
            input.use { source ->
                file.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check()
                        val read = source.read(buffer)
                        if (read < 0) break
                        target.write(buffer, 0, read)
                    }
                }
            }
            if (!hasPdfHeader(file)) throw IOException("This file is not a PDF")
            return inspect(inputInfo, password)
        } catch (e: InvalidPasswordException) {
            // The copy is kept for the password dialog.
            throw PdfPasswordRequired(inputInfo)
        } catch (t: Throwable) {
            // Errors too (out of memory, a PDFBox class that fails to load): never leave the copy behind.
            file.delete()
            throw t
        }
    }

    fun inspect(input: PdfInput, password: String): PdfInput =
        load(input.file, password).use { doc ->
            if (doc.numberOfPages == 0) throw IOException("This PDF has no pages")
            input.copy(pageCount = doc.numberOfPages, password = password)
        }

    /** Opens a copy that [import] found locked; throws [PdfWrongPassword] if [password] doesn't fit. */
    fun unlock(input: PdfInput, password: String): PdfInput = try {
        inspect(input, password)
    } catch (_: InvalidPasswordException) {
        throw PdfWrongPassword()
    }

    fun split(input: PdfInput, groups: List<List<Int>>, baseName: String, check: () -> Unit = {}): List<PdfFile> {
        require(groups.isNotEmpty() && groups.all { it.isNotEmpty() })
        clearOutput()
        outputDir.mkdirs()
        val base = FileNames.sanitize(baseName, "Split")
        val taken = HashSet<String>()
        try {
            load(input.file, input.password).use { source ->
                return groups.mapIndexed { index, pages ->
                    check()
                    require(pages.all { it in 0 until source.numberOfPages })
                    val suffix = if (groups.size == 1) "" else "_${index + 1}_${pages.first() + 1}-${pages.last() + 1}"
                    val name = FileNames.unique(FileNames.sanitize(base + suffix, "Split"), taken)
                    taken += name
                    val target = File(outputDir, "$name.pdf")
                    PDDocument(memory()).use { dest ->
                        for (pageIndex in pages) {
                            check()
                            val page = source.getPage(pageIndex)
                            val imported = dest.importPage(page)
                            if (page.resources != null && !page.cosObject.containsKey(COSName.RESOURCES)) {
                                imported.resources = page.resources
                            }
                        }
                        dest.save(target)
                    }
                    PdfFile(target, pages.size, 0)
                }
            }
        } catch (t: Throwable) {
            clearOutput()
            throw t
        }
    }

    /** [pages] null means concatenate complete documents in [inputs] order. */
    fun merge(inputs: List<PdfInput>, pages: List<PdfPageRef>?, fileName: String, check: () -> Unit = {}): PdfFile {
        require(inputs.size >= 2)
        clearOutput()
        outputDir.mkdirs()
        val target = File(outputDir, "${FileNames.sanitize(fileName, "Merged")}.pdf")
        try {
            PDDocument(memory()).use { dest ->
                val offsets = HashMap<Long, Int>()
                val merger = PDFMergerUtility()
                for (input in inputs) {
                    check()
                    offsets[input.id] = dest.numberOfPages
                    load(input.file, input.password).use { source ->
                        merger.appendDocument(dest, source)
                    }
                }
                if (pages != null) {
                    require(pages.isNotEmpty())
                    val originals = (0 until dest.numberOfPages).map(dest::getPage)
                    repeat(dest.numberOfPages) { dest.removePage(0) }
                    for (ref in pages) {
                        check()
                        val input = inputs.first { it.id == ref.inputId }
                        require(ref.page in 0 until input.pageCount)
                        val page = originals[offsets.getValue(ref.inputId) + ref.page]
                        page.rotation = Math.floorMod(page.rotation + ref.rotation, 360)
                        dest.addPage(page)
                    }
                }
                dest.save(target)
                return PdfFile(target, dest.numberOfPages, 0)
            }
        } catch (t: Throwable) {
            clearOutput()
            throw t
        }
    }

    /**
     * Joins the PDFs in [files] into [target], in order, letting [edit] add to the whole (such as
     * bookmarks) before it is saved. For PDFs Usugami made itself in parts.
     */
    fun join(files: List<File>, target: File, check: () -> Unit = {}, edit: (PDDocument) -> Unit = {}): File {
        PDDocument(memory()).use { dest ->
            val merger = PDFMergerUtility()
            for (file in files) {
                check()
                load(file, "").use { merger.appendDocument(dest, it) }
            }
            check()
            edit(dest)
            dest.save(target)
        }
        return target
    }

    /**
     * Turns pages clockwise by [rotations] (page index to degrees, a multiple of 90), on top of the
     * turn they already have. Only the pages' /Rotate changes; their content is left as it is.
     */
    fun rotate(input: PdfInput, rotations: Map<Int, Int>, fileName: String, check: () -> Unit = {}): PdfFile =
        edit(input, fileName, "Rotated", check) { doc ->
            for ((index, degrees) in rotations) {
                check()
                require(index in 0 until doc.numberOfPages && degrees % 90 == 0)
                val page = doc.getPage(index)
                page.rotation = Math.floorMod(page.rotation + degrees, 360)
            }
        }

    /**
     * Writes on each of [pages] (zero-based) its own page number, as text in Helvetica, at
     * [position] on the page as it is shown.
     */
    fun addPageNumbers(
        input: PdfInput,
        pages: List<Int>,
        position: PageNumberPosition,
        size: PageNumberSize,
        fileName: String,
        check: () -> Unit = {},
    ): PdfFile = edit(input, fileName, "Numbered", check) { doc ->
        require(pages.isNotEmpty())
        val font = PDType1Font.HELVETICA
        // Digits are as tall as capitals.
        val digitHeight = (font.fontDescriptor?.capHeight?.takeIf { it > 0 } ?: 718f) / 1000 * size.points
        for (index in pages) {
            check()
            require(index in 0 until doc.numberOfPages)
            val page = doc.getPage(index)
            val frame = page.frame()
            val label = (index + 1).toString()
            val width = font.getStringWidth(label) / 1000 * size.points
            val (x, y) = PageNumbers.origin(position, frame.shownWidth, frame.shownHeight, width, digitHeight)
            // Added after the page's own drawing, which is wrapped in q/Q, so whatever state that
            // leaves behind can't move or recolour the number.
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                content.transform(frame.toMatrix())
                content.setNonStrokingColor(0f)
                content.beginText()
                content.setFont(font, size.points)
                content.newLineAtOffset(x, y)
                content.showText(label)
                content.endText()
            }
        }
    }

    /**
     * Draws [text] large, diagonally across the middle of every page as it is shown, in [color] and
     * with [opacity]. Throws [WatermarkFontMissing] if no font on the phone can draw [text].
     */
    fun addWatermark(
        input: PdfInput,
        text: String,
        color: WatermarkColor,
        opacity: WatermarkOpacity,
        fileName: String,
        check: () -> Unit = {},
    ): PdfFile {
        val stamp = WatermarkText.render(text.trim(), color.argb)
        // Kept open until the result is saved: the text's font is copied from it.
        return PDDocument.load(stamp).use { stampDoc ->
            edit(input, fileName, "Watermarked", check) { doc ->
                val form = LayerUtility(doc).importPageAsForm(stampDoc, 0)
                // As a transparency group, the opacity below applies to the watermark as a whole, even
                // if its own drawing sets an opacity.
                form.cosObject.setItem(COSName.GROUP, COSDictionary().apply { setItem(COSName.S, COSName.TRANSPARENCY) })
                val box = form.bBox
                val state = PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = opacity.alpha
                    strokingAlphaConstant = opacity.alpha
                }
                for (page in doc.pages) {
                    check()
                    val frame = page.frame()
                    val place = WatermarkLayout.place(frame.shownWidth, frame.shownHeight, box.width, box.height)
                    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
                        content.transform(frame.toMatrix())
                        content.setGraphicsStateParameters(state)
                        content.transform(Matrix.getTranslateInstance(place.centerX, place.centerY))
                        content.transform(Matrix.getRotateInstance(Math.toRadians(place.degrees.toDouble()), 0f, 0f))
                        content.transform(Matrix.getScaleInstance(place.scale, place.scale))
                        content.transform(Matrix.getTranslateInstance(-box.lowerLeftX - box.width / 2, -box.lowerLeftY - box.height / 2))
                        content.drawForm(form)
                    }
                }
            }
        }
    }

    /**
     * Draws [signature] on page [pageIndex] (zero-based) where [placement] puts it on the page as
     * shown, or in its lower right when [placement] is null. It goes in as an image with
     * transparency, trimmed to the ink, at print resolution.
     */
    fun sign(
        input: PdfInput,
        signature: Signature,
        pageIndex: Int,
        placement: SignaturePlacement?,
        fileName: String,
        check: () -> Unit = {},
    ): PdfFile = edit(input, fileName, "Signed", check) { doc ->
        require(pageIndex in 0 until doc.numberOfPages && !signature.isEmpty)
        val page = doc.getPage(pageIndex)
        val frame = page.frame()
        val width = frame.shownWidth
        val height = frame.shownHeight
        val aspect = signature.aspect
        val place = SignatureLayout.clamp(placement ?: SignatureLayout.default(width, height, aspect), width, height, aspect)
        val area = SignatureLayout.pdfArea(place, width, height, aspect)
        val bitmap = signature.render(SignatureLayout.pixels(area.width, area.height).first)
        val image = try {
            LosslessFactory.createFromImage(doc, bitmap)
        } finally {
            bitmap.recycle()
        }
        check()
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { content ->
            content.transform(frame.toMatrix())
            content.drawImage(image, area.x, area.y, area.width, area.height)
        }
    }

    /** Where Redact PDF keeps [input]'s view (see [redactionView]), beside the copy. */
    private fun viewFile(input: PdfInput) = File(inputDir, "${input.id}-view.pdf")

    /**
     * The PDF Redact PDF shows, searches and draws its pictures from: [input] itself, or, when it
     * has a password or annotations, a copy without the password in which each visible annotation is
     * drawn into its page, as viewers show it (Android's renderer leaves annotations out). Made once
     * and kept beside the copy until the PDF is put away.
     */
    fun redactionView(input: PdfInput, check: () -> Unit = {}): PdfInput {
        val file = viewFile(input)
        if (file.isFile) return input.copy(path = file.path, password = "")
        load(input.file, input.password).use { doc ->
            val drawn = Redactor.drawAnnotationsIntoPages(doc, check)
            if (!drawn && input.password.isEmpty()) return input
            check()
            doc.isAllSecurityToBeRemoved = true
            // Written whole before it takes the view's name, so a run cut short leaves no half view.
            val partial = File(inputDir, "${input.id}-view.tmp")
            try {
                doc.save(partial)
                if (!partial.renameTo(file)) throw IOException("Couldn't keep the view")
            } finally {
                partial.delete()
            }
        }
        return input.copy(path = file.path, password = "")
    }

    /** Every place [query] is found in [input]'s text, as boxes over each line of it (see [Redactor.find]). */
    fun findText(input: PdfInput, query: String, check: () -> Unit = {}): List<List<RedactBox>> {
        val view = redactionView(input, check)
        return load(view.file, view.password).use { doc -> Redactor.find(doc, query, check) }
    }

    /**
     * Redacts [input]: each page with [boxes] becomes a picture of itself with the boxes solid
     * black, and what else could leak the hidden content is removed (see [Redactor]).
     */
    fun redact(input: PdfInput, boxes: List<RedactBox>, fileName: String, check: () -> Unit = {}): PdfFile {
        require(boxes.isNotEmpty())
        val view = redactionView(input, check)
        return edit(input, fileName, "Redacted", check) { doc -> Redactor.redact(doc, view.file, boxes, check) }
    }

    /**
     * Opens [input], lets [block] change it, and saves it as [fileName] without a password. [check]
     * is called before saving too, since saving a large PDF takes a while.
     */
    private fun edit(input: PdfInput, fileName: String, fallbackName: String, check: () -> Unit, block: (PDDocument) -> Unit): PdfFile {
        clearOutput()
        outputDir.mkdirs()
        val target = File(outputDir, "${FileNames.sanitize(fileName, fallbackName)}.pdf")
        try {
            load(input.file, input.password).use { doc ->
                block(doc)
                check()
                doc.isAllSecurityToBeRemoved = true
                doc.save(target)
                return PdfFile(target, doc.numberOfPages, 0)
            }
        } catch (t: Throwable) {
            clearOutput()
            throw t
        }
    }

    /** The page as a viewer shows it: its crop box, turned by its rotation. */
    private fun PDPage.frame(): PageFrame = cropBox.let { box -> PageFrame(box.lowerLeftX, box.lowerLeftY, box.width, box.height, rotation) }

    private fun PageFrame.toMatrix(): Matrix = matrix.let { m -> Matrix(m[0], m[1], m[2], m[3], m[4], m[5]) }

    /** Re-encodes only opaque image XObjects; text, vectors, and untouched images stay as PDF objects. */
    fun compress(input: PdfInput, level: PdfCompression, fileName: String, check: () -> Unit = {}): PdfFile {
        clearOutput()
        outputDir.mkdirs()
        val target = File(outputDir, "${FileNames.sanitize(fileName, "Compressed")}.pdf")
        val candidate = File(outputDir, "candidate.tmp")
        try {
            load(input.file, input.password).use { doc ->
                val maxPageSide = (0 until doc.numberOfPages).maxOf { p ->
                    val box = doc.getPage(p).mediaBox
                    maxOf(box.width, box.height)
                }
                val maxDimension = minOf(4000, (maxPageSide * level.dpi / 72f).toInt().coerceAtLeast(1))
                val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
                val done = IdentityHashMap<COSBase, PDImageXObject?>()
                for (page in doc.pages) {
                    check()
                    compressResources(doc, page.resources, level, maxDimension, seen, done, check)
                }
                doc.isAllSecurityToBeRemoved = true
                doc.save(candidate)
            }
            if (input.password.isNotEmpty() || candidate.length() < input.file.length()) candidate.copyTo(target, overwrite = true)
            else input.file.copyTo(target, overwrite = true)
            return PdfFile(target, input.pageCount, input.file.length())
        } catch (t: Throwable) {
            clearOutput()
            throw t
        } finally {
            candidate.delete()
        }
    }

    private fun compressResources(
        doc: PDDocument,
        resources: PDResources?,
        level: PdfCompression,
        maxDimension: Int,
        seen: MutableSet<COSDictionary>,
        done: MutableMap<COSBase, PDImageXObject?>,
        check: () -> Unit,
    ) {
        if (resources == null || !seen.add(resources.cosObject)) return
        for (name in resources.xObjectNames) {
            check()
            val obj = try { resources.getXObject(name) } catch (_: IOException) { continue }
            when (obj) {
                is PDFormXObject -> compressResources(doc, obj.resources, level, maxDimension, seen, done, check)
                is PDImageXObject -> {
                    // Pages often share one image stream (a logo or background). Encode it once and
                    // reuse the result; decoding it again would hand back PDFBox's cached bitmap,
                    // which was recycled after the first use.
                    val stream = obj.cosObject
                    if (done.containsKey(stream)) {
                        done[stream]?.let { resources.put(name, it) }
                        continue
                    }
                    done[stream] = null
                    if (obj.isStencil || obj.softMask != null || obj.mask != null || obj.colorKeyMask != null) continue
                    if (!canDecodeFaithfully(obj)) continue
                    val originalLength = (obj.cosObject as? COSStream)?.length ?: continue
                    if (originalLength < 2048L || obj.width <= 0 || obj.height <= 0) continue
                    try {
                        val longest = maxOf(obj.width, obj.height)
                        val sample = maxOf(1, (longest + maxDimension - 1) / maxDimension)
                        // Some PDFBox-Android decoders fail while cropping a subsampled
                        // bitmap whose dimensions are not divisible by the sample factor.
                        // Decode at full size for ordinary images in that case.
                        // BitmapFactory returns null for JPEG streams it cannot decode, and PDFBox
                        // passes that through, so the result must be treated as nullable.
                        val decoded: Bitmap? = try {
                            obj.getImage(null, sample)
                        } catch (_: IllegalArgumentException) {
                            if (obj.width.toLong() * obj.height > 20_000_000L) continue
                            obj.image
                        }
                        var bitmap: Bitmap = decoded ?: continue
                        try {
                            val scale = minOf(1f, maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height))
                            if (scale < 1f) {
                                val smaller = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
                                bitmap.recycle()
                                bitmap = smaller
                            }
                            // Masked images were skipped above, so the image is opaque. PDFBox's raw
                            // decoder still marks its bitmaps as having alpha (gray ones use the gray
                            // value as alpha), which would make JPEGFactory add a bogus soft mask.
                            bitmap.setHasAlpha(false)
                            val replacement = JPEGFactory.createFromImage(doc, bitmap, level.quality)
                            if ((replacement.cosObject as COSStream).length < originalLength) {
                                done[stream] = replacement
                                resources.put(name, replacement)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    } catch (_: IOException) {
                        // Leave an unsupported image unchanged; other images can still be reduced.
                    } catch (_: IllegalArgumentException) {
                        // Leave an unsupported image unchanged; other images can still be reduced.
                    } catch (_: OutOfMemoryError) {
                        // Leave an image too large to decode unchanged; its buffers are already unreachable.
                    }
                }
            }
        }
    }

    /**
     * PDFBox-Android decodes JPEG through BitmapFactory, but its raw decoder only understands
     * 8-bit RGB and gray. It maps every other color space (CMYK, indexed, separation, Lab...) to
     * RGB and returns scrambled pixels without an error, so those images are left unchanged.
     */
    private fun canDecodeFaithfully(obj: PDImageXObject): Boolean {
        if (obj.decode != null) return false
        if (obj.suffix == "jpg") return true
        if (obj.bitsPerComponent != 8) return false
        return when (val colorSpace = obj.cosObject.getDictionaryObject(COSName.COLORSPACE)) {
            COSName.DEVICERGB, COSName.DEVICEGRAY -> true
            is COSArray -> colorSpace.size() == 2 && colorSpace.getObject(0) == COSName.ICCBASED &&
                (colorSpace.getObject(1) as? COSStream)?.getInt(COSName.N) == 3
            else -> false
        }
    }

    /**
     * Opens [file] with PDFBox. Certificate encryption (Adobe.PubSec) needs BouncyCastle, which isn't
     * bundled: PDFBox then throws an IOException, or would hit a missing class, so both become
     * [PdfCertificateEncrypted].
     */
    private fun load(file: File, password: String): PDDocument = try {
        PDDocument.load(file, password, memory())
    } catch (e: InvalidPasswordException) {
        throw e
    } catch (e: Exception) {
        val certificate = try { usesCertificateEncryption(file) } catch (_: IOException) { false }
        if (certificate) throw PdfCertificateEncrypted(e)
        throw e
    } catch (e: LinkageError) {
        throw PdfCertificateEncrypted(e)
    }

    /**
     * Whether [file] names the certificate security handler. The encryption dictionary is never
     * inside a compressed object stream, so the name is plain text. Only read after a failed load.
     */
    private fun usesCertificateEncryption(file: File): Boolean {
        val needle = "/Adobe.PubSec".toByteArray()
        val buffer = ByteArray(64 * 1024)
        var carried = 0
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer, carried, buffer.size - carried)
                if (read < 0) return false
                val end = carried + read
                for (start in 0..end - needle.size) {
                    if (needle.indices.all { buffer[start + it] == needle[it] }) return true
                }
                // Keep the tail, in case the name straddles two reads.
                carried = minOf(needle.size - 1, end)
                System.arraycopy(buffer, end - carried, buffer, 0, carried)
            }
        }
    }

    /** Reads the first five bytes without InputStream.readNBytes, which needs API 33. */
    private fun hasPdfHeader(file: File): Boolean {
        val expected = "%PDF-".toByteArray()
        val header = ByteArray(expected.size)
        val read = file.inputStream().use { input ->
            var total = 0
            while (total < header.size) {
                val count = input.read(header, total, header.size - total)
                if (count < 0) break
                total += count
            }
            total
        }
        return read == header.size && header.contentEquals(expected)
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0).substringAfterLast('/').takeIf { it.isNotBlank() } else null
        }
    } catch (_: Exception) { null }
}
