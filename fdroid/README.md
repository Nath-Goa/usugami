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
