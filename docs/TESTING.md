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
are turned (`/Rotate`) or cropped, where a signature starts and how far it can grow, how big its
image is, page lists written back as ranges (`2,5-7`), that deleting every page is refused, and
where a form field's or comment's appearance goes when Flatten PDF draws it into the page. For
Edit PDF they cover how points on a page as shown map onto the page's own coordinates for every
turn and crop, the pen's smooth curve and ellipses as cubic curves, arrow heads, moves that stop at
the page's edges, handles (a box's opposite corner stays put, a picture keeps its shape, text grows
within its font sizes), the size slider, which item a touch picks (outlines by their line, the
chosen one first), new items stepping aside from one already in the middle, and the items surviving
being saved and restored. For
Home's search they cover words being matched whatever their case and accents (and the Devanagari
nukta and candrabindu), split at punctuation, each word typed having to begin a word of the tool's
name, description or hidden words, in any order, tools named by the word coming first, and hidden
words in English, French, Spanish and Hindi (in Devanagari and Latin letters). For
Word and PowerPoint files they cover units and colors, telling .docx and .pptx files from old
binary and password-protected ones, list numbers and symbol-font bullets, table column widths,
where a paragraph breaks between pages, and how group shapes place their contents.
For EPUB
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
the page. For the ZIP file of several PDFs they cover the files going in as they are (stored, not
compressed again), in order and byte for byte, names made unique ignoring case and kept free of
folders, names in other scripts, progress, and stopping part way.

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
- merge: file order and page mode (reorder with buttons and by dragging, rotate, remove), with pages
  left out gone from the file, PDFs added still going at the end, and no copy, result or PDFBox
  scratch file left once the tool is left
