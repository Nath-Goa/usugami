package io.github.nath_goa.usugami.core

import java.nio.charset.Charset
import kotlin.math.max

/**
 * HTML's named character references, such as &nbsp; and &eacute;. E-books are XHTML, which is XML,
 * and XML defines only five of them; books use the rest anyway, so the reader is taught all of
 * HTML 4's.
 */
object HtmlEntities {
    val all: Map<String, String> by lazy(::build)

    private fun build(): Map<String, String> {
        val map = HashMap<String, String>(320)
        fun run(names: String, first: Int) = names.split(' ').forEachIndexed { i, name -> map[name] = String(Character.toChars(first + i)) }
        // Latin-1, from U+00A0 to U+00FF in order.
        run(
            "nbsp iexcl cent pound curren yen brvbar sect uml copy ordf laquo not shy reg macr deg plusmn sup2 sup3 acute " +
                "micro para middot cedil sup1 ordm raquo frac14 frac12 frac34 iquest Agrave Aacute Acirc Atilde Auml Aring AElig " +
                "Ccedil Egrave Eacute Ecirc Euml Igrave Iacute Icirc Iuml ETH Ntilde Ograve Oacute Ocirc Otilde Ouml times Oslash " +
                "Ugrave Uacute Ucirc Uuml Yacute THORN szlig agrave aacute acirc atilde auml aring aelig ccedil egrave eacute ecirc " +
                "euml igrave iacute icirc iuml eth ntilde ograve oacute ocirc otilde ouml divide oslash ugrave uacute ucirc uuml " +
                "yacute thorn yuml",
            0xA0,
        )
        run("Alpha Beta Gamma Delta Epsilon Zeta Eta Theta Iota Kappa Lambda Mu Nu Xi Omicron Pi Rho", 913)
        run("Sigma Tau Upsilon Phi Chi Psi Omega", 931)
        run("alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigmaf sigma tau upsilon phi chi psi omega", 945)
        val others = "quot 34 amp 38 apos 39 lt 60 gt 62 OElig 338 oelig 339 Scaron 352 scaron 353 Yuml 376 fnof 402 circ 710 tilde 732 " +
            "thetasym 977 upsih 978 piv 982 ensp 8194 emsp 8195 thinsp 8201 zwnj 8204 zwj 8205 lrm 8206 rlm 8207 ndash 8211 " +
            "mdash 8212 lsquo 8216 rsquo 8217 sbquo 8218 ldquo 8220 rdquo 8221 bdquo 8222 dagger 8224 Dagger 8225 bull 8226 " +
            "hellip 8230 permil 8240 prime 8242 Prime 8243 lsaquo 8249 rsaquo 8250 oline 8254 frasl 8260 euro 8364 image 8465 " +
            "weierp 8472 real 8476 trade 8482 alefsym 8501 larr 8592 uarr 8593 rarr 8594 darr 8595 harr 8596 crarr 8629 " +
            "lArr 8656 uArr 8657 rArr 8658 dArr 8659 hArr 8660 forall 8704 part 8706 exist 8707 empty 8709 nabla 8711 " +
            "isin 8712 notin 8713 ni 8715 prod 8719 sum 8721 minus 8722 lowast 8727 radic 8730 prop 8733 infin 8734 ang 8736 " +
            "and 8743 or 8744 cap 8745 cup 8746 int 8747 there4 8756 sim 8764 cong 8773 asymp 8776 ne 8800 equiv 8801 " +
            "le 8804 ge 8805 sub 8834 sup 8835 nsub 8836 sube 8838 supe 8839 oplus 8853 otimes 8855 perp 8869 sdot 8901 " +
            "lceil 8968 rceil 8969 lfloor 8970 rfloor 8971 lang 9001 rang 9002 loz 9674 spades 9824 clubs 9827 hearts 9829 diams 9830"
        others.split(' ').chunked(2).forEach { (name, code) -> map[name] = String(Character.toChars(code.toInt())) }
        return map
    }
}

