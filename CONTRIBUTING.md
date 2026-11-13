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
services, a separate app, downloads a model the first time it is needed (one for the six
Latin-script languages, one for Hindi). Usugami itself never connects. Usugami Lite and Usugami OCR
must stay free of Google libraries.

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

## Translations

Usugami comes in English, French, Spanish, Hindi, German, Brazilian Portuguese and Indonesian.
English is in `app/src/main/res/values/strings.xml`, and each language has its own copy next to
it; the editions' own texts are in the same folders under `app/src/standard/res` and
`app/src/lite/res`. The store texts are in `fastlane/metadata/android/<locale>/` for Usugami and
`src/lite/fastlane/metadata/android/<locale>/` for Usugami Lite. Each language's folders and tags:

| Language | Resource folder | App tag (`locales_config.xml`) | Store folder |
|---|---|---|---|
| French | `values-fr` | `fr` | `fr-FR` |
| Spanish | `values-es` | `es` | `es-ES` |
| Hindi | `values-hi` | `hi` | `hi-IN` |
| German | `values-de` | `de` | `de-DE` |
| Brazilian Portuguese | `values-pt` (for every Portuguese) | `pt-BR` | `pt-BR` |
| Indonesian | `values-in` (Android's code; not `values-id`) | `id` | `id` |

`./gradlew :core:test` checks the translations (`TranslationsTest`): every language has each
English text that can be translated and no other, with the same placeholders (`%1$s`, `%1$d`),
plural forms and line breaks, apostrophes and quotes escaped with a backslash, and the store texts
of both apps within F-Droid's limits (80 characters for the short description, 4000 for the full
one, 500 for a changelog). To fix or add a translation, change the language's `strings.xml` and
run it. The search words in each `search_keywords_*` string are the words people of that language
would type for the tool, not a word-for-word translation of the English list.

## Reproducible builds and F-Droid

F-Droid builds two of the editions from source: Usugami (the `ocr` flavor,
`io.github.nath_goa.usugami`) with the recipe in [`.fdroid.yml`](.fdroid.yml), and Usugami Lite
(`io.github.nath_goa.usugami.lite`) with [`fdroid/io.github.nath_goa.usugami.lite.yml`](fdroid/io.github.nath_goa.usugami.lite.yml).
It compares what it builds with the release's `Usugami-ocr.apk` and `Usugami-lite.apk` and, if they
are the same apart from the signature, publishes the release APKs, signed with the developer's key.
So these two editions' release builds must be **reproducible**: one commit must give the same
unsigned APK byte for byte, whoever builds it, on any machine and in any folder. (Usugami's
`standard` flavor uses Google ML Kit, which F-Droid doesn't allow; the recipes remove its sources
and its dependency lines.)

What keeps the builds reproducible:

- Pinned tools: the Gradle wrapper with its checksum (F-Droid runs the same Gradle version with its
  own launcher, gradlew-fdroid), AGP and Kotlin versions, NDK 28.2.13676358 (`ndk: 28.2.13676358` in the
  recipes) and CMake 3.22.1.
- The OCR engine (`ocr-native/src/main/cpp/CMakeLists.txt`): a fixed `NCNN_VERSION` instead of the
  build date; no debug info (`-g0`), because clang otherwise writes the absolute path of the source
  file of every OpenMP loop in ncnn into the library; and `-ffile-prefix-map` for the repository,
  the build folder and the NDK, so no absolute path ends up in it.
- Release APKs are signed by AGP itself (APK Signature Scheme v2 and v3, no v1), and there is no
  dependency-metadata block (`dependenciesInfo`), so F-Droid's unsigned build with the release's
  signature copied onto it is exactly the release APK.
- The APK records the commit it was built from (`META-INF/version-control-info.textproto`), so it
  only matches a build of the same commit: the release workflow builds the tagged commit, and so
  does F-Droid.
- Text files have LF line endings in every checkout (`.gitattributes`), so the license texts inside
  the APK don't depend on the system.

When you change the build, don't add anything that depends on when, where or by whom it runs: dates
or times (`__DATE__`, `string(TIMESTAMP)`, a build time in `BuildConfig`), absolute paths, user or
machine names, random values, or files listed in whatever order the file system returns them.
Keep the native flags above, and keep signing in Gradle (re-signing or re-aligning the APK with
apksigner after the build would change its layout). When you update the NDK or CMake, update the
recipes too.

To check it:

- **Locally:** build the same commit in two clones in different folders and compare the APKs
  (without a signing key, release builds come out unsigned):

  ```sh
  git clone --recurse-submodules https://github.com/Nath-Goa/usugami.git a
  git clone --recurse-submodules https://github.com/Nath-Goa/usugami.git some/other/folder/b
  (cd a && ./gradlew :app:assembleOcrRelease :app:assembleLiteRelease)
  (cd some/other/folder/b && ./gradlew :app:assembleOcrRelease :app:assembleLiteRelease)
  for f in ocr/release/app-ocr-release-unsigned.apk lite/release/app-lite-release-unsigned.apk; do
    cmp "a/app/build/outputs/apk/$f" "some/other/folder/b/app/build/outputs/apk/$f" && echo "same: $f"
  done
  ```

- **The F-Droid check:** on GitHub, Actions → **F-Droid** → **Run workflow**, with a branch, tag
  or commit. It builds both apps with fdroidserver from their recipes, as F-Droid does, runs
  F-Droid's scanner on the source and the APKs, and for a release's commit compares the APKs with
  the release's using `apksigcopier compare`. It also runs by itself for each release tag, after
  waiting for the release's APKs.
- **Against a release by hand:** `pip install apksigcopier`, then, with `apksigner` from the
  Android SDK build-tools on your `PATH`:
  `apksigcopier compare --unsigned Usugami-ocr.apk app/build/outputs/apk/ocr/release/app-ocr-release-unsigned.apk`
  (the same for `Usugami-lite.apk`), with a build of the release's tagged commit.

F-Droid's store texts and graphics come from this repository: `fastlane/metadata/android/<locale>/`
for Usugami, and `src/lite/fastlane/metadata/android/<locale>/` for Usugami Lite (its texts; the
graphics it doesn't have come from Usugami's). Each release adds `changelogs/<versionCode>.txt`
(500 characters at most) in every locale of both. F-Droid finds new versions by their `v` tags.
To submit or update the apps, copy the two recipes into fdroiddata as
`metadata/io.github.nath_goa.usugami.yml` and `metadata/io.github.nath_goa.usugami.lite.yml`.

## Pull requests

- Keep each pull request to one change, and describe what it does and how you tested it.
- Run `:core:test`, the three lint tasks and, for anything touching PDFs or text recognition, the
  device tests for each edition.
- Follow the existing style: Kotlin official code style (`kotlin.code.style=official`), Jetpack
  Compose and Material 3 for UI, and user-facing text in `app/src/main/res/values/strings.xml`
  (see *Translations* for the other languages).
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
