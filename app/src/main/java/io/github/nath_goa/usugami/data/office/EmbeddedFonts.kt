package io.github.nath_goa.usugami.data.office

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDFontDescriptor
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import io.github.nath_goa.usugami.core.TrueTypeSubset
import java.io.ByteArrayInputStream
import java.io.StringWriter
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Shrinks the fonts in a PDF that Android's PDF writer made. It embeds each font file whole, about
 * 140 KB for each style of the phone's serif font, however few letters are drawn; this keeps the
 * outlines of the glyphs the pages draw (see [TrueTypeSubset]), so a book or document with a few
 * styles is hundreds of kilobytes smaller and looks exactly the same. A font about which anything
 * is unusual is left whole.
 */
internal object EmbeddedFonts {
    /** Shrinks every TrueType font [doc] embeds for its text. [check] is called for each page and throws to stop. */
    fun shrink(doc: PDDocument, check: () -> Unit) {
        // The glyphs drawn from each font file, and the font descriptors that point to it.
        val glyphs = IdentityHashMap<COSBase, MutableSet<Int>>()
        val users = IdentityHashMap<COSBase, MutableList<PDFontDescriptor>>()
        val skipped = IdentityHashMap<COSBase, Boolean>()
        unscannedFontFiles(doc).forEach { skipped[it] = true }
        val reader = object : PDFTextStripper() {
            override fun startPage(page: PDPage) {
                check()
                super.startPage(page)
            }

            // Every glyph shown, before text extraction looks at it (which skips glyphs it can't map to Unicode).
            override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
                val cidFont = (font as? PDType0Font)?.descendantFont as? PDCIDFontType2
                val descriptor = cidFont?.fontDescriptor ?: font.fontDescriptor ?: return
                val file = descriptor.fontFile2?.cosObject ?: return
                if (skipped.containsKey(file)) return
                // The codes are glyph numbers only in a CID font with the identity encoding and glyph
                // map, as Android writes them (horizontal: vertical text may read the font's vertical
                // metrics, which a shrunk font leaves out). A font file used any other way stays whole.
                val encoding = font.cosObject.getDictionaryObject(COSName.ENCODING)
                val map = cidFont?.cosObject?.getDictionaryObject(COSName.CID_TO_GID_MAP)
                if (cidFont == null || encoding != COSName.IDENTITY_H || (map != null && map != COSName.IDENTITY)) {
                    skipped[file] = true
                    glyphs.remove(file)
                    return
                }
                glyphs.getOrPut(file) { HashSet() } += code
                val list = users.getOrPut(file) { ArrayList() }
                if (list.none { it === descriptor }) list += descriptor
            }
        }
        reader.writeText(doc, StringWriter())
        // Each part of a long book embeds its own copy of each font: copies alike share one smaller
        // file, with the glyphs any of them draws.
        val fonts = ArrayList<SameFont>()
        for ((file, used) in glyphs) {
            check()
            if (skipped.containsKey(file)) continue
            val bytes = (file as? COSStream)?.createInputStream()?.use { it.readBytes() } ?: continue
            val same = fonts.firstOrNull { it.bytes.contentEquals(bytes) } ?: SameFont(bytes).also { fonts += it }
            same.glyphs += used
            same.files += file
        }
        for (font in fonts) {
            check()
            val smaller = TrueTypeSubset.keepGlyphs(font.bytes, font.glyphs) ?: continue
            val stream = PDStream(doc, ByteArrayInputStream(smaller), COSName.FLATE_DECODE)
            stream.cosObject.setInt(COSName.LENGTH1, smaller.size)
            for (file in font.files) users[file]?.forEach { it.fontFile2 = stream }
        }
    }

    /** A font file's contents, the glyphs drawn from it, and the font file streams that hold it. */
    private class SameFont(val bytes: ByteArray) {
        val glyphs = HashSet<Int>()
        val files = ArrayList<COSBase>()
    }

    /**
     * The font files of fonts used where the glyph scan doesn't look: in soft masks, tiling patterns,
     * annotations' appearances and Type 3 glyphs. Android's PDF writer draws no text there, but
     * should a font be drawn there, it is left whole rather than lose letters.
     */
    private fun unscannedFontFiles(doc: PDDocument): Set<COSBase> {
        val files = Collections.newSetFromMap(IdentityHashMap<COSBase, Boolean>())
        // Resource dictionaries walked, from where the scan looks and from where it doesn't.
        val walked = List(2) { Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>()) }

        fun fontFile(font: COSDictionary): COSBase? {
            val descendant = (font.getDictionaryObject(COSName.DESCENDANT_FONTS) as? COSArray)?.getObject(0) as? COSDictionary
            val descriptor = (descendant ?: font).getDictionaryObject(COSName.FONT_DESC) as? COSDictionary
            return descriptor?.getDictionaryObject(COSName.FONT_FILE2)
        }

        fun walk(resources: COSDictionary?, unscanned: Boolean) {
            if (resources == null || !walked[if (unscanned) 1 else 0].add(resources)) return
            fun each(name: COSName, action: (COSBase?) -> Unit) {
                val dictionary = resources.getDictionaryObject(name) as? COSDictionary ?: return
                for (key in dictionary.keySet()) action(dictionary.getDictionaryObject(key))
            }
            each(COSName.FONT) { font ->
                if (font !is COSDictionary) return@each
                if (unscanned) fontFile(font)?.let(files::add)
                if (font.getCOSName(COSName.SUBTYPE) == COSName.TYPE3) walk(font.getDictionaryObject(COSName.RESOURCES) as? COSDictionary, true)
            }
            each(COSName.XOBJECT) { xObject ->
                if (xObject is COSStream && xObject.getCOSName(COSName.SUBTYPE) == COSName.FORM) {
                    walk(xObject.getDictionaryObject(COSName.RESOURCES) as? COSDictionary, unscanned)
                }
            }
            each(COSName.PATTERN) { pattern ->
                if (pattern is COSStream) walk(pattern.getDictionaryObject(COSName.RESOURCES) as? COSDictionary, true)
            }
            each(COSName.EXT_G_STATE) { state ->
                val mask = (state as? COSDictionary)?.getDictionaryObject(COSName.SMASK) as? COSDictionary
                val group = mask?.getDictionaryObject(COSName.G) as? COSStream
                walk(group?.getDictionaryObject(COSName.RESOURCES) as? COSDictionary, true)
            }
        }

        for (page in doc.pages) {
            walk(page.resources?.cosObject, false)
            val annotations = page.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
            for (i in 0 until annotations.size()) {
                val appearance = (annotations.getObject(i) as? COSDictionary)?.getDictionaryObject(COSName.AP) as? COSDictionary ?: continue
                for (key in appearance.keySet()) {
                    when (val entry = appearance.getDictionaryObject(key)) {
                        is COSStream -> walk(entry.getDictionaryObject(COSName.RESOURCES) as? COSDictionary, true)
                        is COSDictionary -> for (state in entry.keySet()) {
                            (entry.getDictionaryObject(state) as? COSStream)?.let { walk(it.getDictionaryObject(COSName.RESOURCES) as? COSDictionary, true) }
                        }
                    }
                }
            }
        }
        return files
    }
}
