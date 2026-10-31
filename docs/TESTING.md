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
lossless PNG encoding, compression sizing, PDF syntax, the PDF writer, file names, page
selections such as `1-3;5;7-9`, where page numbers, watermarks and signatures go on pages that
are turned (`/Rotate`) or cropped, where a signature starts and how far it can grow, and how big its
image is. For Word and PowerPoint files they cover units and colors, telling .docx and .pptx files
from old binary and password-protected ones, list numbers and symbol-font bullets, table column
widths, where a paragraph breaks between pages, and how group shapes place their contents.

## 2. Device tests (Android)

`app/src/androidTest` runs on a connected phone or emulator, once per edition:

```sh
./gradlew :app:connectedStandardDebugAndroidTest
./gradlew :app:connectedOfflineDebugAndroidTest
```

They use small synthetic PDFs, a made-up Word report and a made-up slide deck in
`app/src/androidTest/assets` (the two Office files are rebuilt with `tools/make_office_fixtures.py`,
which needs python-docx and python-pptx), and check, with PDFBox text extraction:

- split: selected pages, separate ranges and combined output
- merge: file order and page mode (reorder, rotate, remove)
- compress: all three levels, selectable text kept, never larger than the original
- password-protected PDFs: opening with the password, then split, compress and rotate
- rotate: only the chosen pages turn, on top of the turn they already had, and nothing else changes
- page numbers: each in the chosen corner of the page as shown (also on turned and cropped pages),
  none on skipped pages, checked both by text positions and by rendering the pages
- watermark: centered along the shown diagonal of every page, translucent or opaque, real text with
  accents, Hindi shaped correctly, and text that no font on the phone can draw refused
- signature: the image lands exactly where the preview put it on upright, turned and cropped pages
  (checked by where the PDF draws it and by rendering the pages), starts in the lower right, has a
  transparent background, is trimmed to the ink at 300 dpi, adds little to the file, and leaves the
  other pages untouched
- Word and PowerPoint to PDF, with the made-up report and deck and with small files the tests
  write themselves: page sizes (A4, A5, landscape sections, 4:3 and 16:9 slides), the text, numbered
  and bulleted lists, tables split over pages with their header row repeated on each, page breaks,
  headers and footers with page numbers, headings bold and larger, pictures where the document puts
  them and decoded no bigger than needed, slides in the presentation's order (not the files'),
  hidden slides left out, progress by page and slide, old .doc and .ppt files, password-protected
  files, other files and XML with a DOCTYPE refused, and cancelling leaving nothing behind
- the screens, through the accessibility tree a screen reader uses: Home's section headings,
  turning pages with buttons, the page-number positions and watermark colors as named radio
  buttons, a chosen position surviving the activity being recreated, the messages for pages to
  skip that can't be used, and a signature drawn on the pad (with injected touches), put on a chosen
  page with the arrow buttons, surviving the activity being recreated, saved where the buttons
  moved it, and still there after going back from the result, and Word and PowerPoint to PDF refusing
  an old .doc file and a document picked as a presentation with their messages, and a picked
  document surviving the activity being recreated and the result
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
- Rotate a few pages, add page numbers with a skipped cover, add a watermark in Hindi, and sign a
  page turned sideways; check the results in another PDF viewer.
- Word to PDF and PowerPoint to PDF with a few real documents and presentations from Files; compare
  the result with the original, and check that an old .doc file is refused.
- Sign PDF in landscape and in the dark theme: the pad fills the screen, drawing feels smooth, and
  dragging the signature doesn't scroll the screen (a swipe off it does).
- Open, Share, Save to Downloads (Android 10 and newer) and Save as….
- Standard edition: the first OCR in each language downloads that model through Google Play services.
- Offline edition: OCR works with the internet turned off.
- The About screen shows the license, privacy policy and notices.

CI and the release workflow also check both APKs' manifests with
`.github/scripts/check-apk-permissions.sh`. It fails if an APK requests any permission besides
the app's own signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, or if a library brings
back an upload backend for ML Kit's usage metrics or the EmojiCompat startup initializer.
