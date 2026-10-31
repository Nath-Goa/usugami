package io.github.nath_goa.usugami.core

/** One block of a document shown in the app. [bold] lists the character ranges of [text] to show in bold. */
sealed interface TextBlock {
    val text: String
    val bold: List<IntRange>

    data class Heading(val level: Int, override val text: String, override val bold: List<IntRange> = emptyList()) : TextBlock

    data class Paragraph(override val text: String, override val bold: List<IntRange> = emptyList()) : TextBlock

    /** A list item; [marker] is "•" or the item's number ("1."), and [depth] is 0 for a top-level item. */
    data class ListItem(
        val marker: String,
        val depth: Int,
        override val text: String,
        override val bold: List<IntRange> = emptyList(),
    ) : TextBlock
}

/**
 * Lays out the documents bundled with the app (privacy policy, license, notices) for a phone screen.
 * It understands only what those files use: headings, bullet and numbered lists, paragraphs, fenced
 * blocks, rules, **bold**, `code` and links (which are shown as text). Text that was wrapped for a
 * wide screen is joined back into flowing paragraphs.
 */
object SimpleMarkdown {
    private val HEADING = Regex("""^ {0,3}(#{1,6})\s+(.*?)(?:\s+#+)?\s*$""")
    private val FENCE = Regex("""^ {0,3}(```|~~~)""")
    private val RULE = Regex("""^ {0,3}([-*_])(?:\s*\1){2,}\s*$""")
    private val LIST_ITEM = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")
    private val DECORATION = Regex("""^\s*[-=_*~]{3,}\s*$""")
    private val SPACES = Regex(" {2,}")
    private val LINK = Regex("""\[([^\]]+)]\(([^)\s]+)\)""")
    private val AUTOLINK = Regex("""<((?:https?|mailto):[^>\s]+)>""")
    private val CODE = Regex("`([^`]+)`")
    private val BOLD = Regex("""\*\*(.+?)\*\*""")

    /** Parses Markdown. Lines of a paragraph are joined as Markdown does; fenced blocks are read as [parsePlain]. */
    fun parse(markdown: String): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        val lines = markdown.lines()
        // The paragraph or list item being collected: its marker (null for a paragraph), depth and lines.
        var marker: String? = null
        var depth = 0
        val pending = StringBuilder()
        // Whether the last line ended with a hard line break: two spaces or a backslash.
        var hardBreak = false
        fun flush() {
            if (pending.isNotEmpty()) {
                val (text, bold) = inline(pending.toString())
                blocks += marker?.let { TextBlock.ListItem(it, depth, text, bold) } ?: TextBlock.Paragraph(text, bold)
            }
            pending.clear()
            marker = null
            hardBreak = false
        }
        fun append(line: String) {
            if (pending.isNotEmpty()) pending.append(if (hardBreak) '\n' else ' ')
            hardBreak = line.endsWith("  ") || line.endsWith("\\")
            pending.append(line.trim().removeSuffix("\\").trimEnd())
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = FENCE.find(line)
            val heading = HEADING.find(line)
            val item = LIST_ITEM.find(line)
            when {
                line.isBlank() -> flush()
                fence != null -> {
                    flush()
                    val body = mutableListOf<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith(fence.groupValues[1])) body += lines[i++]
                    // Titles inside a quoted license text rank below the document's own headings.
                    blocks += plain(body, headingLevel = 4)
                }
                heading != null -> {
                    flush()
                    val (text, bold) = inline(heading.groupValues[2])
                    blocks += TextBlock.Heading(heading.groupValues[1].length, text, bold)
                }
                RULE.matches(line) -> flush()
                item != null -> {
                    flush()
                    val symbol = item.groupValues[2]
                    marker = if (symbol[0].isDigit()) symbol.dropLast(1) + "." else "•"
                    depth = minOf(item.groupValues[1].length / 2, 3)
                    append(item.groupValues[3])
                }
                // Any other line continues the paragraph or list item being collected.
                else -> append(line)
            }
            i++
        }
        flush()
        return blocks
    }

    /**
     * Parses plain text such as the GPL: paragraphs are separated by blank lines, and a short title
     * indented by 8 or more spaces (a centered one) becomes a heading.
     */
    fun parsePlain(text: String): List<TextBlock> = plain(text.lines(), headingLevel = 2)

    private fun plain(lines: List<String>, headingLevel: Int): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        val width = typicalWidth(lines)
        val paragraph = mutableListOf<String>()
        fun flush() {
            if (paragraph.isEmpty()) return
            val centered = paragraph.size <= 3 && paragraph.all { indent(it) >= 8 } && "://" !in paragraph[0]
            blocks += if (centered) {
                TextBlock.Heading(headingLevel, paragraph.joinToString("\n") { it.trim() })
            } else {
                // A line much shorter than the others ends where its author wanted a new line. Runs
                // of spaces (two after a full stop, for example) are typewriter habits: one is enough.
                val joined = StringBuilder(paragraph[0].trim().replace(SPACES, " "))
                for (index in 1 until paragraph.size) {
                    joined.append(if (paragraph[index - 1].trimEnd().length < width * 6 / 10) '\n' else ' ')
                    joined.append(paragraph[index].trim().replace(SPACES, " "))
                }
                TextBlock.Paragraph(joined.toString())
            }
            paragraph.clear()
        }
        for (line in lines) {
            when {
                line.isBlank() -> flush()
                DECORATION.matches(line) -> Unit
                else -> paragraph += line
            }
        }
        flush()
        return blocks
    }

    /** The width the text was wrapped at: the length most lines stay within. */
    private fun typicalWidth(lines: List<String>): Int {
        val lengths = lines.filter { it.isNotBlank() }.map { it.trimEnd().length }.sorted()
        return if (lengths.isEmpty()) 0 else lengths[lengths.size * 9 / 10]
    }

    private fun indent(line: String) = line.length - line.trimStart().length

    /** Applies inline Markdown: links become their text (and address), code loses its backticks, and bold is noted. */
    internal fun inline(source: String): Pair<String, List<IntRange>> {
        var text = LINK.replace(source) { match ->
            val (label, url) = match.destructured
            if (label == url || "mailto:$label" == url) label else "$label ($url)"
        }
        text = AUTOLINK.replace(text) { it.groupValues[1].removePrefix("mailto:") }
        text = CODE.replace(text) { it.groupValues[1] }
        val out = StringBuilder()
        val bold = mutableListOf<IntRange>()
        var last = 0
        for (match in BOLD.findAll(text)) {
            out.append(text, last, match.range.first)
            val inner = match.groupValues[1]
            bold += out.length until out.length + inner.length
            out.append(inner)
            last = match.range.last + 1
        }
        out.append(text, last, text.length)
        return out.toString() to bold
    }
}
