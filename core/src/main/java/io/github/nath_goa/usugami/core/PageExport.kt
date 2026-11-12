package io.github.nath_goa.usugami.core

import java.text.Normalizer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// PDF to images or text: the sizes, names and text rules, apart from Android so they are tested on the JVM.

/** How big PDF to images or text draws pages: [dpi] pixels to an inch of the page. */
enum class ExportImageSize(val dpi: Int) { SMALL(100), MEDIUM(200), LARGE(300) }

/** The kind of image PDF to images or text makes of each page, as the file [type] it is. */
enum class ExportImageFormat(val type: OutputType) { JPG(OutputType.JPEG), PNG(OutputType.PNG) }

/** Pages as images: how many pixels each gets, and what the files are called. */
object ExportImages {
    /** As Redaction.MAX_PIXELS: 48 MB as a bitmap, known to fit on a phone with 2 GB of memory. */
    const val MAX_PIXELS = 12_000_000

    /** As OcrScale.MAX_SIDE. */
    const val MAX_SIDE = 8192

    /** Below this, on the long side, a shortage of memory is reported rather than worked around. */
    const val MIN_LONG_SIDE = 1000

    /** As Redactor.JPEG_QUALITY: the size is the user's choice, not the quality. */
    const val JPEG_QUALITY = 90

    private const val FALLBACK = "Usugami"
    private val TYPED_ENDING = Regex("\\.(pdf|jpg|jpeg|png)$", RegexOption.IGNORE_CASE)
    private val SQRT_2 = sqrt(2.0)

    /** [width] × [height] pixels; [reduced] says the caps (or a shortage of memory) gave fewer than the size chosen. */
    data class Size(val width: Int, val height: Int, val reduced: Boolean)

    /**
     * Pixels for a page [shownWidth] × [shownHeight] points big as shown, at [dpi]: the page's size
     * × dpi / 72, unless that is more than [MAX_PIXELS] or more than [MAX_SIDE] on a side, when it
     * gets as many as the caps allow, in the same shape, and is [Size.reduced]. At least 1 × 1.
     */
    fun pixelSize(shownWidth: Float, shownHeight: Float, dpi: Int): Size {
        require(shownWidth > 0 && shownHeight > 0 && shownWidth.isFinite() && shownHeight.isFinite() && dpi > 0) {
            "Bad page size $shownWidth × $shownHeight at $dpi dpi"
        }
        val w = shownWidth.toDouble()
        val h = shownHeight.toDouble()
        val wanted = dpi / 72.0
        val allowed = min(sqrt(MAX_PIXELS / (w * h)), MAX_SIDE / max(w, h))
        if (wanted <= allowed) {
            val width = max(1, (w * wanted).roundToInt())
            val height = max(1, (h * wanted).roundToInt())
            // Rounded up on both sides, a page just inside the caps could be a pixel over them.
            if (width.toLong() * height <= MAX_PIXELS && max(width, height) <= MAX_SIDE) return Size(width, height, false)
            return capped(w * wanted, h * wanted, reduced = false)
        }
        return capped(w * allowed, h * allowed, reduced = true)
    }

    /** [width] × [height] rounded down, so the caps hold, but never under a pixel. */
    private fun capped(width: Double, height: Double, reduced: Boolean): Size {
        // A hair under a whole number is the whole number: 8192 worked out as 8191.999… stays 8192.
        var w = max(1, floor(width + 1e-6).toInt()).coerceAtMost(MAX_SIDE)
        var h = max(1, floor(height + 1e-6).toInt()).coerceAtMost(MAX_SIDE)
        while (w.toLong() * h > MAX_PIXELS) if (w >= h) w-- else h--
        return Size(w, h, reduced)
    }

    /**
     * The next smaller try after drawing at [size] ran out of memory: half the pixels, in the same
     * shape; null once the long side would be under [MIN_LONG_SIDE].
     */
    fun smaller(size: Size): Size? {
        val width = max(1, floor(size.width / SQRT_2).toInt())
        val height = max(1, floor(size.height / SQRT_2).toInt())
        if (max(width, height) < MIN_LONG_SIDE) return null
        return Size(width, height, reduced = true)
    }

    /** The name typed, without a typed ".pdf", ".jpg", ".jpeg" or ".png" ending, whatever its case. */
    fun typedBase(typed: String): String = typed.trim().trimEnd { it.isWhitespace() || it == '.' }.replace(TYPED_ENDING, "")

    /**
     * The name of each page's image, for the zero-based [pages], without the ending (the engine adds
     * it): "Report_p3" for page 3, as Split PDF names a part of one page. A long name is cut alike
     * for all, so they sort together; a blank one is "Usugami".
     */
    fun names(typed: String, pages: List<Int>): List<String> = FileNames.pageParts(typedBase(typed), pages.map(::listOf), FALLBACK)

    /** What the images are called together, and their ZIP file: "Report_images". */
    fun resultName(typed: String): String = FileNames.withSuffix(typedBase(typed), "_images", FALLBACK)
}

/** Text taken from pages: how a line is written into the file, and what counts as text. */
object ExportText {
    /** At most this many characters of the text show on the result screen. */
    const val PREVIEW_CHARS = 20_000

