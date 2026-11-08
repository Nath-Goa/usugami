# How Usugami is tested

Usugami has three layers of automated checks, plus a manual pass on a phone or emulator before each
release. CI runs the first layer, lint and the release builds of all three editions on every push
and pull request (`.github/workflows/ci.yml`).

## 1. Unit tests for the PDF engine (JVM)

`core/` is plain Kotlin with no Android code, so its tests run on any computer:

```sh
./gradlew :core:test
```

They cover page layout and page sizes, EXIF orientation, JPEG parsing and metadata removal,
lossless PNG encoding, compression sizing, PDF syntax, the PDF writer, file names (also for
several PDFs made at once: cleaned, and told apart whatever their case), what Compress PDF keeps
for each of several PDFs (a level for all of them, one PDF's own level kept until the next level
for all, PDFs added and removed, and all of it packed for Android's saved state), page
selections such as `1-3;5;7-9`, pages to skip for several PDFs at once (read against the longest,
skipped in each PDF that has them, a PDF with every page skipped left with none to number), where
page numbers, watermarks and signatures go on pages that are turned (`/Rotate`) or cropped, where
a signature starts and how far it can grow, how big its image is, page lists written back as
ranges (`2,5-7`), that deleting every page is refused, and
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
words in English, French, Spanish and Hindi (in Devanagari and Latin letters), and the words for
working on several files at once ("batch", "plusieurs", "varios", "कई") finding only the tools that
do. For files shared to Usugami they cover what kind each is, from its type (any case, with
parameters), else its name, else the type the share was sent as, types that say nothing passed over,
and which kind a share of several kinds is taken as: the kind most files are, PDFs first in a tie.
For
Word and PowerPoint files they cover units and colors, telling .docx and .pptx files from old
binary and password-protected ones, list numbers and symbol-font bullets, table column widths,
where a paragraph breaks between pages, and how group shapes place their contents.
For Excel workbooks they cover number formats as Excel shows them: General in at most eleven
characters and narrower in a narrow column, Excel's built-in formats, digit placeholders, thousands
separators and scaling, percent and per mille, scientific notation, fractions, text around the
number, sections for negatives, zero and text, conditions, colors, broken codes falling back to
General, dates and times in the 1900 and 1904 calendars, another language's decimal sign and month
names, and any code with any value shown without an error. They also cover cell references from
A1 to XFD1048576, print areas and other defined names, column widths in points as Excel draws them,
rows and columns split into pages that fit, Fit to shrinking to the largest whole percent that
fits, the page order, paper sizes, palette colors with a tint, dates stored as text, escaped
characters, shared formulas moved to each cell, and headers and footers with their fields. For
Recent files they cover the last file saved coming first and one saved again moving up, the oldest
going past 50, an access given back only once no listed file needs it, and the list read back as
written, damaged text giving the files that can still be read.
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
folders, names in other scripts, progress, and stopping part way. For Usugami OCR's text
recognition they check the post-processing against PaddleOCR's own (fixtures in
`core/src/test/resources/ocr`, made with OpenCV and pyclipper by `tools/make_ocr_test_fixtures.py`):
the boxes found in a real probability map of the detection model, their scores, smallest
enclosing rectangles, decoding the recognition model's output for real lines (repeats, blanks and
the space), and also pieces of one line joined (also tilted, but not across columns, even close
ones, or with much smaller print, and not short words standing tall on lines one under the other,
also on a page turned a quarter round), which boxes are read as lines running down the page, the
reading order (columns, also with only a line height between them, pieces of a line, tilted and
upside-down pages, a line down the margin, lines fanned out by a photo taken at a slant), pages
with thousands of words read to the bottom, the size pictures are read and detected at (a long
screenshot isn't shrunk to its long side), the strips a large page is detected in, and where
over-long lines are cut. For text recognition in
both editions that have it, they check where a point of a picked image lands on its page (A4 with
a margin, Fit, landscape, turned a quarter, and every EXIF orientation and turn): exactly where the
page's own drawing matrix puts it. For cropping photos they find the page on synthetic photos (a
sheet with print on a darker wooden table, with noise): upright, tilted, photographed at a slant,
half in shadow or with the light falling off, with a folded corner, beside a pen and a cup, and on
a large photo, each corner within about 1% of the page's; they are unsure of a page running off
the photo or filling it, a small page, a round plate and a bare table. By its edges and colours,
they also find a page on a brighter table, a white page on an unevenly lit white desk (by the faint
shadow along its edges, also at a slant and under a hand's shadow), a page on a light grey desk only
a little darker than the paper, a page on a pale desk with a busy grain, and a white page on a pale
blue desk by its colour alone; they are unsure of tiles as large as a page and of a keyboard, and
find a page beside a keyboard on a 1200 by 1600 photo in under 400 ms.
They also check the largest four-cornered shape on a convex hull (against trying every four
corners), Otsu's threshold, and the corners: put in order round the page, turned with the page and
back, on the photo as stored for every EXIF orientation, which shapes make a page, and the
straightened page's size (shaped by its average edges, as large as its longest, at most 3000
pixels). For the page looks they use synthetic photos of a printed page (print in several sizes, a
bold heading, a black box, a faint pencil line, a red stamp) lit as a phone lights it: the light
falling off across the page, a hard shadow, a warm lamp, and noise. Black and white turns the
paper white (in the shadow too) and keeps the print black, fills bold print and the box, and keeps
the pencil line, where one global threshold can't; grayscale and enhanced make the paper white and
the ink dark, the lamp's tint goes everywhere, and the stamp stays red. They also check the paper
found under a box, transparency, a blank page, tiny pictures, enhanced written back into the photo
as it is made (the same as beside it), and the time a 12-megapixel photo takes. Lighten brightens
the photo but keeps its look, Super enhanced goes further than Enhanced but gives a faint stain no
more colour than Enhanced does, Ink saver leaves large dark areas hollow and keeps the outline of a
slanted marker stroke, the tone curves never turn back and keep white (the paper round a black bar
stays pure white in every colour look, Lighten too), brightness and contrast change every look, and
each colour look can be written back into the photo. A side of the crop dragged
moves across itself with its corners on the sides beside it (straight across when a side beside is
nearly parallel, and staying on the image), and a quarter turn names each corner for where it
lands. CCITT Group 4
compression is decoded by Java's own TIFF reader, for every run length of
either colour, print, noise, stripes and widths that aren't whole bytes, and is as small as Java's
own T.6 coder; the grey JPEG encoder's output is decoded by Java's JPEG reader at every size and
quality, its Huffman tables are made for each picture (no code over 16 bits, none all ones), and
the PDF writer stores black-and-white pages as CCITT G4 or Flate, whichever is smaller.

