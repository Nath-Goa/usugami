"""Writes core/src/main/java/io/github/nath_goa/usugami/core/MarkGlyphs.kt, the outlines the Usugami
watermark draws on a PDF's pages: the word "Usugami" as the banners write it, and the app's logo to
its right. Drawn as filled shapes rather than written as text, the word isn't found by a PDF's text
search, copied with its text or read out by screen readers.

The word is set as in the feature graphic and the Supporter cover: Segoe UI Semibold, letters closer
by 1/96 of the font size, no kerning (Segoe UI has none between these letters), in the deep purple
#4A3F6B. The source font is Segoe UI Semibold 5.72 as Windows 11 has it (C:/Windows/Fonts/seguisb.ttf,
its SHA-256 below). Microsoft lets logos be made with the fonts Windows comes with
(https://learn.microsoft.com/typography/fonts/font-faq): the word is the logo's, its seven letters'
outlines alone, and no font file is included.

The logo is the launcher icon (docs/icon.svg, app/src/main/res/drawable/ic_launcher_*.xml), its
circle a little taller than the word's capitals and centred on them: the lilac circle, the deep-purple
disc, the sheet of paper over it in white at 0.55, and the sheet's outline in the deep purple at 0.4,
here as the ring that outline covers. Circles and the sheet's round corners are drawn with quadratic
curves, close enough that no difference shows.

    python -m pip install fonttools==4.66.1
    python -I tools/make_mark_glyphs.py C:/Windows/Fonts/seguisb.ttf \
        core/src/main/java/io/github/nath_goa/usugami/core/MarkGlyphs.kt

The build doesn't run it: the Kotlin file it writes is kept with the source code.
"""
import hashlib
import math
import sys

from fontTools.pens.basePen import BasePen
from fontTools.ttLib import TTFont

WORD = "Usugami"
SOURCE_SHA256 = "6c3fd894b19950b10c34bf64069ef821eaf2f58f959146306fee5b367472e501"

# As the banners write the word: letter-spacing of -1/96 of the font size.
LETTER_SPACING_EM = -1 / 96

# The logo's circle, as tall as this many of the word's capital heights, centred on them.
LOGO_CAPS = 1.5
# From the word's end (its advance, the "i" and its side bearing) to the logo's circle, in font sizes.
GAP_EM = 0.27

PURPLE = 0x4A3F6B
LILAC = 0xE8DEF8
WHITE = 0xFFFFFF

# The icon, as docs/icon.svg draws it, in its own units: y down, its circle centred at (54, 54).
ICON_CENTRE = 54.0
ICON_RADIUS = 36.0
# The sheet's frame: centred here, leaning 8 degrees anticlockwise.
SHEET_AT = (61.54, 54.0)
SHEET_TURN = math.radians(-8)
# The sheet, in its frame: 30 x 40, corners of radius 2; its outline 1.4 wide.
SHEET_HALF_W, SHEET_HALF_H, SHEET_CORNER, SHEET_EDGE = 15.0, 20.0, 2.0, 1.4
# The disc, in the sheet's frame: radius 17, through (-15, +-16.93), centred inside the sheet's left edge.
DISC_RADIUS = 17.0
DISC_CENTRE = (-15 + math.sqrt(DISC_RADIUS ** 2 - 16.93 ** 2), 0.0)

# Quadratic curves per full turn of a circle; a quarter of them for each of the sheet's corners.
CIRCLE_SEGMENTS = 16


def number(value):
    """[value] to 2 decimals, as briefly as that is: font units, of which there are 2048 to the font size."""
    text = f"{value:.2f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


class Contour:
    """A closed contour: a start, then lines ("L", point) and quadratic curves ("Q", control, point)."""

    def __init__(self, start):
        self.start = start
        self.segments = []

    def line(self, point):
        self.segments.append(("L", point))

    def quad(self, control, point):
        self.segments.append(("Q", control, point))

    def mapped(self, f):
        out = Contour(f(self.start))
        out.segments = [(s[0],) + tuple(f(p) for p in s[1:]) for s in self.segments]
        return out

    def reversed(self):
        """The same contour the other way round: for a hole, under the nonzero winding rule."""
        points = [self.start] + [s[-1] for s in self.segments]
        out = Contour(points[-1])
        for i in range(len(self.segments) - 1, -1, -1):
            s = self.segments[i]
            out.segments.append(("L", points[i]) if s[0] == "L" else ("Q", s[1], points[i]))
        return out

    def commands(self):
        def at(p):
            return f"{number(p[0])} {number(p[1])}"

        parts = ["M " + at(self.start)]
        for s in self.segments:
            parts.append("L " + at(s[1]) if s[0] == "L" else f"Q {at(s[1])} {at(s[2])}")
        parts.append("Z")
        return " ".join(parts)