/** Paths inside an EPUB (a zip), as its files link to each other. */
object EpubPaths {
    /**
     * The zip path and fragment that [href] points to from the file [base] (such as
     * "OEBPS/text/ch1.xhtml"). Null for links out of the book (http:, mailto:, data:...).
     */
    fun resolve(base: String, href: String): Pair<String, String?>? {
        val trimmed = href.trim()
        if (trimmed.isEmpty()) return null
        if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(trimmed)) return null
        val hash = trimmed.indexOf('#')
        val link = (if (hash >= 0) trimmed.substring(0, hash) else trimmed).substringBefore('?')
        val fragment = if (hash >= 0) decode(trimmed.substring(hash + 1)).takeIf { it.isNotEmpty() } else null
        // "#note" alone points into the same file.
        if (link.isEmpty()) return base to fragment
        val segments = ArrayList<String>()
        if (!link.startsWith("/")) segments += base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        for (segment in decode(link).split('/')) when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
        return segments.joinToString("/") to fragment
    }

    /** %-escapes decoded as UTF-8. Unlike URLDecoder, "+" stays a plus. */
    fun decode(text: String): String {
        if ('%' !in text) return text
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val value = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (value != null) {
                    bytes.write(value)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return bytes.toString("UTF-8")
    }
}

/** Whether an EPUB is protected with DRM, from the files in its META-INF folder. */
object EpubProtection {
    /**
     * The ways books hide embedded fonts (IDPF's and Adobe's). They scramble fonts only, which
     * Usugami doesn't use, so a book that has only these isn't protected.
     */
    private val FONT_OBFUSCATION = setOf("http://www.idpf.org/2008/embedding", "http://ns.adobe.com/pdf/enc#RC")

    /**
     * [rights] when the book has META-INF/rights.xml (Adobe's DRM) or a Readium LCP licence;
     * [algorithms] are those META-INF/encryption.xml lists. Anything but font obfuscation is DRM.
     */
    fun isProtected(rights: Boolean, algorithms: List<String>): Boolean = rights || algorithms.any { it.trim() !in FONT_OBFUSCATION }
}

/** The start of an XML file, looked at before it is parsed. */
object XmlPrefix {
    /** The character set of an XML file that starts with [head] (its first [length] bytes): from its byte order mark or declaration. */
    fun charset(head: ByteArray, length: Int): String {
        fun at(i: Int) = if (i < length) head[i].toInt() and 0xFF else -1
        if (at(0) == 0xFE && at(1) == 0xFF) return "UTF-16BE"
        if (at(0) == 0xFF && at(1) == 0xFE) return "UTF-16LE"
        // UTF-16 without a byte order mark: "<?" with zero bytes between.
        if (at(0) == 0 && at(1) == '<'.code) return "UTF-16BE"
        if (at(0) == '<'.code && at(1) == 0) return "UTF-16LE"
        val text = String(head, 0, minOf(length, 200), Charsets.ISO_8859_1)
        val declared = Regex("""^\s*<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._:-]+)["']""").find(text.removePrefix("\u00EF\u00BB\u00BF"))?.groupValues?.get(1)
        return declared?.takeIf { runCatching { Charset.isSupported(it) }.getOrDefault(false) } ?: "UTF-8"
    }

    /**
     * [text] (the start of a file) without its DOCTYPE, so nothing it declares is ever expanded.
     * Null when a DOCTYPE starts but doesn't end within [text], or when the root element doesn't
     * start within it (a DOCTYPE could still follow).
     */
    fun stripDoctype(text: String): String? {
        val start = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE).find(text)?.range?.first
        val stripped = if (start == null) text else {
            var i = start + 9
            var quote = 0.toChar()
            var depth = 0
            var end = -1
            while (i < text.length) {
                val c = text[i]
                when {
                    quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                    c == '"' || c == '\'' -> quote = c
                    c == '[' -> depth++
                    c == ']' -> depth = max(0, depth - 1)
                    c == '>' && depth == 0 -> {
                        end = i
                        break
                    }
                }
                i++
            }
            if (end < 0) return null
            text.substring(0, start) + text.substring(end + 1)
        }
        // The root element must start here; comments and processing instructions may come first.
        var i = 0
        while (true) {
            i = stripped.indexOf('<', i)
            if (i < 0 || i + 1 >= stripped.length) return null
            val next = stripped[i + 1]
            when {
                next.isLetter() || next == '_' -> return stripped
                stripped.startsWith("<!--", i) -> i = stripped.indexOf("-->", i).takeIf { it >= 0 }?.plus(3) ?: return null
                next == '?' -> i = stripped.indexOf("?>", i).takeIf { it >= 0 }?.plus(2) ?: return null
                else -> return null
            }
        }
    }
}

