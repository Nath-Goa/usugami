# Changelog

All notable changes to Usugami are listed here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) from 1.2.0 on.

## [Unreleased]

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
  behind the navigation bar or the keyboard.

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

[Unreleased]: https://github.com/Nath-Goa/usugami/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/Nath-Goa/usugami/releases/tag/v1.2.0
