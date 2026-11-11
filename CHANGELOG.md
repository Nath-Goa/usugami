# Changelog

All notable changes to Usugami are listed here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 1.2.0 on.

## [Unreleased]

## [1.6.5] - 2026-11-11

### Changed

- **Scan document** and the crop step find a page that runs off the photo on one side, or ends at
  the photo's border there, such as a book's page photographed close: the outline is the paper as
  far as it shows, with the photo's border for that side and the page's own edges for the other
  three. Such a page was never found before, and the outline started 8% in from every side. It is
  found when the paper ends at those three edges with the desk beyond them, and the page shows its
  print. With fewer than three of the page's edges in the photo, the outline still starts 8% in,
  to be dragged: on real photos two edges proved too little to tell a page by.

### Fixed

- **Scan document** and the crop step: a table printed on a page is no longer taken for the page.
  On a photo of a textbook's page taken close, the page as wide as the photo, the outline went
  round a ruled table on the page and the screen said the page was found. Now an outline whose
  sides are rules drawn on the paper, or beyond which the printed paper goes on, is not the page:
  a table, a box round an example, a tinted panel or a printed photograph. Thin rules and print
  are looked at on a finer copy of the photo, where they show. Where the page itself can't be
  told, the screen says to drag the corners instead of offering the table.
- The crop step no longer draws a side of the outline on past the page's corner to a line that
  only passes by, such as a streak in the wood of the desk above the page or a rule printed across
  a receipt. A page lying square on a wooden desk is found more often: the grain alongside it no
  longer counts as the page's edge going on past its corners. In return, a page whose edge is
  covered or fades out right beside a corner, on a desk as white as the paper, is sometimes not
  found for sure, and its corners are then dragged.

## [1.6.4] - 2026-11-10

### Fixed

- **Excel to PDF** starts Arabic and Hebrew text at the right of its cell and lets it run on into
  the empty cells to its left, as Excel does for a cell with no alignment of its own, where it sat
  at the left and ran on to the right. In a cell that wraps its text, such text set to the left or
  to the right now goes to that side instead of the other.
- **Images to PDF**, one PDF per image: a ZIP file of the PDFs saved or shared just as the
  **Usugami watermark** is switched on or off, or changed, waits for the watermark and then holds
  every PDF with it. It could hold some PDFs with the watermark and some without while the screen
  said it was saved, or fail.
- **Usugami watermark**: the message that a PDF certified against any change gets no watermark is
  said once, not again each time Android closes Usugami in the background and the result shows
  again.
- **Usugami watermark**: a crafted PDF whose signature claims an impossibly long signed part gets
  the watermark like any unsigned PDF, where adding it failed with a message.

## [1.6.3] - 2026-11-10

### Fixed

- **Scan document** and the crop step: a page lying tilted on a tall or wide photo, such as one
  turned 30 degrees to the right on a phone's tall photo, comes out of the crop step upright
  instead of on its side. Its top-left corner is now the one nearest the photo's top-left in
  pixels, not in fractions of the photo's sides.
- **Excel to PDF** groups digits the way the phone's region does, as Excel does on a computer set
  to that region: with the phone set to India a number reads 12,34,567.89 instead of 1,234,567.89.
- **Excel to PDF** shows a negative number that rounds to nothing as Excel does, 0.00 instead of
  -0.00, such as the tiny amount left over when a sum should come to zero. A format with a section
  of its own for negative numbers still uses it.
- **Recent files**: a PDF saved with Save as, or into a folder picked to save into, leaves the list
  once it, or that folder, is deleted or renamed in the phone's Files app, instead of staying
  listed as a file that can't be opened.
- **Compress PDF** and the **Usugami watermark** keep what another app added to a digitally signed
  PDF after Usugami's watermark, such as a second signature, a note or a page. Compress PDF cut
  such a PDF back to how it was first signed and said it was kept as it was, and changing the
  watermark or turning it off cut it back too.

## [1.6.2] - 2026-11-09

### Added

- **Word to PDF** prints footnotes and endnotes, which it used to leave out. Each note's mark
  appears in the text in the document's style, usually raised, and again at the start of the note.
  Footnotes go at the foot of the page where their reference falls, above the bottom margin and
  under Word's short line, or just below the text when the document puts them there. A footnote
  that doesn't fit goes on at the foot of the next page, under a line as wide as the text; a long
  one keeps at least two lines on its reference's page, as Word's widow and orphan control does,
  and when even that doesn't fit, the line with the reference moves to the next page with it.
  Endnotes go at the end of the document, or at the end of each section when the document says so.
  Notes are numbered as the document says: footnotes 1, 2, 3 and endnotes i, ii, iii unless it says
  otherwise, or letters, roman numerals or Word's * and † symbols, starting again in each section
  or on each page when it asks, also when a reference moves to the next page. A note with a mark of
  its own, such as *, keeps it.
- While a job shows its progress (making a PDF from images, reading its text, converting a Word,
  PowerPoint, Excel or EPUB file, any PDF tool, the Usugami watermark or a ZIP file), the screen
  stays on instead of going off after the phone's timeout. It goes off as usual once the job ends,
  fails or is cancelled.

### Changed

- **Split PDF** names each PDF by the pages it holds, in every way of splitting: Report_p3.pdf for
  page 3, Report_p3-5.pdf for pages 3 to 5, and Report_p10,1-3,5.pdf for pages 10, 1 to 3 and 5
  put together in that order, where it was Report_split_1_3-3.pdf. A long list of pages keeps its
  first pages and its last, as in Report_p1,3,5,…,99. The file name starts as the PDF's own name,
  Report, where it was Report_split. A name typed instead takes its place, as in Chosen_p1.pdf, and
  names their ZIP file as it is, Chosen.zip, as before; with the PDF's own name, the ZIP file is
  still Report_split.zip. A single PDF given a name of your own keeps exactly that name.
- **Add page numbers**: the numbers, and their distance from the page's edges, grow and shrink with
  the page as it is shown, turned and cropped. They look as before on A4 and Letter paper, but are
  small on a small label instead of covering it, and large on a poster instead of being too small
  to see.
- A message about files left out or that couldn't be used (shared files, picked PDFs or documents,
  and PDFs worked on several at once) names the first three and says how many more there are, such
  as "a.pdf, b.pdf, c.pdf and 247 more", where it named every one, and still counts them all.

### Fixed

