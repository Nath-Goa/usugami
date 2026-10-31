# How Usugami is tested

Usugami has three layers of automated checks, plus a manual pass on a phone or emulator before each
release. CI runs the first layer, lint and both release builds on every push and pull request
(`.github/workflows/ci.yml`).

## 1. Unit tests for the PDF engine (JVM)

`core/` is plain Kotlin with no Android code, so its tests run on any computer:

```sh
./gradlew :core:test
```

They cover page layout and page sizes, EXIF orientation, JPEG parsing and metadata removal,
lossless PNG encoding, compression sizing, PDF syntax, the PDF writer, file names, and page
selections such as `1-3;5;7-9`.

## 2. Device tests (Android)

`app/src/androidTest` runs on a connected phone or emulator, once per edition:

```sh
./gradlew :app:connectedStandardDebugAndroidTest
./gradlew :app:connectedOfflineDebugAndroidTest
```

They use small synthetic PDFs in `app/src/androidTest/assets` and check, with PDFBox text
extraction:

- split: selected pages, separate ranges and combined output
- merge: file order and page mode (reorder, rotate, remove)
- compress: all three levels, selectable text kept, never larger than the original
- password-protected PDFs: opening with the password, then split and compress
- text recognition in English, French, Spanish and Hindi (French and Spanish accents kept),
  including a text layer with both Devanagari and Latin
  text, text that follows tilted lines, and that a failed or cancelled recognition leaves the
  PDFs untouched

The standard edition gets its text-recognition model from Google Play services, so its OCR tests
need a device or emulator with Google Play services and, the first time each language is tested, an internet connection.
The offline edition's tests work without either.

## 3. End-to-end check with independent PDF tools

`tools/validate_pdfs.py` checks the engine's output with tools that share no code with Usugami:
qpdf (through pikepdf), pypdf and MuPDF rendering.

```sh
python -m venv .venv
.venv/bin/pip install -r tools/requirements.txt    # Windows: .venv\Scripts\pip
.venv/bin/python tools/validate_pdfs.py gen  out    # test images + manifest.tsv
./gradlew :core:test -PusugamiFixtures=/absolute/path/to/out   # build the PDFs with Usugami's engine
.venv/bin/python tools/validate_pdfs.py check out   # validate them
```

It checks that qpdf finds no syntax problems and pypdf parses each file in strict mode, that
JPEGs keep exactly the same pixels and lose their EXIF data, that PNGs stay lossless, that page
sizes are right, and that MuPDF renders every page like the expected layout.

## 4. Manual checks before a release

On a phone or emulator, with the release APKs:

- First launch: the language screen, then Home; Settings → Language switches every screen
  (also on Android 12 or older, where Usugami applies the language itself).
- Images to PDF from Photos, Files and a share from the gallery, with OCR on in each language.
- Split, merge in page mode, and compress, including a password-protected PDF.
- Open, Share, Save to Downloads (Android 10 and newer) and Save as….
- Standard edition: the first OCR in each language downloads that model through Google Play services.
- Offline edition: OCR works with the internet turned off.
- The About screen shows the license, privacy policy and notices.

CI and the release workflow also check both APKs' manifests with
`.github/scripts/check-apk-permissions.sh`. It fails if an APK requests any permission besides
the app's own signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, or if a library brings
back an upload backend for ML Kit's usage metrics or the EmojiCompat startup initializer.
