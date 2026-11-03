#!/usr/bin/env bash
# Checks that the native libraries in APKs work on Android devices with 16 KB memory pages:
#   - every lib/*.so is stored in the APK at a 16 KB boundary (zipalign -c -P 16), and
#   - every LOAD segment of each 64-bit library (arm64-v8a, x86_64) is aligned to at least 16 KB
#     (llvm-readelf). Only 64-bit devices have 16 KB pages, so 32-bit libraries may use 4 KB.
# An APK without native libraries passes.
#
#   .github/scripts/check-16kb-alignment.sh app.apk [more.apk ...]
#
# Uses zipalign from the newest build-tools and llvm-readelf from the newest NDK in $ANDROID_HOME,
# or $ZIPALIGN and $READELF if set.
set -euo pipefail

[ "$#" -gt 0 ] || { echo "usage: $0 APK [APK ...]" >&2; exit 2; }

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
find_tool() { # $1: path pattern under the SDK; prints the newest match, with .exe on Windows
  local found
  found="$(ls -d $sdk/$1 $sdk/$1.exe 2>/dev/null | sort -V | tail -n 1 || true)"
  [ -n "$found" ] || { echo "::error::$1 not found in the Android SDK ($sdk)" >&2; exit 1; }
  printf '%s\n' "$found"
}
if [ -z "${ZIPALIGN:-}" ] || [ -z "${READELF:-}" ]; then
  [ -n "$sdk" ] || { echo "::error::ANDROID_HOME is not set"; exit 1; }
fi
zipalign="${ZIPALIGN:-$(find_tool 'build-tools/*/zipalign')}"
readelf="${READELF:-$(find_tool 'ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf')}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
failed=0
for apk in "$@"; do
  [ -f "$apk" ] || { echo "::error::$apk not found"; exit 1; }
  libs="$(unzip -Z1 "$apk" | grep -E '^lib/[^/]+/[^/]+\.so$' || true)"
  if [ -z "$libs" ]; then
    echo "$apk: no native libraries"
    continue
  fi
  if ! "$zipalign" -c -P 16 -v 4 "$apk" > "$work/zipalign.txt" 2>&1; then
    grep -E 'lib/.*\.so' "$work/zipalign.txt" | grep -v '(OK' || cat "$work/zipalign.txt"
    echo "::error::$apk: native libraries not 16 KB aligned in the APK (zipalign -c -P 16)"
    failed=1
  fi
  while IFS= read -r lib; do
    abi="${lib#lib/}"
    abi="${abi%%/*}"
    rm -rf "$work/lib"
    unzip -q -o "$apk" "$lib" -d "$work"
    alignments="$("$readelf" -lW "$work/$lib" | awk '$1 == "LOAD" { print $NF }')"
    [ -n "$alignments" ] || { echo "::error::$apk: $lib has no LOAD segments"; failed=1; continue; }
    smallest="$(for a in $alignments; do printf '%d\n' "$a"; done | sort -n | head -n 1)"
    echo "$apk: $lib LOAD alignment $(printf '0x%x' "$smallest")"
    case "$abi" in
      arm64-v8a|x86_64)
        if [ "$smallest" -lt 16384 ]; then
          echo "::error::$apk: $lib has LOAD segments aligned to less than 16 KB"
          failed=1
        fi
        ;;
    esac
  done <<< "$libs"
done
[ "$failed" -eq 0 ] || exit 1
echo "Native libraries are 16 KB aligned"