    /**
     * A longer text isn't copied: the clipboard goes through a 1 MB limit of Android's, which a
     * text in more than one byte a character would pass. The reader's Copy keeps to it too.
     */
    const val COPY_MAX_CHARS = 200_000

    private const val FALLBACK = "Usugami"
    private val TYPED_TXT = Regex("\\.txt$", RegexOption.IGNORE_CASE)

    /**
     * One line as it is written into the file: presentation forms (ligatures such as "ﬁ", Hebrew
     * and Arabic letters in the shapes they take beside others) as the letters they stand for, as
     * PDFBox's own text has them (NFKC on those alone: on the whole line it would also change "²",
     * "½" or full-width letters; and trimmed, as PDFBox trims them, since an Arabic vowel mark on
     * its own is a space and the mark); control characters but tab, U+FEFF (a byte order mark),
     * U+FFFC and U+FFFD dropped; white space at the end trimmed, at the start kept.
     */
    fun clean(line: String): String {
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\t'.code -> out.append('\t')
                Character.getType(cp) == Character.CONTROL.toInt() || cp == 0xFEFF || cp == 0xFFFC || cp == 0xFFFD -> Unit
                // The ligature for "Allah" after an alef: its letters but the alef, which is there already.
                cp == 0xFDF2 && out.isNotEmpty() && (out.last() == 'ا' || out.last() == 'ﺍ') -> out.append("لله")
                // Trimmed as PDFBox trims them (up to U+0020, as Java's String.trim): an Arabic vowel mark on its own, U+FE76 say, is a space and the mark.
                cp in 0xFB00..0xFDFF || cp in 0xFE70..0xFEFF ->
                    out.append(Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKC).trim { it <= ' ' })
                else -> out.appendCodePoint(cp)
            }
        }
        return out.trimEnd().toString()
    }

    /** Whether a page's text counts as text: it has a letter or a digit, of any script. */
    fun hasText(text: String): Boolean = text.codePoints().anyMatch(Character::isLetterOrDigit)

    /** The text file's name, "Report", without the ending (the engine adds ".txt"); a blank one is "Usugami". */
    fun baseName(typed: String): String =
        FileNames.sanitize(typed.trim().trimEnd { it.isWhitespace() || it == '.' }.replace(TYPED_TXT, ""), FALLBACK)

    /**
     * Zero-based [pages] as a note lists them, counted from 1, in order, with runs as ranges
     * ("3,5-7"), and cut to 40 characters as a part's name cuts them ("1,3,5,…,99").
     */
    fun pagesListed(pages: Collection<Int>): String = FileNames.pagesText(pages.toSortedSet().toList())

    /**
     * What of [text] the result screen shows: all of it when it has at most [PREVIEW_CHARS]
     * characters, else its first [PREVIEW_CHARS], cut back to the last line break in them, and never
     * between the two halves of a character.
     */
    fun preview(text: String): String {
        if (text.length <= PREVIEW_CHARS) return text
        val lineBreak = text.lastIndexOf('\n', PREVIEW_CHARS)
        if (lineBreak > 0) return text.substring(0, lineBreak)
        val end = if (Character.isHighSurrogate(text[PREVIEW_CHARS - 1])) PREVIEW_CHARS - 1 else PREVIEW_CHARS
        return text.substring(0, end)
    }

    /**
     * What a gap between two words of a line is written as, given the code points [before] and
     * [after] it (-1 for none): a space, or nothing between two characters of writing that puts no
     * spaces between its words (Chinese characters, kana, bopomofo and their punctuation, the
     * full-width and half-width forms among it), where a gap is only how the line was spread out.
     */
    fun gap(before: Int, after: Int): String = if (unspaced(before) && unspaced(after)) "" else " "

    /** Whether [codePoint] belongs to writing without spaces between words: Chinese characters, kana, bopomofo, and their punctuation. */
    private fun unspaced(codePoint: Int): Boolean {
        if (codePoint < 0x2E80 || codePoint > Character.MAX_CODE_POINT) return false
        return when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.BOPOMOFO -> true
            // 、。「」 and the like; kana's voiced marks and ゠ ・ ー, which Unicode counts as common to all writing.
            else -> codePoint in 0x3001..0x303F || codePoint in 0x3099..0x309C || codePoint == 0x30A0 || codePoint == 0x30FB || codePoint == 0x30FC ||
                // Full-width punctuation (，！？：；（） and the like) and half-width ｡｢｣､･ ｰ ﾞ ﾟ, common to all writing too.
                codePoint in 0xFF01..0xFF0F || codePoint in 0xFF1A..0xFF20 || codePoint in 0xFF3B..0xFF40 || codePoint in 0xFF5B..0xFF65 ||
                codePoint == 0xFF70 || codePoint == 0xFF9E || codePoint == 0xFF9F
        }
    }

    /**
     * A page's text from the lines text recognition read on it, each with its four corners: the
     * lines in reading order (columns one after the other, see [ReadingOrder.of]), each cleaned
     * ([clean]) and trimmed, empty ones dropped, each ending with "\n". Empty when none is left.
     */
    fun ofLines(lines: List<Pair<String, OcrQuad>>): String {
        val out = StringBuilder()
        for (index in ReadingOrder.of(lines.map { it.second })) {
            val line = clean(lines[index].first).trim()
            if (line.isNotEmpty()) out.append(line).append('\n')
        }
        return out.toString()
    }
}
