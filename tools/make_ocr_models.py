"""
Builds the text-recognition models of Usugami OCR (app/src/ocr/assets/ocr/) from PaddlePaddle's
official ONNX exports of PaddleOCR PP-OCRv5 on Hugging Face (Apache License 2.0):

- PP-OCRv5_mobile_det: finds the lines of text on a page
- latin_PP-OCRv5_mobile_rec: reads English, French and Spanish
- devanagari_PP-OCRv5_mobile_rec: reads Hindi (and English words in Hindi text)

Each ONNX file is downloaded at a pinned revision and checked against its SHA-256, then converted
with pnnx (from Tencent's ncnn project) to ncnn's .param/.bin format with fp16 weights. Each
recognition model's character list comes from PostProcess.character_dict in its inference.yml and
is written one character per line (the model's class 0 is the CTC blank, then these characters,
then a space). The files are made in the work folder and checked against tools/ocr-models.sha256,
so the files in the repository are exactly what this script makes; only then are they copied into
app/src/ocr/assets/ocr (if they differ, the assets are left as they are). Use --update to copy them
and rewrite that list after changing a model or pnnx.

pnnx is used only to run its bundled converter, which needs neither PyTorch nor the network:

    python -m venv .venv-ocr
    .venv-ocr/bin/pip install --no-deps -r tools/ocr-models-requirements.txt   (Windows: .venv-ocr\\Scripts\\pip)
    .venv-ocr/bin/python -I tools/make_ocr_models.py
"""
import argparse
import hashlib
import importlib.metadata
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "ocr" / "assets" / "ocr"
CHECKSUMS = ROOT / "tools" / "ocr-models.sha256"
PNNX_VERSION = "20261020"

# name: (Hugging Face repository, revision, SHA-256 of inference.onnx, SHA-256 of inference.yml,
#        pnnx input shapes, has a character list)
MODELS = {
    "PP-OCRv5_mobile_det": (
        "PaddlePaddle/PP-OCRv5_mobile_det_onnx",
        "e6f4fa85f00e168c862bc462aebca69eef9b3d3d",
        "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d",
        "98069072e1b6b37d727fd9d9f11725faa46d6ea0de012f2ed26caea011c37699",
        ["inputshape=[1,3,320,320]f32", "inputshape2=[1,3,256,256]f32"],
        False,
    ),
    "latin_PP-OCRv5_mobile_rec": (
        "PaddlePaddle/latin_PP-OCRv5_mobile_rec_onnx",
        "89d3a50e2c27e2e7cceeab0e944c25c807d5db4f",
        "7888113072263cb471b93f66dd5e2ad70548dc526fa1ace760d0d973dd121498",
        "0bbe984570f597af3638e50bdf2e8276f3ab26a61966096538b3b0d1849f5c84",
        ["inputshape=[1,3,48,160]f32", "inputshape2=[1,3,48,256]f32"],
        True,
    ),
    "devanagari_PP-OCRv5_mobile_rec": (
        "PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx",
        "251aec19e36739540d35e2cc943f6aa7503b98e5",
        "cb789212ce96c69d3e74728ae4309d179281d68cb3945d0616b67cafab41c986",
        "9bd172dd26440c8ce94d1cde5d5baea6aefdc7cf3c5c8492e0beedef656d4e54",
        ["inputshape=[1,3,48,160]f32", "inputshape2=[1,3,48,256]f32"],
        True,
    ),
}


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def download(repo, revision, name, target, expected):
    """Downloads one file of a Hugging Face repository at a fixed revision (cached in the work folder)."""
    if not target.exists():
        url = f"https://huggingface.co/{repo}/resolve/{revision}/{name}"
        print(f"Downloading {url}")
        with urllib.request.urlopen(url, timeout=120) as response, open(target.with_suffix(".part"), "wb") as out:
            shutil.copyfileobj(response, out)
        target.with_suffix(".part").replace(target)
    if expected is not None and sha256(target) != expected:
        sys.exit(f"{target} has SHA-256 {sha256(target)}, expected {expected}")