def arc(contour, centre, radius, start, end, segments):
    """Adds an arc of a circle to [contour], from angle [start] to [end] (radians), as [segments] quadratic curves."""
    step = (end - start) / segments
    for i in range(segments):
        a, b = start + i * step, start + (i + 1) * step
        m = (a + b) / 2
        reach = radius / math.cos((b - a) / 2)
        contour.quad(
            (centre[0] + reach * math.cos(m), centre[1] + reach * math.sin(m)),
            (centre[0] + radius * math.cos(b), centre[1] + radius * math.sin(b)),
        )


def circle(centre, radius):
    contour = Contour((centre[0] + radius, centre[1]))
    arc(contour, centre, radius, 0, 2 * math.pi, CIRCLE_SEGMENTS)
    return contour


def rounded_rect(half_w, half_h, r):
    """A rectangle centred on the origin with corners of radius [r], clockwise on a screen (y down)."""
    corner = CIRCLE_SEGMENTS // 4
    contour = Contour((-half_w + r, -half_h))
    contour.line((half_w - r, -half_h))
    arc(contour, (half_w - r, -half_h + r), r, -math.pi / 2, 0, corner)
    contour.line((half_w, half_h - r))
    arc(contour, (half_w - r, half_h - r), r, 0, math.pi / 2, corner)
    contour.line((-half_w + r, half_h))
    arc(contour, (-half_w + r, half_h - r), r, math.pi / 2, math.pi, corner)
    contour.line((-half_w, -half_h + r))
    arc(contour, (-half_w + r, -half_h + r), r, math.pi, 3 * math.pi / 2, corner)
    return contour


class OutlinePen(BasePen):
    """Records a glyph's contours, moved right by [dx]."""

    def __init__(self, glyph_set, dx):
        super().__init__(glyph_set)
        self.dx = dx
        self.contours = []

    def point(self, pt):
        return (pt[0] + self.dx, pt[1])

    def _moveTo(self, pt):
        self.contours.append(Contour(self.point(pt)))

    def _lineTo(self, pt):
        self.contours[-1].line(self.point(pt))

    def _qCurveToOne(self, pt1, pt2):
        self.contours[-1].quad(self.point(pt1), self.point(pt2))

    def _curveToOne(self, pt1, pt2, pt3):
        raise ValueError("TrueType outlines have no cubic curves")

    def _closePath(self):
        pass