## 2. Device tests (Android)

`app/src/androidTest` runs on a connected phone or emulator, once per edition, together with the
edition's own tests (`app/src/androidTestStandard` for Usugami, `app/src/androidTestOcr` for
Usugami OCR):

```sh
./gradlew :app:connectedStandardDebugAndroidTest
./gradlew :app:connectedLiteDebugAndroidTest
./gradlew :app:connectedOcrDebugAndroidTest
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
  nothing to flatten reported (an XFA-only form too), and cancelling leaving nothing behind; several
  PDFs at once, each under its own name, with what flattening each says (what it says of several
  said once, naming them all), one with nothing to flatten left out and named (and no result when
  none has anything), and cancelling leaving nothing
- compress: all three levels, selectable text kept, never larger than the original; several PDFs
  at once, each at its own level and under its own name (two alike told apart), a PDF that can't
  get smaller kept byte for byte and said so, a damaged one named while the others are made (and
  no result when none is), PDFs with a password asked for in turn, the levels and names kept when
  the process is restarted, one PDF compressed as before, cancelling leaving nothing behind, and
  each PDF of the result saved on its own, into Downloads (without a second copy) or into the
  document "Save as" made, also after the process was restarted while that screen was open, which
  PDFs were saved kept as a flag for each; Save to Downloads failing partway keeping the PDFs it
  copied (marked saved, and not copied twice when saved again); and PDFs picked together that can't
  be opened named, in one message
- password-protected PDFs: opening with the password, then split, compress and rotate
- files shared to Usugami: copied in at once and kept by name (the app's own copy gone), PDFs taken
  in by a tool without being copied again and still there when the process is restarted, the kind
  most of the files are taken and the others and those Usugami can't use named, a file without type
  or extension taken as the share says, PDFs with a password asked for, files that can't be opened
  named (together, worded for them all; one shared alone told about as one picked), files that
  couldn't be read told about as shared files (images as images), files waiting kept when the process
  is restarted (copies cleared from the cache dropped and said, leftovers deleted), files lost when
  the process is restarted while they were being copied in or taken in said once, files of another
  kind shared while others waited left out and named, dropped files and a tool put away while taking
  them in leaving nothing behind, a share still waiting its turn not dropped with the files before it,
  another tool taking the PDFs without what the last one did (a PDF Reorganize PDF added going with
  its pages), a PDF made not shared to Usugami itself, and which tools one PDF and several PDFs go to
- rotate: only the chosen pages turn, on top of the turn they already had, and nothing else changes
- page numbers: each in the chosen corner of the page as shown (also on turned and cropped pages),
  none on skipped pages, checked both by text positions and by rendering the pages; several PDFs at
  once, each under its own name, pages to skip read against the longest and skipped in each PDF
  that has them, a PDF with every page skipped left out and named, one that can't be read named
  while the others are made, and cancelling leaving nothing
- watermark: centered along the shown diagonal of every page, translucent or opaque, real text with
  accents, Hindi shaped correctly, and text that no font on the phone can draw refused; several PDFs
  at once (one with a password among them), each under its own name, text no font can draw refused
  before any is made, one that can't be read named while the others are made, the first one's
  preview kept (also while it is made) when another is removed, and cancelling leaving nothing
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
  files, other files and XML with a DOCTYPE refused, and cancelling leaving nothing behind; several
  files at once, each into a PDF of its own name (two alike told apart), progress for each, an old
  .doc file among those picked named with why it was left out (two named together, worded for
  both), the files and their names kept when the process is restarted, one that can't be converted
  named while the others are made, books sharing the page size chosen, no copy left behind when the
  files are put away while another is added, and cancelling leaving nothing behind
- Excel to PDF, with small workbooks the tests write: values as their formats show them (shared
  text, a number with thousands, a date, a percentage, TRUE, an error, a formula's last value, and a
  formula with none showing itself), text running on into empty cells but not into a filled one, a
  number too wide for its column showing as ###, a merged title centered across its cells, wrapped
  text growing its row and breaking after a hyphen, hidden rows, columns and sheets and a value only
  a data bar shows left out, an empty sheet making no page, pages numbered through the whole
  workbook, only the pages with something on them printed however far the sheet reaches, the file's
  paper, orientation and title rows (and a paper chosen for all going over the file's), a wide sheet
  fitted to the page width but never below half size, or on pages across when asked not to, with
  every column once; old .xls, password-protected and other files, and a Word document picked as a
  workbook, refused with their own messages, a workbook with nothing to convert saying so, and
  cancelling leaving nothing behind
- Recent files: a PDF saved to Downloads twice listed once, with its name, type and size; Home's
  Recent files heading (a heading for a screen reader), a tap opening the file in another app with
  access to read it, Share handing it on (not to Usugami itself), and the file leaving the list once
  deleted in another app; Remove from list and Clear list (after its question) leaving the files in
  Downloads; a document made with the system's "Save as" screen still readable once the activity it
  was given to has gone, also through the list read back as after a restart, and once that access is
  taken back all the same, still listed, with a tap saying it can't be opened and offering Remove
  from list; and a file saved into a picked folder leaving the list when deleted in another app,
  with Usugami giving back its access to the folder
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
  it, Back clearing it before leaving Usugami, French words finding French tools, and
  "spreadsheet" finding Excel to PDF; Delete pages' pages as named check boxes kept in step with
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
  document surviving the activity being recreated and the result, and after Start over the next
  document's PDF named after that document; EPUB to PDF refusing a file that
  isn't a book and a book with DRM with their messages, and the book and A4 surviving the activity
  being recreated; Redact PDF's Find text boxing a phrase on every page, a box added, moved and
  widened with buttons, the preview naming the chosen box and stepping to the next, a box drawn
  with injected touches, all of it surviving the activity being recreated, deleting a box, "Remove
  all" asking first, the redacted result, and the boxes still there after going back from it; Take
  photo with the camera app caught and answered (it may write the file it is given): the crop
  screen on the page found, a corner moved with a screen reader's actions and another chosen like a
  radio button and moved with the arrow buttons, both kept through the activity being recreated,
  Take another cropping the photo and taking the next, the next one discarded, a page's Crop button
  opening it again with Back instead, and the message on a phone without a camera app; a corner
  dragged with injected touches past the others and back, staying under the finger while the hint
  says the corners make no page, with the image not moving; a side dragged moving across itself,
  its corners going along the sides beside it; Rotate left and right on the crop step turning the
  page, cropped as it was; the Filters step's looks as radio buttons, a new scan starting on
  Enhanced, the look, brightness and contrast kept through the activity being recreated and
  reaching the page, the next photo starting on the look chosen last with the sliders at 0, and
  Grayscale and more contrast given to both pages with Apply to all pages, which each page then
  says to a screen reader; Scan document's title read as one heading; Scan document's Take photo
  opening the camera over the editor, and a scan from Home that leaves no page going back to Home
  (the camera closed without a photo, or saying it took one it never saved, and an image that can't
  be read), also after a document shared to Usugami opened its tool over the scan and Back came
  back to it, while once a page was kept the editor stays, also when that page is removed; Scan
  document's Upload images taking the images through both steps one after another, "Page 2 of 5"
  under the title and in the name a screen reader hears, Next until the last, which has Done, and
  the rest left in the editor as they were added when the screen is closed part way;
  the
  result screen's "Share or save as ZIP" menu for a split and for one PDF per image, with the share
  screen and the "Save as" screen it starts caught and answered (the type, the name and the ZIP
  file written), and no such button for a single PDF; Compress PDF with two PDFs: the level for
  all and one PDF's own level and name, kept through the activity being recreated, how much
  smaller each got or that its original was kept, and each PDF's own Open, Share and Save (with
  "Save as" caught and answered, and Save to Downloads); Add watermark with one PDF as before, then
  with two (the watermark for both, previewed on the first, one PDF's own name, the result);
  Word to PDF with two files (each one's name, the button to add more, one button converting both);
files shared to Usugami from Downloads, as another app shares them: one PDF opening Choose a tool with
every PDF tool, kept through the activity being recreated, Split PDF chosen opening with it and going
back to Choose a tool, and Back from there going Home; two PDFs offering only Merge PDF and the tools
for several, Compress PDF chosen with both; a Word document opening Word to PDF and a text file shared
with it named; a PDF shared over a result not saved asking first, and kept, opening once the tool is
left; a PDF shared while a PDF is being made waiting for it, then asking, and discarded; a PDF
with a password asked for on Choose a tool; images shared over Choose a tool waiting in the
editor, Choose a tool keeping its PDF and the tool opened from it going back to it, then Back from
there showing the images; images shared over Word to PDF opened for a shared document waiting for it
too; and a Word document shared over a result kept, then a PDF shared once Word to PDF shows it,
opening Choose a tool;
  Home's search finding the eight tools that take several files by "several", "batch" and "many";
  Settings → Appearance starting on the phone's
  setting, then Dark and Light holding through the activity being recreated, Usugami being opened
  again and the first-launch language screen (each checked by the configuration, the colors the
  window draws and the status bar icons), both the way Android applies it from Android 12 and the
  way Usugami applies it itself on older versions
- crop, with synthetic photos of a test page (a coloured square in each corner, a line across its
  middle) lying at a slant on a table: the page is found within about 1% (also on a photo stored
  sideways with an EXIF orientation) and not on a bare table; the straightened page has the page's
  shape, each corner's colour in its corner and the line straight across the middle, from a JPEG,
  a PNG (which stays a PNG) and a 17-megapixel photo (made at most 3000 pixels long), with no EXIF
  data or location left. Through the view model, a photo the camera app saves into the file Usugami
  gives it is added and the crop screen opens on the page found; Done crops it and keeps the photo,
  cropping again starts from the photo with the corners as they were and turned with the page,
  Whole image puts the photo back, and the pages cropped before are deleted (a page cropped again
  only once a saved state no longer names it); corners that make no
  page are refused and crossed ones put in order; a photo cancelled, not saved or discarded on the
  crop screen leaves no file; cropped pages and an open crop screen survive the saved state; a crop
  not finished when Android stops Usugami is made again from the saved state (also a page cropped
  again, whose new crop was made before Android closed Usugami), a photo the camera app
  hands back as Usugami starts again is kept, a page removed while it is cropped goes without a
  message, and a page whose photo the system cleared from the cache stays, at start and when made
  into a PDF. The turn, look, brightness and contrast chosen reach the page, which opens on them
  again, while a new photo starts with the look chosen last (Enhanced at first, and again for a new
  scan) and the sliders at 0; Apply to all pages gives every page the look, brightness and contrast
  and leaves each its turn, and reaches an image of the scan still being added; a scan's uploaded
  images go through the crop screen in order, one removed meanwhile left out (and out of the count
  at once), and closing stops the scan, also while the next is being opened, leaving the rest as
  added; and a scan goes on when Usugami starts again from the saved state, without the images
  still being added
- page looks, with a photo of a page in shadow: each look in the PDF as it needs (black and white
  and Ink saver a 1-bit CCITT G4 image, grayscale a one-component JPEG) at the compression levels'
  sizes, drawn by Android's renderer and by PDFBox with the paper white and a black bar black (in
  Ink saver hollow, in Lighten the shadow softened, not taken out), brightness and contrast reaching
  the page; the size of an A4 page in each look at each level (logged; black and white a fraction of
  the photo's); and through the view model, a look for one page or for every page, a page opening on
  its look, the next photo starting with the look chosen last (not with one a gallery photo only
  kept), and the looks kept through the saved state; and each colour look made in the picture
  itself, the same as beside it
- the OCR text layer, in the editions with text recognition: Devanagari and Latin text together,
  and text that follows tilted lines
- text recognition with Google ML Kit (Usugami only, `app/src/androidTestStandard`) in English,
  French, Spanish and Hindi (French and Spanish accents kept), lines on a tilted page, that a
  failed or cancelled recognition leaves the PDFs untouched, and that an image under 32 pixels on a
  side is read rather than refused
- in both editions with text recognition, a photo of a printed page (stored sideways with an EXIF
  orientation, then turned in the editor) made into a PDF at Extreme: its small print, too small to
  read in the PDF's 100-dpi copy, is read from the photo as picked, and the text of a known word
  lies over that word on the page; and a photo of a page lying at a slant beside a sticky note,
  cropped to the page found on it: the page is read as cropped, so the note (read from the photo
  itself) is left out, and the title's text lies over the title on the straightened page; and a
  page in black and white still gets its text layer, and reads word for word as the PDF draws it
- Usugami OCR's built-in text recognition (`app/src/androidTestOcr`): its native library loads (the
  test emulator has 16 KB memory pages), the English and Hindi test pages are read word for word into
  the text layer (the time per page is logged), a page upside down is read the right way up with
  its text layer turned to match, the native bridge answers bad input (wrong sizes, a recycled
  bitmap, a closed network, a missing model) with exceptions instead of crashing, and every
  character the models' dictionaries hold is written into the text layer (circled digits too),
  rather than as a space
- the editions: text recognition (an engine, the text layer's font, the OCR switch in the
  Convert sheet, and Home's words for Images to PDF in all four languages) in Usugami and Usugami
  OCR and nowhere in Usugami Lite, and the Google notices only in Usugami

Usugami gets its text-recognition model from Google Play services, so its OCR tests need a device
or emulator with Google Play services and, the first time each language is tested, an internet
connection.

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
- From Files, share one PDF, two PDFs, a Word document and a PDF with images to Usugami: Choose a
  tool, the converter, and the message naming what was left out; then share a PDF while a tool has
  an unsaved result.
- Scan document's Take photo on a real phone: the camera app opens at once, and closing it without
  a photo goes back to Home. Scan a few school pages one after another with Take another: drag a
  corner and a side (the magnifier follows), rotate left and right on both steps, and on Filters
  try each look with the sliders on a page in a shadow and under a warm lamp. Try a white page on a
  white desk and a page held in your hand: Usugami should find them by their edges.
- Scan document's Upload images with five images: "Page 1 of 5" to "Page 5 of 5", Next on each and
  Done on the last, then the page list. Again, closing on page 3: pages 3 to 5 stay in the page list
  as they were added. Picking nothing leaves Home as it was.
- Android closing Usugami on each step: put Usugami in the background on the crop step, on Filters
  with a look and the sliders moved, and on page 3 of an upload, stop it as Android does
  (`adb shell am kill io.github.nath_goa.usugami`), and open it again from the recent apps: the
  step, corners, turn, look, sliders and page count are as they were.
- Apply to all pages with a look, brightness and contrast: every page gets all three, and each page
  keeps its own turn. Make the PDF and check in another PDF viewer that each page looks as Filters
  showed it; compare the sizes of a page in Black & white, Ink saver and Enhanced.
- In the editor: Take photo, and a page's crop button on a gallery photo and on a page turned in the
  editor, go through both steps; crop a page again and use Whole image; with TalkBack, move a corner
  and a side with their actions, and the sliders.
- Split, merge in page mode, and compress, including a password-protected PDF.
- Rotate a few pages, add page numbers with a skipped cover, add a watermark in Hindi, and sign a
  page turned sideways; check the results in another PDF viewer.
- Reorganize a PDF: drag a page, add the pages of a password-protected PDF and a blank page after a
  page turned sideways, undo, and save. Delete a few pages of a PDF with bookmarks. Flatten a
  filled-in form and a PDF with comments, and check in another viewer that nothing can be edited.
- Word to PDF and PowerPoint to PDF with a few real documents and presentations from Files; compare
  the result with the original, and check that an old .doc file is refused.
- Excel to PDF with a few real workbooks from Files, and a sheet with dates, times, currency,
  percentages, accounting formats and [Red] negatives: each cell reads as Excel prints it (an
  accounting format's currency sign sits next to the number). Then a wide sheet with Fit columns to
  page width on and off, a long sheet, a sheet with a print area and title rows (repeated on every
  page), headers and footers with page numbers (counted through the whole workbook), pictures, and
  a hidden sheet (left out); Gridlines off and on. An old .xls file and a password-protected
  workbook are refused with a message saying what to do. Share an .xlsx to Usugami from Files: Excel
  to PDF opens with it. Convert three workbooks at once.
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
- Recent files: save from Images to PDF, a scan, Word or Excel to PDF and a PDF tool, with Save
  to Downloads, Save as… and Save to folder…, and save a ZIP: each appears at the top of Recent
  files on Home (three there, all of them under See all). Open, Share and Remove from list from a
  file's menu; Clear list asks first, and the files stay where they were saved. Delete a listed
  file in the Files app and go back to Usugami: it leaves the list. Rename one in Downloads: it
  shows its new name. Share a result without saving it: nothing is added. With TalkBack, each file
  is one item, with Share and Remove from list as its actions.
- Split a PDF into single pages, then Share or save as ZIP: share it to Files or a mail app and save
  it with Save as, then open the ZIP file in Files and check the PDFs in it.
- Settings → Appearance: Light and Dark on Android 12 or newer and on an older version, with the
  phone itself set to the other one: every screen, dialogs, the status and navigation bars, and the
  first-launch language screen follow; System default follows the phone again.
- Usugami: the first OCR in each language downloads that model through Google Play services.
- Usugami OCR: OCR works with the internet turned off, on a real ARM phone, with a few photographed
  pages in each language (one upside down); note the time per page.
- Usugami Lite: no OCR switch when creating a PDF, and About shows "Usugami Lite". Installed next
  to Usugami OCR, it is a separate app named "Usugami Lite" in the launcher; a PDF shared from one
  opens in the other.
- The About screen shows the license, privacy policy and notices.

CI and the release workflow also check the three APKs' manifests with
`.github/scripts/check-apk-permissions.sh`. It fails if an APK requests any permission besides
the app's own signature-level `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, or if a library brings
back an upload backend for ML Kit's usage metrics or the EmojiCompat startup initializer. They
check with `.github/scripts/check-no-google-code.sh` that Usugami Lite and Usugami OCR contain no
Google ML Kit, Play services, Firebase or DataTransport classes (listed with apkanalyzer and R8's
mapping) or manifest entries, with `.github/scripts/check-mlkit-registrars.sh` that R8 kept
what ML Kit needs in Usugami, and with `.github/scripts/check-16kb-alignment.sh` that every native
library is stored 16 KB aligned in the APK (`zipalign -c -P 16`) and that the 64-bit ones have
16 KB-aligned segments (`llvm-readelf`), as phones with 16 KB memory pages need.

The F-Droid workflow (`.github/workflows/fdroid.yml`) builds Usugami (the OCR edition) and Usugami
Lite the way F-Droid does, with fdroidserver and the recipes in `.fdroid.yml` and
`fdroid/io.github.nath_goa.usugami.lite.yml`: it runs F-Droid's scanner on the source and on the
APKs, and for a release's commit checks with `apksigcopier compare` that the APKs it built are the
release's APKs byte for byte, apart from the signature (reproducible builds). It runs for every
release tag, and from the Actions tab for any branch, tag or commit. CONTRIBUTING.md explains how
the builds stay reproducible.
