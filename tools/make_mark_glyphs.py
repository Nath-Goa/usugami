"""Writes core/src/main/java/io/github/nath_goa/usugami/core/MarkGlyphs.kt, the outlines of the word
"Usugami" that the Usugami watermark draws on a PDF's pages. Drawn as filled shapes rather than
written as text, the word isn't found by a PDF's text search, copied with its text or read out by
screen readers.

The source font is Liberation Sans Bold 2.1.5 (SIL OFL 1.1), from liberation-fonts-ttf-2.1.5.tar.gz
at https://github.com/liberationfonts/liberation-fonts/releases (its SHA-256 is below). Its letters
are as wide as Helvetica Bold's, the font the watermark was written in before.

    python -m pip install fonttools==4.66.1
    python -I tools/make_mark_glyphs.py LiberationSans-Bold.ttf \
        core/src/main/java/io/github/nath_goa/usugami/core/MarkGlyphs.kt

The build doesn't run it: the Kotlin file it writes is kept with the source code.
"""
import hashlib
import sys

from fontTools.pens.basePen import BasePen
from fontTools.ttLib import TTFont

WORD = "Usugami"
SOURCE_SHA256 = "788abee4c806d660e8aee46689dd8540cd4bb98da03dcc9d171ce3efd99a9173"


def number(value):
    """[value] as briefly as it is exact: TrueType's implied points fall on half units."""
    return str(int(value)) if value == int(value) else repr(float(value))


class OutlinePen(BasePen):
    """Records a glyph's contours, moved right by [dx], as M, L, Q and Z commands."""

    def __init__(self, glyph_set, dx):
        super().__init__(glyph_set)
        self.dx = dx
        self.commands = []

    def point(self, pt):
        return f"{number(pt[0] + self.dx)} {number(pt[1])}"

    def _moveTo(self, pt):
        self.commands.append("M " + self.point(pt))

    def _lineTo(self, pt):
        self.commands.append("L " + self.point(pt))

    def _qCurveToOne(self, pt1, pt2):
        self.commands.append(f"Q {self.point(pt1)} {self.point(pt2)}")

    def _curveToOne(self, pt1, pt2, pt3):
        raise ValueError("TrueType outlines have no cubic curves")

    def _closePath(self):
        self.commands.append("Z")


def main(font_path, out_path):
    data = open(font_path, "rb").read()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        sys.exit(f"{font_path} isn't Liberation Sans Bold 2.1.5")
    font = TTFont(font_path)
    glyph_set = font.getGlyphSet()
    cmap = font.getBestCmap()
    glyf = font["glyf"]
    lines = []
    x = 0
    x_min = y_min = float("inf")
    x_max = y_max = float("-inf")
    for letter in WORD:
        name = cmap[ord(letter)]
        pen = OutlinePen(glyph_set, x)
        glyph_set[name].draw(pen)
        lines.append((letter, " ".join(pen.commands)))
        glyph = glyf[name]
        x_min, y_min = min(x_min, x + glyph.xMin), min(y_min, glyph.yMin)
        x_max, y_max = max(x_max, x + glyph.xMax), max(y_max, glyph.yMax)
        # Side by side by their advance widths, as text without kerning sets them.
        x += font["hmtx"][name][0]

    outline = "\n".join(
        f'        "{commands} "{" +" if i < len(lines) - 1 else ""} // {letter}' for i, (letter, commands) in enumerate(lines)
    )
    kotlin = f"""// Made by tools/make_mark_glyphs.py from Liberation Sans Bold 2.1.5 (SIL Open Font License 1.1,
// see THIRD_PARTY_NOTICES.md). Don't change it by hand: run the script again.
package io.github.nath_goa.usugami.core

/**
 * The word "{WORD}" in Liberation Sans Bold, as outlines: its letters side by side by their advance
 * widths, the baseline starting at the origin, y up, in font units ([UNITS_PER_EM] to the font size).
 */
internal object MarkGlyphs {{
    const val UNITS_PER_EM = {font["head"].unitsPerEm}

    /** The word's width: its letters' advance widths. */
    const val ADVANCE = {x}

    /** The outlines' bounds. */
    const val X_MIN = {number(x_min)}
    const val Y_MIN = {number(y_min)}
    const val X_MAX = {number(x_max)}
    const val Y_MAX = {number(y_max)}

    /**
     * Its contours, filled by the nonzero winding rule: "M x y" starts one, "L x y" draws a line to
     * (x, y), "Q x1 y1 x y" a quadratic curve through the control point (x1, y1), and "Z" closes it.
     */
    const val OUTLINE =
{outline}
}}
"""
    with open(out_path, "w", encoding="utf-8", newline="\n") as out:
        out.write(kotlin)
    print(out_path, len(kotlin), "characters, advance", x, "of", font["head"].unitsPerEm)


if __name__ == "__main__":
    main(*sys.argv[1:])