/** Text in HTML, where runs of spaces and line breaks show as one space. */
object HtmlText {
    /**
     * [text] with each run of white space as one space, and leading white space dropped when the
     * text so far ends in a space ([afterSpace]). No-break spaces stay.
     */
    fun collapse(text: String, afterSpace: Boolean): String {
        val out = StringBuilder(text.length)
        var space = afterSpace
        for (c in text) {
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C') {
                if (!space) out.append(' ')
                space = true
            } else {
                out.append(c)
                space = false
            }
        }
        return out.toString()
    }

    /** A fixed-layout page's size from its viewport, such as "width=1200, height=1600". */
    fun viewport(content: String?): Pair<Int, Int>? {
        val values = content.orEmpty().split(',', ';').mapNotNull { part ->
            val (key, value) = part.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            key.trim().lowercase() to value.trim().toFloatOrNull()?.toInt()
        }.toMap()
        val width = values["width"]?.takeIf { it > 0 } ?: return null
        val height = values["height"]?.takeIf { it > 0 } ?: return null
        return width to height
    }

    /** A list label's format for CSS's list-style-type, or for an ol element's type attribute. Null for none. */
    fun listFormat(value: String?, ordered: Boolean): ListNumbering.Format? = when (value?.trim()) {
        "none" -> null
        "disc", "circle", "square" -> ListNumbering.Format.BULLET
        "decimal", "1" -> ListNumbering.Format.DECIMAL
        "decimal-leading-zero" -> ListNumbering.Format.DECIMAL_ZERO
        "lower-alpha", "lower-latin", "a" -> ListNumbering.Format.LOWER_LETTER
        "upper-alpha", "upper-latin", "A" -> ListNumbering.Format.UPPER_LETTER
        "lower-roman", "i" -> ListNumbering.Format.LOWER_ROMAN
        "upper-roman", "I" -> ListNumbering.Format.UPPER_ROMAN
        else -> if (ordered) ListNumbering.Format.DECIMAL else ListNumbering.Format.BULLET
    }

    /**
     * Column widths for a table whose columns hold [weights] of text (such as their longest cell's
     * length), sharing [available]: half of it evenly, half by the text, so a column with a lot of
     * text gets more room without squeezing the others to nothing.
     */
    fun columnWidths(weights: List<Float>, available: Float): List<Float> {
        if (weights.isEmpty()) return emptyList()
        val total = weights.sumOf { max(1f, it).toDouble() }.toFloat()
        return weights.map { (0.5f / weights.size + 0.5f * max(1f, it) / total) * available }
    }
}

/** One part of a CSS selector, such as p.note or #title: a tag (null for any), an id and classes. */
data class CssCompound(val tag: String?, val id: String?, val classes: List<String>)

/**
 * A CSS selector Usugami understands: compounds joined by descendant (' ') or child ('>')
 * combinators, outermost first. Others (attributes, pseudo-classes, siblings) are left out.
 */
data class CssSelector(val parts: List<CssCompound>, val combinators: List<Char>) {
    /** Ids count 100, classes 10 and tags 1, as in CSS. */
    val specificity: Int = parts.sumOf { (if (it.id != null) 100 else 0) + it.classes.size * 10 + (if (it.tag != null) 1 else 0) }
    val key: CssCompound get() = parts.last()

    fun matches(node: CssNode): Boolean {
        if (!parts.last().matches(node)) return false
        var current: CssNode? = node
        for (i in parts.lastIndex - 1 downTo 0) {
            val part = parts[i]
            current = if (combinators[i] == '>') current?.parent?.takeIf(part::matches) else {
                var up = current?.parent
                while (up != null && !part.matches(up)) up = up.parent
                up
            }
            if (current == null) return false
        }
        return true
    }
}

private fun CssCompound.matches(node: CssNode) =
    (tag == null || tag == node.tag) && (id == null || id == node.id) && classes.all { it in node.classes }

/** An element as CSS sees it, with its parent. */
class CssNode(val tag: String, val id: String?, val classes: Set<String>, val parent: CssNode?)

class CssRule(val selector: CssSelector, val declarations: Map<String, String>, val order: Int)

/** A style sheet's rules, found by the tag, class or id they end with. */
class CssSheet(rules: List<CssRule>) {
    private val byTag = HashMap<String, MutableList<CssRule>>()
    private val byClass = HashMap<String, MutableList<CssRule>>()
    private val byId = HashMap<String, MutableList<CssRule>>()

    init {
        for (rule in rules) {
            val key = rule.selector.key
            when {
                key.id != null -> byId.getOrPut(key.id) { ArrayList() } += rule
                key.classes.isNotEmpty() -> byClass.getOrPut(key.classes[0]) { ArrayList() } += rule
                else -> byTag.getOrPut(key.tag ?: "*") { ArrayList() } += rule
            }
        }
    }