- **Word, PowerPoint, EPUB and Excel to PDF**: text that runs on without a space for more than a
  line, such as a pasted hash, key, base64 block or list of codes, no longer takes huge amounts of
  memory: 100,000 such characters, with or without hyphens, took hundreds of megabytes, about 2 GB
  on some phones, and now take under 10 MB. Such text breaks where Word breaks it, after a hyphen
  when there is one and otherwise at the end of each line, character by character, and no longer
  after its slashes. A word wider than the line goes on lines of its own, and in a justified
  paragraph the line before it, or the one holding its start, stays justified.
- **Images to PDF**, the scanner's **Upload images** and **Edit PDF** read a very large HEIC or
  AVIF photo, such as one of 200 megapixels, a tile at a time, in about a quarter of the memory it
  took, so it is no longer at risk of being too big to add. It is kept at the same size and in the
  same colours as before.
- **Usugami watermark**: it is drawn as the outlines of its letters instead of written as text, so
  searching, selecting or copying a watermarked PDF's text no longer finds "Usugami", and screen
  readers no longer read it out. It looks as before: a bold "Usugami" in the same grey, size, place
  and opacity. The page under its sliders shows exactly the letters the PDF gets, where it
  stretched the phone's own bold font to fit. A PDF watermarked by 1.6.1 still has its watermark
  taken off exactly, and gets the new one when it changes.
- **Usugami watermark** on a digitally signed PDF, such as one Compress PDF keeps as it was: the
  watermark is added after the signed part, so the signatures stay valid, instead of the whole PDF
  being saved again, which broke them. Changing the watermark or turning it off gives back the
  signed PDF byte for byte, also when it has extra bytes after its end, as some downloads add.
  Compress PDF run again on such a PDF keeps it as it was signed when it can't make it smaller. A
  PDF whose certification allows no changes at all gets no watermark, and a short message says so
  once.
- **EPUB to PDF** and **Excel to PDF**: a paragraph, or a cell's text, reads in one direction on all
  its lines, the one its first letter gives. A line after a line break no longer takes its own
  direction, so a Hebrew or Arabic paragraph's line starting with a Latin word or a link no longer
  reads left to right.

## [1.6.1] - 2026-11-09

### Added

- **Images to PDF** and the scanner's **Upload images** take HEIC, AVIF, WebP, BMP and GIF images
  as well as JPG and PNG, from Photos, Files or a share, and put them on the page the right way up,
  as the gallery shows them. HEIC needs Android 9 or newer, and AVIF Android 12 or newer. An
  animated GIF gives its first frame.
- A photo added from **Photos**, Android's photo picker, which hides the photo's file name, is named
  Photo_ and the date and time it was taken, instead of a number such as 1000000163, and so is the
  PDF made of it alone.
- An optional **Usugami watermark**, to support the app: the word "Usugami" in the bottom-right
  corner of each page, small and light grey unless you change it, and upright on turned pages too.
  A switch at the top of the result screen, above Open, Share and Save, turns it on or off for the
  PDFs just made, in every tool, without making them again. The arrow on that switch opens **Size**
  and **Opacity** sliders with a live preview: the size goes from barely visible up to half the
  page's width, and the opacity from faint to solid. It is off until you turn it on, and Usugami
  remembers the choice and the sliders. A copy saved before a change stays as it was; save again
  for the new version.

### Changed

- The message for skipped files names the image types Usugami takes, and on Android 8 to 11 only
  those that Android version can read.
- Images to PDF's card on Home and its empty screen say "images" instead of JPG and PNG, and Home's
  search finds it by JPG, PNG, HEIC or WebP.

### Fixed

- **Word to PDF**: documents with different headers and footers on odd and even pages show the even
  ones on even pages, instead of the odd ones on every page. Headers, footers and contents lines
  with centre and right tab stops keep their middle and right-hand text on the line where Word puts
  it, and their page numbers against the right margin when their style indents the first line.
  Lists in legal style number their sections 1.1 under Article I, not I.1. Cancel stops at once on
  a table thousands of rows long or a table cell holding a long text.
- **PowerPoint to PDF**: text boxes that shrink or grow with their text keep their own alignment,
  direction and line wrapping, so text set not to wrap no longer runs onto extra lines over other
  text. Chinese, Japanese and Korean text in vertical text boxes stands upright, one character under
  another, while Latin letters still turn as in PowerPoint.
- **EPUB to PDF**: Chinese and Japanese books whose files break a paragraph's lines no longer get
  stray spaces in the middle of sentences, also next to italics, links or other marked words.
  Japanese and Chinese books written in columns down the page fill the page's width instead of a
  narrow strip.
- A damaged PDF whose list of pages is wrong (miscounted, a page without its type, or a list that
  leads back into itself) opens with the pages other viewers show, so the PDF tools no longer fail
  on it, Merge PDF no longer puts another PDF's page in its place, and its previews and Redact PDF
  see those same pages.
- **Split PDF**: links between pages that go into the same part keep working, also links to a named
  place, and a damaged named place no longer makes Split fail. The parts split from a PDF with a
  very long name all begin with the same name and keep their page numbers and the _split ending.
- A PDF with a very long name keeps the ending each tool adds to it, such as _redacted, as the name
  is shortened instead.
- **Compress PDF** says when a PDF that had a password couldn't be made any smaller, instead of
  handing back a bigger file without a word.
- **Flatten PDF**, **Sign PDF**, **Edit PDF** and **Redact PDF** show, in some forms, the name
  chosen in a drop-down list, such as a country's, instead of the code the form keeps for it or
  another choice's name.
- **Edit PDF**: a HEIC or AVIF photo stored turned goes on the page the way the gallery shows it,
  not a quarter turn off.
- A page's small picture in the editor, the crop screen's picture and a picture put on a page in
  Edit PDF no longer have a thin grey line along one edge, which showed on white paper and in the
  PDF.
- **Redact PDF**: Find text finds letter-spaced text, such as a name in a heading set in spaced
  capitals; a name, email address or number that breaks onto the next line after a hyphen, boxing
  all of it even when the line before ends the same way (Bob after Bo-); and a word hyphenated at
  the end of a line. Patterns and transparency masks that only a redacted page used are removed too
  where pages share them, so their text no longer stays in the file.
- **Scan document** and **Images to PDF**: a file shared to Usugami while a page is on the crop
  screen no longer loses the corners moved, the Filters step or the look chosen there: Back from the
  tool it opened returns to the page as it was left. Create PDF tapped just after a photo is taken,
  while its crop screen is still opening, no longer opens the PDF choices over that screen, so the
  photo can no longer go into the PDF uncropped.
- A tool's PDF given up for images shared while it was being made no longer shows as being made in
  the next tool opened.
- When Android closes Usugami in the background while it is making a PDF, Usugami says so when you
  come back, instead of showing the screen as if Convert was never tapped. Saving to Downloads or a
  folder again after Android closed Usugami no longer adds a second copy of each PDF there.
