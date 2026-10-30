# PDFer Privacy Policy

Effective date: 30 October 2026

PDFer is an Android app that turns images into PDFs and splits, merges and compresses PDFs. It
works on your phone. It has no ads, no analytics, no tracking, no accounts and no in-app
purchases, and it does not collect, share or sell any data about you.

This policy covers both editions of PDFer: "PDFer" (the standard edition) and "PDFer Offline".
The only difference that matters for privacy is how text recognition (OCR) gets its model,
explained below.

## Who is responsible

PDFer is made by Nathan Kumtakar, an independent developer. It is free and open-source software:
https://github.com/Nath-Goa/pdfer

Questions or concerns: open an issue at https://github.com/Nath-Goa/pdfer/issues. Issues are
public, so please don't include personal details. To report a security problem privately, use
"Report a vulnerability" on the repository's Security tab.

## What PDFer can access

- Only the images and PDFs you choose. You pick them with Android's photo picker or file picker,
  or you share them to PDFer from another app. Android then gives PDFer access to those files
  only. PDFer cannot browse your photos or other files.
- PDFer asks for no permissions: no storage, camera, location, contacts or microphone access.
- PDFer has no internet permission, so Android does not let it connect to the internet. It
  cannot upload your files or send anything about you anywhere.
- When you save a PDF, it goes where you choose: the Downloads folder or a folder you pick with
  the system file picker. When you tap Share or Open, the PDF goes to the app you pick, and that
  app's own privacy policy applies.

## What PDFer stores on your phone

- Working copies: while you work, PDFer keeps copies of the files you picked and the PDFs it
  makes in its private cache on your phone, where other apps can't read them. PDFer deletes
  them automatically once they are no longer needed (for example, when you start over), and
  Android may also clear the cache when storage runs low.
- Settings: your last choices (such as page size, margins and compression level), stored
  privately on your phone.
- PDFer excludes its data from Android's cloud backup and device-to-device transfer.

Nothing is stored anywhere else. PDFer has no servers.

## Text recognition (OCR)

Text recognition is optional. It only runs when you turn on "Make text searchable (OCR)" while
converting images. In both editions it uses Google ML Kit, a library made by Google, and the
recognition itself happens on your phone. Your images and the recognized text are never sent to
Google or anyone else.

### PDFer (standard edition)

- Text recognition runs through Google Play services, a separate app from Google that is
  installed on most Android phones.
- The first time you use it, Google Play services downloads Google's text-recognition model
  once. This needs an internet connection that one time. Google Play services then keeps the
  model for later use. Google Play services does this download, not PDFer, and Google's
  Privacy Policy applies to it: https://policies.google.com/privacy
- According to Google's ML Kit data disclosure, ML Kit may collect diagnostic and usage
  information about the ML Kit feature, such as device and app information and performance
  metrics, and send it to Google:
  https://developers.google.com/ml-kit/android-data-disclosure
- PDFer has no internet permission, and it removes the part of Google's library that would
  upload these metrics from inside PDFer, so PDFer itself sends nothing. Google Play services is
  a separate app, and what it sends is covered by Google's policies, not by PDFer.
- Everything else in PDFer (images to PDF, split, merge, compress) works without Google Play
  services and without internet.

### PDFer Offline

- The text-recognition model is built into the app. It works with no internet connection and
  without Google Play services.
- PDFer Offline makes no network connections at all.

If you want no network use of any kind, install PDFer Offline.

## Data collection and sharing

PDFer does not collect personal data. It has no analytics, crash reporting, advertising IDs or
accounts. The developer receives nothing from the app and cannot see what you do with it.

## Children

PDFer does not collect personal information from anyone, including children.

## Keeping and deleting your data

- Working copies are deleted automatically (see above).
- To delete everything PDFer stores on your phone, go to Settings > Apps > PDFer > Storage and
  tap "Clear storage", or uninstall PDFer.
- PDFs you saved to Downloads or another folder are your files. They stay until you delete them,
  even if you uninstall PDFer.
- In the standard edition, the text-recognition model is kept and managed by Google Play
  services.

## Changes to this policy

Any change to this policy is made in the file PRIVACY.md in PDFer's public repository, so every
version and change is visible in its history:
https://github.com/Nath-Goa/pdfer/commits/main/PRIVACY.md

The effective date at the top changes with each update, and app releases include the version of
the policy that applies to them.
