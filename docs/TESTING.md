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
ranges (`2,5-7`), the pages selected in Reorganize PDF (their places following them as pages are
moved and removed, the Selected pages field keeping what was typed while it names the selected
pages and otherwise showing them as ranges, and the selection packed into the saved state as
ranges, "1-5000" for every page of 5,000, and dropped when it doesn't fit), typed pages read or the
reason they can't be, and
where a form field's or comment's appearance goes when Flatten PDF draws it into the page. For
Annotate (Edit PDF's tools, in the reader) they cover how points on a page as shown map onto the
page's own coordinates for every
turn and crop, the pen's smooth curve and ellipses as cubic curves, arrow heads, moves that stop at
the page's edges, handles (a box's opposite corner stays put, a picture keeps its shape, text grows
within its font sizes), the size slider, which item a touch picks (outlines by their line, the
chosen one first), new items stepping aside from one already in the middle, and the items surviving
being saved and restored. For
Home's search they cover words being matched whatever their case and accents (and the Devanagari
nukta and candrabindu), split at punctuation, each word typed having to begin a word of the tool's
name, description or hidden words, in any order, tools named by the word coming first, and hidden
words in each of the app's languages (Hindi in Devanagari and Latin letters), the words for
working on several files at once ("batch", "plusieurs", "varios", "कई") finding only the tools that
do, and Reorganize PDF, and only it, found by the words of the two tools it replaced ("rotate",
"turn", "delete", "remove"). For files shared to Usugami they cover what kind each is, from its
type (any case, with
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
the page; Chinese, Japanese and Korean as written (a word wrapped onto the next line at any
character, a name with an ideographic space found with and without it, Japanese and a Latin word
with a sliver of room between them, the query's spaces beside Chinese or Japanese dropped, Korean
found across a line break but not across a space, full-width letters and digits found by plain
ones, half-width and ordinary katakana finding each other); the next letter down a column (not the
next column, not 15 points further down, also on a turned page); and the list of pages Find text
couldn't read ("3", "2, 5-7", eight entries and "…"). For the ZIP file of several PDFs they cover
the files going in as they are (stored, not
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
a large photo, each corner within about 1% of the page's; they are unsure of a page with a corner
cut off by the photo or filling it, a small page, a round plate and a bare table. By its edges and colours,
they also find a page on a brighter table, a white page on an unevenly lit white desk (by the faint
shadow along its edges, also at a slant and under a hand's shadow), a page on a light grey desk only
a little darker than the paper, a page on a pale desk with a busy grain, and a white page on a pale
blue desk by its colour alone; they are unsure of tiles as large as a page and of a keyboard, and
find a page beside a keyboard on a 1200 by 1600 photo in under 400 ms.
On a textbook's page as wide as a tall photo (its left edge on the photo's border, desk above its
top edge, darkness below its bottom edge), they find the paper as far as it shows and not the
ruled table printed on it, and with the whole page on a dark table, the page and not its table or
the photograph printed on it; with the page running off the photo left and right they are unsure,
and on a page that fills the photo, of a table, an empty box with rules a pixel thin, a tinted
panel and a printed photograph, and of the lit part of a page under a hard-edged shadow. They find a page that runs off the photo at its
top, and in colour at its left with light wood as bright as the paper beyond its other edges,
that side the photo's border; they are unsure of a page across a corner of the photo, of a page
with only one edge in the photo, of a bare white wall, of a white table a page lies on (the page's
edges aren't joined to the table's) and of a book open across the photo up to its gutter. They
don't draw a page's sides on to a streak in the wood above it, and keep a dark band printed
across a page's top in its outline. They find a page all in the photo between dark bands along
the photo's borders, a page with a narrower printed sheet showing from under its top edge (the
page, without the strip of the sheet), a white page lifted off a white desk, by the shadow along
two of its sides, and a page on a white desk in a dim photo speckled with noise. On a photo of
sticks lying every way and on rectangles one inside the other, the search takes at most four
times as long as on a plain page. Some of what the search weighs has no photo of its own here and
rests on real photos kept outside the repository: a side ending at the page's corner where a
streak in the wood or a rule across a receipt passes by, the grain of a desk alongside a page, and
how many outlines are weighed.
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

For the reader they cover how a file opened with Usugami is told apart: a PDF by "%PDF-" anywhere
in its first 1024 bytes (after a byte order mark or a blank line, not later), a ZIP file by its
first four bytes, and what the content says winning over the file's type and name, which stand
when the content says nothing. They cover the page in view (the one filling most of the screen,
the first on a tie), which page lies under a point (gaps belong to none), a page's picture keeping
its shape within 1600 pixels across, 4 million pixels and 4096 pixels on its longer side (A4 at
1080 pixels, a 1:3 page, a 1:20 strip, a tablet's 2560 pixels, a page box of no size), zooming about
a point keeping what is under it there (in and out, at half size and five times), the sideways pan
staying on the pages and centring them when zoomed out, and the sharp part drawn when zoomed: what
it covers (what shows and a fifth of the screen each way, cut to the page), its cap of 6 million
pixels, none for a page off the screen, and when it serves again (after a small scroll, not after a
zoom of 10% or a scroll past its edge). For selecting text they cover a long-press taking the word
under it or within reach beside it (nothing far from text, a Chinese character or a kana alone), a
handle standing on the nearest glyph of the nearest line (also beside a line's end and in columns
down the page), a selection over pages (the first from its glyph, the pages between whole, the last
up to its glyph, from either end), one box a line, the text copied (a space between words, none
between Chinese characters, a line break between lines, "ﬁ" as "fi") and the handles swapping ends
in right-to-left text.

For PDF to images or text they cover the image size of a page at 100, 200 and 300 dpi, the caps of
12 million pixels and 8192 pixels a side (A0 at 300 dpi, a long strip, a page of a point), the
halving after running out of memory and where it stops, the names of the images, their ZIP file and
the text file ("Report_p3", "Report_images", a typed .pdf, .jpg or .png dropped, a blank or long
name), cleaning a line of text (ligatures, Arabic presentation forms with a vowel mark kept on its
letter, a byte order mark, control characters, trailing space), what counts as text, the gap rule
for Chinese and Japanese (full-width punctuation and half-width kana marks written without a
space), the list of pages a note shows, the preview's cut, and lines read by text recognition put
in reading order (columns one after the other). For the files a tool makes they cover a file's type
from its name's ending (PDF unless it ends in .jpg, .jpeg, .png or .txt, any case, only the last
ending) and from a MIME type (any image as an image, `text/*` as text, everything else as a PDF).
For Documents to PDF they cover how a picked file is sorted: its kind by type, then name (an old .doc
counts as Word; PDFs, images and text are not taken), which files are worth looking into, the kind
most files are (Word before PowerPoint before EPUB before Excel on a tie), and what a ZIP file's
first entries say (EPUB by its mimetype or container, Word, PowerPoint and Excel by their folder,
nothing for a plain ZIP or an ODT file).

For Page layout they cover where a crop box drawn on a page as shown goes on the page itself for
every turn and earlier crop (worked by hand for pages turned 0, 90, 180 and 270 degrees whose crop
box doesn't start at 0,0), the whole page giving the page's own box, the box never leaving the
media box, a box under 3 points leaving the page as it is, sides and corners stopping at the page's
edges and at 5% of the page, which handle a touch takes (corners before sides, nothing in the
middle), the 1% step buttons stopping at their limits, and the box saved and restored; and for
pages per sheet the grid and the sheet's way round that draw the pages biggest (A4 pages at 2, 4, 6
and 9, 16:9 and 4:3 slides, a cover the other way round, ties), cells across then down within the
18-point margin and 9-point gap, pages of other shapes centred in their cells, the matrix that puts a
turned or cropped page upright in its place, US Letter or A4 paper from the first page, and the
number of sheets. For an ID card they cover where its sides go on their page: two sides at 85.6 mm
on A4, 10 mm apart and centred as a pair, one side centred, sides cropped at a slant still taken as a
card, passport-shaped sides at 125 mm, sides too big for A5 with a big margin shrunk together, Auto,
Portrait and Landscape, Fit pages, and zero or three sides refused; which images make one page (a
card's two sides next to each other) and where a card's back goes when it is added; a side keeping
twice the level's resolution (1011 by 638 pixels at Recommended); a PDF page drawing two images in
one content stream; and a card with rounded corners lying askew on a table found on a photo.

For Black and white they cover the gray of RGB (the luma the PDF standard gives), CMYK (dark inks
kept apart, black ink alone exactly black), Lab, CalGray and CalRGB, palettes and spot colours, each
colour space's initial colour, default decode and whether it is gray; the colour state of a page's
content (`rg`, `k`, `cs`, `sc` and `scn` written as gray, `q`/`Q` nesting, failures counted,
missing resources, default colour spaces); picture rows of 1 to 16 bits with /Decode, palettes,
tints and colour keys; and mesh gradients of types 4 to 7 kept byte for byte, records cut short
dropped and sizes refused. For Document info they cover cleaning a title or the like (control
characters, language escapes, cutting without splitting an emoji, PDF strings in ASCII or UTF-16
with their byte order marks, keywords split for XMP), PDF dates (with and without the offset's
apostrophes, Z, no offset taken in the phone's zone, partial dates, XMP's ISO 8601, impossible dates
refused) and Usugami's own strict XMP reader (an Acrobat packet, attribute forms, unusual prefixes,
UTF-16, entities and CDATA, and every refusal: DOCTYPE, an unknown entity, mismatched tags, 65
levels deep, over 2 MB, no rdf:RDF, UTF-32, Latin-1, cut off), and the XMP writer changing only the
fields asked for: the title replaced in both its forms, an emptied author removed, keywords as typed
with a dc:subject bag without repeats, `& < > "`, Chinese, Hindi and an emoji read back as typed,
other prefixes patched by namespace, and everything else in the packet (the author's list, rights,
dates, PDF/A identification, the wrapper) written as it was.

For launcher shortcuts they cover what a shortcut does wherever Usugami is: refused with a message
while a job runs, ignored while shared files wait or a question shows, asking over unsaved work in
a tool, opening over saved work, Home and Choose a tool, and in Images to PDF keeping the images
(Scan document adds a photo only on the plain image list; Merge and Compress open over them); and a
shortcut found by its id and nothing else. For Usugami OCR they also check that its Latin
dictionary has every German and Portuguese letter (ä, ö, ü, ß, ã, õ, ç and the accented vowels,
capitals too). The translations are checked as well (`TranslationsTest`): each language has every
English text and no other, with the same placeholders, plural forms and line breaks, apostrophes
and quotes escaped, and the store texts of both apps within F-Droid's limits.

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
- reorganize: the new order, pages turned on top of the turn they had (an inherited one too) and
  keeping an inherited page size, pages from another PDF (one with a password too) put in after the
  last selected page, blank pages the size of the page before them as it shows, Undo, a page left
  out gone from the file, and links between pages kept still working. Pages only turned or removed
  are saved in place: only a turned page's `/Rotate` changes (every other key, and the content of a
  page left alone, stay as they were), and nothing of a removed page is left anywhere in the file
  (its text, a form field's value, its bookmarks, tags and the pictures only it used); bookmarks to
  the pages kept still lead there, a link to a removed page leads nowhere, page labels are kept, a
  nested page tree stays nested with its inherited size and turn, an empty page list is refused with
  nothing left, and a damaged page tree that lists one page twice keeps its pages in order when the
  second is removed. The selected pages are turned and removed together, one Undo step each, the
  last page is never removed, and the selection follows a page moved and survives the process being
  recreated, also 5,000 pages of it
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
- password-protected PDFs: opening with the password, then split, compress and a page turned
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
- annotate (Edit PDF's engine): on upright, turned and cropped pages, a filled box, a pen stroke, an
  arrow, text and a picture each land where the preview put them, checked by where the page's
  content draws them (paths, the image's corners, the text's position, baseline and size) and by
  rendering the pages (each item's color where the item is); the picture keeps its transparency; a
  highlighter is see-through and multiplies, so text under it stays dark; Hindi text matches
  Android's own shaping and placement when rendered; a sideways photo comes out upright as JPEG;
  pages without items keep their content stream byte for byte; Undo, Redo, drags as one change, and
  the items surviving a trip through a Parcel as Android saves them; cancelling leaves nothing
  behind
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
- the screens, through the accessibility tree a screen reader uses: Home's six section headings
  (View, Create PDF, Organize, Edit and sign, Optimize and protect, Convert from PDF) with each tool
  under its own, in order; Home's search finding tools by name, description and hidden
  words (any case, accents or word order, tools named by the word first) in one list without the
  headings, saying when nothing matches, the line with the count read out as it changes, the clear
  button, the search surviving the activity being recreated and coming back after a tool opened from
  it, Back clearing it before leaving Usugami, French words finding French tools ("supprimer"
  finding only Reorganize PDF, "annoter" and "modifier" Read and annotate), "spreadsheet" finding
  only Documents to PDF and "docx", "slides" and "e-book" finding it, "rotate" and "delete" finding
  only Reorganize PDF, and "edit", "annotate", "draw", "highlight", "text", "open", "view" and "read"
  finding Read and annotate; Reorganize PDF's pages as named check boxes kept in step with the
  Selected pages field (a tap writes `2`, Select all writes `1-3` and turns into Select none, the
  bar says "Page 2" or "Selected: 3 of 3"), every selected page turned at once, Remove refusing to
  take every page ("Keep at least one page."), pages typed that don't exist selecting nothing and
  explained once a page is tapped, a page typed and removed with the bar, two pages tapped in one
  frame both selected, leaving with a page turned asking first, the saved `alpha_reorganized.pdf`,
  a blank page added and undone, a page removed with a screen reader's action, and the pages and
  the selection surviving the activity being recreated; Home and Choose a tool without Rotate PDF,
  Delete pages and Edit PDF; Flatten PDF's line saying what it does and its message when there is
  nothing to flatten; Annotate, opened from the reader: Save PDF not there before anything is
  added, text typed in a dialog and moved with a button, a filled rectangle from the Shape menu with
  the line saying it only covers, Undo and Redo, the text reachable and chosen by a screen reader
  even under the rectangle, an arrow and a pen stroke (with injected touches) on page 2, all of it
  surviving the activity being recreated, More without Share, Print or Open with another app once
  Save PDF shows, the saved `alpha_annotated.pdf` with the items where they were put and page 3
  untouched, Back to editing returning to Annotate on page 2 with the four items, and Back to
  reading showing "2 of 3" with the items and Save PDF still there;
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
  Home's search finding the five tools that take several files (Documents to PDF, Add page
  numbers, Add watermark, Compress PDF and Flatten PDF) by "several", "batch" and "many";
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
  French, Spanish and Hindi (French and Spanish accents kept), German and Portuguese text with
  umlauts, ß, tildes and cedillas, lines on a tilted page, an ID card's two sides (the page reported
  once, a word of each side found lying over its own side), that a failed or cancelled recognition
  leaves the PDFs untouched, and that an image under 32 pixels on a side is read rather than
  refused
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
  bitmap, a closed network, a missing model) with exceptions instead of crashing, every
  character the models' dictionaries hold is written into the text layer (circled digits too),
  rather than as a space, German and Portuguese letters are read (umlauts, ß, tildes and
  cedillas), an ID card's two sides are each read onto their own side, and PDF to images or text
  reads a page that is only a picture of printed words for its text