- With large text in landscape, the PDF password box can be scrolled to (a long file name used to
  push it out of sight), and the Language and Appearance choices in Settings and the text
  recognition problem message scroll instead of being cut off.
- The clock and battery above the signature pad show in dark icons on the light theme, as on every
  other screen, instead of white ones that couldn't be seen.
- **Usugami OCR** reads very large words, such as a poster's, a sign's or a slide title photographed
  close up, also on small pages such as a postcard, which it used to leave out or read only in
  pieces. A stamp, logo or picture beside the text no longer moves lines out of order.
- Crafted Word, PowerPoint and Excel files built to keep Usugami busy, in more of the ways such
  files are made (written in UTF-16, for one, or with tags and text hidden behind comments), are
  refused as damaged in moments, instead of keeping Usugami busy for minutes where Cancel couldn't
  stop it. Leaving Word, PowerPoint, Excel or EPUB to PDF while such a file is being checked stops
  the check at once, so the next file opened no longer waits behind it, and Cancel also stops a
  conversion stuck reading the links between such a file's parts.
- A crafted PDF whose small parts unpack to gigabytes can no longer fill the phone's storage while a
  PDF tool reads it: Usugami leaves a quarter of a gigabyte free for the phone and its other apps,
  and says storage ran out if it needs more.
- **Android 8**: opening a PDF with a password no longer stops Android's renderer from drawing any
  PDF until Usugami is closed, which left page previews, Redact PDF, Edit PDF and Sign PDF blank and
  made redacting fail. Excel to PDF makes PDFs whose text looks, searches and copies right, where
  viewers drew each letter about nine times as wide, over the letters after it. With Dark or Light
  chosen in Settings, the status bar's icons follow it, where they could be hard to see.

## [1.6.0] - 2026-11-09

### Added

- **Scan document**, a large card at the top of Home: **Take photo** opens your phone's camera app,
  and **Upload images** picks one or more images from your gallery. Each page then goes through two
  steps: **Crop page**, then **Filters**. After a photo, **Take another** or **Done**; uploaded
  images come one after another ("Page 2 of 5"), with **Next**, and **Done** on the last. Closing
  part way keeps the images not cropped yet in the page list as they are. After Done, the pages wait
  in Images to PDF, to reorder and make into a PDF as before; a scan from Home that ends with no
  page, such as the camera closed without a photo, goes back to Home. Usugami still asks for no
  permissions: your camera app takes the photos.
- **Filters**, the second step: the page large, as it will be in the PDF, each look shown on the
  page itself, and **Brightness** and **Contrast** sliders that change every look, black and white
  included. **Apply to all pages** gives every page the look, brightness and contrast, also the
  images of a scan still being added.
- Three new looks, for seven in all (Original, Lighten, Enhanced, Super enhanced, Grayscale,
  Black & white and Ink saver): **Lighten** brightens the page and softens its shadows, keeping it
  close to the photo; **Super enhanced** makes the paper whiter, the print darker and sharper and
  colours richer; **Ink saver** is black and white that leaves large dark areas hollow, to save ink
  or toner, and is stored one bit a pixel too.
- **Crop page**: a handle in the middle of each side moves that whole side, and **Rotate left** and
  **Rotate right** turn the image a quarter at a time, on both steps.
- A screen reader hears which page of a scan each step is on ("Crop page, Page 2 of 5") and finds
  Scan document as a heading; with large text, the buttons under each step stack instead of
  breaking words.
- **Excel to PDF**, under Create PDF on Home, after PowerPoint to PDF. Usugami reads .xlsx workbooks
  (.xlsm and templates too, leaving their macros alone) on your phone, with no upload, and puts each
  visible sheet on pages as Excel prints it: its print area, or everything from A1, with its title
  rows and columns on every page, its page breaks, and its paper size, orientation, margins, scale
  and Fit to. Cells show their numbers, currencies, percentages, fractions, dates and times as
  Excel shows them, red negatives and other format colors included. Fonts, colors, fills, borders,
  alignment, wrapped and merged cells, column widths and row heights, pictures, and headers and
  footers carry over, with pages numbered through the whole workbook. Hidden sheets, rows and
  columns are left out, and so are pages with nothing on them. The text stays searchable, and
  Home's search finds the tool by words such as "spreadsheet" or "xlsx".
- Excel to PDF's choices, the same for every workbook: **Page size** and **Orientation** ("As in
  the file" at first, or one for every sheet); **Fit columns to page width**, on at first, which
  shrinks a wide sheet so its columns fit across the page, but never below half size (a sheet set
  to fit its own way keeps that, and with it off, columns that don't fit go on more pages, as Excel
  prints them); and **Gridlines**, off at first, as in Excel: a sheet set to print its gridlines
  always gets them, and with the switch on, light gray lines also go on every sheet that shows them
  on screen. Pick several workbooks to convert them at once, one PDF each.
- What Excel to PDF leaves out: charts, shapes, comments, conditional formatting (the values a data
  bar hides stay hidden), table styles and sparklines. Formulas aren't recalculated: each cell
  shows the value the file saved, and a formula the file kept no value for shows itself. Your
  phone's fonts stand in for the workbook's, at the same widths, so text wraps about where it does
  in Excel. Old .xls files and password-protected workbooks are refused with a message saying what
  to do, and so is a workbook too big for a phone (over 5,000 pages): set a print area for the part
  you need.
- **Recent files**, on Home under Scan document (the last three) and on a screen of their own
  (**See all**): the files you save, from any tool, newest first, with when you saved each and its
  size. Tap a file to open it in another app, or use its menu to **Share** it or **Remove from
  list**; **Clear list** on the Recent files screen empties the list after asking. Neither deletes
  a file. Files saved to Downloads, with **Save as…** or into a folder are listed, ZIP files of
  several PDFs and each PDF saved on its own too; a file saved again moves to the top instead of
  showing twice, and the list keeps the last 50. Files only shared or opened aren't listed.
- A file deleted in another app leaves Recent files by itself, and one renamed in Downloads shows
  its new name (one renamed or moved anywhere else leaves the list). If Usugami can no longer open
  a listed file, tapping it says so and offers to remove it. A screen reader hears each file as one
  item, with Share and Remove from list as its actions, and finds Recent files as a heading.
- Recent files needs no permission and stays on your phone. Usugami can always read what it saved
  to Downloads; for the other files on the list, it keeps read access only to them (at most 50)
  and to a folder saved into while one of its files is listed, and gives that access back once
  they leave the list.

### Changed

