# Usugami Privacy Policy

Effective date: 9 November 2026

Usugami is an Android app that turns images, Word documents, PowerPoint presentations, Excel
workbooks and EPUB e-books into PDFs, and splits, merges, reorganizes, compresses, edits, signs and
redacts PDFs. It works on your phone. It has no ads, no analytics, no tracking, no accounts and no
in-app purchases, and it does not collect, share or sell any data about you.

This policy covers all three editions of Usugami: "Usugami" (the main edition), "Usugami Lite"
and "Usugami OCR". The only difference that matters for privacy is text recognition (OCR),
explained below.

## Who is responsible

Usugami is made by Nathan Kumtakar, an independent developer. It is free and open-source software:
https://github.com/Nath-Goa/usugami

Questions or concerns: open an issue at https://github.com/Nath-Goa/usugami/issues. Issues are
public, so please don't include personal details. To report a security problem privately, use
"Report a vulnerability" on the repository's Security tab.

## What Usugami can access

- Only the images, PDFs, Word, PowerPoint and Excel files, and EPUB books you choose. You pick them
  with Android's photo picker or file picker, or you share them to Usugami from another app.
  Android then gives Usugami access to those files only, and to a folder you pick to save into (see
  Recent files below). Usugami cannot browse your photos or any other files or folders.
- Usugami asks for no permissions: no storage, camera, location, contacts or microphone access.
- **Take photo** opens your phone's own camera app, which saves just that one photo for Usugami.
  Usugami never uses the camera itself, and the photo stays in Usugami's private working copies
  until you make it into a PDF or remove it.
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
- Recent files: the name, type, size and place of the last 50 files you saved with Usugami, and
  when you saved them, stored privately on your phone so you can open and share them again from
  Home. Usugami can read the files it saved to Downloads without any permission. For a file saved
  with "Save as", Usugami keeps Android's permission to read that file while it is on the list.
  For files saved into a folder you picked, it keeps Android's permission to read that folder, and
  what is in it, while one of them is on the list, and uses it only to open, share and check on
  those files. Usugami gives each permission back once no file on the list needs it.
- Usugami excludes its data from Android's cloud backup and device-to-device transfer.

Nothing is stored anywhere else. Usugami has no servers.

## Text recognition (OCR)

Text recognition is optional. It only runs when you turn on "Make text searchable (OCR)" while
converting images, and the recognition itself happens on your phone. Your images and the
recognized text are never sent to Google or anyone else. How it works depends on the edition.

### Usugami (the main edition)

- Text recognition uses Google ML Kit, a library made by Google, and runs through Google Play
  services, a separate app from Google that is installed on most Android phones.
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
- Everything else in Usugami (images, Word, PowerPoint, Excel and EPUB to PDF, and all the PDF
  tools) works without Google Play services and without internet.

### Usugami OCR

- Text recognition is open source and built into the app, models included: PaddleOCR's PP-OCRv5
  models, run on your phone's processor by Tencent's ncnn. It works with no internet connection and
  doesn't need Google Play services.
- The page images are read in the app's memory and only the recognized text is kept, as the PDF's
  invisible text layer. Nothing is sent anywhere or stored anywhere else.
- Usugami OCR contains no Google libraries and makes no network connections at all.

### Usugami Lite

- Usugami Lite has no text recognition. It contains no Google libraries and makes no network
  connections at all.

If you want no network use of any kind, install Usugami OCR or Usugami Lite.

## Data collection and sharing

Usugami does not collect personal data. It has no analytics, crash reporting, advertising IDs or
accounts. The developer receives nothing from the app and cannot see what you do with it.

## Children

Usugami does not collect personal information from anyone, including children.

## Keeping and deleting your data

- Working copies are deleted automatically (see above).
- To take a file off the Recent files list, or clear the list, use the Recent files screen (See
  all on Home). The files themselves stay where you saved them.
- To delete everything Usugami stores on your phone, go to Settings > Apps > Usugami > Storage and
  tap "Clear storage", or uninstall Usugami.
- PDFs you saved to Downloads or another folder are your files. They stay until you delete them,
  even if you uninstall Usugami.
- In Usugami (the main edition), the text-recognition models are kept and managed by Google Play
  services. In Usugami OCR they are part of the app and go when you uninstall it.

## Changes to this policy

Any change to this policy is made in the file PRIVACY.md in Usugami's public repository, so every
version and change is visible in its history:
https://github.com/Nath-Goa/usugami/commits/main/PRIVACY.md

The effective date at the top changes with each update, and app releases include the version of
the policy that applies to them.