- the editions: text recognition (an engine, the text layer's font, the OCR switch in the
  Convert sheet with its text-language menu, and Home's words for Images to PDF in all seven
  languages) in Usugami and Usugami OCR and nowhere in Usugami Lite, the Google notices only in
  Usugami, and each of the seven languages known from its phone's locale (Portuguese from Portugal,
  German from Austria and Indonesian written "in" too), a language Usugami lacks falling back to
  English
- opening files: Usugami is in Android's "Open with" list once, under its own name, for the six PDF
  and document types, and for binary data, a ZIP file or no type when the address ends in .pdf,
  .PDF (after several dots too), .docx, .xlsx, .pptx or .epub, but not for binary data without such
  an ending, a text file, an old .doc or a file: address (on Android 15 and on Android 8); a file
  opened is copied in at once and waits for the reader, also through a process restart; a restart
  during the copy is told once and nothing waits; what a file is is told by its content (a Word file
  without an ending, a PDF called .txt), and a text file, an unreadable address and an address
  ending in .pdf that can't be read, whose app gives no name or type, are refused in words of their
  own; the file opened last wins, and a PDF shared afterwards takes its place
- the reader: a part of a page turned a quarter and cropped, drawn at 8 pixels a point, matches the
  whole page drawn (the dark pixels within a pixel), and the box Find text gives a word there covers
  that word's pixels and nothing near it; a 40-page PDF searched from page 31 marks page 31's match
  as soon as pages 31 to 40 are in, ends with all 40 matches in page order and the same match still
  marked, goes round with Next and Previous, isn't pulled back by earlier pages coming in, and
  leaves nothing found when stopped; a Word file opened is made into the PDF the reader shows (named
  after it, in its place), Save as PDF makes a result under the document's name, and an old .doc
  gets the converter's message and leaves nothing; the copy handed to another app (Share, Open with
  another app) is whole, named as a PDF, reused for the same PDF, left as nothing when cut short and
  one PDF's at a time; on a page turned a quarter and cropped, "Hello world" reads as written, as
  one line, a long-press on the second word's dark pixels takes "world" with its line box over those
  pixels, and a page that is only a picture has no text to select