- Images to PDF's **Take photo** and a page's crop button go through the same two steps: the look
  is chosen on Filters instead of beside the corners.
- Every new scan starts on **Enhanced**; after that, each photo taken or image uploaded starts on
  the look chosen last.
- Usugami also looks for a page by its edges and colours, as document scanners do, so it can now
  find a white page on a white or light desk, a page on a brighter table or a pale coloured desk,
  and one held in a hand, with its corners placed more exactly. When it can't, drag the corners
  onto the page as before.
- **Share to Usugami** also takes Excel files (.xlsx), which open in Excel to PDF. Home's search
  finds Excel to PDF among the tools for several files too, with "batch", "many" or "several".
- The privacy policy (Settings → About) says what Recent files keeps on your phone and which read
  access Usugami keeps for it.

### Fixed

- **Word, PowerPoint and EPUB to PDF**, **Compress PDF**, **Add page numbers**, **Add watermark**
  and **Flatten PDF**: after Start over, the next file's PDF gets that file's name, not the name the
  file before had.
- **Word and PowerPoint to PDF**: a damaged or crafted file with a tag or text over 8 MB long, a tag
  with thousands of attributes, or parts nested tens of thousands of levels deep is refused as
  damaged within seconds, instead of running out of memory or taking minutes.

## [1.5.3] - 2026-11-06

### Fixed

- **Merge PDF**: merging whole PDFs keeps each one's page numbers; one without labels before one
  with them is numbered 1, 2, 3, in labels viewers can read.
- **Redact PDF** also takes an image under a box out of the file when an annotation on another page
  draws it out of sight.
- **Sign PDF**: a signature much taller than it is wide, such as one stroke down, can be made
  smaller than the page instead of filling its height; on Android 17 it lands exactly where the
  preview shows it.
- **Edit PDF**: a slider or drag brought back where it started no longer takes Redo away; a slider
  whose item is let go while a finger still holds it no longer joins the next change into one Undo;
  a size or thickness changed with a screen reader is also what the next text, stroke or shape gets.
- **Split PDF**: the chosen pages belong to their PDF, so the next PDF picked starts with none chosen.
- **Delete pages** and **Split PDF**: tapping a page while the typed pages can't be read keeps what
  was typed.
- **Add watermark** previews the new first PDF when the PDFs are reordered while a preview is being
  made.
- A long name is shortened between whole letters, so an accent, a vowel sign or part of an emoji
  isn't cut off; a name Windows keeps for a device (CON, NUL, COM1…) gets an underscore, so the file
  can be copied to a PC or unzipped there.
- A JPEG with a JFIF header keeps the colours the gallery shows, even when it labels its colours R,
  G and B; a picture more than 4800 times longer than wide gets a page viewers accept.
- A photo just taken is no longer lost when the storage is almost full.
- Saving to a folder again after a failed save copies only the missing PDFs, instead of adding
  "Name (1).pdf" for the others.
- Cancelling Create PDF while text is being read no longer leaves the PDFs in the cache.
- An app a PDF was shared with can no longer read a later, different PDF with the same name.
- "Discard images?" no longer stays over the screen that shared files open, where Discard threw them
  away.
- A language picked before the phone was updated to Android 13, or Light or Dark picked before it
  was updated to Android 12, is kept.
- Text recognition that Google Play services stops by itself is reported, instead of the PDF being
  dropped without a word.
- **Usugami OCR**: words on a very long line keep the space between them where the line is cut.
- **Word to PDF**: a paragraph with more space before it than the page has stays on the page, and a
  short one stays whole; tab stops on a paragraph add to its style's, and a stop it clears is left
  out; a paragraph hundreds of pages long converts in seconds instead of minutes; a page number in
  the body text shows its own page, not 0 or the previous section's; tables whose cells claim
  millions of columns, or a negative number, convert instead of failing.
- **PowerPoint to PDF**: a damaged chart, SmartArt, picture or table style leaves out just that
  shape, not the whole presentation; a table saved without column widths fills its frame instead of
  running four slides wide; numbered lists go on counting after an empty paragraph; colours set to
  their inverse, grey or complementary colour are drawn that way, and shapes set to take their
  group's fill are filled as the group is.
- **Word and PowerPoint**: Strict Open XML files keep their line spacing, colour shades, gradients,
  shrunk text, superscripts and picture crops.
- **EPUB to PDF**: an `<object>` shows the picture or words it holds in its place; bookmarks to a
  heading that follows text open on the heading's page; lists get space above and below (only nested
  lists go without); text sized in rems of a smaller root font shows at its size, not 1.6 times too
  big; a crafted style sheet no longer hangs the conversion; a picture written into the page itself
  (a `data:` address) leaves its description in its place.
- Usugami's own F-Droid repository shows each version's What's new and dates each version by its
  release, and isn't published when a release APK is left out, such as one signed by another key.

## [1.5.2] - 2026-11-05

### Added

- **Usugami's own F-Droid repository**: add [nathank.me/usugami](https://nathank.me/usugami/) to
  F-Droid, Droid-ify or Neo Store to get Usugami (with the open-source text recognition of Usugami
  OCR) and Usugami Lite with automatic updates. It serves exactly the APKs published here, signed
  with the same key.

### Fixed

- **Redact PDF**: Find text now also finds text in link and email addresses (also when written with
  escapes, like `Jos%C3%A9`), in the files links and buttons open, and in form fields' hidden values,
  names, tooltips, choices and default values, even in fields no page shows, and takes it out of the
  file.
- **Redact PDF**: Find text now finds Hebrew and Arabic text typed as you would write it.
- **Split PDF** no longer leaves blank pages where a page's picture is a JPEG 2000 image, and
  Redact PDF and Delete pages see that such pages draw those images.
- A PDF nested too deeply, in its contents or its pages (a damaged or crafted file), is refused as
  unreadable when it is added instead of closing Usugami.
- **Flatten PDF**: fields it can't draw stay fillable even when another field's widget doesn't say
  which page it is on.
- **Compress PDF** leaves spot-color, CMYK and Lab JPEG images as they are instead of changing their
  colors.
- **Delete pages**, **Reorganize PDF** and **Merge PDF** no longer hang on a PDF whose bookmarks
  loop, and can be cancelled there.
- Photos taken while other images are still being added are no longer lost when Android closes
  Usugami in the background.
- Images still being added when Android closes Usugami are no longer lost without a word: Usugami
  says to share or add them again.
- **Edit PDF**: resizing text or a drawing by its handle no longer makes it jump bigger as the drag
  starts, or refuse to shrink.
- **EPUB to PDF**: books whose first page is a picture other than the cover open on their cover, and
  the cover is no longer added again at the end.
