package io.github.nath_goa.usugami.ui.kit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// A desk of thin paper: a pale lilac ground; on it vellum sheets, each holding one group of related
// controls divided by hairlines; wells cut into the paper for everything typed, chosen or slid. Depth
// comes from what lies on what, never from shadows.

/**
 * Usugami's colours beyond Material's scheme, read as [Usugami.colors]. Translucent only where a
 * comment says so; the rest are solid.
 */
@Immutable
class UsugamiColors(
    /** The screen's background (= Material's `background`). */
    val ground: Color,
    /** A sheet (= Material's `surface`). */
    val sheet: Color,
    /** Dialogs, menus, bottom sheets, the result's file sheet. */
    val sheetTop: Color,
    /** The bottom action bar and the reader's Copy bar. */
    val actionBar: Color,
    /** Text fields, the segmented track and progress tracks. */
    val well: Color,
    /** The 1 dp edge of a well (dark only: a darker step on near black is hard to see). */
    val wellEdge: Color,
    /** A primary or tonal button that can't be used: the well in light; in dark a step lighter than what it lies on, as a darker one reads as a hole in the bar (translucent). */
    val disabledFill: Color,
    /** Behind the reader's pages. */
    val desk: Color,
    /** Sheet edges and dividers (translucent). */
    val hairline: Color,
    /** Paper buttons and chips, the drag handle, dashed blank sheets (translucent). */
    val hairlineStrong: Color,
    /** The fill of paper buttons, chips and paper icon buttons (translucent). */
    val paper: Color,
    /** The segmented thumb and the slider thumb. */
    val thumb: Color,
    /** The first under-sheet of a stack. */
    val fan1: Color,
    /** The second under-sheet of a stack. */
    val fan2: Color,
    /** The Scan block, selected tiles, the selection bar, a tool pill (= `primaryContainer`). */
    val lilac: Color,
    /** Text and glyphs on [lilac] (= `onPrimaryContainer`). */
    val onLilac: Color,
    /** Secondary text on [lilac]. */
    val onLilacVariant: Color,
    /** The reader's page pill and the page-number badge: over white pages in both themes (solid: what is on the page must not show through). */
    val pill: Color,
    /** Text on the [pill]. */
    val onPill: Color,
    /** The 1 dp edge of the pill (translucent). */
    val pillEdge: Color,
    /** Behind a modal bottom sheet (translucent). */
    val scrim: Color,
    /** The opaque backdrop behind the screens; a screen at 88 % alpha over it is "dimmed 12 %". */
    val screenDim: Color,
    /** The fill of a pressed row or tile (translucent). */
    val pressTint: Color,
    /** The hairline round real paper (translucent). */
    val pageEdge: Color,
    /** Everything drawn on real paper or a photo: frames, handles, crop outlines. */
    val paperInk: Color,
    /** The keyboard and D-pad focus outline (= `primary`). */
    val focus: Color,
    val isDark: Boolean,
)

internal val LightUsugamiColors = UsugamiColors(
    ground = Color(0xFFF1ECFA),
    sheet = Color(0xFFFBFAFE),
    sheetTop = Color(0xFFFFFFFF),
    actionBar = Color(0xFFFCFAFE),
    well = Color(0xFFE4DEEF),
    wellEdge = Color(0x00000000),
    disabledFill = Color(0xFFE4DEEF),
    desk = Color(0xFFE4DEEF),
    hairline = Color(0x214A3F6B),
    hairlineStrong = Color(0x3D4A3F6B),
    paper = Color(0xB8FFFFFF),
    thumb = Color(0xFFFFFFFF),
    fan1 = Color(0xFFD8CBF1),
    fan2 = Color(0xFFC9BBE9),
    lilac = Color(0xFFE8DEF8),
    onLilac = Color(0xFF2A2243),
    onLilacVariant = Color(0xFF575268),
    pill = Color(0xFF4A3F6B),
    onPill = Color(0xFFF4EFFC),
    pillEdge = Color(0x00000000),
    scrim = Color(0x521B1924),
    screenDim = Color(0xFF1B1924),
    pressTint = Color(0x0F4A3F6B),
    pageEdge = Color(0x21000000),
    paperInk = Color(0xFF4A3F6B),
    focus = Color(0xFF4A3F6B),
    isDark = false,
)

