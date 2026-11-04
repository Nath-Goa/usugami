# Contributing to Usugami

Thanks for helping! Bug reports, ideas and pull requests are all welcome.

## Reporting bugs and ideas

Open an [issue](https://github.com/Nath-Goa/usugami/issues). For bugs, include your phone model,
Android version, the Usugami version and edition (Usugami, Usugami Lite or Usugami OCR) and the
steps that trigger it. Please don't attach private documents; a made-up file that shows the
problem is perfect. Security problems go through private reporting instead (see
[SECURITY.md](SECURITY.md)).

## What fits Usugami

Usugami is small, offline and private. Pull requests that add any of the following won't be
accepted:

- ads, analytics, crash reporting, tracking or accounts
- the internet permission, network access or any feature that uploads or downloads files
- new proprietary libraries

Text recognition in the main edition (Usugami) is the one exception to "no network": Google Play
services, a separate app, downloads the model the first time each language is used. Usugami
itself never connects. Usugami Lite and Usugami OCR must stay free of Google libraries.

## Building

You need JDK 17 or newer (Android Studio's bundled JBR works; CI uses Temurin 21) and the Android
SDK, with its path in `local.properties` (`sdk.dir=...`; Android Studio creates this file).

Usugami OCR's text-recognition engine is built from source: Tencent's ncnn is a git submodule
(`git clone --recurse-submodules`, or `git submodule update --init third_party/ncnn` in an existing
clone; its own submodules aren't needed), compiled with the Android NDK 28.2.13676358 and CMake
3.22.1 (install both with Android Studio's SDK Manager or `sdkmanager "ndk;28.2.13676358"
"cmake;3.22.1"`). Gradle wants the NDK even for the other two editions, and downloads it by itself
if it's missing; they don't need the submodule or CMake.

```sh
./gradlew :core:test                          # PDF engine unit tests (JVM)
./gradlew :app:assembleStandardDebug          # Usugami, debug build
./gradlew :app:assembleLiteDebug              # Usugami Lite, debug build
./gradlew :app:assembleOcrDebug               # Usugami OCR, debug build
./gradlew :app:lintStandardDebug :app:lintLiteDebug :app:lintOcrDebug
./gradlew :app:connectedStandardDebugAndroidTest   # device tests, phone or emulator
./gradlew :app:connectedLiteDebugAndroidTest
./gradlew :app:connectedOcrDebugAndroidTest
```

The three editions are product flavors in the `edition` dimension:

- `standard` (Usugami): text recognition with Google ML Kit through Google Play services. Code in
  `app/src/standard`; the only edition with Google libraries.
- `lite` (Usugami Lite): no text recognition. Code in `app/src/lite`. Its application ID has
  `.lite` added (`io.github.nath_goa.usugami.lite`) and its launcher name is "Usugami Lite", so it
  is an app of its own and installs next to the other two, which share theirs. Anything that names
  the package must use the app's own (`context.packageName`, `${applicationId}` in the manifest).
- `ocr` (Usugami OCR): open-source text recognition built in: PaddleOCR's PP-OCRv5 models
  (`app/src/ocr/assets/ocr`, made by `tools/make_ocr_models.py`) run by ncnn. The pipeline is in
  `app/src/ocr`, its pure logic (text boxes, reading order, decoding) in `core`, and ncnn with the
  JNI bridge in the `ocr-native` module, which only this edition depends on. If you change which
  layers ncnn is built with (`ocr-native/src/main/cpp/CMakeLists.txt`), run the edition's device
  tests: a missing layer only shows when a model loads.

Shared code lives in `app/src/main` and should work in all three. Each edition provides
`ocrEngine(context)` (null in Usugami Lite); `BuildConfig.EDITION` and `BuildConfig.HAS_OCR` tell
them apart. Release builds are only signed when a key is configured (see the README), so you don't
need one to contribute. [docs/TESTING.md](docs/TESTING.md) explains the tests.

## Pull requests

- Keep each pull request to one change, and describe what it does and how you tested it.
- Run `:core:test`, the three lint tasks and, for anything touching PDFs or text recognition, the
  device tests for each edition.
- Follow the existing style: Kotlin official code style (`kotlin.code.style=official`), Jetpack
  Compose and Material 3 for UI, and user-facing text in `app/src/main/res/values/strings.xml`.
- Write plain, short UI text and comments.
- If you add or update a dependency, update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). For
  Google libraries, regenerate `third_party/google-notices.txt` with `tools/google_notices.py`. For
  Usugami OCR's models, run `tools/make_ocr_models.py`, which checks them against
  `tools/ocr-models.sha256`.
- CI must pass. It also fails if any APK requests a permission (besides the app's own
  signature-level receiver permission), or if a library adds back ML Kit's metrics upload backend
  or the EmojiCompat initializer. If a dependency update trips this, remove the entry in
  `app/src/main/AndroidManifest.xml` with `tools:node="remove"`, or explain in the pull request
  why Usugami needs it. CI also fails if Usugami Lite or Usugami OCR contains any Google ML Kit,
  Play services, Firebase or DataTransport code, or if a native library isn't aligned for phones
  with 16 KB memory pages.

## License of contributions

Usugami is licensed under the GNU General Public License version 3 or later, with an additional
permission under section 7 that allows linking with Google ML Kit and Google Play services
([LICENSE](LICENSE), [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md)).

By submitting a contribution, you agree that it is licensed under the same terms: GPL-3.0-or-later
together with that additional permission. This keeps the whole app distributable. If you can't
agree to that, please open an issue first.