- **EPUB to PDF**: in right-to-left books, text set to align right or left stays on that side, and
  list bullets and indents are on the right.
- **Word to PDF**: Arabic, Hebrew, Hindi and other complex-script text gets the size, bold and
  italic Word gives it.
- Cancelling a ZIP file saved over an existing file, or running out of space while saving it,
  leaves that file as it was.
- A name ending in ".pdf." no longer saves as "Name.pdf.pdf".
- Usugami's F-Droid repository shows Usugami's icon instead of a QR code, and Usugami Lite is no
  longer listed under OCR.

## [1.5.1] - 2026-11-04

### Changed

- **Usugami Lite is an app of its own** (package `io.github.nath_goa.usugami.lite`), so it can be
  listed on F-Droid and installs next to Usugami. Had Lite 1.5.0? The new Lite installs as a separate
  app: save anything you need from the old one (listed as "Usugami"), then uninstall it.
- **Getting ready for F-Droid**: Usugami (with the open-source text recognition of Usugami OCR) and
  Usugami Lite build reproducibly, so F-Droid can offer exactly the APKs published here, signed with
  the same key, and you can switch between F-Droid, GitHub and Obtainium without reinstalling.
- Usugami OCR's text-recognition library comes out byte for byte the same wherever it is built, and
  is 17 KB smaller.

## [1.5.0] - 2026-11-04

### Added

- **Three editions.** Usugami now comes as **Usugami** (Usugami.apk, recommended), **Usugami Lite**
  (Usugami-lite.apk) and **Usugami OCR** (Usugami-ocr.apk). They share a package name and signing
  key, so you can install any one over another and keep your files and settings. Settings → About
  shows which one you have.
  - **Usugami** works as before, with text recognition through Google Play services.
  - **Usugami Lite** has every tool except text recognition, and no Google code at all.
  - **Usugami OCR** has open-source text recognition built in: PaddleOCR's PP-OCRv5 models, run on
    your phone by Tencent's ncnn. It reads English, French, Spanish and Hindi (Hindi mode also
    reads English words), fully offline and with no Google code. It is about 14 MB.
- **Scan with the camera.** **Take photo** in Images to PDF opens your phone's own camera app, page
  after page (Take another, then Done); Usugami still asks for no permissions. Drag the four corners
  onto the page's corners and it comes out straight, as if scanned; Usugami finds the page by itself
  when it stands out from the table, a magnifier shows what is under your finger, and arrow buttons
  and screen-reader actions move a corner a step at a time. Every page has a **Crop** button, for
  gallery photos, files and shared images too, and can be cropped again from scratch.
- **Looks for scanned pages**: **Original**, **Enhanced** (white paper, no lamp color or shadows,
  darker print), **Grayscale** or **Black & white**, previewed as they will be in the PDF, for one
  page or all. Black-and-white pages are stored one bit a pixel (CCITT Group 4, as scanners do),
  so a page of print takes about 10 KB instead of 50 to 350 KB as a photo. Text recognition reads
  cropped pages as cropped.
- **Batch mode.** Compress PDF, Add page numbers, Add watermark and Flatten PDF work on many PDFs at
  once, and Word, PowerPoint and EPUB to PDF convert many files at once, one PDF each. Pick several,
  add or remove files as you go, and name each new PDF. **Compress PDF** sets the level for all PDFs
  in one go or gives a PDF a level of its own. Files are made one after another with "PDF 2 of 5"
  progress, Cancel stops them all, and a file that can't be made is named while the others are
  still made. Home's search finds these tools with "batch", "many" or "several".
- When a tool makes several PDFs (batch mode, or Split PDF in parts), each PDF on the result screen
  has its own **Open**, **Share** and **Save** buttons and a "Saved" mark; **Save to Downloads** and
  **Save to folder…** still save them all, each as its own file, never twice.
- **Share PDFs, Word, PowerPoint and EPUB files to Usugami** from another app, as well as images.
  PDFs open the new **Choose a tool** screen (every PDF tool for one PDF; Merge PDF and the batch
  tools for several), and documents open their converter with the files added. Shared files are
  copied in at once; files shared while a tool has unsaved work ask first, and ones shared while a
  PDF is being made wait for it.

### Changed

- **Usugami OCR replaces Usugami Offline**, which used Google's closed-source ML Kit.
  Usugami-offline.apk is no longer published: install Usugami-ocr.apk (or Usugami-lite.apk) over
  it, and your files and settings stay. If Obtainium follows Usugami-offline.apk for you, add
  Usugami OCR or Usugami Lite instead.
- **Text recognition reads your original photos**, not the compressed copies inside the PDF, so
  small print is found even at Extreme compression. In tests at Extreme, Usugami OCR's errors fell
  from about 29% to under 4%, and Usugami's own recognition got better too. Long scrolling
  screenshots are read at a readable size instead of being shrunk to a sliver first.
- Usugami OCR reads pages photographed upside down or sideways the right way up, and keeps lines
  in order on slanted photos and in columns.
- At large font sizes and in long translations, titles, buttons, choices and labels shrink, wrap or
  move to the next line instead of being cut off.
- The open-source notices no longer say they apply to Usugami 1.2.0.

### Fixed

- **Split PDF**: each part now holds only its own pages. A part cut from pages with links,
  comments or form fields could carry the rest of the PDF hidden inside it (other pages' text,
  pictures and form values) and be as big as the original. Splitting into many parts also no
  longer gets slower with every part.
- **Delete pages, Reorganize PDF and Merge PDF**: pictures and tags (alternate text) of the pages
  you remove no longer stay hidden in the file when pages share them, and every page keeps its page
  label (such as "iv" or "245") instead of the labels shifting.
- **Split PDF** messages say what to type for comma-separated custom ranges and backwards ranges
  (8-3); tapping a preview no longer re-sorts the pages typed; Fixed chunks shows an example and
  opens the number keyboard.
- **Merge PDF** says a second PDF is needed when only one is added, follows the file order in page
  mode, and no longer asks to discard changes when nothing changed.
- **Compress PDF** now shrinks very large JPEG images (600-dpi scans, 50-megapixel photos) instead
  of saying the PDF is already compact, uses less memory for the others, and keeps the original
  when it would save less than 1% (no more "0% smaller").
- **Password-protected PDFs** show their pages in Split's picker, Merge's page mode, Reorganize and
  the PDF's card, instead of a placeholder icon.
- PDFs with a few bytes before their start (a byte order mark or a blank line, as some websites
  send) now open.
