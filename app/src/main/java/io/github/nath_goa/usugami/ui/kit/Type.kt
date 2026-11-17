package io.github.nath_goa.usugami.ui.kit

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

// The system font, in weights 400 and 500 only. Larger is lighter and tighter; smaller is firmer.
// Hindi keeps the sizes and weights, with no tracking and taller lines for the Devanagari marks.

/** Usugami's text styles beyond Material's slots, read as [Usugami.type]. */
@Immutable
class UsugamiType(
    /** Section headings on the ground. */
    val section: TextStyle,
) {
    /** [style] with tabular figures: for digits that change in place or line up. */
    fun tabular(style: TextStyle) = style.copy(fontFeatureSettings = "tnum")
}

/**
 * The lines of every style as Material sets its own: the line height centred on the text and kept
 * whole above the first line and below the last, so a line of 22 sp takes 22 sp.
 */
private val WholeLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Float, line: Int, hindiLine: Int, weight: Int, tracking: Float, devanagari: Boolean) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = (if (devanagari) hindiLine else line).sp,
    letterSpacing = if (devanagari) 0.sp else tracking.sp,
    lineHeightStyle = WholeLines,
)

/** Material's type slots in Usugami's sizes; [devanagari] for Hindi. */
fun usugamiTypography(devanagari: Boolean): Typography {
    val display = style(30f, 36, 40, 400, -0.4f, devanagari)
    val headline = style(24f, 30, 34, 400, -0.25f, devanagari)
    return Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = headline,
        // The wordmark: "Usugami" in Home's bar only.
        headlineMedium = style(26f, 32, 36, 500, -0.5f, devanagari),
        headlineSmall = headline,
        titleLarge = style(20f, 26, 30, 500, -0.15f, devanagari),
        titleMedium = style(16f, 22, 26, 500, 0f, devanagari),
        titleSmall = style(15f, 20, 24, 500, 0f, devanagari),
        bodyLarge = style(16f, 22, 26, 400, 0f, devanagari),
        bodyMedium = style(14f, 20, 24, 400, 0.1f, devanagari),
        bodySmall = style(13f, 18, 22, 400, 0.1f, devanagari),
        labelLarge = style(14f, 20, 22, 500, 0.1f, devanagari),
        labelMedium = style(12f, 16, 20, 500, 0.3f, devanagari),
        labelSmall = style(11f, 16, 18, 500, 0.4f, devanagari),
    )
}

/** Usugami's own styles; [devanagari] for Hindi. */
fun usugamiType(devanagari: Boolean): UsugamiType =
    UsugamiType(section = style(17f, 22, 26, 500, -0.1f, devanagari))
