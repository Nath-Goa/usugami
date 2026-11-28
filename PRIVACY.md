# Usugami Privacy Policy

Effective date: 28 November 2026

Usugami is an Android app that turns images, Word documents, PowerPoint presentations, Excel
workbooks and EPUB e-books into PDFs; opens, annotates, prints, splits, merges, reorganizes,
compresses, signs, redacts and translates PDFs; and turns PDFs into images or text. It works on
your phone. It has no ads, no analytics of its own, no tracking, no accounts and no in-app
purchases, and it does not collect, share or sell any data about you. In Usugami (the main
edition), Google's libraries inside the app hand records of their use to Google Play services on
your phone, which may send them to Google (see below).

This policy covers all three editions of Usugami: "Usugami" (the main edition, Usugami.apk, from
GitHub and Obtainium only), "Usugami Lite" and "Usugami OCR". The app called "Usugami" in
Usugami's F-Droid repository is Usugami OCR, and so is the edition made for Google Play
(Usugami-ocr.aab): they have no Translate PDF, no internet permission and no Google code. The
differences that matter for privacy are text recognition (OCR) and, in the main edition only,
Translate PDF and the internet permission its language packs need, explained below.

## Who is responsible

Usugami is made by Nathan Kumtakar, an independent developer. It is free and open-source software:
https://github.com/Nath-Goa/usugami

Questions or concerns: open an issue at https://github.com/Nath-Goa/usugami/issues. Issues are
public, so please don't include personal details. To report a security problem privately, use
"Report a vulnerability" on the repository's Security tab.

## What Usugami can access

- Only the images, PDFs, Word, PowerPoint and Excel files, and EPUB books you choose. You pick them
  with Android's photo picker or file picker, you share them to Usugami from another app, or you
  open them with Usugami from another app ("Open with"). Android then gives Usugami access to those
  files only, and to a folder you pick to save into (see
  Recent files below). Usugami cannot browse your photos or any other files or folders.
- Usugami asks for no permissions: no storage, camera, location, contacts or microphone access.
  Usugami (the main edition) declares two permissions that Android grants when it installs an app,
  without asking: internet access, and seeing whether the phone is online. They are there only for
  Translate PDF's language packs (see below). Usugami Lite and Usugami OCR declare neither.
- **Take photo** opens your phone's own camera app, which saves just that one photo for Usugami.
  Usugami never uses the camera itself, and the photo stays in Usugami's private working copies
  until you make it into a PDF or remove it.
- Usugami Lite and Usugami OCR have no internet permission, so Android does not let them connect
  to the internet, and they cannot upload your files. Usugami (the main edition) has it only so
  that Android's download manager can download Translate PDF's language packs. Usugami's own code
  opens no connection, and it does not upload your files (see "Translate PDF" below).
- When you save a file, it goes where you choose: the Downloads folder or a folder you pick with
  the system file picker. When you tap Share or Open with another app, the file goes to the app you
  pick, and that app's own privacy policy applies. When you tap Print, the PDF goes to Android's
  print screen and then to the printer or print service you choose there.

## What Usugami stores on your phone

- Working copies: while you work, Usugami keeps copies of the files you picked or opened and the
  files it makes in its private cache on your phone, where other apps can't read them. Usugami
  deletes them automatically once they are no longer needed (for example, when you start over), and
  Android may also clear the cache when storage runs low.
- Signatures: a signature you draw for "Sign PDF" is kept only while Usugami is open, in its
  memory and in the screen state Android keeps for it, so you can use it again. It is never saved
  in a file of its own, only in the PDFs you sign.
- Annotate: the text, drawings and shapes you add to a PDF in the reader are kept the same way,
  until you close it, and only end up in the PDF you save. Pictures you add are working copies, as
  above. The file you opened is never changed.
- Settings: your last choices (such as page size, margins and compression level), stored
  privately on your phone.
- Recent files: the name, type, size and place of the last 50 files you saved with Usugami, and
  when you saved them, stored privately on your phone so you can open and share them again from
  Home. Usugami can read the files it saved to Downloads without any permission. For a file saved
  with "Save as", Usugami keeps Android's permission to read that file while it is on the list.
  For files saved into a folder you picked, it keeps Android's permission to read that folder, and
  what is in it, while one of them is on the list, and uses it only to open, share and check on
  those files. Usugami gives each permission back once no file on the list needs it.
