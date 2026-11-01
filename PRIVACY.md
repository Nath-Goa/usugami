# Usugami Privacy Policy

Effective date: 31 October 2026

Usugami is an Android app that turns images into PDFs and splits, merges and compresses PDFs. It
works on your phone. It has no ads, no analytics, no tracking, no accounts and no in-app
purchases, and it does not collect, share or sell any data about you.

This policy covers both editions of Usugami: "Usugami" (the standard edition) and "Usugami Offline".
The only difference that matters for privacy is how text recognition (OCR) works, explained
below.

## Who is responsible

Usugami is made by Nathan Kumtakar, an independent developer. It is free and open-source software:
https://github.com/Nath-Goa/usugami

Questions or concerns: open an issue at https://github.com/Nath-Goa/usugami/issues. Issues are
public, so please don't include personal details. To report a security problem privately, use
"Report a vulnerability" on the repository's Security tab.

## What Usugami can access

- Only the images, PDFs, Word and PowerPoint files, and EPUB books you choose. You pick them with Android's
  photo picker or file picker, or you share images to Usugami from another app. Android then gives Usugami access to those files
  only. Usugami cannot browse your photos or other files.
- Usugami asks for no permissions: no storage, camera, location, contacts or microphone access.
- Usugami has no internet permission, so Android does not let it connect to the internet, and it
  cannot upload your files.
- When you save a PDF, it goes where you choose: the Downloads folder or a folder you pick with
  the system file picker. When you tap Share or Open, the PDF goes to the app you pick, and that
  app's own privacy policy applies.

## What Usugami stores on your phone

- Working copies: while you work, Usugami keeps copies of the files you picked and the PDFs it
  makes in its private cache on your phone, where other apps can't read them. Usugami deletes
  them automatically once they are no longer needed (for example, when you start over), and
  Android may also clear the cache when storage runs low.
- Signatures: a signature you draw for "Sign PDF" is kept only while Usugami is open, in its
  memory and in the screen state Android keeps for it, so you can use it again. It is never saved
  in a file of its own, only in the PDFs you sign.
- Edit PDF: the text, drawings and shapes you add to a PDF are kept the same way, until you leave
  the tool, and only end up in the PDF you save. Pictures you add are working copies, as above.
- Settings: your last choices (such as page size, margins and compression level), stored
  privately on your phone.
- Usugami excludes its data from Android's cloud backup and device-to-device transfer.

Nothing is stored anywhere else. Usugami has no servers.

## Text recognition (OCR)

Text recognition is optional. It only runs when you turn on "Make text searchable (OCR)" while
converting images. In both editions it uses Google ML Kit, a library made by Google, and the
recognition itself happens on your phone. Your images and the recognized text are never sent to
Google or anyone else.

### Usugami (standard edition)

- Text recognition runs through Google Play services, a separate app from Google that is
  installed on most Android phones.
- The first time you use a text language, Google Play services downloads Google's
  text-recognition model for it (one model covers English, French and Spanish; Hindi has its own). This needs an internet connection at that time. Google
  Play services then keeps the model for later use; if it removes a model, it downloads it again
  the next time you use that language. Google Play services does these downloads, not Usugami,
  and Google's Privacy Policy applies to them: https://policies.google.com/privacy
- To download and run text recognition, Google's libraries inside Usugami talk to Google Play
  services on your phone. Google Play services may report to Google how these Google APIs were
  used (for example, whether a call succeeded).
- According to Google's ML Kit data disclosure, ML Kit may also collect diagnostic and usage
  information about the ML Kit feature, such as device and app information and performance
  metrics, and send it to Google:
  https://developers.google.com/ml-kit/android-data-disclosure
- Your images and the recognized text are not part of any of this.
- Usugami has no internet permission, and it disables (unregisters) the part of Google's library
  that would upload ML Kit's metrics from inside Usugami, so those metrics are dropped. Google
  Play services is a separate app, and what it sends is covered by Google's policies, not by
  Usugami.
- Everything else in Usugami (images, Word, PowerPoint and EPUB to PDF, and all the PDF tools) works
  without Google Play services and without internet.

### Usugami Offline

- The text-recognition models are built into the app. Text recognition works with no internet
  connection and doesn't need Google Play services.
- Usugami Offline makes no network connections at all.

If you want no network use of any kind, install Usugami Offline.

## Data collection and sharing

Usugami does not collect personal data. It has no analytics, crash reporting, advertising IDs or
accounts. The developer receives nothing from the app and cannot see what you do with it.

## Children

Usugami does not collect personal information from anyone, including children.

## Keeping and deleting your data

- Working copies are deleted automatically (see above).
- To delete everything Usugami stores on your phone, go to Settings > Apps > Usugami > Storage and
  tap "Clear storage", or uninstall Usugami.
- PDFs you saved to Downloads or another folder are your files. They stay until you delete them,
  even if you uninstall Usugami.
- In the standard edition, the text-recognition models are kept and managed by Google Play
  services.

## Changes to this policy

Any change to this policy is made in the file PRIVACY.md in Usugami's public repository, so every
version and change is visible in its history:
https://github.com/Nath-Goa/usugami/commits/main/PRIVACY.md

The effective date at the top changes with each update, and app releases include the version of
the policy that applies to them.
