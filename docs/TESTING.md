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
widths, where a paragraph breaks between pages, and how group shapes place their contents. For EPUB
books they cover HTML's named characters, links between the book's files, telling DRM from font
obfuscation, the character set and DOCTYPE of XHTML files, white space, CSS selectors, cascading,
lengths and font sizes, and table column widths. They also cover shrinking an embedded TrueType font
to the glyphs a PDF draws: glyph numbers kept, the parts of composite glyphs kept, the letters
FreeType's auto-hinter measures kept for each script drawn (Latin, also for digits alone, Greek,
Cyrillic, superscripts and subscripts, and all three for a glyph without a character of its own),
positioning tables left out while GSUB and GDEF stay, shorter offsets, checksums right, and fonts
left whole when they draw another script, have CJK ideographs, draw the rupee sign beside
Devanagari letters, have no readable Unicode character map, or are PostScript, variable, bitmap
or damaged (random damage never crashes it). For Redact PDF they cover where a box goes on a page
as shown (turned or cropped pages included), drawing, moving and resizing boxes and keeping them on
the page, where a new box goes, which box a tap picks, how big the picture of a page is (200 dpi,
smaller for huge pages), which pixels a box blacks out, and Find text: capitals, accents,
ligatures, spaces and line breaks, and one box per line of a match, also for text running down
the page.

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
- EPUB to PDF, with small EPUB 2 and EPUB 3 books the tests write: A5 and A4 pages, chapters in the
  reading order (not the manifest's), each on a new page, page breaks from the style sheet, HTML's
  named characters, lists and tables, hidden parts and scripts left out, headings larger and bold,
  the cover first (also one the book only declares), pictures, an SVG drawing replaced by its
  description, bookmarks from an NCX file and from a navigation document (places further into a
  chapter too), the title, author and language, fixed-layout books at one page each, a damaged
  chapter, progress by chapter, a book of over 400 pages written in parts and joined with its
  bookmarks, a book without container.xml, books with DRM refused (scrambled fonts alone accepted),
  other files refused, and cancelling leaving nothing behind
- redact: with a made-up PDF that hides one word everywhere a PDF can keep text (upright, turned
  and cropped pages, a form drawing, a comment, a form field, the document details, XMP metadata, a
  bookmark, a named destination, an attached file, JavaScript, tags, private application data and
  a thumbnail), the word is gone from the redacted file: text extraction finds nothing on any page,
  and neither does a search of every string and decoded stream, through PDFBox and straight from
  the file's bytes. The word is drawn in red, and the redacted pages have no red pixels left. Find
  text boxes every letter of every match and no other word; the boxes are black where they were
  drawn, page sizes, crop boxes and turns are kept, redacted pages are one 200-dpi picture each
  (lossless in grey for text, JPEG for photos), comments on them become part of the picture, the
  page without boxes keeps its content, its form field and its look pixel for pixel, an image only
  a redacted page showed is emptied although every page lists it, password-protected PDFs work,
  the boxes come back after the process is stopped, and cancelling leaves nothing behind
- fonts: those Android's PDF writer embeds whole are shrunk to the glyphs drawn (serif styles,
  monospace, a font with hinting instructions of its own, Greek, Cyrillic, superscripts, digits
  alone, accents on capitals), the Hebrew font is left whole, and the pages then render pixel for
  pixel the same at three sizes, read the same, and take a fraction of the space; the two parts of
  a long book share one copy of each font
- the screens, through the accessibility tree a screen reader uses: Home's section headings,
  turning pages with buttons, the page-number positions and watermark colors as named radio
  buttons, a chosen position surviving the activity being recreated, the messages for pages to
  skip that can't be used, and a signature drawn on the pad (with injected touches), put on a chosen
  page with the arrow buttons, surviving the activity being recreated, saved where the buttons
  moved it, and still there after going back from the result, and Word and PowerPoint to PDF refusing
  an old .doc file and a document picked as a presentation with their messages, and a picked
  document surviving the activity being recreated and the result; EPUB to PDF refusing a file that
  isn't a book and a book with DRM with their messages, and the book and A4 surviving the activity
  being recreated; Redact PDF's Find text boxing a phrase on every page, a box added, moved and
  widened with buttons, the preview naming the chosen box and stepping to the next, a box drawn
  with injected touches, all of it surviving the activity being recreated, deleting a box, "Remove
  all" asking first, the redacted result, and the boxes still there after going back from it
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
- EPUB to PDF with a few real DRM-free books, one with a cover and one fixed-layout; check the
  bookmarks in another PDF viewer, and that a book with DRM is refused.
- Redact PDF with a few real documents, a scanned one among them: find a name, draw a box on the
  scan, and check in another PDF viewer that nothing under the boxes can be selected, searched or
  copied, and that the pages without boxes still have their text.
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