- Language packs (Usugami, the main edition): the packs Translate PDF has downloaded, about 45 MB
  each once unpacked, in Usugami's private storage. Beside them, Google's libraries keep a random
  identifier ML Kit makes for this installation, the packs' checksums and a database of usage
  events. Google's ML Kit data disclosure lists identifiers of this kind among what ML Kit collects
  with its usage records (see "Translate PDF" below). They keep no Firebase identifier, token or
  settings.
- Usugami excludes its data from Android's cloud backup and device-to-device transfer.

Usugami stores nothing anywhere else, and it has no servers. Outside Usugami's own storage, two
things on your phone hold traces of its use in Usugami (the main edition): the phone's system log
holds, for a while, the text Translate PDF translated and the translation (see "Translate PDF"
below), and Google Play services keeps the text-recognition models and receives the usage records
Google's libraries hand it (see "Text recognition" and "Translate PDF" below).

## Text recognition (OCR)

Text recognition is optional. It only runs when you turn on "Make text searchable (OCR)" while
converting images, or "Read pages without text" in PDF to images or text, or, in the main edition,
when Translate PDF comes to a scanned page. The recognition itself happens on your phone. Your
images and the recognized text are never sent to Google or anyone else. How it works depends on
the edition.

### Usugami (the main edition)

- Text recognition uses Google ML Kit, a library made by Google, and runs through Google Play
  services, a separate app from Google that is installed on most Android phones.
- The first time you use a text language, Google Play services downloads Google's text-recognition
  model for it (one model covers English, French, Spanish, German, Portuguese and Indonesian; Hindi
  has its own). This needs an internet connection at that time. Google Play services then keeps the
  model for later use; if it removes a model, it downloads it again the next time you use that
  language. Google Play services does these downloads, not Usugami, and Google's Privacy Policy
  applies to them: https://policies.google.com/privacy
- To download and run text recognition, Google's libraries inside Usugami talk to Google Play
  services on your phone. Google Play services may report to Google how these Google APIs were
  used (for example, whether a call succeeded).
- According to Google's ML Kit data disclosure, ML Kit may also collect diagnostic and usage
  information about the ML Kit feature, such as device and app information and performance
  metrics, and send it to Google:
  https://developers.google.com/ml-kit/android-data-disclosure
- Your images and the recognized text are not part of any of this.
- Usugami disables (unregisters) the part of Google's library that would upload ML Kit's metrics
  from inside Usugami, so those metrics are dropped. Google Play services is a separate app, and
  what it sends is covered by Google's policies, not by Usugami.
- Everything else in Usugami (the reader, images, Word, PowerPoint, Excel and EPUB to PDF, and all
  the PDF tools but Translate PDF) works without Google Play services and without internet.

### Usugami OCR

- Text recognition is open source and built into the app, models included: PaddleOCR's PP-OCRv5
  models, run on your phone's processor by Tencent's ncnn. It works with no internet connection and
  doesn't need Google Play services.
- The page images are read in the app's memory and only the recognized text is kept, as the PDF's
  invisible text layer or in the text file PDF to images or text makes. Nothing is sent anywhere or
  stored anywhere else.
- Usugami OCR contains no Google libraries and makes no network connections at all.

### Usugami Lite

- Usugami Lite has no text recognition. It contains no Google libraries and makes no network
  connections at all.

## Translate PDF (Usugami, the main edition, only)

Translate PDF makes a copy of a PDF in another language. It is only in Usugami, the main edition;
Usugami Lite and Usugami OCR don't have it, its permission or its Google code.

- **On your phone.** The translating is done on your phone, by Google ML Kit's translation library
  inside Usugami. The PDF, its text and the translation are not sent to Google or anyone else. The
  PDF's language is also worked out on the phone, by a library whose model is built into the app.
