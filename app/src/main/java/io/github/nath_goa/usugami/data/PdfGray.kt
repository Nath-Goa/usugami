package io.github.nath_goa.usugami.data

import android.graphics.Bitmap
import com.tom_roush.pdfbox.contentstream.PDContentStream
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageTree
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.function.PDFunction
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDInlineImage
import com.tom_roush.pdfbox.util.Matrix
import io.github.nath_goa.usugami.core.ContentGray
import io.github.nath_goa.usugami.core.GrayImage
import io.github.nath_goa.usugami.core.GrayJpeg
import io.github.nath_goa.usugami.core.GrayMath
import io.github.nath_goa.usugami.core.GrayPixels
import io.github.nath_goa.usugami.core.GraySpace
import io.github.nath_goa.usugami.core.PdfSyntax
import io.github.nath_goa.usugami.core.isOutOfSpace
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.IdentityHashMap
import java.util.zip.Deflater
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * What Black and white did to a PDF: [changed], some colour was turned gray; [colorPages], the
 * pages (zero-based, in order) that draw something it couldn't turn gray.
 */
data class GrayOutcome(val changed: Boolean, val colorPages: List<Int>)

/**
 * Compress PDF's Black and white: turns every colour [doc] draws into a shade of gray while text
 * stays text and drawings stay drawings. No page is ever turned into a picture.
 *
 * [convert] goes through each page's content one operator at a time ([ContentGray] decides what each
 * colour operator becomes, with the formulas of [GrayMath]), and through everything that content
 * draws with: forms, tiling patterns, Type 3 glyphs, graphics states (transfer functions, a Type 3
 * font) and small pictures written inside the content. Then the colours outside content streams:
 * annotations' appearances and colour arrays, the text colour of form fields (/DA), transparency
 * groups (made DeviceGray) and page thumbnails (removed: they would show the old colours). Forms,
 * patterns and glyphs are changed in place, so every reference to them sees the change; a page
 * gets one new content stream, so a stream several pages share is never changed under another page.
 * At the end, colour space resources nothing uses any more leave the file (ICC profiles can be
 * megabytes), but never from a dictionary that content left as it was, or content not read on
 * purpose (a soft mask's group, an uncoloured pattern, a d1 glyph), may still use.
 *
 * What can't be read is left exactly as it was and its pages are reported in [GrayOutcome.colorPages]:
 * a content stream PDFBox can't parse, a colour space that can't be read, an inline JPEG.
 * Soft masks are left alone: they only give opacity, and their luminosity is the formula used here.
 * Colours are turned gray one object at a time, so blend modes and overprint can come out a little
 * lighter or darker than a gray photo of the colour page would. ICC profiles are not read (there is
 * no colour engine on the phone): an ICCBased space counts as gray, RGB or CMYK by its components.
 *
 * Pictures ([GrayPictures]) and shadings ([GrayShadings]) a content stream draws are turned gray in
 * place as they are met, once each. Compress's own pass over pictures (pass 1) comes first: it stores
 * the pictures it re-encodes as gray JPEGs ([jpeg]) and names those that were in colour
 * ([turnedGray]), a change once something draws them; this pass turns the rest gray, plain pictures
 * that only it can read at the level's size ([maxDimension], [level]).
 * [scratchDir] is where the pictures only Android's renderer can read (JPEG 2000, four-component
 * JPEGs) are drawn from, alone in a temporary PDF.
 */
