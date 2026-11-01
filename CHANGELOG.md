# Changelog

All notable changes to Usugami are listed here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 1.2.0 on.

## [Unreleased]

### Added

- **Word to PDF** and **PowerPoint to PDF**, under Create PDF on Home. Usugami reads .docx documents
  and .pptx presentations itself, on your phone, with no upload and no extra library. Text stays
  searchable; styles and headings, lists, tables (split across pages, header rows repeated),
  pictures, headers and footers, page sizes and landscape pages, slide backgrounds, shapes, and
  the slide master's text styles carry over. Hidden slides are left out, and pictures are
  compressed. Fonts become the phone's own, and charts show as gray boxes with their title. Old
  .doc and .ppt files and password-protected ones are refused with a message saying what to do.

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

### Changed

- Home groups the tools in four sections, **Create PDF**, **Organize**, **Edit and sign** and
  **Optimize and protect**, with smaller cards and shorter descriptions, so more of them fit on the
  screen.
- Pages deleted or left out (in Delete pages, Reorganize PDF and Merge PDF's page mode) are really
  gone from the new PDF: their bookmarks and form fields are removed, and links to them lead
  nowhere, so none of their text, pictures or form values stays in the file.
- **Back to editing** from a PDF tool's result now returns to your choices as you left them (such as
  the pages typed, the watermark text or where a signature goes) instead of starting them afresh.

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

[Unreleased]: https://github.com/Nath-Goa/usugami/compare/v1.3.0...HEAD
[1.3.0]: https://github.com/Nath-Goa/usugami/compare/v1.2.0...v1.3.0
[1.2.0]: https://github.com/Nath-Goa/usugami/releases/tag/v1.2.0