- **Language packs.** Translating from or into a language needs that language's pack; English is
  built in. The first time a translation needs a pack, Usugami asks you first. If you agree,
  Android's download manager, a part of Android outside Usugami, downloads it from Google's
  servers: about 40 MB, once per language. As with any download, Google's servers receive the
  request, which comes from your phone's internet address and names the language pack being
  downloaded, and Google's Privacy Policy applies to it: https://policies.google.com/privacy. Android counts the download as Usugami's in its data
  usage. If you cancel, the download stops and a pack that hadn't finished is removed. A pack then
  stays on your phone, and translating needs no internet, until you delete it under Language packs
  on Translate PDF's screen.
- **Why the internet permission.** Android's download manager only downloads for an app that has
  the internet permission, so Usugami has it. Seeing whether the phone is online lets Usugami tell
  you, before and during a download, when there is no connection.
- **Usugami itself opens no connection.** From the moment Usugami starts, its own process refuses
  to open any web address, to any server. Google's translation library would otherwise register
  itself with Google's Firebase service and fetch settings from it; those attempts are refused
  inside the app before any connection is made, and the network library the registration would
  use is left out of Usugami. A recording of the network traffic of Usugami's release build, made
  on an emulator, showed traffic only while a pack downloaded, from Google's download servers, and
  none when translating again, hours or days later, offline or left idle.
- **Usage records.** As with text recognition, using the translation library hands a record of its
  use to Google Play services on your phone, which may send it to Google. According to Google's ML
  Kit data disclosure (above), such records can hold, among other things, the phone's maker, model
  and Android version, the app's name and version, identifiers for the device and for this
  installation, how much text went in and out and how long it took, and, for Translate PDF, the
  languages chosen and the language detected. What Google Play services sends is covered by
  Google's policies, not by Usugami.
- **The system log.** Google's translation library writes the text it translates, and the
  translation, into the phone's system log. Other apps you install can't read the system log, but
  a computer connected to the phone for USB debugging can, and so can a bug report made on the
  phone, which you might send to someone. Android keeps the log in memory, where newer lines
  replace older ones, and on most phones a restart clears it. Usugami keeps the Java code of
  Google's libraries from writing debug and info messages (their warnings and errors, which hold no
  text, stay), but not these lines, which come from the library's native code: silencing them would
  take native code of Usugami's own, and only Android 11 and newer allow it.
- **Scanned pages** are read with text recognition first, as described above for the main edition,
  including its own first download through Google Play services.

If you want no network use of any kind, install Usugami OCR or Usugami Lite.

## Data collection and sharing

Usugami does not collect personal data. It has no analytics of its own, crash reporting,
advertising IDs or accounts; in Usugami (the main edition), what Google's libraries hand to Google
Play services is described above. The developer receives nothing from the app and cannot see what
you do with it.

## Children

Usugami does not collect personal information from anyone, including children.

## Keeping and deleting your data

- Working copies are deleted automatically (see above).
- To take a file off the Recent files list, or clear the list, use the Recent files screen (See
  all on Home). The files themselves stay where you saved them.
- To delete everything Usugami stores on your phone, go to Settings > Apps > Usugami > Storage and
  tap "Clear storage", or uninstall Usugami.
- In Usugami (the main edition), clearing its storage doesn't remove the lines Translate PDF's
  library wrote to the phone's system log; on most phones, restarting the phone does.
- PDFs you saved to Downloads or another folder are your files. They stay until you delete them,
  even if you uninstall Usugami.
- In Usugami (the main edition), the text-recognition models are kept and managed by Google Play
  services. In Usugami OCR they are part of the app and go when you uninstall it.
- In Usugami (the main edition), Translate PDF's language packs stay until you delete them under
  Language packs on its screen, clear Usugami's storage or uninstall it. Installing Usugami OCR
  over Usugami keeps them, and Usugami OCR can't show or delete them: delete them before you
  switch, or clear its storage.

## Changes to this policy

Any change to this policy is made in the file PRIVACY.md in Usugami's public repository, so every
version and change is visible in its history:
https://github.com/Nath-Goa/usugami/commits/main/PRIVACY.md

The effective date at the top changes with each update, and app releases include the version of
the policy that applies to them.