- **Redact PDF** is more thorough: a page without boxes that still holds, out of sight, part of what
  a box hides now becomes a picture too; a picture another page shows cropped no longer keeps what a
  box hides; attached files, a digital signature's signer details and the names of links to
  redacted pages are removed. Find text also searches comments, and finds D’Souza for D'Souza,
  Straße for Strasse, Wałęsa for Walesa and numbers written with special hyphens or minus signs.
  Form values Usugami couldn't draw are shown and found instead of looking empty.
- **Flatten PDF** no longer blanks a filled-in field it can't draw (such as ₹ or Hindi in a form
  made for English): the field keeps its look or stays fillable, and Usugami tells you. The tools'
  previews show such fields as they really look.
- **Edit PDF and Sign PDF**: what you add over a form field, comment or stamp now covers it, as the
  preview shows. WebP photos are compressed like other photos (often several times smaller), Ultra
  HDR photos no longer carry their hidden HDR data into the PDF, tapping an addition no longer
  clears Redo, a pen stroke drawn as the page loads is no longer lost, and quick presses of an arrow
  button each move one step.
- **Word to PDF**: letterheads, logos and watermarks in headers and footers no longer push the text
  off the page; rotated, flipped, grouped and hidden pictures and text boxes come out as in Word;
  page numbers follow their format (i, ii, A, B) and odd/even-page sections; Greek letters, maths
  symbols and check boxes from the Symbol and Wingdings fonts show as themselves; lists linked to a
  list style keep their numbers; automatic text color turns white on dark shading; right-to-left
  paragraphs, lists and tables are mirrored; phonetic guides keep their base text; and a document
  whose whole body is kept in another format now says so instead of giving a blank PDF.
- **PowerPoint to PDF**: elbow and curved connectors are drawn as lines with arrowheads; ink,
  equations, 3-D models, Slide Zooms and embedded objects show as PowerPoint's own pictures of them;
  slide numbers count hidden slides; dark backgrounds and layouts keep their colors; flipped groups
  are mirrored; empty placeholders are left out; table borders follow the table style; right-to-left
  paragraphs are aligned from the right; Symbol and Wingdings characters, link colors and text
  anchored at the bottom or middle of a box come out as in PowerPoint.
- **EPUB to PDF**: a picture, line break or rule hidden by a book's style sheet no longer cuts the
  rest of its section out of the PDF; tables keep the space above them; inline pictures fit the
  line; very long paragraphs convert much faster; formulas no longer print their TeX source;
  bookmarks into tables and old-style anchors lead to the right page; fixed-layout pages stay on one
  page; and running out of storage gives an error instead of a PDF with missing pages.
- E-books, Word documents and presentations whose internal file names start with "/" now open on
  Android 14 and newer, and very deeply nested Word and PowerPoint files convert instead of
  crashing.
  Pictures fitted with Crop > Fit keep their shape, and fonts like Century Gothic and Monotype
  Corsiva get a similar font.
- **Images to PDF**: with Fit page size, long scrolling screenshots keep their width instead of
  becoming unreadable slivers, and images added while others are still being added go after them
  instead of in between.
- An unsaved result, even one with hundreds of PDFs, comes back after Android stops Usugami in the
  background, and going to the background with thousands of images or pages no longer crashes.
- Back, Start over and Create wait while PDFs are being saved, sharing images during a save no
  longer deletes the PDFs being saved, and images shared while you kept a tool's PDF show once you
  leave the tool.
- When the phone's storage is full, adding images, PDFs, documents or pictures says so instead of
  calling the file damaged, and phones without a file picker get a message instead of a crash.
- Screen readers hear when a page has been turned in Images to PDF and Merge PDF.
- **Fast and double taps** do each thing once. A double tap on Save as, Share, Open or a Pick or
  Add button opens one screen instead of two (whose second pick was lost); Save to Downloads tapped
  twice no longer saves a second copy; Reverse order, Edit PDF's text Done and Shape menu, and
  Reorganize's Reset act once; and a quick second tap on a turn button turns the page again
  instead of being lost.
- **Redact PDF**: tapping Delete box twice quickly no longer also deletes another box, which could
  be on another page and leave its area unredacted.
- Two pages tapped together in Delete pages and Split PDF are both marked; a page turned and then
  quickly moved or removed is moved or removed; and Add pages > Blank page asked twice no longer
  adds extra pages later.
- **Edit PDF and Sign PDF**: tapping an item only chooses it (a slightly moving finger no longer
  nudges it or clears Redo), Save shows it is working right away so edits made meanwhile can't be
  left out, and Clear on the signature pad also clears a stroke still being drawn.
- A ZIP file being saved is no longer cancelled (and deleted) by Back, Start over or images shared
  meanwhile, its progress shows over any screen, and a file chosen in Save as while another save
  runs gets its PDF instead of staying empty.
- Opening a tool again right after leaving it starts it fresh, a PDF picked after leaving its tool
  no longer lands in another tool, the same message no longer repeats for seconds, and choosing
  languages quickly applies your last choice.
- Text recognition: a very small or very thin image no longer leaves every PDF made with it
  without searchable text.
- **Redact PDF on Android 17**: redacted pages, and the Redact and Edit PDF previews, are drawn
  exactly as big as the page, so a box placed near the foot of a page no longer leaves a sliver of
  what it hides showing.

## [1.4.1] - 2026-11-02

### Changed

- **Dark theme** now uses neutral dark grays instead of reddish-brown ones, so it looks black
  rather than brown. The red accent is unchanged.

## [1.4.0] - 2026-11-01

### Added

- **Word to PDF** and **PowerPoint to PDF**, under Create PDF on Home. Usugami reads .docx documents
  and .pptx presentations itself, on your phone, with no upload and no extra library. Text stays
  searchable; styles and headings, lists, tables (split across pages, header rows repeated),
  pictures, headers and footers, page sizes and landscape pages, slide backgrounds, shapes, and
  the slide master's text styles carry over. Hidden slides are left out, and pictures are
  compressed. Fonts become the phone's own, and charts show as gray boxes with their title. Old
  .doc and .ppt files and password-protected ones are refused with a message saying what to do.
- **EPUB to PDF**, under Create PDF on Home. Usugami reads EPUB 2 and EPUB 3 e-books on your phone
  and lays them out on A5 pages (or A4), the cover first and each chapter on a new page. Headings,
  text styles, lists, quotes, simple tables and pictures carry over, with the basics of the book's
  style sheet (alignment, indents, spacing, font sizes, page breaks). The table of contents becomes
  the PDF's bookmarks, and the book's title, author and language go into the PDF. Fixed-layout
  books get one page per page of the book. Books with DRM, and files that aren't e-books, are
  refused with a message.
