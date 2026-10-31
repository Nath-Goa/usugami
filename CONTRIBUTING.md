# Contributing to Usugami

Thanks for helping! Bug reports, ideas and pull requests are all welcome.

## Reporting bugs and ideas

Open an [issue](https://github.com/Nath-Goa/usugami/issues). For bugs, include your phone model,
Android version, the Usugami version and edition (Usugami or Usugami Offline) and the steps that
trigger it. Please don't attach private documents; a made-up file that shows the
problem is perfect. Security problems go through private reporting instead (see
[SECURITY.md](SECURITY.md)).

## What fits Usugami

Usugami is small, offline and private. Pull requests that add any of the following won't be
accepted:

- ads, analytics, crash reporting, tracking or accounts
- the internet permission, network access or any feature that uploads or downloads files
- new proprietary libraries

Text recognition in the standard edition is the one exception to "no network": Google Play
services, a separate app, downloads the model once. Usugami itself never connects.

## Building

You need JDK 17 or newer (Android Studio's bundled JBR works; CI uses Temurin 21) and the Android
SDK, with its path in `local.properties` (`sdk.dir=...`; Android Studio creates this file).

```sh
./gradlew :core:test                          # PDF engine unit tests (JVM)
./gradlew :app:assembleStandardDebug          # Usugami, debug build
./gradlew :app:assembleOfflineDebug           # Usugami Offline, debug build
./gradlew :app:lintStandardDebug :app:lintOfflineDebug
./gradlew :app:connectedStandardDebugAndroidTest   # device tests, phone or emulator
./gradlew :app:connectedOfflineDebugAndroidTest
```

The two editions are product flavors in the `ocr` dimension:

- `standard`: text recognition through Google Play services. Code in `app/src/standard`.
- `offline`: text-recognition model built in. Code in `app/src/offline`.

Shared code lives in `app/src/main` and should work in both; `BuildConfig.OCR_BUNDLED` tells them
apart. Release builds are only signed when a key is configured (see the README), so you don't
need one to contribute. [docs/TESTING.md](docs/TESTING.md) explains the tests.

## Pull requests

- Keep each pull request to one change, and describe what it does and how you tested it.
- Run `:core:test`, both lint tasks and, for anything touching PDFs or text recognition, the
  device tests for both editions.
- Follow the existing style: Kotlin official code style (`kotlin.code.style=official`), Jetpack
  Compose and Material 3 for UI, and user-facing text in `app/src/main/res/values/strings.xml`.
- Write plain, short UI text and comments.
- If you add or update a dependency, update [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). For
  Google libraries, regenerate `third_party/` with `tools/google_notices.py`.
- CI must pass. It also fails if either APK requests any permission (besides the app's own
  signature-level receiver permission), or if a library adds back ML Kit's metrics upload backend
  or the EmojiCompat initializer. If a dependency update trips this, remove the entry in
  `app/src/main/AndroidManifest.xml` with `tools:node="remove"`, or explain in the pull request
  why Usugami needs it.

## License of contributions

Usugami is licensed under the GNU General Public License version 3 or later, with an additional
permission under section 7 that allows linking with Google ML Kit and Google Play services
([LICENSE](LICENSE), [LICENSE-EXCEPTION.md](LICENSE-EXCEPTION.md)).

By submitting a contribution, you agree that it is licensed under the same terms: GPL-3.0-or-later
together with that additional permission. This keeps the whole app distributable. If you can't
agree to that, please open an issue first.
