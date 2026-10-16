"""
End-to-end check of the PDF engine against independent tools.

  python tools/validate_pdfs.py gen   <dir>   # write test images + manifest.tsv
  ./gradlew :core:test -PpdferFixtures=<dir>  # build the PDFs with the app's engine
  python tools/validate_pdfs.py check <dir>   # validate them

Needs: pillow numpy pikepdf pypdf pymupdf
"""
import io
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageOps

MM = 72 / 25.4
SIZES = {"A4": (210 * MM, 297 * MM), "LETTER": (612, 792), "LEGAL": (612, 1008), "A3": (297 * MM, 420 * MM), "A5": (148 * MM, 210 * MM)}
MARGINS = {"NONE": 0, "SMALL": 18, "BIG": 54}

# Pillow transpose that undoes each EXIF orientation (what a camera would have stored).
STORE_FOR_EXIF = {
    1: None,
    2: Image.Transpose.FLIP_LEFT_RIGHT,
    3: Image.Transpose.ROTATE_180,
    4: Image.Transpose.FLIP_TOP_BOTTOM,
    5: Image.Transpose.TRANSPOSE,
    6: Image.Transpose.ROTATE_90,   # stored rotated 90° CCW; displaying needs 90° CW
    7: Image.Transpose.TRANSVERSE,
    8: Image.Transpose.ROTATE_270,
}


