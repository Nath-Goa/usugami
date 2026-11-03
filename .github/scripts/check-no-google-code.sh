#!/usr/bin/env bash
# Checks that APKs contain no Google ML Kit, Google Play services, Firebase or DataTransport code,
# as Usugami Lite and Usugami OCR promise. Fails if an APK has
#   - a class in one of the packages in forbidden_packages below, listed from its dex files with
#     apkanalyzer. Release builds are obfuscated, so pass R8's mapping.txt to see the classes'
#     original names ("-" for a build that isn't obfuscated), or
#   - a manifest entry (a component or meta-data) named after one of them.
#
#   .github/scripts/check-no-google-code.sh app.apk mapping.txt [more.apk mapping.txt ...]
#
# Uses apkanalyzer from the Android SDK's cmdline-tools and aapt2 from the newest build-tools in
# $ANDROID_HOME, or $APKANALYZER and $AAPT2 if set.
set -euo pipefail

[ "$#" -gt 0 ] && [ $(($# % 2)) -eq 0 ] || { echo "usage: $0 APK MAPPING|- [APK MAPPING|- ...]" >&2; exit 2; }

forbidden_packages=(
  com.google.android.gms.          # Google Play services
  com.google.mlkit.                # Google ML Kit
  com.google.android.odml.         # ML Kit's image library
  com.google.firebase.             # Firebase components (used by ML Kit)
  com.google.android.datatransport. # Google's usage-metrics transport (used by ML Kit)
)

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
find_tool() { # $1: path pattern under the SDK; prints the newest match, with .exe/.bat on Windows
  local found
  found="$(ls -d $sdk/$1 $sdk/$1.exe $sdk/$1.bat 2>/dev/null | sort -V | tail -n 1 || true)"
  [ -n "$found" ] || { echo "::error::$1 not found in the Android SDK ($sdk)" >&2; exit 1; }
  printf '%s\n' "$found"
}
if [ -z "${APKANALYZER:-}" ] || [ -z "${AAPT2:-}" ]; then
  [ -n "$sdk" ] || { echo "::error::ANDROID_HOME is not set"; exit 1; }
fi
apkanalyzer="${APKANALYZER:-$(find_tool 'cmdline-tools/*/bin/apkanalyzer')}"
aapt2="${AAPT2:-$(find_tool 'build-tools/*/aapt2')}"

matches_forbidden() { # reads names on stdin, prints those in a forbidden package
  awk -v list="${forbidden_packages[*]}" '
    BEGIN { n = split(list, p, " ") }
    { for (i = 1; i <= n; i++) if (index($0, p[i]) == 1) { print; next } }'
}

status=0
while [ "$#" -gt 0 ]; do
  apk="$1" mapping="$2"
  shift 2
  [ -f "$apk" ] || { echo "::error::$apk not found"; status=1; continue; }
  args=(dex packages --defined-only)
  if [ "$mapping" != "-" ]; then
    [ -f "$mapping" ] || { echo "::error::$mapping not found"; status=1; continue; }
    args+=(--proguard-mappings "$mapping")
  fi
  # Class rows look like "C d 1 1 123 com.example.Name": the last field is the class name.
  classes="$("$apkanalyzer" "${args[@]}" "$apk" | awk '$1 == "C" { print $NF }' | tr -d '\r')"
  # Here-strings, not pipes: grep -q stops reading early, which would fail the pipe (pipefail).
  count="$(grep -c . <<< "$classes" || true)"
  # Usugami's own classes must be there, or the list (or its mapping) can't be trusted.
  if ! grep -q '^io\.github\.nath_goa\.usugami\.' <<< "$classes"; then
    echo "::error file=$apk::$apk: apkanalyzer listed none of Usugami's own classes ($count classes)"
    status=1
    continue
  fi
  google="$(matches_forbidden <<< "$classes")"

  manifest="$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")"
  names="$(sed -n 's/.*android:name([^)]*)="\([^"]*\)".*/\1/p' <<< "$manifest")"
  [ -n "$names" ] || { echo "::error file=$apk::$apk: no android:name found in the manifest dump"; status=1; continue; }
  google_manifest="$(matches_forbidden <<< "$names" | paste -sd ' ' -)"

  if [ -n "$google" ] || [ -n "$google_manifest" ]; then
    echo "::error file=$apk::$apk contains Google code: $(grep -c . <<< "$google" || true) classes, manifest entries: ${google_manifest:-none}"
    sed -n '1,20s/^/  /p' <<< "$google"
    status=1
  else
    echo "$apk: no Google code among $count classes and the manifest"
  fi
done
exit "$status"
