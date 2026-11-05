# F-Droid recipes

F-Droid lists two of Usugami's editions as two apps, both built from this repository:

| App on F-Droid | Edition (flavor) | Package name | Recipe | Release APK it must match |
|---|---|---|---|---|
| Usugami | `ocr` (open-source text recognition) | `io.github.nath_goa.usugami` | [`../.fdroid.yml`](../.fdroid.yml) | `Usugami-ocr.apk` |
| Usugami Lite | `lite` (no text recognition) | `io.github.nath_goa.usugami.lite` | [`io.github.nath_goa.usugami.lite.yml`](io.github.nath_goa.usugami.lite.yml) | `Usugami-lite.apk` |

The recipes are in fdroiddata's format, ready to be copied into fdroiddata as
`metadata/<package name>.yml` (fdroidserver also reads `.fdroid.yml` when it runs inside this
repository). Their `MaintainerNotes` explain each step for F-Droid's reviewers.

Both builds are reproducible, so F-Droid publishes the APKs from the GitHub release, signed by the
developer (`Binaries` and `AllowedAPKSigningKeys`). `.github/workflows/fdroid.yml` builds both apps
with fdroidserver from these recipes and checks that; see *Reproducible builds and F-Droid* in
[CONTRIBUTING.md](../CONTRIBUTING.md).

## Usugami's own F-Droid repository

Until the apps are in F-Droid's main repository, `.github/workflows/fdroid-repo.yml` publishes a
repository of its own at <https://nathank.me/usugami/fdroid/repo> (GitHub Pages of this
repository; the page at <https://nathank.me/usugami/> explains how to add it). After each release
it takes the release's `Usugami-ocr.apk` and `Usugami-lite.apk` (signed by the developer, nothing is
rebuilt), and `make_repo.py` sets up fdroidserver with the store texts above and the recipes' store
fields; `fdroid update` then makes the index and signs it with the repository key, which only signs
the index. Its fingerprint is
`8D92FFB46DB5C052B334438D8E012974D3969A27EACFF4AEEE687B99A4760DA8`. The key lives in the
`fdroid-repo` environment's secrets, which only `main` can use; if it is lost, everyone has to add
the repository again.
