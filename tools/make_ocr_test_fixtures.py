"""
Writes the small test fixtures in core/src/test/resources/ocr/ that check Usugami OCR's Kotlin
post-processing (core/.../TextDetection.kt and TextRecognition.kt) against PaddleOCR's own
algorithms, run here with OpenCV and pyclipper as PaddleOCR runs them:

- detection.png: the text detection model's probability map (levels 0..255) for a synthetic page,
  and detection.txt: the boxes PaddleOCR's DBPostProcess finds in it (score, then 4 corners)
- recognition.txt: for a few lines, the recognition model's most likely class and its probability
  at each step, with the text and confidence PaddleOCR's CTCLabelDecode makes of them
- min_area_rect.txt: random point sets with the side lengths of OpenCV's minAreaRect

The models are the official ONNX files that tools/make_ocr_models.py downloads into its work
folder. Needs numpy 2.5.4, opencv-python-headless 5.0.0.93, onnxruntime 1.31.1 and pyclipper 1.4.0:

    python -I tools/make_ocr_models.py --work ocr-work
    python -I tools/make_ocr_test_fixtures.py ocr-work
"""
import sys
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
import pyclipper

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "core" / "src" / "test" / "resources" / "ocr"
DICTIONARY = ROOT / "app" / "src" / "ocr" / "assets" / "ocr" / "latin_PP-OCRv5_mobile_rec.dict.txt"


def page():
    """A white 480 x 320 page with level, tilted and small text (OpenCV's built-in font)."""
    img = np.full((320, 480, 3), 255, np.uint8)
    font = cv2.FONT_HERSHEY_SIMPLEX
    cv2.putText(img, "Usugami reads text", (16, 44), font, 1.1, (0, 0, 0), 2, cv2.LINE_AA)
    cv2.putText(img, "line by line, 2026.", (16, 92), font, 0.9, (30, 30, 30), 2, cv2.LINE_AA)
    cv2.putText(img, "small print here", (16, 296), font, 0.5, (0, 0, 0), 1, cv2.LINE_AA)
    tilted = np.full((320, 480, 3), 255, np.uint8)
    cv2.putText(tilted, "A tilted line", (90, 190), font, 1.0, (0, 0, 0), 2, cv2.LINE_AA)
    turn = cv2.getRotationMatrix2D((240, 180), 10, 1.0)
    tilted = cv2.warpAffine(tilted, turn, (480, 320), borderValue=(255, 255, 255))
    return np.minimum(img, tilted)


def detection_map(models, img):
    det = ort.InferenceSession(str(models / "PP-OCRv5_mobile_det.onnx"), providers=["CPUExecutionProvider"])
    x = (img.astype(np.float32) / 255.0 - [0.485, 0.456, 0.406]) / [0.229, 0.224, 0.225]
    p = det.run(None, {"x": x.transpose(2, 0, 1)[None].astype(np.float32)})[0][0, 0]
    return np.clip(np.round(p * 255), 0, 255).astype(np.uint8)


# PaddleOCR's DBPostProcess (ppocr/postprocess/db_postprocess.py), on the 0..255 map.
def mini_box(contour):
    box = cv2.minAreaRect(contour)
    points = sorted(list(cv2.boxPoints(box)), key=lambda p: p[0])
    i1, i4 = (0, 1) if points[1][1] > points[0][1] else (1, 0)
    i2, i3 = (2, 3) if points[3][1] > points[2][1] else (3, 2)
    return np.array([points[i1], points[i2], points[i3], points[i4]]), min(box[1])


def box_score_fast(bitmap, box):
    h, w = bitmap.shape[:2]
    box = box.copy()
    xmin = np.clip(np.floor(box[:, 0].min()).astype(np.int32), 0, w - 1)
    xmax = np.clip(np.ceil(box[:, 0].max()).astype(np.int32), 0, w - 1)
    ymin = np.clip(np.floor(box[:, 1].min()).astype(np.int32), 0, h - 1)
    ymax = np.clip(np.ceil(box[:, 1].max()).astype(np.int32), 0, h - 1)
    mask = np.zeros((ymax - ymin + 1, xmax - xmin + 1), dtype=np.uint8)
    box[:, 0] -= xmin
    box[:, 1] -= ymin
    cv2.fillPoly(mask, box.reshape(1, -1, 2).astype(np.int32), 1)
    return cv2.mean(bitmap[ymin:ymax + 1, xmin:xmax + 1], mask)[0]