internal val DarkUsugamiColors = UsugamiColors(
    ground = Color(0xFF141318),
    sheet = Color(0xFF232227),
    sheetTop = Color(0xFF2E2D31),
    actionBar = Color(0xFF242329),
    well = Color(0xFF0C0B0E),
    wellEdge = Color(0x17FFFFFF),
    disabledFill = Color(0x14FFFFFF),
    desk = Color(0xFF0C0B0E),
    hairline = Color(0x17FFFFFF),
    hairlineStrong = Color(0x2EFFFFFF),
    paper = Color(0x14FFFFFF),
    thumb = Color(0xFF3C3A45),
    fan1 = Color(0xFF3A3155),
    fan2 = Color(0xFF2C2640),
    lilac = Color(0xFF4A3F6B),
    onLilac = Color(0xFFEFE8FC),
    onLilacVariant = Color(0xFFD3CBE8),
    pill = Color(0xFF4A3F6B),
    onPill = Color(0xFFF4EFFC),
    pillEdge = Color(0x29FFFFFF),
    scrim = Color(0x8C000000),
    screenDim = Color(0xFF000000),
    pressTint = Color(0x14FFFFFF),
    pageEdge = Color(0x21000000),
    paperInk = Color(0xFF4A3F6B),
    focus = Color(0xFFE8DEF8),
    isDark = true,
)

val LocalUsugamiColors = staticCompositionLocalOf { LightUsugamiColors }
val LocalUsugamiType = staticCompositionLocalOf { usugamiType(devanagari = false) }

/** Usugami's own colours and text styles, as [androidx.compose.material3.MaterialTheme] gives Material's. */
object Usugami {
    val colors: UsugamiColors
        @Composable @ReadOnlyComposable get() = LocalUsugamiColors.current
    val type: UsugamiType
        @Composable @ReadOnlyComposable get() = LocalUsugamiType.current
}

/** Material's shape slots: a dialog (which reads `extraLarge`) is 24 without help. */
val UsugamiShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// Rectangles hold, pills act, real paper is square.

/** Sheets, the Scan block, blank sheets. */
val SheetShape = RoundedCornerShape(16.dp)

/** Page cells, look samples, tool buttons. */
val TileShape = RoundedCornerShape(12.dp)

/** Text fields, the segmented track. */
val WellShape = RoundedCornerShape(12.dp)

/** The segmented thumb and a segment's press tint (12 − 4, concentric with the track). */
val ThumbShape = RoundedCornerShape(8.dp)

/** The bottom action bar. */
val ActionBarShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 0.dp, bottomStart = 0.dp)

/** A modal bottom sheet. */
val BottomSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 0.dp, bottomStart = 0.dp)

/** Dialogs. */
val DialogShape = RoundedCornerShape(24.dp)

/** Page thumbnails. */
val PaperShape = RoundedCornerShape(2.dp)

/** Spacing. */
object Space {
    /** The screen margin: sheets, wells and buttons span 16 … width − 16. */
    val screen = 16.dp

    /** The extra inset of text set on the ground (so it starts 24 from the edge). */
    val ground = 8.dp

    /** Padding inside a sheet. */
    val sheet = 16.dp

    /** Between sibling blocks (sheet to sheet, sheet to well). */
    val gap = 12.dp

    /** Around a pair of buttons or a lone well. */
    val gapWide = 16.dp

    /** From the top bar to the first content. */
    val bar = 8.dp

    /** Above a section heading (Home: 16). */
    val headingTop = 24.dp

    /** Below a section heading (Home: 8). */
    val headingBottom = 10.dp

    /** A row's icon. */
    val icon = 24.dp

    /** The gap after a row's icon. */
    val iconGap = 16.dp

    /** Where an icon row's divider starts inside its sheet (16 + 24 + 16). */
    val dividerInset = 56.dp

    /** Free room above anything that carries a stack. */
    val stack = 18.dp

    /** The widest content, centred (the result keeps 480). */
    val content = 560.dp
}