    val isEmpty: Boolean get() = byTag.isEmpty() && byClass.isEmpty() && byId.isEmpty()

    /** The rules that apply to [node]. */
    fun matching(node: CssNode): List<CssRule> {
        val found = ArrayList<CssRule>()
        byTag[node.tag]?.let(found::addAll)
        byTag["*"]?.let(found::addAll)
        for (c in node.classes) byClass[c]?.let(found::addAll)
        node.id?.let { byId[it] }?.let(found::addAll)
        return found.filter { it.selector.matches(node) }
    }

    companion object {
        /**
         * The declarations for [node] from [sheets] (in the order the document links them), then its
         * own style attribute [inline]: each later or more specific one wins.
         */
        fun cascade(node: CssNode, sheets: List<CssSheet>, inline: String?): Map<String, String> {
            val result = HashMap<String, String>()
            var sheetIndex = 0
            val rules = ArrayList<Triple<Int, Int, CssRule>>()
            for (sheet in sheets) {
                for (rule in sheet.matching(node)) rules += Triple(rule.selector.specificity, sheetIndex * 1_000_000 + rule.order, rule)
                sheetIndex++
            }
            rules.sortWith(compareBy({ it.first }, { it.second }))
            for ((_, _, rule) in rules) result.putAll(rule.declarations)
            if (!inline.isNullOrBlank()) result.putAll(SimpleCss.declarations(inline))
            return result
        }
    }
}

/** Just enough CSS for e-books: rules with simple selectors and their declarations. */
object SimpleCss {
    fun parse(text: String): CssSheet {
        val css = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(text, " ")
        val rules = ArrayList<CssRule>()
        var i = 0
        var order = 0
        while (i < css.length) {
            val open = css.indexOf('{', i)
            if (open < 0) break
            val prelude = css.substring(i, open).trim()
            // The block, with any blocks nested in it (@media rules hold whole rules).
            var depth = 1
            var j = open + 1
            while (j < css.length && depth > 0) {
                when (css[j]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                j++
            }
            val body = css.substring(open + 1, (j - 1).coerceAtLeast(open + 1))
            i = j
            // At-rules (@font-face, @page, @media...) are left out, statements such as @import too.
            val start = prelude.substringAfterLast(';').trim()
            if (start.startsWith("@") || start.isEmpty()) continue
            val declarations = declarations(body)
            if (declarations.isEmpty()) continue
            for (selectorText in start.split(',')) {
                val selector = selector(selectorText) ?: continue
                rules += CssRule(selector, declarations, order++)
            }
        }
        return CssSheet(rules)
    }

    /** The declarations of a rule or style attribute, by lowercase name, with !important dropped. */
    fun declarations(block: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in block.split(';')) {
            val colon = part.indexOf(':')
            if (colon <= 0) continue
            val name = part.substring(0, colon).trim().lowercase()
            val value = part.substring(colon + 1).replace(Regex("""!\s*important""", RegexOption.IGNORE_CASE), "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) out[name] = value
        }
        return out
    }

    /** A selector such as "div.poem > p", or null for one Usugami doesn't understand. */
    fun selector(text: String): CssSelector? {
        val tokens = text.trim().replace(Regex("""\s*>\s*"""), " > ").split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val parts = ArrayList<CssCompound>()
        val combinators = ArrayList<Char>()
        var child = false
        for (token in tokens) {
            if (token == ">") {
                if (parts.isEmpty() || child) return null
                child = true
                continue
            }
            if (parts.isNotEmpty()) combinators += if (child) '>' else ' '
            child = false
            parts += compound(token) ?: return null
        }
        if (child) return null
        return CssSelector(parts, combinators)
    }

    private fun compound(token: String): CssCompound? {
        val match = Regex("""^([a-zA-Z][a-zA-Z0-9-]*|\*)?((?:[.#][a-zA-Z_][a-zA-Z0-9_-]*)*)$""").find(token) ?: return null
        val tag = match.groupValues[1].lowercase().takeIf { it.isNotEmpty() && it != "*" }
        var id: String? = null
        val classes = ArrayList<String>()
        for (piece in Regex("""[.#][a-zA-Z_][a-zA-Z0-9_-]*""").findAll(match.groupValues[2])) {
            val name = piece.value.substring(1)
            if (piece.value[0] == '#') id = name else classes += name
        }
        return CssCompound(tag, id, classes)
    }
}

/** CSS values, in points. Books are written for a 16 px default font; Usugami's is [base] points. */
object CssValues {
    /**
     * A length such as "1.5em", "12px" or "5%". [em] is the element's font size, [base] the book's,
     * [percentOf] what 100% is (null when percentages don't apply). Absolute units scale with the
     * book's font, as 16 px (12 pt) is to [base].
     */
    fun length(value: String?, em: Float, base: Float, percentOf: Float? = null): Float? {
        val match = Regex("""^(-?\d*\.?\d+)\s*(em|rem|ex|px|pt|pc|in|cm|mm|%)?$""").find(value?.trim()?.lowercase() ?: return null) ?: return null
        val number = match.groupValues[1].toFloatOrNull() ?: return null
        val scale = base / 12f
        return when (match.groupValues[2]) {
            "em" -> number * em
            "rem" -> number * base
            "ex" -> number * em * 0.5f
            "px" -> number * 0.75f * scale
            "pt" -> number * scale
            "pc" -> number * 12f * scale
            "in" -> number * 72f * scale
            "cm" -> number * 72f / 2.54f * scale
            "mm" -> number * 72f / 25.4f * scale
            "%" -> percentOf?.let { number / 100f * it }
            else -> if (number == 0f) 0f else null
        }
    }

