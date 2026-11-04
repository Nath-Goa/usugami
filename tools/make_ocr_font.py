"""Builds app/src/main/assets/fonts/UsugamiOcr-Regular.ttf, the glyph-less font for Usugami's invisible
OCR text layer.

The text layer is drawn with rendering mode 3 (invisible), so glyph outlines are never used. What
matters is that every character ML Kit or Usugami OCR's PP-OCRv5 models can return has a glyph (or
PDFBox throws and PdfOcr writes a space) and a sensible advance width (for selection and search
boxes). This keeps the cmap and widths of Noto Sans and Noto Sans Devanagari, empties every outline,
and adds empty glyphs for the rest of the ranges below, then for every character of the PP-OCRv5
dictionaries still missing (as a last step, after the others: a font made before can be brought up
to date with that step alone, see add_dictionaries). Same idea as Tesseract's GlyphLessFont, but
with one glyph per character so that PDFBox's ToUnicode map stays exact.

The source fonts are Noto Sans 2.008 and Noto Sans Devanagari 2.002 (SIL OFL 1.1), from
https://github.com/notofonts/latin-greek-cyrillic and https://github.com/notofonts/devanagari.

    python -m pip install fonttools==4.66.1
    python -I tools/make_ocr_font.py NotoSans-Regular.ttf NotoSansDevanagari-Regular.ttf \
        app/src/main/assets/fonts/UsugamiOcr-Regular.ttf app/src/ocr/assets/ocr/*.dict.txt

Only the dictionaries' characters, added to the font as it is (after a model update, say):

    python -I tools/make_ocr_font.py --add-dictionaries app/src/main/assets/fonts/UsugamiOcr-Regular.ttf \
        app/src/ocr/assets/ocr/*.dict.txt
"""
import sys
import unicodedata

from fontTools import subset
from fontTools.merge import Merger
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.ttLib import TTFont

# Everything the ML Kit Latin and Devanagari recognizers can output (taken from the label maps in
# text-recognition 16.0.1 / text-recognition-devanagari 16.0.1), widened to whole Unicode blocks so
# that model updates delivered by Google Play services are covered too.
RANGES = [
    (0x0020, 0x007E), (0x00A0, 0x024F), (0x0250, 0x02FF), (0x0300, 0x036F), (0x0370, 0x03FF),
    (0x0900, 0x097F), (0x0980, 0x09FF), (0x1CD0, 0x1CFF), (0x1E00, 0x1EFF), (0x2000, 0x206F),
    (0x20A0, 0x20CF), (0x2100, 0x218F), (0x2190, 0x21FF), (0x2200, 0x22FF), (0x2500, 0x25FF),
    (0x2600, 0x27BF), (0xA8E0, 0xA8FF), (0xFB00, 0xFB06),
]


def subset_options():
    o = subset.Options()
    o.hinting = False
    o.layout_features = []
    o.drop_tables += ["STAT", "gasp", "DSIG", "GDEF", "GPOS", "GSUB"]
    o.notdef_outline = True
    o.name_IDs = [0, 1, 2, 3, 4, 5, 6, 13, 14]
    o.name_languages = [0x409]
    return o


def subset_font(path, codepoints):
    o = subset_options()
    font = subset.load_font(path, o)
    s = subset.Subsetter(o)
    s.populate(unicodes=codepoints)
    s.subset(font)
    return font


def dictionary_characters(paths):
    """Every character of the PP-OCRv5 dictionaries at [paths], one entry per line: Usugami OCR's
    recognizers output exactly these, whatever their Unicode category (U+F8FF, private use, too)."""
    return {ord(c) for path in paths for line in open(path, encoding="utf-8").read().splitlines() for c in line}