- Documents to PDF's picks, through the view model: two presentations, a document and a text file
  give PowerPoint to PDF with the two decks in order and the left-out messages in order; a .docx and
  an EPUB without an extension are known by their content; a text file without one is named and
  nothing opens
- print: the PDF handed to Android's print system byte for byte, nothing written once printing is
  cancelled, and a file that has gone failing rather than printing nothing; a PDF saved to
  Downloads and listed in Recent files read back with the bytes saved, and not once deleted in
  another app (Android 10 and newer); Print opening Android's print screen from the activity, which
  covers Usugami and closes with it (it must run once below Android 13 to tell anything); skipped on
  a phone that can't print
- launcher shortcuts: Usugami started in French lists its four shortcuts in order (scan, images,
  merge, compress) with the French tile names, each opening this very app with its action and
  target; a shortcut used waits in the view model until taken, and the next one, the same again,
  still arrives; a shortcut that starts Usugami before a language is chosen is kept for after the
  language screen
- results that aren't PDFs: a JPEG and a .txt file left byte for byte as they were with the Usugami
  watermark on, each opened and shared with its own type (`*/*` for both together), and saved to
  Downloads under their names and listed in Recent files as `image/jpeg` and `text/plain` (Android 10
  and newer); a tool's job that makes no file shows no result and says its message word for word,
  and the progress a job reports shows while it works
