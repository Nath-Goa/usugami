package com.nathan.pdfer.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcelable
import android.provider.OpenableColumns
import com.nathan.pdfer.core.FileNames
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
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

class PdfToolsEngine(private val context: Context) {
    private val inputDir = File(context.filesDir, "pdf-inputs")
    private val outputDir = File(context.filesDir, "output/tools")
    private val scratchDir = File(context.cacheDir, "pdfbox")

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
        scratchDir.mkdirs()
    }

    private fun memory() = MemoryUsageSetting.setupMixed(24L * 1024 * 1024).setTempDir(scratchDir)

    fun clearOutput() { outputDir.deleteRecursively() }

    fun delete(input: PdfInput) { input.file.delete() }

    fun deleteAllExcept(keep: Collection<PdfInput>) {
        val names = keep.mapTo(HashSet()) { it.file.name }
        inputDir.listFiles()?.forEach { if (it.name !in names) it.delete() }
    }

    /** A local copy keeps access after the system document picker's grant expires. */
    fun import(uri: Uri, id: Long, password: String = ""): PdfInput {
        inputDir.mkdirs()
        val file = File(inputDir, "$id.pdf")
        val inputInfo = PdfInput(id, file.path, displayName(uri) ?: "Document.pdf", 0, password)
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Could not open PDF")
            input.use { source -> file.outputStream().use { source.copyTo(it, 64 * 1024) } }
            if (!hasPdfHeader(file)) throw IOException("This file is not a PDF")
            return inspect(inputInfo, password)
        } catch (e: InvalidPasswordException) {
            throw PdfPasswordRequired(inputInfo)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    fun inspect(input: PdfInput, password: String): PdfInput =
        PDDocument.load(input.file, password, memory()).use { doc ->
            if (doc.numberOfPages == 0) throw IOException("This PDF has no pages")
            input.copy(pageCount = doc.numberOfPages, password = password)
        }

    fun split(input: PdfInput, groups: List<List<Int>>, baseName: String, check: () -> Unit = {}): List<PdfFile> {
        require(groups.isNotEmpty() && groups.all { it.isNotEmpty() })
        clearOutput()
        outputDir.mkdirs()
        val base = FileNames.sanitize(baseName, "Split")
        val taken = HashSet<String>()
        try {
            PDDocument.load(input.file, input.password, memory()).use { source ->
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
                    PDDocument.load(input.file, input.password, memory()).use { source ->
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

    /** Re-encodes only opaque image XObjects; text, vectors, and untouched images stay as PDF objects. */
    fun compress(input: PdfInput, level: PdfCompression, fileName: String, check: () -> Unit = {}): PdfFile {
        clearOutput()
        outputDir.mkdirs()
        val target = File(outputDir, "${FileNames.sanitize(fileName, "Compressed")}.pdf")
        val candidate = File(outputDir, "candidate.tmp")
        try {
            PDDocument.load(input.file, input.password, memory()).use { doc ->
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