def add_empty_glyphs(font, codepoints):
    """Gives each of [codepoints] an empty glyph, after the glyphs there, in code point order."""
    glyf, hmtx = font["glyf"], font["hmtx"]
    cmap = font.getBestCmap()
    order = list(font.getGlyphOrder())
    for cp in sorted(codepoints):
        # Bengali mirrors the Devanagari block 0x80 lower; marks take no space; the rest ~ a digit.
        analog = cmap.get(cp - 0x80) if 0x0980 <= cp <= 0x09FF else None
        mark = unicodedata.category(chr(cp)) in ("Mn", "Me")
        width = hmtx[analog][0] if analog else (0 if mark else 600)
        glyph = f"uni{cp:04X}"
        order.append(glyph)
        glyf[glyph] = TTGlyphPen(None).glyph()
        hmtx[glyph] = (width, 0)
        for table in font["cmap"].tables:
            if table.isUnicode() and table.format != 14:
                table.cmap[cp] = glyph
    font.setGlyphOrder(order)
    font["maxp"].numGlyphs = len(order)


def add_dictionaries(font, dictionary_paths):
    """The last step: an empty glyph for each character of the dictionaries the font doesn't have.
    Characters beyond U+FFFF (two mathematical italic letters) are left out: PDFBox's font subsetter
    writes a format 4 cmap only, and fails on them. PdfOcr writes their compatibility form instead
    (NFKC: "u" for MATHEMATICAL ITALIC SMALL U)."""
    wanted = {cp for cp in dictionary_characters(dictionary_paths) if cp <= 0xFFFF}
    add_empty_glyphs(font, wanted - set(font.getBestCmap()))


def report(out_path):
    import os
    check = TTFont(out_path)
    print(out_path, os.path.getsize(out_path), "bytes,", len(check.getBestCmap()), "characters,",
          check["maxp"].numGlyphs, "glyphs")


def update(font_path, dictionary_paths):
    """Brings the font made before at [font_path] up to date with the dictionaries' characters."""
    font = TTFont(font_path)
    add_dictionaries(font, dictionary_paths)
    font.recalcBBoxes = False
    font.recalcTimestamp = False
    font.save(font_path)
    report(font_path)


def main(latin_path, devanagari_path, out_path, *dictionary_paths):
    wanted = {cp for a, b in RANGES for cp in range(a, b + 1)
              if unicodedata.category(chr(cp)) not in ("Cn", "Co", "Cs")}
    latin_cmap = set(TTFont(latin_path).getBestCmap())
    parts = []
    for path, cps in ((latin_path, wanted & latin_cmap), (devanagari_path, wanted - latin_cmap)):
        part = subset_font(path, cps)
        tmp = out_path + f".part{len(parts)}.ttf"
        part.save(tmp)
        parts.append(tmp)
    font = Merger().merge(parts)  # Noto Sans first, so it wins for shared characters

    names = font["name"]
    names.names = [n for n in names.names if n.nameID in (0, 13, 14)]
    for name_id, value in ((1, "Usugami OCR"), (2, "Regular"), (3, "UsugamiOCR-Regular-1.0"),
                           (4, "Usugami OCR Regular"), (5, "Version 1.0; glyph-less, from Noto Sans 2.008 and Noto Sans Devanagari 2.002"),
                           (6, "UsugamiOCR-Regular")):
        names.setName(value, name_id, 3, 1, 0x409)

    glyf = font["glyf"]
    for glyph in font.getGlyphOrder():
        if glyph != ".notdef":
            glyf[glyph] = TTGlyphPen(None).glyph()
    add_empty_glyphs(font, wanted - set(font.getBestCmap()))
    add_dictionaries(font, dictionary_paths)
    font["post"].formatType = 3.0  # no glyph names
    font.recalcBBoxes = False  # keep Noto's bounding box: PDFBox writes it to /FontBBox
    # Reproducible output: take the dates from the source font instead of the build time.
    source_head = TTFont(latin_path)["head"]
    font["head"].created = source_head.created
    font["head"].modified = source_head.modified
    font.recalcTimestamp = False
    font.save(out_path)

    import os
    for tmp in parts:
        os.remove(tmp)
    report(out_path)


if __name__ == "__main__":
    if sys.argv[1] == "--add-dictionaries":
        update(sys.argv[2], sys.argv[3:])
    else:
        main(*sys.argv[1:])
