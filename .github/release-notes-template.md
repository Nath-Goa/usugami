---

## Which file should I download?

| File | Size | Pick it if… | Text recognition (OCR) |
|---|---|---|---|
| **[Usugami.apk](https://github.com/${GITHUB_REPOSITORY}/releases/download/${TAG}/Usugami.apk)** (recommended) | ${STANDARD_MB} MB | You're not sure. Works on most phones. | Runs through Google Play services. The first time you use it, Play services downloads the text-recognition model (one for English, French and Spanish, one for Hindi), which needs internet then. |
| [Usugami-offline.apk](https://github.com/${GITHUB_REPOSITORY}/releases/download/${TAG}/Usugami-offline.apk) | ${OFFLINE_MB} MB | Your phone has no Google Play services, or you want no network use at all. ARM phones (virtually all phones). | Built into the app. Works without internet, ever. |

Everything else (images to PDF, split, merge, compress) works offline in both, and Usugami itself has
no internet permission. Both files are signed with the same key: install one over the other to
switch editions.

Needs Android 8.0 or newer. New to installing APKs? Follow the
[step-by-step guide](https://github.com/${GITHUB_REPOSITORY}#install).

## Verify your download (optional)

- Package name: `io.github.nath_goa.usugami`
- Signing certificate SHA-256: `${CERT_SHA256}`

On a phone with Android 9 or newer, paste those two lines into
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
