# PDFer

A personal, ad-free Android app for images and PDFs. Everything runs on the phone:
the app has no internet permission and uploads nothing.

## Features

- Add images from **Photos** (system photo picker), **Files**, or by **sharing** images to PDFer from another app.
- Live page previews. **Long-press and drag** to reorder, rotate left/right, remove, sort by name or reverse.
- Page size: Fit (same as image), A4, US Letter, US Legal, A3, A5. Orientation: Auto, Portrait, Landscape. Margin: none, small, big.
- **One PDF** with every image, or **one PDF per image**.
- **Always compressed**: each image is reduced to the chosen resolution for the space it fills on the page and stored as JPEG (Less 200 dpi, Recommended 150 dpi, Extreme 100 dpi). If the original is smaller, the original is kept.
- Phone photos come out the right way up (EXIF orientation). EXIF/GPS metadata and "motion photo" video data are removed.
- Open, Share, **Save to Downloads**, or **Save as…** to any folder.
- **Optional searchable PDF (OCR)** while converting JPG or PNG images. Choose English
  (default) or Hindi. Recognition models and fonts are bundled in the APK.
- **Split PDF:** extract selected pages, enter separate page ranges, split into fixed
  page counts, or make one PDF per page. Combine selected pages/ranges or save separate files.
- **Merge PDF:** combine full files in a chosen order, sort file names, or switch to page
  mode to reorder, rotate, and remove individual pages before merging.
- **Compress PDF:** Less, Recommended, or Extreme. Re-encodes eligible images while
  retaining selectable text and vector drawings. An unencrypted result never exceeds
  its source file size; PDFs with no useful compression can remain the same size.
- Password-protected PDFs can be opened with their password for split, merge, and compression.

See [CHECKLIST.md](CHECKLIST.md) for the feature and test checklist.

## Install

Copy `PDFer-1.1.apk` to the phone and open it (allow "install unknown apps" when asked),
or with USB debugging on: `adb install -r PDFer-1.1.apk`. Needs Android 8.0 or newer.

## Build

Needs the Android SDK (local.properties `sdk.dir`) and JDK 17+ (Android Studio's JBR works).

```
./gradlew :core:test          # PDF engine unit tests
./gradlew :app:connectedDebugAndroidTest # device tests for split, merge, compress and OCR
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

## Layout

- `core/` – plain Kotlin PDF engine (no Android): PDF writer, page layout, EXIF orientation maths, JPEG parsing and metadata stripping, lossless PNG encoding, compression sizing. Unit tested on the JVM.
- `app/` – the Android app (Jetpack Compose): picking/importing, previews, export,
  OCR, PDF tools, saving and sharing.
- `tools/validate_pdfs.py` – end-to-end check: generates test images, builds PDFs with the engine, and verifies them with qpdf (pikepdf), pypdf and MuPDF rendering.