- PDFs made from Word, PowerPoint and EPUB files keep only the letters they use of the phone's
  fonts, so they are much smaller (a short sample book went from 490 KB to 121 KB) and look exactly
  the same. Fonts for scripts other than Latin, Greek and Cyrillic still go in whole.

- **Rotate PDF**: turn single pages, or all of them, left or right. Only the way each page is shown
  changes; nothing is re-encoded.
- **Add page numbers**: at the top or bottom, left, center or right, in three sizes, skipping the
  pages you choose (like a cover). Each page keeps its own number. Numbers sit at the bottom or top
  of the page as it is shown, also on pages turned sideways or cropped.
- **Add watermark**: text drawn large and diagonally across every page, in five colors and three
  opacities, with a preview of the first page. It is real text, drawn with the phone's own fonts,
  so accents and Hindi work too.
- **Sign PDF**: draw your signature with a finger, in black or blue ink, choose the page, then drag
  it into place on a preview and set its size (arrow buttons move it a step at a time). It goes on
  the page as a sharp picture with a transparent background, exactly where the preview showed it,
  also on turned or cropped pages. Usugami keeps the signature only while it is open, so you can
  sign another PDF without drawing it again. It is a picture of a handwritten signature, not a
  certified digital signature.

- **Reorganize PDF**: a PDF's pages in a grid, to move (with buttons or by dragging), turn and
  remove, and to add pages after the chosen one, from another PDF (password-protected ones too) or
  blank, the size of the page before. Undo takes back one change at a time. Bookmarks, links and
  form fields of the pages kept stay as they were.
- **Delete pages**: tap the pages to delete or type them like 2,5-7, and see how many will go. A PDF
  keeps at least one page.
- **Flatten PDF**: filled-in form fields, comments, highlights, drawings and stamps become part of
  the page, so nothing can be changed or filled in any more; links keep working. Fields that a form
  app left without an appearance are drawn first. A dynamic XFA form is removed, with a note, and
  its ordinary fields are flattened. A PDF with nothing to flatten is reported instead of saved again.

- **Edit PDF**, first under Edit and sign: add text, drawings, highlights, shapes (rectangles,
  filled or not, ellipses, lines and arrows) and pictures on top of any page. Each can be dragged,
  resized with its handles or a slider, moved with arrow buttons, recolored and deleted, and Undo
  and Redo go back and forth one change at a time. They land exactly where the preview shows them,
  also on pages turned sideways or cropped: text stays real, searchable text (Hindi included),
  drawings and shapes stay sharp vector lines, and photos are compressed; pages you don't change
  stay exactly as they were. A filled box only covers what's under it, and Usugami says so.

- **Search** at the top of Home: type to find a tool by its name, what it does or a word you might
  use for it, such as "combine", "shrink" or "scan" (in each of the app's languages; Hindi also
  takes common English words). Case and accents don't matter, and every word typed must match. The
  tools found show in one list, Back clears the search, and a screen reader says how many were
  found.
- **Redact PDF**, under **Optimize and protect** on Home. Draw boxes
  over what to hide, or let **Find text** box a word or phrase on every page (ignoring capitals and
  accents), then move, resize or delete boxes by dragging or with buttons. It is true removal: each
  page with a box is saved as a picture of itself at 200 dpi with the boxes painted black, so the
  hidden text and images are gone from the file, while pages without boxes stay as they were. The
  document's details and XMP metadata, bookmarks, accessibility tags, attachments, scripts, and the
  comments and form fields of redacted pages are removed too. Password-protected PDFs work, and the
  preview shows comments and filled-in form fields as other PDF viewers do.
- **Share or save as ZIP** on the result screen, when a tool makes several PDFs (Split PDF in parts,
  or one PDF per image): one ZIP file with exactly those PDFs, put in as they are, named after them
  (such as Report_split.zip). Share it, or save it anywhere with the system's Save screen. A big set
  shows its progress and can be cancelled.
- **Appearance** in Settings: **System default** (as before), **Light** or **Dark**, for every
  screen, the first-launch language screen included. On Android 12 and newer, Android keeps the
  choice for Usugami and applies it from the first frame.

### Changed

- A new app icon: three thin sheets of paper fanned out, deep purple on lavender, with a matching
  themed icon on Android 13 and newer.
- Home groups the tools in four sections, **Create PDF**, **Organize**, **Edit and sign** and
  **Optimize and protect**, with smaller cards and shorter descriptions, so more of them fit on the
  screen.
- Pages deleted or left out (in Delete pages, Reorganize PDF and Merge PDF's page mode) are really
  gone from the new PDF: their bookmarks and form fields are removed, and links to them lead
  nowhere, so none of their text, pictures or form values stays in the file.
- **Back to editing** from a PDF tool's result now returns to your choices as you left them (such as
  the pages typed, the watermark text or where a signature goes) instead of starting them afresh.
- Leaving a PDF tool, or removing its PDF, now asks first when that would throw away your changes
  (Edit PDF's additions, Redact PDF's boxes, or pages turned or moved). The "Choose another PDF"
  button is gone: the X on the PDF does the same.
- A tool's progress dialog only shows when it takes a while, instead of flashing by.

## [1.3.0] - 2026-10-31

### Added