def pnnx_executable():
    """The converter inside the pnnx package (not imported: its Python part needs PyTorch)."""
    try:
        installed = importlib.metadata.version("pnnx")
        package = importlib.util.find_spec("pnnx")
    except importlib.metadata.PackageNotFoundError:
        installed, package = None, None
    if installed != PNNX_VERSION or package is None:
        sys.exit(f"pnnx {PNNX_VERSION} is needed, found {installed}")
    folder = Path(package.submodule_search_locations[0])
    executable = folder / ("pnnx.exe" if os.name == "nt" else "pnnx")
    if not executable.exists():
        sys.exit(f"pnnx's converter is missing: {executable}")
    return str(executable)


def convert(pnnx, onnx, shapes, work, name):
    """ONNX -> ncnn .param/.bin (fp16 weights). Returns the two files."""
    out = work / name
    out.mkdir(exist_ok=True)
    files = {
        "pnnxparam": out / "model.pnnx.param",
        "pnnxbin": out / "model.pnnx.bin",
        "pnnxpy": out / "model_pnnx.py",
        "pnnxonnx": out / "model.pnnx.onnx",
        "ncnnparam": out / "model.ncnn.param",
        "ncnnbin": out / "model.ncnn.bin",
        "ncnnpy": out / "model_ncnn.py",
    }
    command = [pnnx, str(onnx), *shapes, "fp16=1", "optlevel=2", "device=cpu"]
    command += [f"{key}={path}" for key, path in files.items()]
    result = subprocess.run(command, cwd=out, capture_output=True, text=True, encoding="utf-8", errors="replace")
    (out / "pnnx.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode != 0 or not files["ncnnparam"].exists():
        sys.exit(f"pnnx failed for {name}; see {out / 'pnnx.log'}")
    return files["ncnnparam"], files["ncnnbin"]


def write_dictionary(yml, target):
    characters = yaml.safe_load(yml.read_text(encoding="utf-8"))["PostProcess"]["character_dict"]
    for c in characters:
        # One character per line, so none may be empty, white space or a line break.
        if not isinstance(c, str) or not c or c.isspace() or len(c.splitlines()) != 1:
            sys.exit(f"Unexpected entry {c!r} in {yml}")
    target.write_bytes(("\n".join(characters) + "\n").encode("utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--work", type=Path, help="folder for downloads and pnnx's files (default: a temporary one)")
    parser.add_argument("--update", action="store_true", help="rewrite tools/ocr-models.sha256 instead of checking it")
    args = parser.parse_args()

    pnnx = pnnx_executable()
    work = args.work or Path(tempfile.mkdtemp(prefix="usugami-ocr-models-"))
    work.mkdir(parents=True, exist_ok=True)
    # Made here first: the assets are only replaced by files that passed the check (or with --update).
    staged = work / "assets"
    staged.mkdir(exist_ok=True)
    made = []
    for name, (repo, revision, onnx_sha, yml_sha, shapes, has_dictionary) in MODELS.items():
        onnx = work / f"{name}.onnx"
        download(repo, revision, "inference.onnx", onnx, onnx_sha)
        param, model = convert(pnnx, onnx, shapes, work, name)
        shutil.copyfile(param, staged / f"{name}.ncnn.param")
        shutil.copyfile(model, staged / f"{name}.ncnn.bin")
        made += [f"{name}.ncnn.param", f"{name}.ncnn.bin"]
        if has_dictionary:
            yml = work / f"{name}.yml"
            download(repo, revision, "inference.yml", yml, yml_sha)
            write_dictionary(yml, staged / f"{name}.dict.txt")
            made.append(f"{name}.dict.txt")

    lines = [f"{sha256(staged / file)}  {file}" for file in sorted(made)]
    if not args.update:
        expected = CHECKSUMS.read_text(encoding="utf-8").splitlines()
        if lines != expected:
            for line in sorted(set(lines) ^ set(expected)):
                print(("made     " if line in lines else "expected ") + line)
            sys.exit(f"The models differ from tools/ocr-models.sha256; {ASSETS} is unchanged (the new files are in {staged})")
    ASSETS.mkdir(parents=True, exist_ok=True)
    for file in made:
        shutil.copyfile(staged / file, ASSETS / file)
    if args.update:
        CHECKSUMS.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
        print(f"Copied the models to {ASSETS} and wrote {CHECKSUMS}")
        return
    print(f"All {len(made)} files match tools/ocr-models.sha256")


if __name__ == "__main__":
    main()