def build(font):
    """The mark's layers [(colour, opacity, name, contours)], its advance, and its ink's bounds."""
    upm = font["head"].unitsPerEm
    glyph_set = font.getGlyphSet()
    cmap = font.getBestCmap()
    spacing = LETTER_SPACING_EM * upm
    word = []
    x = 0.0
    for i, letter in enumerate(WORD):
        name = cmap[ord(letter)]
        pen = OutlinePen(glyph_set, x)
        glyph_set[name].draw(pen)
        word += pen.contours
        x += font["hmtx"][name][0] + (spacing if i < len(WORD) - 1 else 0)
    word_end = x

    cap = font["OS/2"].sCapHeight
    diameter = LOGO_CAPS * cap
    scale = diameter / (2 * ICON_RADIUS)
    left = word_end + GAP_EM * upm
    centre = (left + diameter / 2, cap / 2)

    def icon(p):
        """From the icon's units (y down) to the word's (y up)."""
        return (centre[0] + (p[0] - ICON_CENTRE) * scale, centre[1] - (p[1] - ICON_CENTRE) * scale)

    cos, sin = math.cos(SHEET_TURN), math.sin(SHEET_TURN)

    def sheet(p):
        """From the sheet's frame to the icon's units."""
        return (SHEET_AT[0] + p[0] * cos - p[1] * sin, SHEET_AT[1] + p[0] * sin + p[1] * cos)

    def in_sheet(contour):
        return contour.mapped(lambda p: icon(sheet(p)))

    half = SHEET_EDGE / 2
    layers = [
        (PURPLE, 1.0, "the word", word),
        (LILAC, 1.0, "the logo's circle", [circle((ICON_CENTRE, ICON_CENTRE), ICON_RADIUS).mapped(icon)]),
        (PURPLE, 1.0, "the disc", [in_sheet(circle(DISC_CENTRE, DISC_RADIUS))]),
        (WHITE, 0.55, "the sheet", [in_sheet(rounded_rect(SHEET_HALF_W, SHEET_HALF_H, SHEET_CORNER))]),
        (
            PURPLE,
            0.4,
            "the sheet's outline",
            [
                in_sheet(rounded_rect(SHEET_HALF_W + half, SHEET_HALF_H + half, SHEET_CORNER + half)),
                in_sheet(rounded_rect(SHEET_HALF_W - half, SHEET_HALF_H - half, SHEET_CORNER - half)).reversed(),
            ],
        ),
    ]
    xs, ys = [], []
    for _, _, _, contours in layers:
        for c in contours:
            for p in [c.start] + [q for s in c.segments for q in s[1:]]:
                xs.append(p[0])
                ys.append(p[1])
    # The circle's right edge: nothing reaches further.
    advance = centre[0] + diameter / 2
    return layers, upm, advance, (min(xs), min(ys), max(xs), max(ys)), word_end, cap


def main(font_path, out_path):
    data = open(font_path, "rb").read()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        sys.exit(f"{font_path} isn't Segoe UI Semibold 5.72")
    font = TTFont(font_path)
    layers, upm, advance, (x_min, y_min, x_max, y_max), word_end, cap = build(font)

    def float_literal(value):
        return number(value) + ("f" if "." in number(value) else ".0f")

    blocks = []
    for colour, opacity, name, contours in layers:
        lines = [f'            "{c.commands()} "' for c in contours]
        body = " +\n".join(lines)
        blocks.append(f"        // {name[0].upper()}{name[1:]}.\n        MarkLayer(0x{colour:06X}, {opacity}f,\n{body},\n        ),")
    kotlin = f"""// Made by tools/make_mark_glyphs.py from Segoe UI Semibold 5.72 and the app's icon (docs/icon.svg).
// Don't change it by hand: run the script again.
package io.github.nath_goa.usugami.core

/**
 * The Usugami watermark as outlines: the word "{WORD}" in Segoe UI Semibold as the banners set it,
 * then the app's logo, a circle {LOGO_CAPS} times the capitals' height centred on them. The word's
 * baseline starts at the origin, y up, in font units ([UNITS_PER_EM] to the font size).
 */
internal object MarkGlyphs {{
    const val UNITS_PER_EM = {upm}

    /** The mark's width: the word ({number(word_end)}, its letters' advance widths), a gap, and the logo's circle. */
    const val ADVANCE = {float_literal(advance)}

    /** The capitals' height, which the logo's circle is centred on. */
    const val CAP_HEIGHT = {cap}

    /** The outlines' bounds. */
    const val X_MIN = {float_literal(x_min)}
    const val Y_MIN = {float_literal(y_min)}
    const val X_MAX = {float_literal(x_max)}
    const val Y_MAX = {float_literal(y_max)}

    /**
     * The shapes, painted in this order, each filled in its colour (0xRRGGBB) at its opacity by the
     * nonzero winding rule: "M x y" starts a contour, "L x y" draws a line to (x, y), "Q x1 y1 x y" a
     * quadratic curve through the control point (x1, y1), and "Z" closes it.
     */
    val LAYERS = listOf(
{chr(10).join(blocks)}
    )
}}
"""
    with open(out_path, "w", encoding="utf-8", newline="\n") as out:
        out.write(kotlin)
    print(out_path, len(kotlin), "characters; advance", number(advance), "word", number(word_end), "of", upm,
          "bounds", number(x_min), number(y_min), number(x_max), number(y_max))


if __name__ == "__main__":
    main(*sys.argv[1:])