- Usugami now comes in **French**, **Spanish** and **Hindi** as well as English. The first time you
  open it, you pick a language; you can change it any time in Settings (on Android 13 and newer,
  also in the phone's settings for Usugami). The license and privacy policy stay in English.
- Text recognition reads **French** and **Spanish** too, accents included. It starts on the app's
  language. In the standard edition, French and Spanish use the same download as English.
- A Settings page (the gear on Home) with the language, a link to the source code on GitHub, and
  About.

### Changed

- About is now in Settings.

## [1.2.0] - 2026-10-31

First public release, under a new name: PDFer is now **Usugami** (薄紙, Japanese for "thin paper").
It is open source (GPL-3.0-or-later) and comes in two editions.

**Had PDFer 1.0 or 1.1?** Those were private test builds with a different package name
(`com.nathan.pdfer`) and signing key, so Android treats 1.2.0 as a different app and can't update
them. Install 1.2.0, then uninstall the old "PDFer".

### Added

- Two editions with the same package name and signing key. Install one over the other to switch;
  your files and settings are kept.
  - **Usugami** (`Usugami.apk`, about 2.4 MB): text recognition (OCR) runs through Google Play
    services. The first time you use each language (English or Hindi), Play services downloads
    its text-recognition model, which needs an internet connection then.
  - **Usugami Offline** (`Usugami-offline.apk`, about 22 MB, ARM phones): the text-recognition models are
    built in. Works with no internet at all and without Google Play services.
- Text recognition shows its progress: the model download in the standard edition (with a
  percentage when known), creating the PDF, then reading the text page by page.
- If text recognition isn't available, for example without Google Play services or without
  internet for the model download, Usugami explains why and mentions Usugami Offline. If recognition
  fails after the PDF is made, you keep the PDF without searchable text.
- About screen with the license, the privacy policy and the open-source licenses, readable
  without internet.
- A notice when the result of splitting, merging or compressing a password-protected PDF is saved
  without a password.
- Short animations between screens and for cards, lists and collapsing sections.
- Compress tells you when a PDF was already compact and the original size was kept.
- Add several password-protected PDFs at once: Usugami asks for each password in turn and names
  the file it is asking about.
- Screen readers can move pages earlier or later in the editor and in Merge, without dragging.

### Changed

- New name, Usugami, because "PDFer" was already used by several other PDF apps.
- New package name `io.github.nath_goa.usugami` and a new release signing key. The certificate
  fingerprint is in the README and in every release.
- The standard APK went from about 52 MB to about 2.4 MB: text recognition moved to Google Play
  services, and unused PDFBox data files, BouncyCastle and library translations were removed.
- The invisible text layer of searchable PDFs uses one 18 KB font instead of two Noto fonts
  (0.8 MB).
- Temporary working files are kept in the app's cache and cleaned up automatically.
- Photo metadata is removed with an allow-list: only what is needed to display the image is kept,
  so every other kind of embedded metadata is removed too, not only EXIF/GPS, XMP, IPTC and
  comments.
- Google ML Kit's built-in usage-metrics uploader is disabled. (Usugami has no internet
  permission, so it could never upload them itself; now ML Kit doesn't store those metrics either.)
- Usugami no longer starts EmojiCompat, which asked Google Play services for an emoji font every
  time the app opened. Emoji are shown with the phone's own font.

### Fixed

- Hindi text recognition keeps English words, numbers and email addresses. In 1.1 the searchable
  text lost every Latin letter in Hindi mode.
- "Save as…" fully replaces an existing file. Before, overwriting a larger file could leave old
  bytes at the end and damage the PDF.
- Opening a PDF in Split, Merge or Compress crashed on Android 8 to 12.
- Compress handles more unusual images safely: images it can't decode, or that are too large for
  memory, are left unchanged instead of failing.
- Text recognition no longer fails the whole conversion when a recognized character has no glyph
  in the font.
- Split shows a clear message for an invalid chunk size, and page counts use "1 page" / "2 pages".
- Accessibility and layout fixes, including screen-reader labels and controls that were hidden
  behind the navigation bar or the keyboard. Buttons like "Recommended" are no longer cut off on
  small screens or with large text.
- The searchable text follows tilted lines, so selecting and searching text works on photos taken
  at an angle.
- A text-recognition download that makes no progress (for example without internet) stops after a
  minute with a clear message instead of waiting forever.
- A failed save no longer leaves a half-written file behind, and a PDF that can't be read no longer
  empties the file you chose to overwrite.
- Your images are kept when Android closes Usugami in the background, for example while you pick
  more photos.
- Wide-colour photos (Display P3, common on newer phones) keep their colours in the PDF.
- The password field uses a password keyboard, so the keyboard doesn't learn PDF passwords.

### Security

- Images shared to Usugami from other apps are checked, so a share can't make Usugami read its own
  private files.

## [1.1] - 2026-10-16

Private test build, named PDFer (package `com.nathan.pdfer`), not published.

### Added

- Home screen with four tools: Images to PDF, Split PDF, Merge PDF and Compress PDF.
- Split PDF: pick pages by tapping previews or typing numbers and ranges, enter separate ranges
  (`1-3;5;7-9`), split into fixed-size chunks or one file per page, and combine the selection
  into one PDF or save separate files.
- Merge PDF: add PDFs, move or sort them, or switch to page mode to reorder, rotate and remove
  individual pages.
- Compress PDF with Less, Recommended and Extreme levels. Selectable text, vector drawings and
  transparency are kept, unsupported images are left unchanged, and an unprotected PDF never gets
  bigger than the original.
- Password-protected PDFs can be opened with their password for split, merge and compress.
- Optional searchable PDF (OCR) when converting images, in English or Hindi, with the recognition
  models built into the app.

## [1.0] - 2026-10-14

Private test build, named PDFer (package `com.nathan.pdfer`), not published.

### Added

- JPG and PNG to PDF. Add images from Photos (the system photo picker), Files, or by sharing them
  to Usugami from another app.
- Live page previews. Long-press and drag to reorder; rotate, remove, sort by name or reverse.
- Page size Fit (same as image), A4, US Letter, US Legal, A3 or A5; orientation Auto, Portrait or
  Landscape; no margin, small or big margin.
- One PDF with every image, or one PDF per image.
- Always compressed: each image is reduced to the chosen resolution for the space it fills on the
  page (Less 200 dpi, Recommended 150 dpi, Extreme 100 dpi). If the original is smaller, it is
  kept.
- Phone photos come out the right way up (EXIF orientation), and EXIF/GPS metadata and "motion
  photo" video data are removed.
- Open, Share, Save to Downloads, or Save as… to any folder.
- No internet permission: nothing is uploaded.

[Unreleased]: https://github.com/Nath-Goa/usugami/compare/v1.6.5...HEAD
[1.6.5]: https://github.com/Nath-Goa/usugami/compare/v1.6.4...v1.6.5
[1.6.4]: https://github.com/Nath-Goa/usugami/compare/v1.6.3...v1.6.4
[1.6.3]: https://github.com/Nath-Goa/usugami/compare/v1.6.2...v1.6.3
[1.6.2]: https://github.com/Nath-Goa/usugami/compare/v1.6.1...v1.6.2
[1.6.1]: https://github.com/Nath-Goa/usugami/compare/v1.6.0...v1.6.1
[1.6.0]: https://github.com/Nath-Goa/usugami/compare/v1.5.3...v1.6.0
[1.5.3]: https://github.com/Nath-Goa/usugami/compare/v1.5.2...v1.5.3
[1.5.2]: https://github.com/Nath-Goa/usugami/compare/v1.5.1...v1.5.2
[1.5.1]: https://github.com/Nath-Goa/usugami/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/Nath-Goa/usugami/compare/v1.4.1...v1.5.0
[1.4.1]: https://github.com/Nath-Goa/usugami/compare/v1.4.0...v1.4.1
[1.4.0]: https://github.com/Nath-Goa/usugami/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/Nath-Goa/usugami/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/Nath-Goa/usugami/releases/tag/v1.2.0