- PDF to images or text: pages drawn as a viewer shows them (upright, turned a quarter, cropped to
  its top half, at the expected pixel sizes), a PNG without alpha of a password-protected PDF with a
  filled form field drawn in, a 5000-point page reported as reduced and within the caps, and a cancel
  after two pictures leaving none behind; the text of a page in its lines as the page breaks them,
  one empty line between pages, UTF-8 without a byte order mark, a turned page and a label turned a
  quarter on an upright page read in their lines, nothing of pages without text, of a form field's
  value or of text below a crop box, a Hebrew line in reading order, Japanese down the page (upright
  and turned) a column to a line, two Korean words down a column with room between them one line
  with a space; text recognition read only for pages without text (a page with only a filled field
  read with its field, an empty page never), its lines top first, a page it fails on named and the
  rest kept, nothing made when it can't get ready or when no page has text, and nothing left (the
  .txt or its spool) when a run is cancelled while a page is read; through the view model, a
  two-page PDF with a 5000-point poster page becoming `Poster_p1.jpg` and `Poster_p2.jpg` in
  `Poster_images.zip` with the poster noted, kept through the saved state, pages without text with
  "Read pages without text" off giving each edition's message, a page without text noted in
  `Mixed.txt`'s result, and the name example under the field cut exactly as the images are
- page layout, on PDFs made in the test: Crop giving the chosen pages a crop box that is the chosen
  share of the page as shown (to 0.1 point) and leaving the other pages, the text, five bookmarks,
  two form fields and the links as they were; a page turned 90° and already cropped cropped where it
  shows, also when a damaged page tree lists it twice; Pages per sheet making two A4 sheets from a
  password PDF without a password, pages across then down as text (a filled field's value drawn in),
  only forms on the sheets (nothing turned into a picture), a border round each page, and no links,
  comments, form or bookmarks; a turned page landing upright at the top left of its cell with what a
  crop box hides not showing; a cancel leaving nothing in `cache/output/tools`; and pages that share
  one list of every picture each getting a form listing only its own
- document info, on a fixture with info everywhere a PDF keeps it: `/Info`'s four fields (a
  PDFDocEncoding é read right), both dates as the moments they name, the apps, version and size,
  XMP's title and author filling what `/Info` lacks, a compressed packet in a password PDF read and
  one padded past 2 MB refused, and a signed PDF said to be signed; saving a title in Devanagari,
  Chinese and an emoji with the author emptied gives a PDF without a password whose `/Info` and XMP
  have just those changes, the same `/ID`, the PDF/A identification and rights still there, the page
  turn, crop box, link and bookmark as before, a packet with a DOCTYPE dropped and said, and an `/ID`
  of two numbers made random; Remove all info leaves no `/Info`, `/Metadata`, `/PieceInfo` or
  `/CreatorInfo` anywhere, no `/LastModified` on the page, a new random `/ID`, and none of the
  fixture's eleven secrets in the file's bytes, with only a title typed as well; through the view
  model, a PDF whose copy was cleared put away with its message, a text typed a change until it is
  the PDF's again, a line break typed or pasted one space, Undo after Remove all info, the form kept
  through the saved state (asking before leaving even before the info is read again), and the PDF
  saved as `report_info.pdf` with only the title typed
- black and white: a PDF made in the test with text in RGB red, a CMYK rectangle, a blue line, a spot
  colour at full and half tint, a tiling pattern, a form, an inline picture, a soft mask, a square
  comment, a link with a blue border, a shading, a pattern with hard colour stops, an RGB JPEG, a
  picture with see-through parts, a 2-bit palette, a CMYK picture, a picture with a colour key and an
  8-bit spot-colour picture with `/Decode [1 0]`, compressed with Black and white: no page left in
  colour, the text extracting the same, no content setting a colour but a gray, the soft mask's group
  byte for byte, the comments' colours gray, and Android's renderer drawing the pages with no colour
  and the original's lightness; a mesh gradient and a colour space that can't be read left with
  their pages named, a spot colour or palette that can't be read keeping its colours, colour nothing
  shows (a link with no border, an unused form or photo) not counted, and cancelling leaving no PDF;
  with the switch, a coloured PDF that can't get smaller coming out gray, a PDF with only gray kept
  byte for byte, a scan's colour photo one gray JPEG smaller than in colour, a password PDF gray
  without its password, and with several PDFs only those asked for gray; JPEGs in a spot colour or
  with an inverting `/Decode`, CMYK JPEGs (with and without an inverting `/Decode`, with a soft
  mask), JPEG 2000 pictures and a JPEG drawn in two bands each coming out a gray JPEG as light as the
  original drew, while a JPEG 2000 the renderer can't decode or with its transparency in its data
  leaves only its page in colour, bytes untouched, and PDF thumbnails still draw after it (on
  Android 8 too; the pictures come from `tools/make_gray_fixtures.py`, Pillow 12.3.0, run without
  `-I`); with the switch off, Compress is byte for byte what it was; and through the view model, the
  messages naming a PDF still partly in colour and one gray but no smaller, the outcome kept with the
  result after a restart, and a result kept by 1.6.5 coming back with none
- ID cards: an ordinary page and a two-sided card made into one PDF at Recommended, the card's sides
  sharing the second page, each 85.6 mm wide, centred across and as a pair down the page, front above
  back, each kept at twice the level's resolution (1011 pixels of 1712); made one PDF per image, the
  card one PDF named after its front; the card's sides sorted, moved (also by drag and drop) and
  removed together, by either side, through the saved state; the flow through the view model (the
  front a photo, the back an image chosen, each on its own crop screen with its look, a side
  discarded on its crop screen asked for again, the flow kept through the saved state, No back side
  and Add back side, Cancel while the front is still being added adding nothing, and a card whose
  front went while its back was cropped keeping its back alone)
