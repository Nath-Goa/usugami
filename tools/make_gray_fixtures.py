"""
Writes the two pictures that GrayDeviceTest (picturesCompressAloneLeavesAsTheyAreTurnGrayToo) puts
in PDFs by hand to check Black and white's route F, the pictures only Android's PDF renderer reads,
since Android can make neither kind:

- gray_cmyk.jpg: a 32 by 32 four-component (CMYK) JPEG, its left half one flat colour and its
  right half another
- gray_color.jp2: a 32 by 32 RGB JPEG 2000 (a JP2 file, lossless), its left half red and its right
  half blue

into app/src/androidTest/assets/. The halves meet at x = 16, on the edge of the JPEG's 8 by 8
blocks, so nothing rings across it. The test compares the gray pictures made from them with the
original's render, so which colours these are doesn't matter, only that the halves differ.

Needs Pillow built with OpenJPEG (as its wheels are); the committed files were made with Pillow
12.3.0, run from the repository's root:

    python tools/make_gray_fixtures.py
"""
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "androidTest" / "assets"


def halves(mode, left, right):
    """A 32 by 32 picture in [mode], [left] in its left half and [right] in its right half."""
    picture = Image.new(mode, (32, 32), left)
    picture.paste(right, (16, 0, 32, 32))
    return picture


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cmyk = OUT / "gray_cmyk.jpg"
    # Inks with some black in both halves, so that the halves still differ with every ink inverted.
    halves("CMYK", (0, 200, 200, 60), (200, 60, 0, 20)).save(cmyk, "JPEG", quality=95)
    jp2 = OUT / "gray_color.jp2"
    halves("RGB", (255, 0, 0), (0, 0, 255)).save(jp2, "JPEG2000", irreversible=False)

    data = cmyk.read_bytes()
    assert data[:2] == b"\xff\xd8", "not a JPEG"
    with Image.open(cmyk) as check:
        assert check.mode == "CMYK" and check.size == (32, 32), (check.mode, check.size)
    jp2_data = jp2.read_bytes()
    assert jp2_data[:12] == b"\x00\x00\x00\x0cjP  \r\n\x87\n", "not a JP2 file"
    for path in (cmyk, jp2):
        size = path.stat().st_size
        assert size < 2048, f"{path.name} is {size} bytes"
        print(f"{path.name}: {size} bytes")


if __name__ == "__main__":
    main()