- delete pages: the pages left, in order, and nothing of the deleted ones anywhere in the file (their
  text, a form field's value, their bookmarks); bookmarks to the pages kept still lead there, a link
  to a deleted page leads nowhere, and deleting every page is refused
- reorganize: the new order, pages turned on top of the turn they had (an inherited one too) and
  keeping an inherited page size, pages from another PDF (one with a password too) put in after the
  chosen page, blank pages the size of the page before them as it shows, Undo, a page left out gone
  from the file, and links between pages kept still working
- flatten: field values and a ticked check box become part of the page (by text extraction and by
  rendering), a field left without an appearance drawn, a comment with no appearance of its own
  drawn, no fields or widgets left, links kept, a dynamic XFA form removed and the rest flattened,
  nothing to flatten reported (an XFA-only form too), and cancelling leaving nothing behind
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
- edit PDF: on upright, turned and cropped pages, a filled box, a pen stroke, an arrow, text and a
  picture each land where the preview put them, checked by where the page's content draws them
  (paths, the image's corners, the text's position, baseline and size) and by rendering the pages
  (each item's color where the item is); the picture keeps its transparency; a highlighter is
  see-through and multiplies, so text under it stays dark; Hindi text matches Android's own shaping
  and placement when rendered; a sideways photo comes out upright as JPEG; pages without items keep
  their content stream byte for byte; Undo, Redo, drags as one change, and the items surviving a
  trip through a Parcel as Android saves them; cancelling leaves nothing behind
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
- ZIP of several PDFs: a split into one PDF per page and one PDF per image (two pictures named
  alike) each go into one ZIP file holding exactly those PDFs, byte for byte; it is shared as
  application/zip through Usugami's file provider, named after the run, or written into the document
  picked (replacing a longer one), and saving it counts as saving the PDFs. Cancelling leaves no ZIP
  file and no message, PDFs cleared from the cache are reported, and so is a "Save as" answered
  after the PDFs have gone, and starting over clears the ZIP file
- fonts: those Android's PDF writer embeds whole are shrunk to the glyphs drawn (serif styles,
  monospace, a font with hinting instructions of its own, Greek, Cyrillic, superscripts, digits
  alone, accents on capitals), the Hebrew font is left whole, and the pages then render pixel for
  pixel the same at three sizes, read the same, and take a fraction of the space; the two parts of
  a long book share one copy of each font
- the screens, through the accessibility tree a screen reader uses: Home's four section headings
  with each tool under its own, in order; Home's search finding tools by name, description and hidden
  words (any case, accents or word order, tools named by the word first) in one list without the
  headings, saying when nothing matches, the line with the count read out as it changes, the clear
  button, the search surviving the activity being recreated and coming back after a tool opened from
  it, Back clearing it before leaving Usugami, and French words finding French tools; Delete pages' pages as named check boxes kept in step with
  the field, the count, and the messages for every page and for pages that don't exist; Reorganize
  PDF's buttons naming the chosen page, a blank page added and undone, a page removed with a screen
  reader's action, and the pages and the chosen one surviving the activity being recreated; Flatten
  PDF's line saying what it does and its message when there is nothing to flatten; Edit PDF's text
  typed in a dialog and moved with a button, a filled rectangle from the Shape menu with the line
  saying it only covers, Undo and Redo, the text reachable and chosen by a screen reader even under
  the rectangle, an arrow and a pen stroke (with injected touches) on page 2, all of it surviving the
  activity being recreated, the saved PDF, and the items still there after going back from the
  result;
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
  all" asking first, the redacted result, and the boxes still there after going back from it; the
  result screen's "Share or save as ZIP" menu for a split and for one PDF per image, with the share
  screen and the "Save as" screen it starts caught and answered (the type, the name and the ZIP
  file written), and no such button for a single PDF; Settings → Appearance starting on the phone's
  setting, then Dark and Light holding through the activity being recreated, Usugami being opened
  again and the first-launch language screen (each checked by the configuration, the colors the
  window draws and the status bar icons), both the way Android applies it from Android 12 and the
  way Usugami applies it itself on older versions
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
- Home's search in each language with TalkBack on: type a few words, hear how many tools were
  found, clear it with the button and with Back.
- Images to PDF from Photos, Files and a share from the gallery, with OCR on in each language.
- Split, merge in page mode, and compress, including a password-protected PDF.
- Rotate a few pages, add page numbers with a skipped cover, add a watermark in Hindi, and sign a
  page turned sideways; check the results in another PDF viewer.
- Reorganize a PDF: drag a page, add the pages of a password-protected PDF and a blank page after a
  page turned sideways, undo, and save. Delete a few pages of a PDF with bookmarks. Flatten a
  filled-in form and a PDF with comments, and check in another viewer that nothing can be edited.
- Word to PDF and PowerPoint to PDF with a few real documents and presentations from Files; compare
  the result with the original, and check that an old .doc file is refused.
- EPUB to PDF with a few real DRM-free books, one with a cover and one fixed-layout; check the
  bookmarks in another PDF viewer, and that a book with DRM is refused.
- Redact PDF with a few real documents, a scanned one among them: find a name, draw a box on the
  scan, and check in another PDF viewer that nothing under the boxes can be selected, searched or
  copied, and that the pages without boxes still have their text.
- Sign PDF in landscape and in the dark theme: the pad fills the screen, drawing feels smooth, and
  dragging the signature doesn't scroll the screen (a swipe off it does).
- Edit PDF on a page turned sideways: add text in Hindi, a photo and a PNG with transparency, draw
  and highlight, drag and resize with the handles, undo and redo, then check the result in another
  PDF viewer (the highlight see-through, the text selectable). Close Usugami from the recent apps
  while editing and reopen it: the items are still there.
- Open, Share, Save to Downloads (Android 10 and newer) and Save as….
- Split a PDF into single pages, then Share or save as ZIP: share it to Files or a mail app and save
  it with Save as, then open the ZIP file in Files and check the PDFs in it.
- Settings → Appearance: Light and Dark on Android 12 or newer and on an older version, with the
  phone itself set to the other one: every screen, dialogs, the status and navigation bars, and the
  first-launch language screen follow; System default follows the phone again.
- Standard edition: the first OCR in each language downloads that model through Google Play services.
- Offline edition: OCR works with the internet turned off.
- The About screen shows the license, privacy policy and notices.

CI and the release workflow also check both APKs' manifests with
`.github/scripts/check-apk-permissions.sh`. It fails if an APK requests any permission besides
the app's own signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, or if a library brings
back an upload backend for ML Kit's usage metrics or the EmojiCompat startup initializer.