    /** A font-size: relative to [parent], keywords and absolute sizes relative to [base]; kept between 0.6 and 3 times [base]. */
    fun fontSize(value: String?, parent: Float, base: Float): Float? {
        val v = value?.trim()?.lowercase() ?: return null
        val size = when (v) {
            "xx-small" -> base * 0.6f
            "x-small" -> base * 0.75f
            "small" -> base * 0.89f
            "medium" -> base
            "large" -> base * 1.2f
            "x-large" -> base * 1.5f
            "xx-large" -> base * 2f
            "smaller" -> parent * 0.83f
            "larger" -> parent * 1.2f
            else -> length(v, parent, base, parent)
        } ?: return null
        return size.coerceIn(base * 0.6f, base * 3f)
    }

    /** Whether a font-weight is bold; null when it doesn't say. */
    fun bold(value: String?): Boolean? = when (val v = value?.trim()?.lowercase()) {
        null -> null
        "bold", "bolder" -> true
        "normal", "lighter" -> false
        else -> v.toIntOrNull()?.let { it >= 600 }
    }

    /** The vertical part of a margin shorthand: its top and bottom values. */
    fun verticalMargins(shorthand: String?): Pair<String, String>? {
        val parts = shorthand?.trim()?.split(Regex("""\s+"""))?.takeIf { it.isNotEmpty() && it[0].isNotEmpty() } ?: return null
        return when (parts.size) {
            1 -> parts[0] to parts[0]
            2 -> parts[0] to parts[0]
            else -> parts[0] to parts[2]
        }
    }

    /** The horizontal part of a margin shorthand: its left and right values. */
    fun horizontalMargins(shorthand: String?): Pair<String, String>? {
        val parts = shorthand?.trim()?.split(Regex("""\s+"""))?.takeIf { it.isNotEmpty() && it[0].isNotEmpty() } ?: return null
        return when (parts.size) {
            1 -> parts[0] to parts[0]
            2, 3 -> parts[1] to parts[1]
            else -> parts[3] to parts[1]
        }
    }

    /** The generic family ("serif", "sans-serif" or "monospace") for a font-family list, or null when none is clear. */
    fun fontFamily(value: String?): String? {
        for (raw in value.orEmpty().split(',')) {
            val name = raw.trim().trim('"', '\'').lowercase()
            when {
                name.isEmpty() -> Unit
                name == "monospace" || listOf("courier", "mono", "consolas", "menlo", "monaco").any { it in name } -> return "monospace"
                "sans" in name || listOf("arial", "helvetica", "verdana", "roboto", "open sans", "tahoma", "calibri").any { it in name } -> return "sans-serif"
                "serif" in name || listOf("times", "georgia", "garamond", "palatino", "baskerville", "book", "minion", "caslon", "charter", "cambria").any { it in name } -> return "serif"
            }
        }
        return null
    }

    /** Whether a page-break-before/after or break-before/after value asks for a new page. */
    fun pageBreak(value: String?): Boolean = value?.trim()?.lowercase() in setOf("always", "page", "left", "right", "recto", "verso")
}