def unclip(box, ratio=1.5):
    area = cv2.contourArea(box.astype(np.float32))
    length = cv2.arcLength(box.astype(np.float32), True)
    offset = pyclipper.PyclipperOffset()
    offset.AddPath(box.tolist(), pyclipper.JT_ROUND, pyclipper.ET_CLOSEDPOLYGON)
    return np.array(offset.Execute(area * ratio / length))


def db_boxes(levels):
    prob = levels.astype(np.float32) / 255.0
    bitmap = (prob > 0.3).astype(np.uint8)
    contours, _ = cv2.findContours(bitmap * 255, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    for contour in contours[:1000]:
        points, short_side = mini_box(contour)
        if short_side < 3:
            continue
        score = box_score_fast(prob, points.reshape(-1, 2))
        if score < 0.6:
            continue
        grown = unclip(points)
        if len(grown) == 0:
            continue
        box, short_side = mini_box(grown.reshape(-1, 1, 2).astype(np.float32))
        if short_side < 5:
            continue
        boxes.append((score, box))
    return boxes


def crop(img, box):
    w = int(max(np.linalg.norm(box[0] - box[1]), np.linalg.norm(box[2] - box[3])))
    h = int(max(np.linalg.norm(box[0] - box[3]), np.linalg.norm(box[1] - box[2])))
    m = cv2.getPerspectiveTransform(box.astype(np.float32), np.float32([[0, 0], [w, 0], [w, h], [0, h]]))
    return cv2.warpPerspective(img, m, (w, h), borderMode=cv2.BORDER_REPLICATE, flags=cv2.INTER_CUBIC)


def recognition_lines(models, img, boxes):
    rec = ort.InferenceSession(str(models / "latin_PP-OCRv5_mobile_rec.onnx"), providers=["CPUExecutionProvider"])
    characters = ["<blank>"] + DICTIONARY.read_text(encoding="utf-8").splitlines() + [" "]
    lines = []
    for _, box in boxes:
        c = crop(img, box)
        width = max(1, int(np.ceil(48 * c.shape[1] / c.shape[0])))
        x = (cv2.resize(c, (width, 48)).astype(np.float32) / 255.0 - 0.5) / 0.5
        padded = np.zeros((3, 48, max(320, width)), np.float32)
        padded[:, :, :width] = x.transpose(2, 0, 1)
        out = rec.run(None, {"x": padded[None]})[0][0]
        best = out.argmax(axis=1)
        keep = np.ones(len(best), bool)
        keep[1:] = best[1:] != best[:-1]
        keep &= best != 0
        text = "".join(characters[i] for i in best[keep])
        confidence = float(out.max(axis=1)[keep].mean()) if keep.any() else 0.0
        steps = " ".join(f"{i}:{p:.6f}" for i, p in zip(best, out.max(axis=1)))
        lines.append(f"{text}\t{confidence:.6f}\t{out.shape[1]}\t{steps}")
    return lines


def main():
    models = Path(sys.argv[1])
    OUT.mkdir(parents=True, exist_ok=True)
    img = page()
    levels = detection_map(models, img)
    cv2.imwrite(str(OUT / "detection.png"), levels)
    boxes = db_boxes(levels)
    with open(OUT / "detection.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write("# score, then x y of the top-left, top-right, bottom-right and bottom-left corners\n")
        for score, box in boxes:
            f.write(f"{score:.4f} " + " ".join(f"{v:.2f}" for v in box.reshape(-1)) + "\n")
    with open(OUT / "recognition.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write("# text, confidence, classes, then class:probability at each step (latin model)\n")
        f.write("\n".join(recognition_lines(models, img, boxes)) + "\n")
    rng = np.random.default_rng(2026)
    with open(OUT / "min_area_rect.txt", "w", encoding="utf-8", newline="\n") as f:
        f.write("# width height (width >= height) of OpenCV's minAreaRect, then the points as x y pairs\n")
        for _ in range(30):
            n = int(rng.integers(3, 40))
            angle = rng.uniform(0, np.pi)
            pts = rng.normal(size=(n, 2)) * [rng.uniform(5, 60), rng.uniform(1, 15)]
            pts = pts @ np.array([[np.cos(angle), np.sin(angle)], [-np.sin(angle), np.cos(angle)]])
            pts = np.round(pts + rng.uniform(0, 300, 2)).astype(np.float32)
            (_, _), (w, h), _ = cv2.minAreaRect(pts)
            f.write(f"{max(w, h):.4f} {min(w, h):.4f} " + " ".join(f"{v:.0f}" for v in pts.reshape(-1)) + "\n")
    print(f"{len(boxes)} boxes; fixtures in {OUT}")


if __name__ == "__main__":
    main()