- Chinese, Japanese and Korean, on PDFs made in the test with stand-in fonts that have no font file
  and no ToUnicode map: Find text reads Japanese, Simplified and Traditional Chinese and Korean in
  the encodings TCPDF, FPDF, iText and older Acrobat write, with one box per line and no page listed
  as unread; wrapped words, a spaced name, full-width and half-width letters; writing down the page
  in a vertical font, on an upright page and one turned a quarter, with one box over a word's cells,
  digits in a column part of the word, a word wrapped into the next column boxed in each, and Korean
  down a column found with and without the space; a page with a font naming a CMap PDFBox lacks,
  glyphs that read as nothing, a character outside the first plane or a form that can't be read
  listed as unread while the rest is searched, and those pages kept through the saved state; and a
  form whose fields are set in such fonts kept as it is by Merge PDF and Reorganize PDF (values kept,
  not redrawn, NeedAppearances still on, also for widgets only on the page and a CMap of the PDF's
  own), by Flatten PDF (both fields kept fillable) and for a text note in such a font
- the screens of 2.0.0's new parts: a PDF opened from another app showing in the reader with its
  name and "1 of 3", shown anew when opened again, Go to page to page 3 kept through the activity
  being recreated, Find text with the keyboard's search key ("Match 3 of 3", Next going round, Close
  search taking the matches away), More, Tools to Choose a tool, and Back leaving Usugami; Read and
  annotate on Home opening a file in the reader, a Compress result's Open showing a preview with its
  name, Go to page and Open with another app (no Tools) and Back returning to the result; a saved PDF
  tapped in Recent files opening in the reader; a word long-pressed and copied, and the page's "Copy
  the text of this page"; Documents to PDF opening PowerPoint to PDF with the decks picked and naming
  the document left out; Print buttons on the result screen and a Print action on a Recent file; a
  launcher shortcut over an unsaved PDF asking "Discard this PDF?", Keep leaving it and Discard
  opening Merge PDF empty; PDF to images or text's button saying "Convert to images" or "Convert to
  text", the text result with its card and Copy putting the whole text on the clipboard; results of
  images saying "Your images are ready", with no watermark switch and no page count; Page layout
  cropping with its buttons, keeping its box, choice and pages through the activity being recreated
  and saving `alpha_layout.pdf`, and Pages per sheet naming its preview "The first sheet, with 3
  pages" and saving one sheet whose pages are still text; Document info showing the title with Save
  PDF off, then Remove all info emptying the fields, saying "Will be removed" and saving; Compress
  PDF's Black and white row off at first, kept through the activity being recreated, worded for all
  PDFs with two, still on after Back from the result, and "Page 1 is still partly in color" under a
  PDF it couldn't fully turn gray; an ID card scanned front and back from Home's button onto one
  tile, and Cancel from the front going Home, No back side and Add back side (in Usugami and Usugami
  Lite); Redact PDF's notice naming the page Find text couldn't read; the three new tools opening from
  Home and taking a PDF; and the first-launch screen and Settings scrolling to all seven languages,
  German picked from the Convert sheet's text-language menu

Usugami gets its text-recognition model from Google Play services, so its OCR tests need a device
or emulator with Google Play services and, the first time each model is tested (one for the
Latin-script languages, one for Hindi), an internet connection.

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

- First launch: the language screen with its seven languages, then Home; Settings → Language
  switches every screen (also on Android 12 or older, where Usugami applies the language itself).
  On Android 13 and newer, the phone's own language settings for Usugami list the seven too; a
  phone set to Portuguese (Portugal) gets the Portuguese texts. German, Portuguese and Indonesian
  at the default and the largest font: Home, a tool, a result, the Convert sheet and the editor
  have nothing cut off.
- Home's search in each language with TalkBack on: type a few words, hear how many tools were
  found, clear it with the button and with Back.
- Images to PDF from Photos, Files and a share from the gallery, with OCR on in each language.
- A HEIC, an AVIF, a WebP, a BMP and an animated GIF, each from Photos, from Files and shared from
  the gallery, in Images to PDF and in Scan document's Upload images: each comes in the way the
  gallery shows it (the GIF as its first frame), also a HEIC taken in portrait, and the PDF looks
  the same in another viewer. On Android 8 a HEIC, and on Android 9 to 11 an AVIF, is skipped with
  a message naming the types that Android version reads. A photo added from Photos is named Photo_
  and the date and time it was taken, and so is the PDF made of it alone. Annotate: a HEIC photo
  taken in portrait goes on the page upright.
- Very large photos, on a phone with 4 GB of memory or less, and on Android 9 to 11, 12, and 13
  or newer: a 200-megapixel HEIC, a 48-megapixel iPhone photo in Display P3 and, from Android 12, a
  large AVIF are added to Images to PDF, Upload images and Annotate without running out of memory
  and no slower than in 1.6.1, and their colours in the PDF match the same photo saved smaller.
- From Files, share one PDF, two PDFs, a Word document and a PDF with images to Usugami: Choose a
  tool, the converter, and the message naming what was left out; then share a PDF while a tool has
  an unsaved result. Share ten PDFs and seven images together: the message names three of the
  images and says "and 4 more".
- With the screen timeout at 15 seconds: OCR on twenty photos, a long Word document, Compress PDF
  on a large PDF and a ZIP of many PDFs keep the screen on to the end, and it goes off 15 seconds
  after each one ends, fails or is cancelled.
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
- Split, merge in page mode, and compress, including a password-protected PDF. Compress a
  password-protected PDF that is already as small as it gets: Usugami says it couldn't be made any
  smaller, alone and with another PDF.
- Split PDF's names: split a 12-page Report.pdf as 1-2;3;4-6 with the name it starts with:
  Report_p1-2.pdf, Report_p3.pdf and Report_p4-6.pdf, in Report_split.zip. Typed as Chosen:
  Chosen_p1-2.pdf and so on, in Chosen.zip. Pages 10,1-3,5 put together: Report_p10,1-3,5.pdf, or
  just Chosen.pdf when typed as Chosen.
- Turn a few pages in Reorganize PDF, add page numbers with a skipped cover, add a watermark in
  Hindi, and sign a page turned sideways; check the results in another PDF viewer.
- Add page numbers, large, to an A4 PDF, a 2 by 1 inch label and an A0 poster, each also turned
  sideways and cropped: on A4 they look as in 1.6.1, on the label they are small and leave most of
  it clear, and on the poster they can be seen with the whole page on the screen.
