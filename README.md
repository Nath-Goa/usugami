# PDFer

A personal, ad-free Android app that turns JPG and PNG images into a PDF, like the
"JPG to PDF" tools on iLovePDF and Smallpdf. Everything runs on the phone: the app has
no internet permission and uploads nothing.

## Features

- Add images from **Photos** (system photo picker), **Files**, or by **sharing** images to PDFer from another app.
- Live page previews. **Long-press and drag** to reorder, rotate left/right, remove, sort by name or reverse.
- Page size: Fit (same as image), A4, US Letter, US Legal, A3, A5. Orientation: Auto, Portrait, Landscape. Margin: none, small, big.
- **One PDF** with every image, or **one PDF per image**.
- **Always compressed**: each image is reduced to the chosen resolution for the space it fills on the page and stored as JPEG (Less 200 dpi, Recommended 150 dpi, Extreme 100 dpi). If the original is smaller, the original is kept.
- Phone photos come out the right way up (EXIF orientation). EXIF/GPS metadata and "motion photo" video data are removed.
- Open, Share, **Save to Downloads**, or **Save as…** to any folder.

## Install

Copy `PDFer-1.0.apk` to the phone and open it (allow "install unknown apps" when asked),
or with USB debugging on: `adb install -r PDFer-1.0.apk`. Needs Android 8.0 or newer.

## Build

Needs the Android SDK (local.properties `sdk.dir`) and JDK 17+ (Android Studio's JBR works).

```
./gradlew :core:test          # PDF engine unit tests
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

## Layout

- `core/` – plain Kotlin PDF engine (no Android): PDF writer, page layout, EXIF orientation maths, JPEG parsing and metadata stripping, lossless PNG encoding, compression sizing. Unit tested on the JVM.
- `app/` – the Android app (Jetpack Compose): picking/importing, previews, export, saving and sharing.
- `tools/validate_pdfs.py` – end-to-end check: generates test images, builds PDFs with the engine, and verifies them with qpdf (pikepdf), pypdf and MuPDF rendering.