def upright(w=240, h=160):
    """An asymmetric picture: four coloured quadrants and a black 'F' near the top left."""
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, w // 2, h // 2], fill=(220, 40, 40))
    d.rectangle([w // 2, 0, w, h // 2], fill=(40, 180, 60))
    d.rectangle([0, h // 2, w // 2, h], fill=(40, 70, 220))
    d.rectangle([w // 2, h // 2, w, h], fill=(240, 200, 30))
    d.rectangle([20, 15, 32, 70], fill=(0, 0, 0))
    d.rectangle([20, 15, 70, 27], fill=(0, 0, 0))
    d.rectangle([20, 38, 55, 48], fill=(0, 0, 0))
    return img


def gen(out: Path):
    out.mkdir(parents=True, exist_ok=True)
    base = upright()
    expected = {}  # file -> (upright RGB image as the user should see it before their rotation)

    for o in range(1, 9):
        stored = base if STORE_FOR_EXIF[o] is None else base.transpose(STORE_FOR_EXIF[o])
        exif = Image.Exif()
        exif[0x0112] = o
        name = f"exif{o}.jpg"
        stored.save(out / name, quality=95, exif=exif.tobytes())
        check = ImageOps.exif_transpose(Image.open(out / name)).convert("RGB")
        assert check.size == base.size, (o, check.size)
        expected[name] = check

    base.save(out / "progressive.jpg", quality=92, progressive=True)
    expected["progressive.jpg"] = Image.open(out / "progressive.jpg").convert("RGB")
    base.convert("L").save(out / "gray.jpg", quality=92)
    expected["gray.jpg"] = Image.open(out / "gray.jpg").convert("RGB")

    rgba = base.convert("RGBA")
    alpha = Image.linear_gradient("L").transpose(Image.Transpose.ROTATE_90).resize(base.size)
    rgba.putalpha(alpha)
    rgba.save(out / "alpha.png")
    white = Image.new("RGBA", base.size, (255, 255, 255, 255))
    expected["alpha.png"] = Image.alpha_composite(white, rgba).convert("RGB")

    base.save(out / "rgb.png")
    expected["rgb.png"] = base
    base.convert("L").save(out / "gray.png")
    expected["gray.png"] = base.convert("L").convert("RGB")
    base.convert("P", palette=Image.Palette.ADAPTIVE, colors=16).save(out / "palette.png")
    expected["palette.png"] = Image.open(out / "palette.png").convert("RGB")
    sixteen = (np.asarray(base.convert("L"), dtype=np.uint16) * 257)
    Image.fromarray(sixteen).save(out / "deep16.png")
    expected["deep16.png"] = base.convert("L").convert("RGB")
    tall = upright(90, 300)
    tall.save(out / "tall.png")
    expected["tall.png"] = tall

    # Manifest: (pdf, size, orientation, margin, [(file, exif, rotation)])
    jpegs = [f"exif{o}.jpg" for o in range(1, 9)]
    docs = [
        ("orient_fit.pdf", "FIT", "PORTRAIT", "NONE", [(f, int(f[4]), 0) for f in jpegs]),
        ("orient_rot.pdf", "FIT", "PORTRAIT", "SMALL",
         [(f, int(f[4]), r) for f in jpegs for r in (90, 180, 270)]),
        ("a4_auto_small.pdf", "A4", "AUTO", "SMALL",
         [("exif6.jpg", 6, 0), ("progressive.jpg", 1, 90), ("gray.jpg", 1, 0), ("alpha.png", 1, 0), ("tall.png", 1, 270)]),
        ("letter_landscape_big.pdf", "LETTER", "LANDSCAPE", "BIG",
         [("exif8.jpg", 8, 0), ("tall.png", 1, 0), ("palette.png", 1, 180)]),
        ("pngs_fit.pdf", "FIT", "PORTRAIT", "NONE",
         [(f, 1, 0) for f in ["alpha.png", "rgb.png", "gray.png", "palette.png", "deep16.png", "tall.png"]]),
        ("a5_portrait_none.pdf", "A5", "PORTRAIT", "NONE", [("exif3.jpg", 3, 0), ("exif5.jpg", 5, 90)]),
    ]
    lines = []
    for name, size, orient, margin, pages in docs:
        lines.append("\t".join(["pdf", name, size, orient, margin]))
        for f, e, r in pages:
            lines.append("\t".join(["img", f, str(e), str(r)]))
    (out / "manifest.tsv").write_text("\n".join(lines) + "\n")
    for k, v in expected.items():
        v.save(out / f"expected_{k}.png")
    print(f"wrote {len(expected)} images and {len(docs)} documents to {out}")


def layout(img_w, img_h, size, orient, margin):
    """Independent re-implementation of PageLayout.compute (bottom-left origin)."""
    if size == "FIT":
        m = MARGINS[margin] / (210 * MM) * min(img_w, img_h)
        pw, ph = img_w + 2 * m, img_h + 2 * m
        k = min(1.0, 14400 / max(pw, ph))
        if min(pw, ph) * k < 3:
            k = min(3 / min(pw, ph), 14400 / max(pw, ph))
        return pw * k, ph * k, m * k, m * k, img_w * k, img_h * k
    short, long_ = SIZES[size]
    land = {"AUTO": img_w > img_h, "PORTRAIT": False, "LANDSCAPE": True}[orient]
    pw, ph = (long_, short) if land else (short, long_)
    m = MARGINS[margin]
    s = min((pw - 2 * m) / img_w, (ph - 2 * m) / img_h)
    w, h = img_w * s, img_h * s
    return pw, ph, (pw - w) / 2, (ph - h) / 2, w, h


def check(out: Path):
    import pikepdf
    import pymupdf
    import pypdf

    rows = [l.split("\t") for l in (out / "manifest.tsv").read_text().splitlines() if l]
    docs, cur = [], None
    for r in rows:
        if r[0] == "pdf":
            cur = (r[1], r[2], r[3], r[4], [])
            docs.append(cur)
        else:
            cur[4].append((r[1], int(r[2]), int(r[3])))

    failures = 0
    worst = 0.0
    for name, size, orient, margin, pages in docs:
        path = out / name
        # 1. Structure: qpdf (via pikepdf) must find no problems; pypdf in strict mode must parse.
        with pikepdf.open(path) as pdf:
            problems = pdf.check_pdf_syntax()
            assert not problems, (name, problems)
            assert len(pdf.pages) == len(pages)
            assert str(pdf.docinfo["/Producer"]) == "PDFer"
            for i, (f, e, r) in enumerate(pages):
                xobj = list(pdf.pages[i].Resources.XObject.values())[0]
                raw = xobj.read_raw_bytes()
                if f.endswith(".jpg"):
                    assert xobj.Filter == "/DCTDecode"
                    assert b"Exif" not in raw, f"{name}: EXIF left in {f}"
                    # Same JPEG data -> identical pixels to the source file.
                    a = np.asarray(Image.open(io.BytesIO(raw)).convert("RGB"), dtype=np.int16)
                    b = np.asarray(Image.open(out / f).convert("RGB"), dtype=np.int16)
                    assert a.shape == b.shape and np.array_equal(a, b), f"{name}: {f} pixels changed"
                else:
                    assert xobj.Filter == "/FlateDecode"
                    got = pikepdf.PdfImage(xobj).as_pil_image().convert("RGB")
                    want = Image.open(out / f"expected_{f}.png").convert("RGB")
                    assert np.array_equal(np.asarray(got), np.asarray(want)), f"{name}: {f} not lossless"
        reader = pypdf.PdfReader(path, strict=True)
        assert len(reader.pages) == len(pages)

        # 2. Rendering: MuPDF's picture of each page must match the expected composition.
        doc = pymupdf.open(path)
        for i, (f, e, r) in enumerate(pages):
            shown = Image.open(out / f"expected_{f}.png").convert("RGB")
            shown = shown.rotate(-r, expand=True) if r else shown  # PIL rotates CCW
            pw, ph, x, y, w, h = layout(shown.width, shown.height, size, orient, margin)
            page = doc[i]
            assert abs(page.rect.width - pw) < 0.01 and abs(page.rect.height - ph) < 0.01, (name, i, page.rect, pw, ph)
            zoom = 500 / max(pw, ph)
            pix = page.get_pixmap(matrix=pymupdf.Matrix(zoom, zoom), alpha=False)
            got = Image.frombytes("RGB", (pix.width, pix.height), pix.samples)
            exp = Image.new("RGB", got.size, "white")
            left, top = x * zoom, (ph - y - h) * zoom
            box = (round(left), round(top), round(left + w * zoom), round(top + h * zoom))
            exp.paste(shown.resize((box[2] - box[0], box[3] - box[1]), Image.Resampling.LANCZOS), box[:2])
            diff = np.abs(np.asarray(got, dtype=np.int16) - np.asarray(exp, dtype=np.int16)).mean()
            worst = max(worst, diff)
            status = "ok " if diff < 6 else "BAD"
            if diff >= 6:
                failures += 1
                got.save(out / f"render_{name}_{i}.png")
                exp.save(out / f"want_{name}_{i}.png")
            print(f"{status} {name} p{i + 1}: {f} exif={e} rot={r} page={pw:.1f}x{ph:.1f} diff={diff:.2f}")
    print(f"\n{failures} page(s) failed; worst mean pixel difference {worst:.2f}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    {"gen": gen, "check": check}[sys.argv[1]](Path(sys.argv[2]))
