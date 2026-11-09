---

## Which file should I download?

| File | Size | Pick it if… | Text recognition (OCR) |
|---|---|---|---|
| **[Usugami.apk](https://github.com/${GITHUB_REPOSITORY}/releases/download/${TAG}/Usugami.apk)** (recommended) | ${STANDARD_MB} MB | You're not sure. Works on most phones. | Runs through Google Play services. The first time you use it, Play services downloads the text-recognition model (one for English, French and Spanish, one for Hindi), which needs internet then. |
| [Usugami-lite.apk](https://github.com/${GITHUB_REPOSITORY}/releases/download/${TAG}/Usugami-lite.apk) | ${LITE_MB} MB | You don't need text recognition, and want the smallest app with no Google code at all. | None. |
| [Usugami-ocr.apk](https://github.com/${GITHUB_REPOSITORY}/releases/download/${TAG}/Usugami-ocr.apk) | ${OCR_MB} MB | Your phone has no Google Play services, or you want text recognition with no network use at all. ARM phones (virtually all phones). | Open source and built into the app (PaddleOCR's models, run by ncnn). Works without internet, ever. |

Every other tool works offline in all three, and Usugami itself has no internet permission. In
Usugami.apk, text recognition uses Google's ML Kit, which runs on your phone but is closed source;
Usugami Lite and Usugami OCR contain no Google code. The rest of Usugami is GPL-3.0-or-later. All
three files are signed with the same key. Usugami and Usugami OCR are the same app: install one over
the other to switch. Usugami Lite is an app of its own (package name `io.github.nath_goa.usugami.lite`)
that installs next to them.

Had Usugami Lite 1.5.0? It was still the same app as Usugami, so Usugami-lite.apk now installs as a
new app, "Usugami Lite", next to it. Save any PDFs you need from the old one, then uninstall it (it
is called "Usugami"). In Obtainium, remove the old Usugami Lite and
[add it again](https://github.com/${GITHUB_REPOSITORY}#automatic-updates-with-obtainium).

Had Usugami Offline? It is now Usugami OCR: install Usugami-ocr.apk over it, and your files and
settings are kept. In Obtainium, change its APK filter to `^Usugami-ocr\.apk$`.

Needs Android 8.0 or newer. New to installing APKs? Follow the
[step-by-step guide](https://github.com/${GITHUB_REPOSITORY}#install).

## Verify your download (optional)

- Package name: `io.github.nath_goa.usugami` (Usugami and Usugami OCR) or
  `io.github.nath_goa.usugami.lite` (Usugami Lite)
- Signing certificate SHA-256: `${CERT_SHA256}`

On a phone with Android 9 or newer, paste the package name and the certificate into
[AppVerifier](https://github.com/soupslurpr/AppVerifier). On a computer:

```sh
sha256sum -c --ignore-missing SHA256SUMS.txt
apksigner verify --print-certs Usugami.apk
gh attestation verify Usugami.apk --repo ${GITHUB_REPOSITORY}
```

SHA256SUMS.txt:

```text
${SHA256SUMS}
```

The mapping zip file is only for developers: it holds the R8 mapping files that turn stack
traces in crash reports back into readable code. You don't need it to install Usugami.

Usugami-ocr.aab is Usugami OCR as an Android App Bundle, the file Google Play takes. Phones can't
install it: download one of the APKs above.
