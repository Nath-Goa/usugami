package io.github.nath_goa.usugami.ui

import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.text.BreakIterator

// A label in a narrow place (a long translation, a large font size) may take more lines, breaking
// between its words, but a word too wide for its line would be broken inside it: "aufnehme" over
// "n". Such a label shrinks instead, in 0.5 sp steps, as far as its widest word needs.
// TextAutoSize.StepBased can't see this: a word broken over two lines doesn't overflow.

/** The smallest a label shrinks to, as segmented buttons' labels do; a word still too wide breaks. */
private val SmallestWholeWords = 8.sp

/**
 * Auto-size for a label that keeps its words whole: from [maxFontSize] (its style's size), it shrinks
 * only while a line of it ends inside a word, or it overflows (with one line, while it's cut short).
 * Its style wants [withEmLineHeight], or a smaller label on two lines keeps them as far apart as before.
 */
internal data class WholeWordsAutoSize(val maxFontSize: TextUnit) : TextAutoSize {
    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit =
        largestWhole(maxFontSize) { size ->
            val layout = performLayout(constraints, text, size)
            !layout.hasVisualOverflow && !layout.breaksAWord()
        }
}

/**
 * One size for [labels] side by side, each [width] wide, in [style]: [style]'s own, or as much smaller
 * as the one that needs it most to keep its words whole, so the labels stay alike. [width] is worked
 * out from paddings the layout rounds to whole pixels, so give each label [WholeWordsAutoSize] from
 * this size too: one whose word then misses its line by a pixel goes a step smaller.
 */
@Composable
internal fun wholeWordsFontSize(labels: List<String>, style: TextStyle, width: Dp): TextUnit {
    val measurer = rememberTextMeasurer()
    val widthPx = with(LocalDensity.current) { width.toPx().toInt() }.coerceAtLeast(0)
    return remember(labels, style, widthPx, measurer) {
        largestWhole(style.fontSize) { size ->
            labels.none { measurer.measure(it, style.copy(fontSize = size), constraints = Constraints(maxWidth = widthPx)).breaksAWord() }
        }
    }
}

/**
 * This style with its line height relative to its font size, the same at that size: a label made
 * smaller keeps its lines as close as its letters are small.
 */
internal fun TextStyle.withEmLineHeight(): TextStyle =
    if (fontSize.isSp && lineHeight.isSp) copy(lineHeight = (lineHeight.value / fontSize.value).em) else this

/** The largest size from [maxFontSize] down, in 0.5 sp steps, that [fits], or [SmallestWholeWords]. */
private inline fun largestWhole(maxFontSize: TextUnit, fits: (TextUnit) -> Boolean): TextUnit {
    var size = maxFontSize.value
    while (size > SmallestWholeWords.value && !fits(size.sp)) size -= 0.5f
    return size.coerceAtLeast(SmallestWholeWords.value).sp
}

/** Whether a line of this text ends where the text can't break: inside a word too wide for its line. */
private fun TextLayoutResult.breaksAWord(): Boolean {
    if (lineCount < 2) return false
    val breaks = BreakIterator.getLineInstance().apply { setText(layoutInput.text.text) }
    return (0 until lineCount - 1).any { !breaks.isBoundary(getLineEnd(it)) }
}
