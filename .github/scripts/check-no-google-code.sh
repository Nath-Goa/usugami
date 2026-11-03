#!/usr/bin/env bash
# Checks that APKs contain no Google ML Kit, Google Play services, Firebase or DataTransport code,
# as Usugami Lite and Usugami OCR promise. Fails if an APK has
#   - a class in one of the packages in forbidden_packages below, listed from its dex files with
#     apkanalyzer. Release builds are obfuscated, so pass R8's mapping.txt to see the classes'
#     original names ("-" for a build that isn't obfuscated),
#   - a name in one of those packages anywhere in R8's mapping.txt: R8 can inline a library's code
#     into Usugami's classes, or merge or remove its classes, so it ships without a class of its own
#     in the dex; the mapping still names where the code came from, or
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

# The packages as a regular expression, with dots or slashes (type descriptors in the mapping).
forbidden_pattern="$(printf '%s\n' "${forbidden_packages[@]}" | sed 's/\./[.\/]/g' | paste -sd '|' -)"

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
  google_mapping=""
  if [ "$mapping" != "-" ]; then
    google_mapping="$(grep -oE "($forbidden_pattern)[A-Za-z0-9_\$./]*" "$mapping" | sort -u || true)"
  fi

  manifest="$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")"
  names="$(sed -n 's/.*android:name([^)]*)="\([^"]*\)".*/\1/p' <<< "$manifest")"
  [ -n "$names" ] || { echo "::error file=$apk::$apk: no android:name found in the manifest dump"; status=1; continue; }
  google_manifest="$(matches_forbidden <<< "$names" | paste -sd ' ' -)"

  if [ -n "$google" ] || [ -n "$google_mapping" ] || [ -n "$google_manifest" ]; then
    echo "::error file=$apk::$apk contains Google code: $(grep -c . <<< "$google" || true) classes, $(grep -c . <<< "$google_mapping" || true) names in the mapping, manifest entries: ${google_manifest:-none}"
    sed -n '1,20s/^/  /p' <<< "$google"
    sed -n '1,20s/^/  mapping: /p' <<< "$google_mapping"
    status=1
  else
    echo "$apk: no Google code among $count classes, the mapping and the manifest"
  fi
done
exit "$status"