internal class PdfGray(
    private val doc: PDDocument,
    private val maxDimension: Int,
    private val level: PdfCompression,
    private val scratchDir: File,
    private val check: () -> Unit,
) {
    private var changed = false

    private val pictures = GrayPictures(doc, maxDimension, level, scratchDir, check)
    private val shadings = GrayShadings(doc, check)

    /** Every object converted: true when it, and all it draws, turned gray. Put in as true before work on it starts, so a loop of references ends. */
    private val converted = IdentityHashMap<COSBase, Boolean>()

    /** Colour space arrays already read. */
    private val spaces = IdentityHashMap<COSBase, GraySpace>()

    /** What is known of each /ColorSpace resource dictionary, for pruning at the end. */
    private val uses = IdentityHashMap<COSDictionary, SpaceUse>()

    /** /ColorSpace dictionaries whose odd /DefaultGray was taken out, with what it was. */
    private val removedGray = IdentityHashMap<COSDictionary, GraySpace>()

    /** The resources of every content stream read, in order, for the sweep. */
    private val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
    private val visitOrder = ArrayList<Res>()

    /** The groups of soft masks met: the sweep leaves them as they are. */
    private val maskGroups = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())

    /** Forms, patterns and Type 3 fonts without resources of their own, with those they were read under. */
    private val inheritedFrom = IdentityHashMap<COSBase, COSDictionary?>()

    /** /DA strings done, so a field that is also its widget is read once. */
    private val daDone = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())

    /** Resources dictionaries [protect] has marked already: a graphics state met again marks its caller's at no cost. */
    private val protectedRoots = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())

    private var operators = 0

    /** The gray JPEGs pass 1 put in place of pictures in colour ([turnedGray]). */
    private val fromColour = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())

    /**
     * Pass 1's picture, decoded and scaled by Compress, as a one-component DeviceGray JPEG at
     * [quality] (0 to 1, as Compress's levels give it). The pixels are read a row at a time after the
     * caller's `setHasAlpha(false)` (PDFBox's decoder stores a gray picture's value in the alpha too),
     * and [bitmap] is recycled as soon as they are, before encoding.
     */
    fun jpeg(bitmap: Bitmap, quality: Float): PDImageXObject {
        val width = bitmap.width
        val height = bitmap.height
        val image = GrayImage(width, height)
        try {
            val row = IntArray(width)
            for (y in 0 until height) {
                if (y and 63 == 0) check()
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (x in 0 until width) {
                    val p = row[x]
                    image.pixels[y * width + x] = GrayMath.rgb8((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toByte()
                }
            }
        } finally {
            bitmap.recycle()
        }
        val bytes = GrayJpeg.encode(image, (quality * 100).roundToInt().coerceIn(1, 100), check)
        return PDImageXObject(doc, ByteArrayInputStream(bytes), COSName.DCT_DECODE, width, height, 8, PDDeviceGray.INSTANCE)
    }

    /**
     * Pass 1 put [picture], a gray JPEG, in place of one in colour. That counts as a change once
     * something draws it ([picture]): a colour picture nothing draws shows no colour, as the sweep has it.
     */
    fun turnedGray(picture: COSStream) {
        fromColour += picture
    }

    /**
     * Turns [doc] gray, page by page, then the form fields' text colours, what the pages' resources
     * list but nothing drew (the sweep, whose failures count for no page), and prunes the colour
     * space resources no longer used.
     */
    fun convert(): GrayOutcome {
        val colorPages = ArrayList<Int>()
        var index = 0
        for (page in doc.pages) {
            check()
            val dict = page.cosObject
            var ok = pageContent(dict)
            ok = annotations(dict) && ok
            group(dict)
            dict.removeItem(COSName.THUMB)
            if (!ok) colorPages += index
            index++
        }
        formTexts()
        // What no page draws is turned gray too, but nobody sees it: it isn't a change, so a PDF whose
        // only colour is never drawn counts as having none.
        val drawn = changed
        sweep()
        changed = drawn
        prune()
        return GrayOutcome(changed, colorPages)
    }

    /**
     * The colour space [value] names, as Black and white reads it: a device name (or its short form
     * in an inline image), a name in [resources]' /ColorSpace dictionary, or an array. Arrays are read
     * once ([spaces]). What can't be read is [GraySpace.Unknown]; a palette or a spot colour whose
     * colours can't be worked out is too.
     */
    internal fun space(value: COSBase?, resources: COSDictionary?): GraySpace = space(value, resources, 0)

    // ---- Content streams -------------------------------------------------------------------------

    /**
     * The resources a content stream reads names from: [dict], null when it has none, and [owner],
     * the dictionary they are (or would be) the /Resources of, so the gray pattern space can be added
     * where there was none.
     */
    private class Res(var dict: COSDictionary?, val owner: COSDictionary?)

    /** One content stream read: [data], its gray content when anything changed (else null); [ok], all of it and all it draws turned gray. */
    private class Read(val data: COSStream?, val ok: Boolean)

    /** What reading a stream through [drive] found. */
    private class Driven(val changed: Boolean, val failed: Boolean)

    /** What became of an inline image. */
    private enum class Inline { KEPT, CHANGED, FAILED }

    /** The colour state of a /ColorSpace resource dictionary, for [prune]. */
    private class SpaceUse {
        /** Read by a content stream: only those are pruned. */
        var met = false

        /** Content that uses it was left in colour, so every name stays. */
        var failed = false

        /** A stream not read on purpose may use it (a soft mask's group, an uncoloured pattern, a d1 glyph). */
        var neverPrune = false

        /** The names still used by what was left as it was. */
        val kept = HashSet<String>()
    }

    /** A page's content, read as one stream (a /Contents array joined); a new stream becomes its only /Contents. */
    private fun pageContent(page: COSDictionary): Boolean {
        val contents = page.getDictionaryObject(COSName.CONTENTS) ?: return true
        val resources = PDPageTree.getInheritableAttribute(page, COSName.RESOURCES) as? COSDictionary
        // The page's own streams count as being read, so a Do naming one of them (a damaged file)
        // doesn't rewrite it under the reader.
        when (contents) {
            is COSStream -> converted.putIfAbsent(contents, true)
            is COSArray -> for (i in 0 until contents.size()) (contents.getObject(i) as? COSStream)?.let { converted.putIfAbsent(it, true) }
            else -> {}
        }
        val read = content({ PDPage(page).contents }, Res(resources, page), GraySpace.Gray, GraySpace.Gray, 0)
        read.data?.let {
            page.setItem(COSName.CONTENTS, it)
            changed = true
        }
        return read.ok
    }

    /**
     * Reads a content stream from what [open] opens and writes it anew, gray, into a new stream of
     * the document. The input is opened here and always closed here (PDFBox's parser never closes
     * what it reads, and a stream opened is decoded into the scratch file until it is closed), before
     * the caller puts the new data in place ([replaceData]). A stream that can't be read is left as
     * it was, and that is a failure; so is a stream that had to keep some colour. [glyph]: a Type 3
     * glyph procedure, left as it is when it starts with d1 (viewers ignore its colours).
     */
    private fun content(open: () -> InputStream, res: Res, fill: GraySpace, stroke: GraySpace, depth: Int, glyph: Boolean = false): Read {
        check()
        val use = use(res)
        val gray = ContentGray({ name -> lookup(name, res) }, { grayPattern(res) }, fill, stroke)
        val kept = HashSet<String>()
        val data = doc.document.createCOSStream()
        var keepData = false
        try {
            val driven = try {
                open().use { input ->
                    data.createOutputStream(COSName.FLATE_DECODE).use { out -> drive(input, out, res, gray, depth, glyph, kept) }
                }
            } catch (e: IOException) {
                if (e.isOutOfSpace()) throw e
                null.also { use?.failed = true }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // PDFBox's parser and its functions throw these on damaged content.
                if (e.isOutOfSpace()) throw e
                null.also { use?.failed = true }
            } ?: return Read(null, false)
            if (driven === LEFT_ON_PURPOSE) {
                res.dict?.let { protect(it) }
                return Read(null, true)
            }
            use?.kept?.addAll(gray.kept)
            use?.kept?.addAll(kept)
            if (driven.failed) use?.failed = true
            if (!driven.changed) return Read(null, !driven.failed)
            keepData = true
            return Read(data, !driven.failed)
        } finally {
            if (!keepData) data.close()
        }
    }

    /**
     * The driver: one token at a time from [input] (never the whole stream in memory), each operator
     * given to [gray], what it draws with converted first. Returns [LEFT_ON_PURPOSE] for a [glyph]
     * that starts with d1. Throws when the stream can't be read (also for a standalone ID: an inline
     * image PDFBox couldn't read whole, which the writer would write without its data).
     */
    private fun drive(input: InputStream, out: OutputStream, res: Res, gray: ContentGray, depth: Int, glyph: Boolean, kept: MutableSet<String>): Driven {
        val parser = PDFStreamParser(Source(input))
        val writer = ContentStreamWriter(out)
        val operands = ArrayList<COSBase>()
        var first = true
        var failed = false
        var inlineChanged = false
        while (true) {
            val token = parser.parseNextToken() ?: break
            if (token !is Operator) {
                operands += token as COSBase
                continue
            }
            if ((++operators and 0xFFF) == 0) check()
            val name = token.name
            if (first) {
                first = false
                if (glyph && name == "d1") return LEFT_ON_PURPOSE
            }
            when (name) {
                "ID" -> throw IOException("An inline image that couldn't be read whole")
                "Do" -> (operands.lastOrNull() as? COSName)?.let { if (!xObject(it, res, gray.fill, gray.stroke, depth)) failed = true }
                "gs" -> (operands.lastOrNull() as? COSName)?.let { if (!graphicsState(it, res, depth)) failed = true }
                "Tf" -> (operands.firstOrNull() as? COSName)?.let { if (!font(it, res, depth)) failed = true }
                "scn", "SCN" -> (operands.lastOrNull() as? COSName)?.let { if (!pattern(it, res, depth)) failed = true }
                "sh" -> (operands.lastOrNull() as? COSName)?.let { name ->
                    val shading = res.dict?.getCOSDictionary(COSName.SHADING)?.getDictionaryObject(name) as? COSDictionary
                    if (shading != null && !grayShading(shading, res)) failed = true
                }
                "BI" -> when (inlineImage(token, res, kept)) {
                    Inline.CHANGED -> inlineChanged = true
                    Inline.FAILED -> failed = true
                    Inline.KEPT -> {}
                }
            }
            val text = gray.rewrite(name, numbers(operands), (operands.lastOrNull() as? COSName)?.name)
            if (text != null) {
                out.write((text + "\n").toByteArray(Charsets.US_ASCII))
            } else {
                writer.writeTokens(operands)
                writer.writeToken(token)
            }
            operands.clear()
        }
        // Operands with no operator after them stay.
        writer.writeTokens(operands)
        return Driven(gray.changed || inlineChanged, failed || gray.failed)
    }

    /** The operands that are numbers, in order; null when one is neither a number nor a trailing name. */
    private fun numbers(operands: List<COSBase>): FloatArray? {
        val count = if (operands.lastOrNull() is COSName) operands.size - 1 else operands.size
        val values = FloatArray(count)
        for (i in 0 until count) values[i] = (operands[i] as? COSNumber)?.floatValue() ?: return null
        return values
    }

    /** `Do`: a form is converted, starting with the colour spaces of the content that draws it; a picture is turned gray. */
    private fun xObject(name: COSName, res: Res, fill: GraySpace, stroke: GraySpace, depth: Int): Boolean {
        val stream = res.dict?.getCOSDictionary(COSName.XOBJECT)?.getDictionaryObject(name) as? COSStream ?: return true
        return when (stream.getCOSName(COSName.SUBTYPE)) {
            COSName.FORM -> form(stream, res, fill, stroke, depth)
            COSName.IMAGE -> picture(stream, res)
            else -> true
        }
    }

    /**
     * A picture, turned gray in place once ([GrayPictures]); drawn again, it gives the same answer, so
     * every page that draws a picture left in colour is named. Its colour space is read as the content
     * drawing it reads one (a resource name, a default space). What it still names in that content's
     * /ColorSpace dictionary is kept there; left in colour, it keeps the whole dictionary.
     */
    private fun picture(stream: COSStream, res: Res): Boolean {
        // Drawn, a picture pass 1 turned gray is a change (in the sweep, [convert] doesn't count it).
        if (stream in fromColour) changed = true
        val ok = converted[stream] ?: run {
            converted[stream] = true
            val done = pictures.convert(stream, drawnIn(stream.getDictionaryObject(COSName.COLORSPACE), res))
            if (done == Grayed.CHANGED) changed = true
            (done != Grayed.LEFT).also { converted[stream] = it }
        }
        stillNamed(stream, res, ok)
        return ok
    }

    /** A shading, turned gray in place once ([GrayShadings]), by `sh` or a shading pattern; as [picture] for the rest. */
    private fun grayShading(shading: COSDictionary, res: Res): Boolean {
        val ok = converted[shading] ?: run {
            converted[shading] = true
            val done = shadings.convert(shading, drawnIn(shading.getDictionaryObject(COSName.COLORSPACE), res))
            if (done == Grayed.CHANGED) changed = true
            (done != Grayed.LEFT).also { converted[shading] = it }
        }
        stillNamed(shading, res, ok)
        return ok
    }

    /**
     * The colour space [value] of a picture or shading drawn by content read with [res]: a device
     * space reads through the /DefaultGray, /DefaultRGB or /DefaultCMYK there, as the content's own
     * colours do, when that has as many components and can be read.
     */
    private fun drawnIn(value: COSBase?, res: Res): GraySpace {
        val read = space(value, res.dict)
        val v = if (value is COSObject) value.`object` else value
        val device = when (v) {
            is COSName -> v.name
            is COSArray -> if (v.size() == 1) (v.getObject(0) as? COSName)?.name else null
            else -> null
        }
        val name = when (device) {
            "DeviceGray", "G" -> "DeviceGray"
            "DeviceRGB", "RGB" -> "DeviceRGB"
            "DeviceCMYK", "CMYK" -> "DeviceCMYK"
            else -> return read
        }
        return lookup(name, res)?.takeIf { it.components == read.components && readable(it) } ?: read
    }

    /**
     * After a picture or shading [item] is done: the /ColorSpace resource names it still uses are kept
     * in [res]' dictionary; one left in colour ([ok] false) keeps that dictionary whole.
     */
    private fun stillNamed(item: COSDictionary, res: Res, ok: Boolean) {
        val use = use(res) ?: return
        if (!ok) use.failed = true
        when (val value = item.getDictionaryObject(COSName.COLORSPACE)) {
            is COSName -> use.kept += value.name
            is COSArray -> use.kept += namesIn(value)
            else -> {}
        }
    }

    /**
     * A form XObject or an annotation's appearance, changed in place, once: in the context of the
     * first place that draws it (drawn again under another inherited colour space, it keeps that
     * gray). Without /Resources of its own it uses [caller]'s. Its transparency group is made gray.
     */
    private fun form(stream: COSStream, caller: Res, fill: GraySpace, stroke: GraySpace, depth: Int): Boolean {
        val own = stream.getDictionaryObject(COSName.RESOURCES) as? COSDictionary
        val res = if (own != null) Res(own, stream) else caller
        converted[stream]?.let { return it.also { if (own == null) drawnAgain(stream, caller) } }
        if (own == null) inheritedFrom[stream] = caller.dict
        if (depth >= MAX_DEPTH) return false.also { use(res)?.failed = true }
        converted[stream] = true
        val read = content({ stream.createInputStream() }, res, fill, stroke, depth + 1)
        read.data?.let {
            replaceData(stream, it)
            changed = true
        }
        group(stream)
        converted[stream] = read.ok
        return read.ok
    }

    /**
     * [item], without resources of its own, read already under other resources, is drawn by content
     * under [caller]'s: its names were noted for the first, so [caller]'s colour spaces are never
     * pruned (it may use any of them). It keeps the gray it was given the first time; the gray pattern
     * spaces that reading may have written into the first resources' /ColorSpace are put in [caller]'s
     * too, where the name is free, so a pattern it fills with is found here as well.
     */
    private fun drawnAgain(item: COSBase, caller: Res) {
        val dict = caller.dict ?: return
        val first = inheritedFrom[item]
        if (first === dict) return
        var spaces = dict.getDictionaryObject(COSName.COLORSPACE) as? COSDictionary
        spaces?.let { useOf(it).neverPrune = true }
        val written = first?.getDictionaryObject(COSName.COLORSPACE) as? COSDictionary ?: return
        for (key in written.keySet().toList()) {
            if (!key.name.startsWith(GRAY_PATTERN) || !isGrayPattern(written.getDictionaryObject(key))) continue
            val into = spaces ?: COSDictionary().also {
                dict.setItem(COSName.COLORSPACE, it)
                useOf(it).neverPrune = true
                spaces = it
            }
            if (!into.containsKey(key)) into.setItem(key, grayPatternSpace())
        }
    }

    /** `scn`/`SCN` with a pattern's name: a tiling pattern's content, or a shading pattern's shading and graphics state. */
    private fun pattern(name: COSName, res: Res, depth: Int): Boolean {
        val pattern = res.dict?.getCOSDictionary(COSName.PATTERN)?.getDictionaryObject(name) as? COSDictionary ?: return true
        return when (pattern.getInt(COSName.PATTERN_TYPE)) {
            1 -> (pattern as? COSStream)?.let { tiling(it, res, depth) } ?: true
            2 -> shadingPattern(pattern, res, depth)
            else -> true
        }
    }

    /** A shading pattern (/PatternType 2): its /Shading turned gray, its /ExtGState visited as `gs` visits one. */
    private fun shadingPattern(pattern: COSDictionary, res: Res, depth: Int): Boolean {
        var ok = (pattern.getDictionaryObject(COSName.SHADING) as? COSDictionary)?.let { grayShading(it, res) } ?: true
        (pattern.getDictionaryObject(COSName.EXT_G_STATE) as? COSDictionary)?.let { ok = visitState(it, res, depth) && ok }
        return ok
    }

    /**
     * A tiling pattern, changed in place, starting gray. An uncoloured one (/PaintType 2) is left as
     * it is: its colour comes from the `scn` that paints with it, which is rewritten; the colour
     * spaces its resources hold are then never pruned.
     */
    private fun tiling(stream: COSStream, caller: Res, depth: Int): Boolean {
        val own = stream.getDictionaryObject(COSName.RESOURCES) as? COSDictionary
        val res = if (own != null) Res(own, stream) else caller
        converted[stream]?.let { return it.also { if (own == null) drawnAgain(stream, caller) } }
        if (own == null) inheritedFrom[stream] = caller.dict
        if (stream.getInt(COSName.PAINT_TYPE) == 2) {
            res.dict?.let { protect(it) }
            converted[stream] = true
            return true
        }
        if (depth >= MAX_DEPTH) return false.also { use(res)?.failed = true }
        converted[stream] = true
        val read = content({ stream.createInputStream() }, res, GraySpace.Gray, GraySpace.Gray, depth + 1)
        read.data?.let {
            replaceData(stream, it)
            changed = true
        }
        converted[stream] = read.ok
        return read.ok
    }

    /** `Tf`: a Type 3 font's glyph procedures. */
    private fun font(name: COSName, res: Res, depth: Int): Boolean {
        val font = res.dict?.getCOSDictionary(COSName.FONT)?.getDictionaryObject(name) as? COSDictionary ?: return true
        return type3(font, res, depth)
    }

    /**
     * A Type 3 font: each glyph procedure in place, starting gray. One that starts with d1 is left as
     * it is (viewers ignore its colours). Without /Resources of its own it uses [caller]'s.
     */
    private fun type3(font: COSDictionary, caller: Res, depth: Int): Boolean {
        if (font.getCOSName(COSName.SUBTYPE) != COSName.TYPE3) return true
        val own = font.getDictionaryObject(COSName.RESOURCES) as? COSDictionary
        val res = if (own != null) Res(own, font) else caller
        converted[font]?.let { return it.also { if (own == null) drawnAgain(font, caller) } }
        if (own == null) inheritedFrom[font] = caller.dict
        if (depth >= MAX_DEPTH) return false.also { use(res)?.failed = true }
        converted[font] = true
        var ok = true
        val procs = font.getDictionaryObject(COSName.CHAR_PROCS) as? COSDictionary
        if (procs != null) {
            for (key in procs.keySet().toList()) {
                val glyph = procs.getDictionaryObject(key) as? COSStream ?: continue
                ok = glyph(glyph, res, depth + 1) && ok
            }
        }
        converted[font] = ok
        return ok
    }

    private fun glyph(stream: COSStream, res: Res, depth: Int): Boolean {
        converted[stream]?.let { return it }
        converted[stream] = true
        val read = content({ stream.createInputStream() }, res, GraySpace.Gray, GraySpace.Gray, depth, glyph = true)
        read.data?.let {
            replaceData(stream, it)
            changed = true
        }
        converted[stream] = read.ok
        return read.ok
    }

    /**
     * `gs`: the graphics state, once. A transfer function of four (one per colour component) becomes
     * its fourth, the one for gray, so it can't tint gray. A Type 3 font in /Font is converted. A soft
     * mask is left alone with its group: it only gives opacity, and its luminosity is the formula used
     * here, so changing its colours could only disturb it. Since its group isn't read, every colour
     * space dictionary it may use is never pruned.
     */
    private fun graphicsState(name: COSName, res: Res, depth: Int): Boolean {
        val state = res.dict?.getCOSDictionary(COSName.EXT_G_STATE)?.getDictionaryObject(name) as? COSDictionary ?: return true
        return visitState(state, res, depth)
    }

    /** [graphicsState] for the dictionary itself: a shading pattern's /ExtGState. */
    private fun visitState(state: COSDictionary, res: Res, depth: Int): Boolean {
        val seen = converted[state]
        if (seen == null) {
            converted[state] = true
            for (key in listOf(COSName.TR, COSName.TR2)) {
                val functions = state.getDictionaryObject(key) as? COSArray ?: continue
                if (functions.size() != 4) continue
                val gray = functions.get(3)
                val same = (0 until 3).all { functions.getObject(it) === functions.getObject(3) }
                state.setItem(key, gray)
                if (!same) changed = true
            }
        }
        // Also when it was met before, maybe under other resources: a Type 3 font or a soft mask's
        // group without resources of its own uses those of the content setting the state, so these
        // are looked after too (a font converted already is only noted as drawn again).
        var ok = true
        val font = (state.getDictionaryObject(COSName.FONT) as? COSArray)?.getObject(0) as? COSDictionary
        if (font != null) ok = type3(font, res, depth + 1)
        val mask = state.getDictionaryObject(COSName.SMASK) as? COSDictionary
        if (mask != null) {
            val group = mask.getDictionaryObject(COSName.G) as? COSStream
            group?.let { maskGroups += it }
            val groupResources = group?.getDictionaryObject(COSName.RESOURCES) as? COSDictionary
            (groupResources ?: res.dict)?.let { protect(it) }
        }
        if (seen != null) return seen
        converted[state] = ok
        return ok
    }

    // ---- Inline images ---------------------------------------------------------------------------

    /**
     * An inline image (`BI … ID … EI`), changed on its token: a stencil or a gray one is kept; a
     * palette over colours gets a palette of grays (its data untouched); one with lossless filters and
     * 1, 2, 4 or 8 bits becomes 8-bit DeviceGray of the same size. Anything else (an inline JPEG, a
     * colour space that can't be read) is kept, and is a failure. The name of a colour space resource
     * it keeps goes into [kept].
     */
    private fun inlineImage(operator: Operator, res: Res, kept: MutableSet<String>): Inline {
        val params = operator.imageParameters ?: return Inline.KEPT
        val data = operator.imageData ?: return Inline.KEPT
        if (params.getBoolean(COSName.IM, COSName.IMAGE_MASK, false)) return Inline.KEPT
        val value = params.getDictionaryObject(COSName.CS, COSName.COLORSPACE)
        val named = (value as? COSName)?.name?.takeIf { it !in INLINE_DEVICE_NAMES }
        fun keep(failed: Boolean): Inline {
            if (named != null) kept += named
            if (value is COSArray) kept += namesIn(value)
            return if (failed) Inline.FAILED else Inline.KEPT
        }
        val space = if (value == null) GraySpace.Unknown(0) else space(value, res.dict, 0)
        if (space.isGray || space is GraySpace.None) return keep(false)
        // PDFBox finds the end of the data by the first "EI" between spaces that the next operator it
        // knows follows: data holding another such "EI" may have swallowed what came after it (another
        // inline image, say), which a gray picture written from it would lose. Left as it was, it is
        // written back byte for byte.
        if (endsEarly(data)) return keep(true)
        if (space is GraySpace.Indexed) {
            params.removeItem(COSName.COLORSPACE)
            params.setItem(COSName.CS, grayIndexed(space, inline = true))
            return Inline.CHANGED
        }
        val filters = filterNames(params.getDictionaryObject(COSName.F, COSName.FILTER)) ?: return keep(true)
        if (filters.any { it !in LOSSLESS_FILTERS }) return keep(true)
        val bits = params.getInt(COSName.BPC, COSName.BITS_PER_COMPONENT, -1)
        val width = params.getInt(COSName.W, COSName.WIDTH, -1)
        val height = params.getInt(COSName.H, COSName.HEIGHT, -1)
        if (bits !in INLINE_BITS || width <= 0 || height <= 0 || width.toLong() * height > MAX_INLINE_PIXELS) return keep(true)
        val decode = (params.getDictionaryObject(COSName.D, COSName.DECODE) as? COSArray)?.let { floats(it) }
        val gray = try {
            val pixels = GrayPixels(space, bits, decode)
            val samples = PDInlineImage(params, data, PDResources(res.dict ?: COSDictionary())).createInputStream()
            val packed = ByteArray(pixels.rowBytes(width))
            val rows = ByteArray(width * height)
            val row = ByteArray(width)
            samples.use { input ->
                for (y in 0 until height) {
                    // Rows the data lacks are read as zeros.
                    val got = readFully(input, packed)
                    if (got < packed.size) packed.fill(0, got, packed.size)
                    pixels.row(packed, width, row)
                    System.arraycopy(row, 0, rows, y * width, width)
                }
            }
            rows
        } catch (_: IllegalArgumentException) {
            return keep(true)
        } catch (_: IOException) {
            return keep(true)
        } catch (_: OutOfMemoryError) {
            return keep(true)
        }
        val replaced = COSDictionary()
        for ((key, item) in params.entrySet()) if (key.name !in INLINE_REPLACED) replaced.setItem(key, item)
        replaced.setItem(COSName.BPC, COSInteger.get(8))
        replaced.setItem(COSName.CS, COSName.getPDFName("G"))
        val stored = inlineData(gray)
        stored.filter?.let { replaced.setItem(COSName.F, it) }
        operator.imageParameters = replaced
        operator.imageData = stored.bytes
        return Inline.CHANGED
    }

    // ---- Colour spaces ---------------------------------------------------------------------------

    /**
     * What [ContentGray] asks for: a device space's default space (/DefaultGray, /DefaultRGB,
     * /DefaultCMYK in the resources' /ColorSpace dictionary), "Pattern", or a resource by name; null
     * when there is none.
     */
    private fun lookup(name: String, res: Res): GraySpace? {
        val resources = res.dict
        val dict = resources?.getDictionaryObject(COSName.COLORSPACE) as? COSDictionary
        return when (name) {
            "DeviceGray" -> defaultGray(dict, resources)
            "DeviceRGB" -> dict?.getDictionaryObject(COSName.DEFAULT_RGB)?.let { space(it, resources, 1) }
            "DeviceCMYK" -> dict?.getDictionaryObject(COSName.DEFAULT_CMYK)?.let { space(it, resources, 1) }
            "Pattern" -> GraySpace.Pattern(null)
            else -> dict?.getDictionaryObject(COSName.getPDFName(name))?.let { space(it, resources, 1) }
        }
    }

    /**
     * /DefaultGray. One that is gray (CalGray, a gray ICC profile) changes nothing. Any other that can
     * be read (a spot colour, say) is used to read `g` and `G`, which are then rewritten, and is taken
     * out of its dictionary at once (remembered for content read later), so the gray written can't be
     * recoloured.
     */
    private fun defaultGray(dict: COSDictionary?, resources: COSDictionary?): GraySpace? {
        if (dict == null) return null
        removedGray[dict]?.let { return it }
        val value = dict.getDictionaryObject(COSName.DEFAULT_GRAY) ?: return null
        val space = space(value, resources, 1)
        if (space.isGray || space.components != 1 || !readable(space)) return space
        dict.removeItem(COSName.DEFAULT_GRAY)
        removedGray[dict] = space
        return space
    }

    private fun space(value: COSBase?, resources: COSDictionary?, depth: Int): GraySpace {
        val v = if (value is COSObject) value.`object` else value
        return when (v) {
            is COSName -> when (v.name) {
                "DeviceGray", "G" -> GraySpace.Gray
                "DeviceRGB", "RGB" -> GraySpace.Rgb
                "DeviceCMYK", "CMYK" -> GraySpace.Cmyk
                "Pattern" -> GraySpace.Pattern(null)
                else -> {
                    val found = (resources?.getDictionaryObject(COSName.COLORSPACE) as? COSDictionary)?.getDictionaryObject(v)
                    if (found == null || depth >= MAX_SPACE_DEPTH) GraySpace.Unknown(0) else space(found, resources, depth + 1)
                }
            }
            is COSArray -> {
                spaces[v]?.let { return it }
                if (depth >= MAX_SPACE_DEPTH) return GraySpace.Unknown(0)
                // A space that is there but can't be read (a palette that won't decode, a function
                // PDFBox throws on) keeps its number of components, so the colours set in it stay
                // with its `cs`, which is kept: Unknown(0) is for a space that isn't there, whose
                // colours are read by their count.
                val space = try {
                    array(v, resources, depth)
                } catch (e: IOException) {
                    if (e.isOutOfSpace()) throw e
                    GraySpace.Unknown(componentsOf(v))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    if (e.isOutOfSpace()) throw e
                    GraySpace.Unknown(componentsOf(v))
                }
                spaces[v] = space
                space
            }
            else -> GraySpace.Unknown(0)
        }
    }

    private fun array(array: COSArray, resources: COSDictionary?, depth: Int): GraySpace {
        val family = (array.getObject(0) as? COSName)?.name ?: return GraySpace.Unknown(0)
        fun item(i: Int): COSBase? = if (i < array.size()) array.getObject(i) else null
        return when (family) {
            "DeviceGray", "G" -> GraySpace.Gray
            "DeviceRGB", "RGB" -> GraySpace.Rgb
            "DeviceCMYK", "CMYK" -> GraySpace.Cmyk
            "CalGray" -> GraySpace.CalGray(((item(1) as? COSDictionary)?.getDictionaryObject(COSName.GAMMA) as? COSNumber)?.floatValue() ?: 1f)
            "CalRGB" -> GraySpace.CalRgb(((item(1) as? COSDictionary)?.getDictionaryObject(COSName.GAMMA) as? COSArray)?.let { floats(it) } ?: floatArrayOf(1f, 1f, 1f))
            "Lab" -> {
                val range = ((item(1) as? COSDictionary)?.getDictionaryObject(COSName.RANGE) as? COSArray)?.let { floats(it) }
                    ?.takeIf { it.size >= 4 } ?: floatArrayOf(-100f, 100f, -100f, 100f)
                GraySpace.Lab(range[0], range[1], range[2], range[3])
            }
            "ICCBased" -> {
                // The profile isn't read (there is no colour engine on the phone): by its components.
                val stream = item(1) as? COSStream ?: return GraySpace.Unknown(0)
                when (val n = stream.getInt(COSName.N, -1)) {
                    1 -> GraySpace.Gray
                    3 -> GraySpace.Rgb
                    4 -> GraySpace.IccCmyk
                    else -> stream.getDictionaryObject(COSName.ALTERNATE)?.let { space(it, resources, depth + 1) }
                        ?: GraySpace.Unknown(n.coerceAtLeast(0))
                }
            }
            "Indexed", "I" -> {
                val base = space(item(1), resources, depth + 1)
                val hival = (item(2) as? COSNumber)?.intValue() ?: return GraySpace.Unknown(1)
                val count = (hival.coerceIn(0, 255) + 1) * base.components
                val lookup = when (val table = item(3)) {
                    is COSString -> table.bytes
                    is COSStream -> table.createInputStream().use { readAtMost(it, count) }
                    else -> return GraySpace.Unknown(1)
                }
                try {
                    GraySpace.Indexed.of(base, hival, lookup)
                } catch (_: IllegalArgumentException) {
                    GraySpace.Unknown(1)
                }
            }
            "Separation" -> {
                if ((item(1) as? COSName)?.name == "None") GraySpace.None(1)
                else tint(1, item(2), item(3), resources, depth)
            }
            "DeviceN" -> {
                val names = item(1) as? COSArray ?: return GraySpace.Unknown(0)
                val n = names.size()
                if (n == 0) return GraySpace.Unknown(0)
                if ((0 until n).all { (names.getObject(it) as? COSName)?.name == "None" }) GraySpace.None(n)
                else tint(n, item(2), item(3), resources, depth)
            }
            "Pattern" -> GraySpace.Pattern(item(1)?.let { space(it, resources, depth + 1) })
            else -> GraySpace.Unknown(0)
        }
    }

    /** A Separation or DeviceN space: its tint transform gives the alternate's colour. One whose alternate or transform can't be read is [GraySpace.Unknown]. */
    private fun tint(components: Int, alternate: COSBase?, transform: COSBase?, resources: COSDictionary?, depth: Int): GraySpace {
        val alt = space(alternate, resources, depth + 1)
        if (!readable(alt) || alt is GraySpace.Pattern) return GraySpace.Unknown(components)
        val function = try {
            PDFunction.create(transform ?: return GraySpace.Unknown(components))
        } catch (e: IOException) {
            if (e.isOutOfSpace()) throw e
            return GraySpace.Unknown(components)
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            // PDFBox throws these for a damaged function: a type 4 function that isn't a stream, a
            // program it can't parse.
            return GraySpace.Unknown(components)
        }
        return GraySpace.Tint(components, alt) { c ->
            try {
                function.eval(c)
            } catch (e: IOException) {
                throw IllegalArgumentException("The tint transform failed", e)
            }
        }
    }

    // ---- Colour outside content ------------------------------------------------------------------

    /**
     * A page's annotations: colour arrays become three equal numbers (a neutral gray every viewer
     * reads, and the only form a redaction's /IC and /OC allow), /DA is rewritten, and every
     * appearance stream (/N, /R, /D) is converted, starting gray, without inherited resources.
     */
    private fun annotations(page: COSDictionary): Boolean {
        val annots = page.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: return true
        var ok = true
        for (i in 0 until annots.size()) {
            val annot = annots.getObject(i) as? COSDictionary ?: continue
            converted[annot]?.let {
                ok = it && ok
                continue
            }
            converted[annot] = true
            var annotOk = true
            colors(annot, COSName.C, count = !hiddenLinkBorder(annot))
            colors(annot, COSName.IC)
            // /OC is optional content, but an array of numbers in a redaction annotation: a colour.
            if (annot.getDictionaryObject(COSName.OC) is COSArray) colors(annot, COSName.OC)
            (annot.getDictionaryObject(COSName.MK) as? COSDictionary)?.let {
                colors(it, COSName.BC)
                colors(it, COSName.BG)
            }
            annotOk = textColor(annot) && annotOk
            val appearances = annot.getDictionaryObject(COSName.AP) as? COSDictionary
            if (appearances != null) {
                for (key in listOf(COSName.N, COSName.R, COSName.D)) {
                    when (val entry = appearances.getDictionaryObject(key)) {
                        is COSStream -> annotOk = form(entry, Res(null, entry), GraySpace.Gray, GraySpace.Gray, 0) && annotOk
                        is COSDictionary -> for (state in entry.keySet().toList()) {
                            val stream = entry.getDictionaryObject(state) as? COSStream ?: continue
                            annotOk = form(stream, Res(null, stream), GraySpace.Gray, GraySpace.Gray, 0) && annotOk
                        }
                        else -> {}
                    }
                }
            }
            converted[annot] = annotOk
            ok = annotOk && ok
        }
        return ok
    }

    /**
     * A colour array of three numbers (RGB) or four (CMYK) becomes three equal ones; none, one, or
     * three equal already stay. [count]: false for a colour no viewer draws, turned gray all the same
     * but no change by itself.
     */
    private fun colors(dict: COSDictionary, key: COSName, count: Boolean = true) {
        val array = dict.getDictionaryObject(key) as? COSArray ?: return
        val c = floats(array) ?: return
        val gray = when (c.size) {
            3 -> if (c[0] == c[1] && c[1] == c[2]) return else GrayMath.rgb(c[0], c[1], c[2])
            4 -> GrayMath.cmyk(c[0], c[1], c[2], c[3])
            else -> return
        }
        val text = PdfSyntax.number(GrayMath.unit(gray).toDouble())
        dict.setItem(key, COSArray().apply { repeat(3) { add(COSFloat(text)) } })
        if (count) changed = true
    }

    /**
     * A link without an appearance whose border is 0 wide (as LaTeX's hidden links are): viewers draw
     * nothing with its /C. /BS's /W comes before /Border; with neither, the border is 1 wide.
     */
    private fun hiddenLinkBorder(annot: COSDictionary): Boolean {
        if (annot.getCOSName(COSName.SUBTYPE)?.name != "Link" || annot.getDictionaryObject(COSName.AP) is COSDictionary) return false
        val style = annot.getDictionaryObject(COSName.BS) as? COSDictionary
        val width = if (style != null) {
            (style.getDictionaryObject(COSName.W) as? COSNumber)?.floatValue()
        } else {
            ((annot.getDictionaryObject(COSName.BORDER) as? COSArray)?.takeIf { it.size() >= 3 }?.getObject(2) as? COSNumber)?.floatValue()
        }
        return width != null && width <= 0f
    }

    /** A /DA string, through the same driver with no resources; replaced when something changed. */
    private fun textColor(dict: COSDictionary): Boolean {
        if (!daDone.add(dict)) return true
        val da = dict.getDictionaryObject(COSName.DA) as? COSString ?: return true
        val res = Res(null, null)
        val gray = ContentGray({ name -> lookup(name, res) }, { GRAY_PATTERN }, GraySpace.Gray, GraySpace.Gray)
        val out = ByteArrayOutputStream()
        val driven = try {
            drive(ByteArrayInputStream(da.bytes), out, res, gray, MAX_DEPTH, false, HashSet())
        } catch (_: IOException) {
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (_: RuntimeException) {
            return false
        }
        if (driven.changed) {
            val bytes = out.toByteArray()
            var end = bytes.size
            while (end > 0 && bytes[end - 1].toInt().let { it == 10 || it == 13 || it == 32 }) end--
            dict.setItem(COSName.DA, COSString(bytes.copyOf(end)))
            changed = true
        }
        return !driven.failed
    }

    /** The AcroForm's /DA and every field's, walking /Fields and /Kids at most 64 deep. Failures here count for no page. */
    private fun formTexts() {
        val form = doc.documentCatalog.cosObject.getDictionaryObject(COSName.ACRO_FORM) as? COSDictionary ?: return
        textColor(form)
        val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        fun walk(fields: COSArray?, depth: Int) {
            if (fields == null || depth > MAX_FIELD_DEPTH) return
            for (i in 0 until fields.size()) {
                val field = fields.getObject(i) as? COSDictionary ?: continue
                if (!seen.add(field)) continue
                textColor(field)
                walk(field.getDictionaryObject(COSName.KIDS) as? COSArray, depth + 1)
            }
        }
        walk(form.getDictionaryObject(COSName.FIELDS) as? COSArray, 0)
    }

    /**
     * A transparency group whose colour space isn't gray gets /DeviceGray: gray composited in a CMYK
     * group comes out of some renderers as a tinted black. By itself that is no change: a group's
     * colour space colours nothing, and many PDF makers put an RGB group on every page.
     */
    private fun group(dict: COSDictionary) {
        val group = dict.getDictionaryObject(COSName.GROUP) as? COSDictionary ?: return
        val value = group.getDictionaryObject(COSName.CS) ?: return
        if (!space(value, null, 1).isGray) group.setItem(COSName.CS, COSName.DEVICEGRAY)
    }

    // ---- Sweep and resources ---------------------------------------------------------------------

    /**
     * What no content drew but a resources dictionary that was read still lists (pages often share
     * one long list): its forms, pictures, shadings and coloured tiling and shading patterns, without
     * counting failures for any page (nor, as [convert] has it, as a change).
     * A soft mask's group listed there is left as it is, as when a graphics state sets it.
     */
    private fun sweep() {
        var i = 0
        while (i < visitOrder.size) {
            val res = visitOrder[i++]
            val dict = res.dict ?: continue
            check()
            dict.getCOSDictionary(COSName.XOBJECT)?.let { xObjects ->
                for (key in xObjects.keySet().toList()) {
                    val stream = xObjects.getDictionaryObject(key) as? COSStream ?: continue
                    when (stream.getCOSName(COSName.SUBTYPE)) {
                        COSName.FORM -> if (stream !in maskGroups) form(stream, res, GraySpace.Gray, GraySpace.Gray, 0)
                        COSName.IMAGE -> picture(stream, res)
                        else -> {}
                    }
                }
            }
            dict.getCOSDictionary(COSName.PATTERN)?.let { patterns ->
                for (key in patterns.keySet().toList()) {
                    val pattern = patterns.getDictionaryObject(key) as? COSDictionary ?: continue
                    when (pattern.getInt(COSName.PATTERN_TYPE)) {
                        1 -> (pattern as? COSStream)?.let { tiling(it, res, 0) }
                        2 -> shadingPattern(pattern, res, 0)
                    }
                }
            }
            dict.getCOSDictionary(COSName.SHADING)?.let { listed ->
                for (key in listed.keySet().toList()) {
                    val shading = listed.getDictionaryObject(key) as? COSDictionary ?: continue
                    grayShading(shading, res)
                }
            }
        }
    }

    /**
     * Notes [res] as read (for the sweep) and gives the state of its /ColorSpace dictionary, if it has
     * one. An odd /DefaultGray in it goes the first time it is met ([defaultGray]), also when the
     * content reading it has no `g`, so no gray written can be recoloured by it.
     */
    private fun use(res: Res): SpaceUse? {
        val dict = res.dict ?: return null
        if (visited.add(dict)) visitOrder += res
        val spaces = dict.getDictionaryObject(COSName.COLORSPACE) as? COSDictionary ?: return null
        val use = useOf(spaces)
        if (!use.met) {
            use.met = true
            defaultGray(spaces, dict)
        }
        return use
    }

    private fun useOf(spaces: COSDictionary): SpaceUse = uses.getOrPut(spaces) { SpaceUse() }

    /**
     * Marks the /ColorSpace dictionary of [resources], and those of the forms and patterns it lists
     * (followed 8 deep), never to be pruned: something not read may use them.
     */
    private fun protect(resources: COSDictionary) {
        if (!protectedRoots.add(resources)) return
        val seen = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
        fun mark(dict: COSDictionary?, depth: Int) {
            if (dict == null || depth > MAX_PROTECT_DEPTH || !seen.add(dict)) return
            (dict.getDictionaryObject(COSName.COLORSPACE) as? COSDictionary)?.let { useOf(it).neverPrune = true }
            for (kind in listOf(COSName.XOBJECT, COSName.PATTERN)) {
                val listed = dict.getCOSDictionary(kind) ?: continue
                for (key in listed.keySet()) {
                    val item = listed.getDictionaryObject(key) as? COSDictionary ?: continue
                    mark(item.getDictionaryObject(COSName.RESOURCES) as? COSDictionary, depth + 1)
                }
            }
        }
        mark(resources, 0)
    }

    /**
     * Takes out of each /ColorSpace dictionary read every name nothing uses any more, unless content
     * using it was left in colour or something not read may use it (pruning only saves space). The
     * gray pattern space stays, and so does a /DefaultGray that is gray: it still colours the grays.
     */
    private fun prune() {
        for ((dict, use) in uses) {
            if (!use.met || use.failed || use.neverPrune) continue
            // A name kept keeps the names its space refers to (a palette's base given by name, say).
            val kept = HashSet(use.kept)
            val queue = ArrayDeque(kept)
            while (queue.isNotEmpty()) {
                val value = dict.getDictionaryObject(queue.removeFirst()) as? COSArray ?: continue
                for (name in namesIn(value)) if (dict.containsKey(name) && kept.add(name)) queue += name
            }
            for (key in dict.keySet().toList()) {
                val name = key.name
                if (name in kept || name.startsWith(GRAY_PATTERN)) continue
                if (key == COSName.DEFAULT_GRAY && space(dict.getDictionaryObject(key), null, 1).isGray) continue
                dict.removeItem(key)
            }
        }
    }

    /** The name of a `[/Pattern /DeviceGray]` space in [res]' /ColorSpace dictionary, made there (and the dictionaries with it) the first time. */
    private fun grayPattern(res: Res): String {
        val resources = res.dict ?: COSDictionary().also { made ->
            res.owner?.setItem(COSName.RESOURCES, made)
            res.dict = made
        }
        val dict = resources.getDictionaryObject(COSName.COLORSPACE) as? COSDictionary
            ?: COSDictionary().also { resources.setItem(COSName.COLORSPACE, it) }
        var n = 1
        while (true) {
            val name = if (n == 1) GRAY_PATTERN else "$GRAY_PATTERN$n"
            val existing = dict.getDictionaryObject(name)
            if (existing == null) {
                dict.setItem(name, grayPatternSpace())
                return name
            }
            if (isGrayPattern(existing)) return name
            n++
        }
    }

    /** The parser's input, opened and closed by the driver: the parser is never handed a stream it would open itself. */
    private class Source(private val input: InputStream) : PDContentStream {
        override fun getContents(): InputStream = input
        override fun getResources(): PDResources? = null
        override fun getBBox(): PDRectangle? = null
        override fun getMatrix(): Matrix? = null
    }

    private companion object {
        const val MAX_DEPTH = 32
        const val MAX_SPACE_DEPTH = 8
        const val MAX_PROTECT_DEPTH = 8
        const val MAX_FIELD_DEPTH = 64
        const val GRAY_PATTERN = "UsugamiGrayPattern"
        const val MAX_INLINE_PIXELS = 16_000_000L
        val LEFT_ON_PURPOSE = Driven(changed = false, failed = false)
        val INLINE_DEVICE_NAMES = setOf("G", "RGB", "CMYK", "DeviceGray", "DeviceRGB", "DeviceCMYK", "I", "Indexed", "Pattern")
        val INLINE_BITS = setOf(1, 2, 4, 8)
        val LOSSLESS_FILTERS = setOf(
            "AHx", "ASCIIHexDecode", "A85", "ASCII85Decode", "LZW", "LZWDecode", "Fl", "FlateDecode", "RL", "RunLengthDecode",
        )

        /** Inline image entries the gray picture doesn't keep: its bits, space, decode, filters and lengths are new. */
        val INLINE_REPLACED = setOf(
            "BPC", "BitsPerComponent", "CS", "ColorSpace", "D", "Decode", "DP", "DecodeParms", "F", "Filter",
            "L", "Length", "DL", "IM", "ImageMask",
        )

        /** Whether a colour in [space] has a gray. */
        fun readable(space: GraySpace): Boolean = when (space) {
            is GraySpace.Unknown, is GraySpace.None -> false
            is GraySpace.Pattern -> space.base?.let { readable(it) } ?: false
            is GraySpace.Tint -> space.alternate !is GraySpace.Pattern && readable(space.alternate)
            else -> true
        }
    }
}

/**
 * Puts [data]'s bytes in place of [old]'s, so every reference to [old] (a form, a pattern, a glyph,
 * a picture with its masks) sees the change, then closes [data]. [old] must not be open for reading:
 * PDFBox doesn't stop it, it closes the data under the reader. [filter] is how [data] is stored;
 * whatever else told how the old data was stored goes.
 */
internal fun replaceData(old: COSStream, data: COSStream, filter: COSName = COSName.FLATE_DECODE) {
    data.createRawInputStream().use { input -> old.createRawOutputStream().use { input.copyTo(it) } }
    old.setItem(COSName.FILTER, filter)
    for (key in listOf(COSName.DECODE_PARMS, COSName.F, COSName.F_FILTER, COSName.F_DECODE_PARMS, COSName.DL)) old.removeItem(key)
    data.close()
}

/**
 * An Indexed space's palette as grays, `[/Indexed /DeviceGray hival <one byte per entry>]` (or, for
 * an inline image, `[/I /G …]`): what draws in it stays in palette numbers, so its data, /Decode and
 * a colour-key mask stay as they are.
 */
internal fun grayIndexed(space: GraySpace.Indexed, inline: Boolean = false): COSArray = COSArray().apply {
    add(COSName.getPDFName(if (inline) "I" else "Indexed"))
    add(if (inline) COSName.getPDFName("G") else COSName.DEVICEGRAY)
    add(COSInteger.get((space.grays.size - 1).toLong()))
    add(COSString(ByteArray(space.grays.size) { GrayMath.level(space.grays[it]).toByte() }))
}

/** An inline image's data as stored: [bytes] and the /F they need (null for none). */
private class InlineData(val bytes: ByteArray, val filter: COSBase?)

/**
 * Gray samples stored for an inline image: deflated when that is smaller, else bare. PDFBox (as
 * other readers) finds the end of inline data by looking for "EI" between spaces, so data that
 * holds those bytes is written as ASCIIHex instead, which never does.
 */
private fun inlineData(gray: ByteArray): InlineData {
    val deflated = deflate(gray)
    val smaller = deflated.size < gray.size
    val bytes = if (smaller) deflated else gray
    if (!endsEarly(bytes)) return InlineData(bytes, if (smaller) COSName.getPDFName("Fl") else null)
    val hex = StringBuilder(bytes.size * 2 + 1)
    for (b in bytes) hex.append(HEX_DIGITS[(b.toInt() shr 4) and 0x0F]).append(HEX_DIGITS[b.toInt() and 0x0F])
    hex.append('>')
    val filter = if (smaller) COSArray().apply {
        add(COSName.getPDFName("AHx"))
        add(COSName.getPDFName("Fl"))
    } else COSName.getPDFName("AHx")
    return InlineData(hex.toString().toByteArray(Charsets.US_ASCII), filter)
}

private const val HEX_DIGITS = "0123456789ABCDEF"

/** Whether [bytes] hold "EI" between white space (or at the end), where a reader would take the data to end. */
private fun endsEarly(bytes: ByteArray): Boolean {
    fun white(b: Byte) = b.toInt() == 0 || b.toInt() == 9 || b.toInt() == 10 || b.toInt() == 12 || b.toInt() == 13 || b.toInt() == 32
    for (i in 0 until bytes.size - 1) {
        if (bytes[i] != 'E'.code.toByte() || bytes[i + 1] != 'I'.code.toByte()) continue
        val before = i == 0 || white(bytes[i - 1])
        val after = i + 2 == bytes.size || white(bytes[i + 2])
        if (before && after) return true
    }
    return false
}

private fun deflate(bytes: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    try {
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream(bytes.size / 2 + 64)
        val buffer = ByteArray(8192)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

/** The filter names of an image's /F or /Filter (a name or an array of names); an empty list for none, null for something else. */
internal fun filterNames(value: COSBase?): List<String>? = when (value) {
    null -> emptyList()
    is COSName -> listOf(value.name)
    is COSArray -> (0 until value.size()).map { (value.getObject(it) as? COSName)?.name ?: return null }
    else -> null
}

/** A new `[/Pattern /DeviceGray]`: the space an uncoloured pattern over a colour base is painted in once gray. */
private fun grayPatternSpace(): COSArray = COSArray().apply {
    add(COSName.PATTERN)
    add(COSName.DEVICEGRAY)
}

/** Whether [value] is `[/Pattern /DeviceGray]`. */
private fun isGrayPattern(value: COSBase?): Boolean =
    value is COSArray && value.size() == 2 && value.getObject(0) == COSName.PATTERN && value.getObject(1) == COSName.DEVICEGRAY

/**
 * How many components a colour space of [array]'s family takes, told without reading the space (0
 * when it can't be told): what a space that can't be read keeps.
 */
private fun componentsOf(array: COSArray): Int {
    fun item(i: Int): COSBase? = if (i < array.size()) array.getObject(i) else null
    return when ((item(0) as? COSName)?.name) {
        "DeviceGray", "G", "CalGray", "Indexed", "I", "Separation", "Pattern" -> 1
        "DeviceRGB", "RGB", "CalRGB", "Lab" -> 3
        "DeviceCMYK", "CMYK" -> 4
        "DeviceN" -> (item(1) as? COSArray)?.size() ?: 0
        "ICCBased" -> (item(1) as? COSStream)?.getInt(COSName.N, 0)?.coerceAtLeast(0) ?: 0
        else -> 0
    }
}

/** The names in a colour space array, and in the arrays in it (a few deep). */
private fun namesIn(array: COSArray, depth: Int = 0): List<String> {
    if (depth > 4) return emptyList()
    val names = ArrayList<String>()
    for (i in 0 until array.size()) {
        when (val item = array.getObject(i)) {
            is COSName -> names += item.name
            is COSArray -> names += namesIn(item, depth + 1)
            else -> {}
        }
    }
    return names
}

/** The numbers of [array], or null when one isn't a number. */
internal fun floats(array: COSArray): FloatArray? {
    val values = FloatArray(array.size())
    for (i in values.indices) values[i] = (array.getObject(i) as? COSNumber)?.floatValue() ?: return null
    return values
}

/** Reads [buffer] full, or as much as there is; returns how much was read. */
internal fun readFully(input: InputStream, buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = input.read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return total
}

/** At most [limit] bytes of [input]. */
private fun readAtMost(input: InputStream, limit: Int): ByteArray {
    val buffer = ByteArray(limit.coerceAtLeast(0))
    return buffer.copyOf(readFully(input, buffer))
}