- Reorganize a PDF: drag a page, add the pages of a password-protected PDF and a blank page after a
  page turned sideways, undo, and save. Remove a few pages of a PDF with bookmarks in Reorganize
  PDF. Flatten a
  filled-in form and a PDF with comments, and check in another viewer that nothing can be edited.
- Word to PDF and PowerPoint to PDF with a few real documents and presentations from Files; compare
  the result with the original, and check that an old .doc file is refused. A document with
  different odd and even headers shows the even ones on even pages, and a contents page keeps its
  page numbers against the right margin; a Japanese vertical text box stands upright.
- Word footnotes and endnotes, next to Word's own PDF of the same documents: one with footnotes on
  several pages, a long one near the foot of a page (it goes on at the foot of the next page under
  a line as wide as the text, or its reference's line moves there with it) and endnotes at the
  end; one numbered with Word's * and † symbols, starting again on each page; one with endnotes at
  the end of each section. Every mark matches its note, in the text and at the note's start.
- Word, PowerPoint, EPUB and Excel files with a pasted 100,000-character base64 block, and one with
  hyphens in it, also on Android 8: each converts in seconds without running out of memory, its
  lines broken at the line's end (after a hyphen when there is one), not after its slashes.
- Excel to PDF with a few real workbooks from Files, and a sheet with dates, times, currency,
  percentages, accounting formats and [Red] negatives: each cell reads as Excel prints it (an
  accounting format's currency sign sits next to the number). Then a wide sheet with Fit columns to
  page width on and off, a long sheet, a sheet with a print area and title rows (repeated on every
  page), headers and footers with page numbers (counted through the whole workbook), pictures, and
  a hidden sheet (left out); Gridlines off and on. An old .xls file and a password-protected
  workbook are refused with a message saying what to do. Share an .xlsx to Usugami from Files: Excel
  to PDF opens with it. Convert three workbooks at once.
- EPUB to PDF with a few real DRM-free books, one with a cover and one fixed-layout; check the
  bookmarks in another PDF viewer, and that a book with DRM is refused. A Japanese book written in
  columns down the page fills the page's width, and Chinese or Japanese sentences have no stray
  spaces. A Hebrew or Arabic paragraph with a line break before a Latin word or a link, and an
  Excel cell like it, read right to left on every line.
- Excel to PDF with the phone's region set to India and then to the United States: 1234567.89 in a
  cell with thousands separators reads 12,34,567.89 and then 1,234,567.89. A cell holding -0.001
  shown to two decimals reads 0.00. A cell of Hebrew or Arabic text with no alignment of its own
  starts at the right of its cell, and a cell of Latin text at the left.
- Images to PDF with forty photos, one PDF per image: tap the Usugami watermark switch and save a
  ZIP file of the PDFs straight away: every PDF in it has the watermark.
- Scan document with a tall photo of a page turned about 30 degrees to the right: after the crop
  step the page stands upright, not on its side.
- Scan document with a photo of a textbook's page taken close, a ruled table printed on it: the
  crop step never outlines the table. With three of the page's edges in the photo and the desk
  beyond them, it says the page was found and outlines the paper as far as it shows; with the
  page filling the photo, or running off it on two sides, it says to drag the corners and the
  outline starts a little in from every side.
- Settings → Donate opens buymeacoffee.com/nath_goa in the browser once, however fast it is
  tapped twice; with no browser installed, a message says no app can open web links.
- Recent files: save a PDF with Save as, delete it in the Files app and come back to Usugami: it
  has left the list.
- A digitally signed PDF that Compress PDF kept as it was and Usugami watermarked, then signed
  again or given a note in another app: Compress PDF, and changing the watermark or turning it
  off, keep the second signature or the note.
- Redact PDF with a few real documents, a scanned one among them: find a name, draw a box on the
  scan, and check in another PDF viewer that nothing under the boxes can be selected, searched or
  copied, and that the pages without boxes still have their text. Find text also finds a name in a
  heading set in spaced capitals, a name or email address broken onto the next line after a
  hyphen (all of it boxed, also Bob after a line ending in Bo-), and a word hyphenated at the end of
  a line.
- Sign PDF in landscape and in the dark theme: the pad fills the screen, drawing feels smooth, and
  dragging the signature doesn't scroll the screen (a swipe off it does).
- Annotate, from the reader, on a page turned sideways: add text in Hindi, a photo and a PNG with
  transparency, draw and highlight, drag and resize with the handles, undo and redo, then check the
  result in another PDF viewer (the highlight see-through, the text selectable). Close Usugami from
  the recent apps while editing and reopen it: the items are still there.
- Open, Share, Save to Downloads (Android 10 and newer) and Save as….
- Usugami watermark: after a fresh install the switch at the top of the result screen is off and
  the PDF has no mark. Turning it on puts a small, grey, half see-through "Usugami" upright in the
  bottom-right corner of every page, also on a page turned sideways and on a page whose crop box is
  smaller than its media box; check it in another PDF viewer. The arrow on the switch's row opens
  Size and Opacity sliders with a live preview, at 20% size and 30% opacity at first. At the
  largest size the word's left edge reaches the page's vertical centre line; at the smallest it can
  only just be made out with a whole A4 page on the phone's screen; opacity goes from faint to
  solid. Letting go of a slider redraws the mark in the PDFs, and Open, Share and Save wait for it.
  With TalkBack, the row is one switch and the arrow says what it opens; with the largest text in
  landscape, the row and the sliders can be scrolled to.
- Usugami watermark on and off: save a PDF with the mark, turn the switch off, and the mark is gone
  from the PDF just made, without making it again; Save then writes the clean version. Close
  Usugami and open it again: the switch and the sliders are as they were left. With it on, make one
  PDF per image, Split a PDF into parts, compress a batch (one PDF in it already as small as it
  gets, so its original is kept) and convert a batch of Word documents, then share each result as a
  ZIP: every page of every PDF has the mark.
- Usugami watermark as shapes: in another PDF viewer, searching a watermarked PDF for Usugami finds
  nothing, its copied text has no "Usugami", and TalkBack doesn't read it. The page under the
  sliders shows the same letters as the PDF, sharp at every size, also on Android 8. A PDF saved
  with the mark by 1.6.1, put through Compress PDF (its original kept) with the mark on, comes out
  with the new mark only, and with no mark once the switch is off.
- Usugami watermark on signed PDFs: compress a digitally signed PDF that is already as small as it
  gets, with the mark on. Acrobat Reader shows its signatures valid, the mark as a change after
  signing; turn the mark off and the file is the original byte for byte (same SHA-256). Again with
  bytes added after the signed PDF's end, and again by compressing the marked PDF a second time. A
  PDF certified to allow no changes gets no mark, and the message says so once.
- Android closing Usugami while it makes a PDF: tap Convert on a long document, put Usugami in the
  background, stop it (`adb shell am kill io.github.nath_goa.usugami`) and open it again from the
  recent apps: it says it was closed before the PDF was finished. Save a PDF to Downloads, stop
  Usugami the same way, open it and save again: Downloads has one copy.
- Largest text and display size, in landscape: the password box of a PDF with a long name, and
  Settings → Language and Appearance, can all be scrolled to. In the light theme, the clock and
  battery above the signature pad can be seen.
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
- Usugami: the first OCR downloads the model through Google Play services (one for English, French,
  Spanish, German, Portuguese and Indonesian, and one for Hindi).
- Usugami OCR: OCR works with the internet turned off, on a real ARM phone, with a few photographed
  pages in each language (one upside down); note the time per page. A poster or a sign photographed
  close up, and a postcard, have their very large words read whole.
- Usugami Lite: no OCR switch when creating a PDF, PDF to images or text says it can't read scanned
  pages, About shows "Usugami Lite", and searching Home for HEIC finds Images to PDF. Installed next to Usugami OCR, it is a separate app named "Usugami
  Lite" in the launcher; a PDF shared from one opens in the other.
- The About screen shows the license, privacy policy and notices.
- Open with: a PDF, a .docx, an .xlsx, a .pptx and an .epub each from the Files app, a browser's
  downloads and a chat or mail app: Usugami is listed once, by name, and with Usugami Lite installed
  too, each once under its own name; a .doc, a photo and a .txt don't list it. A page over 200 times
  as tall as wide opens, narrower than the others, in its shape and centred. A password PDF opened
  from Home or from another app asks once; Cancel in the password dialog and during "Opening…"
  goes back where the file was opened from. A large file from a cloud provider: "Opening…" shows at
  once and Cancel stops the copy.
- Usugami as the phone's PDF app: choose "Always" for PDFs. A result's Open shows the preview,
  Recent files open the reader, and Open with another app lists the other apps and not Usugami.
  Settings → Apps → Usugami → Open by default → Clear defaults undoes it.
- The reader's zoom: pinch in and out stays under the fingers; double-tap in and back; a sideways
  pan while zoomed in stops at the page's edges, and quick sideways swipes don't zoom; zoomed out to
  half, the pages are centred and a finger beside them scrolls and flings them; at five times, small
  print at a page's right edge, on a turned and on a cropped page, is sharp and doesn't jump against
  the blurry part, and found matches sit on their words; a page far taller than wide is sharp all
  the way to its end, also the 1:20 strip zoomed; the zoom and page come back after turning the phone,
  also while zoomed, and after Android closes Usugami; TalkBack's Zoom in and Zoom out.
- The reader's memory: a 2,000-page PDF, a 150 MB scan and a poster page several metres long:
  scroll fast, zoom to five times and search; `adb shell dumpsys meminfo` stays flat.
- The reader's Back: opened from the Files app, Back returns to Files, and so do Cancel during
  "Opening…" and Cancel in the password dialog; Usugami opened later shows Home. Images waiting in
  Images to PDF are still there; with additions, the question comes first; in split screen and with
  predictive back (Android 14 and newer), no glitch.
- Restarts ("Don't keep activities", and `adb shell am kill` with Usugami in the background):
  while reading (page, zoom and search come back), while a .docx is converted (it starts again), in
  Annotate with items (they are there), in a preview (it reopens), and while a large file is copied
  in ("Usugami was closed before it had opened your file").
- A file opened with Usugami over an unsaved Compress result (the question; Keep, then leaving the
  tool opens it), over the reader with additions, over a running Images to PDF job (it waits), over
  the crop screen and over the language screen of a fresh install.
- Documents in the reader: one of each kind, a 300-page book and a wide workbook; Cancel during the
  conversion; a .doc (its message); Save as PDF and its result (Share, Save, Print and the watermark
  switch); Annotate on a .docx, with Save as PDF hidden while Save PDF shows.
- Find text in the reader: Hindi, Arabic, a phrase across a line break, a turned page, "e" in a
  long book (the first 2,000 matches), closing during the search, and a scan (its message).
- Selecting text: the handles at half size, at 1 and at five times; across two pages; a handle
  dragged to the bottom edge scrolls the pages, and text selected there moves up above the Copy
  bar; Arabic or Hebrew, and vertical Japanese (the handles' ends, the text copied in reading
  order); a turned page; a form's filled-in text; a scan (Usugami Lite's own words in Usugami Lite);
  TalkBack's "Copy the text of this page" in the actions menu; "Text copied" below Android 13.
- Share and Open with another app from the reader: the receiving app sees the file's own name, a
  password PDF asks for its password there, Share then Open with another app copy it once, and only
  the last PDF's copy is kept. A 150 MB PDF: the share sheet comes after the copy, once for two taps.
- Annotate, from the reader: on a password PDF and on a Word file (Save PDF; Save as PDF hidden
  meanwhile); a picture from the photo picker lands mid-page, on a PDF opened from Home and on one
  opened with "Open with", and again after Android closed Usugami while the picker was open; Keep over
  a file opened meanwhile, then Save PDF. With additions not saved, More has no Share, Print or Open
  with another app.
- Update over 1.6.5: with Edit PDF open (a shape added) or Rotate PDF open in 1.6.5, install 2.0.0
  with `adb install -r`: Usugami starts on Home, without a crash.
- Documents to PDF: the picker on Android 8 or 9 and on Android 14 or newer shows .docx, .pptx, .xlsx
  and .epub files and ticks several; files from Google Drive work; one file of each kind picked
  together opens Word to PDF and names the other three; a large cloud file keeps Home responsive
  while it is sorted (a second pick meanwhile is ignored); "Don't keep activities" with the picker
  open still opens the converter.
- Print: the preview on Android 8, 10, 13 and 16; paper size and orientation; a turned, a cropped,
  a filled and a signed PDF; the Usugami watermark on, then off; a PDF over 100 MB; turning the phone
  and changing the language with the print screen open; a Recent file deleted in the Files app
  (Usugami says so, and a Recent file's Print does nothing once Usugami has left the screen while the
  file was checked); the print spooler turned off; the four buttons on a 360 dp phone in English;
  a password PDF from the reader (no password asked in the print screen).
- Launcher shortcuts: a long-press on Usugami's icon shows Scan document, Images to PDF, Merge PDF and
  Compress PDF, in that order, with round lilac icons. Each with Usugami closed (also a cold start
  behind the language screen), on Home, in a tool with changes ("Discard your changes?"), on a saved
  result (it opens, no question), on an unsaved image or text result ("Discard what you made?"),
  during a long Compress (the message, and the job goes on), in Images to PDF (Scan document adds a
  photo; Merge PDF opens over the images and Back returns to them), with Usugami in the background
  on the editor (Scan document opens the camera), on Choose a tool, and during a scan (it brings the
  scan back). Not looked at yet: Samsung's launcher, a pinned shortcut after reinstalling, the reader
  with additions not saved (asks) or a file only read (opens), a shortcut while a picker or the
  print screen is open, and Android 16 and 8.
- Results that aren't PDFs, on Android 10, 13 and 16: a JPG saved to Downloads keeps its name and
  shows in a gallery, a .txt opens in a text viewer or says there is no app; on Android 8 and 9,
  Save as… offers the file with its type and name; Save to folder with images on another documents
  provider (an SD card, a cloud) doesn't double the ending; with the watermark on, a result of
  images shows no "Adding the Usugami watermark…" step, and later changes of the setting don't touch
  it.
- PDF to images or text: a text longer than 12 lines shows Show more and Show less, and after Show
  more, a few screens of scrolling and turning the phone, the card stays expanded near the place; a
  text over 20,000 characters ends with the "beginning" line; Hebrew or Arabic paragraphs are set
  from the right; copying 150,000 characters pastes whole, over 200,000 gives the too-long message,
  and Android 13 and newer show only the system's own confirmation; the notes on scans read with
  text recognition, failed pages, pages without text (Usugami Lite, the switch off, nothing found),
  forms and reduced images; the standard edition in flight mode with the switch on gives the "Text
  recognition isn't available" dialog ending "To get only the text that can be copied, turn off
  "Read pages without text" and try again."; Android closing Usugami during a run says "Usugami was
  closed before it had finished."; a real landscape PDF with /Rotate 90 and one with sideways labels
  on an upright page are read in lines; with TalkBack, choosing Text makes the button say "Convert
  to text", and a page tapped changes the count line.
- Page layout with a finger on a real phone: the handles drag without scrolling or going back, and
  Back to editing keeps the box. Turned and cropped real PDFs, checked in two viewers; sheets of real
  PDFs (gradients, patterns, transparency, a scan, a filled form with comments, layers, shared
  resources); size and memory on a 300-page PDF and a long scan; huge PDFs, and Cancel leaving
  `cache/output/tools` and `cache/pdfbox` empty; 9 per sheet on a slow phone; a password PDF on
  Android 8; the Usugami watermark on the result; one sheets job in a release build of each edition;
  the sheets made on the paper the Paper size row shows; TalkBack on the crop preview, the step
  buttons and the count.
- Document info on a dozen real PDFs from different makers (Word, LibreOffice, Acrobat, a scanner
  app, a PDF/A file): their XMP is read, and a title changed in them reads back in Acrobat or with
  `exiftool -xmp:all`, the PDF/A identification still there. Remove all info on a PDF of several
  hundred MB and on one with thousands of pages finishes or cancels (out of memory says "Couldn't
  process this PDF"). Signed and password PDFs on a phone. Typing: fast, in Hindi, Japanese, emoji
  and right-to-left text, a long paste cut at 2,000 characters, the keyboard not covering the field,
  turning the phone with the keyboard open, the largest font, and TalkBack reading each row as
  "label, value" and the card after Remove all info. Android closing Usugami with the form changed
  and during a save; the cache cleared with Document info open or about to open (Settings → Apps →
  Usugami → Clear cache): "1 file was removed…" said once and the PDF put away; Back and removing
  the PDF ask only with changes; Back to editing keeps what was typed.
- Black and white: a real scanned PDF with JPEG 2000 pages and a real print PDF with CMYK JPEGs come
  out gray, the right way up, not blank and not negative (an Adobe-inverted CMYK JPEG looks as in
  the phone's viewers). TalkBack reads Compress PDF's Black and white row as one item with its
  title, detail and state.
- ID card: the camera's photo comes back as the card's side after Android closed Usugami behind the
  camera app, on Android 8 and on Android 16, also from the front's prompt on Home. A real card
  printed at 100% on A4 and on Letter measures 85.6 mm, and the PDF looks right in two viewers. A
  real card at Recommended and Extreme, zoomed to its small print, with the file size (under about
  400 KB for two sides at Recommended). With text recognition on, a word from each side is found in
  a viewer on its own side. The page finder on real card photos, also a light card with a dark band
  near its foot (a bank card's stripe), once outlined without its last 6% and still said found. Not
  tried yet: two 50-megapixel photos as a card's sides, and TalkBack through the flow. Home's search
  for "passport", "ID card" and "licence" finds Scan document alone; aapt shows no camera permission.
- Reorganize PDF with TalkBack: a page is read as a check box with its turn, the count and the
  field's messages are read as they change, each page has its actions, and the bar's buttons are
  named. `5,1` typed, then a tap on page 3, gives `1,3,5`; then Move earlier, then a blank page. The
  saved files in another viewer (bookmarks, links, form values, a password PDF), and Merge PDF's page
  mode. At 360 dp with the largest font, the header row and the bar (also in German) fit.
- Redact PDF's notice under Find text: in the light and dark themes and in landscape, a long list
  ending in "…", read once by TalkBack when it appears, no gap under the field while it is hidden,
  and gone once the PDF is removed and another added.

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
