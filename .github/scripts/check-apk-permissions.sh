#!/usr/bin/env bash
# Checks the privacy promises that live in each APK's manifest, so a library update can't quietly
# break them. Fails if an APK:
#   - requests any permission other than the app's own <package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
#     (signature-level; AndroidX declares it to keep the app's broadcast receivers private), apart
#     from those in standard_permissions below for the APK given after --standard, or
#   - contains one of the manifest entries in forbidden_names below.
#
#   .github/scripts/check-apk-permissions.sh [--standard] APK [[--standard] APK ...]
#
# --standard marks the APK right after it as Usugami's standard edition (Usugami.apk), whose
# Translate PDF downloads language packs with Google ML Kit. Usugami Lite and Usugami OCR never get
# it: they have no internet permission at all.
#
# Uses aapt2 from the newest Android build-tools in $ANDROID_HOME, or $AAPT2 if set.
set -euo pipefail

usage() { echo "usage: $0 [--standard] APK [[--standard] APK ...]" >&2; exit 2; }
[ "$#" -gt 0 ] || usage

# The only permission the APKs may request, after "<package>.".
allowed_permission=DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION

# What the standard edition may request besides: ML Kit's translation library asks for both, to
# download a language's pack the first time it is needed.
standard_permissions=(
  android.permission.INTERNET
  android.permission.ACCESS_NETWORK_STATE
)

# Manifest android:name values (matched as prefixes) that must not be in the APK. Libraries add
# them; app/src/main/AndroidManifest.xml and app/src/standard/AndroidManifest.xml remove them with
# tools:node="remove".
forbidden_names=(
  'backend:'                                    # an upload backend for ML Kit's usage metrics (DataTransport)
  'androidx.emoji2.text.EmojiCompatInitializer' # asks Google Play services for an emoji font at startup
)

aapt2="${AAPT2:-}"
if [ -z "$aapt2" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -n "$sdk" ] || { echo "::error::ANDROID_HOME is not set"; exit 1; }
  newest="$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -n 1)"
  aapt2="${newest}aapt2"
  [ -x "$aapt2" ] || aapt2="${aapt2}.exe" # Windows SDK
fi

status=0
standard=false
for arg in "$@"; do
  if [ "$arg" = --standard ]; then
    [ "$standard" = false ] || usage
    standard=true
    continue
  fi
  apk="$arg"
  is_standard="$standard"
  standard=false
  [ -f "$apk" ] || { echo "::error::$apk not found"; status=1; continue; }
  perms="$("$aapt2" dump permissions "$apk")"
  if [ "$is_standard" = true ]; then echo "$apk (the standard edition)"; else echo "$apk"; fi
  printf '%s\n' "$perms" | sed 's/^/  /'

  package="$(printf '%s\n' "$perms" | sed -n 's/^package: //p')"
  [ -n "$package" ] || { echo "::error file=$apk::$apk: aapt2 printed no package name"; status=1; continue; }

  allowed=("$package.$allowed_permission")
  [ "$is_standard" = false ] || allowed+=("${standard_permissions[@]}")

  # "permission:" lines are permissions the app declares; only requests matter here.
  mapfile -t requests < <(printf '%s\n' "$perms" | grep '^uses-permission' || true)
  for line in "${requests[@]}"; do
    ok=false
    for name in "${allowed[@]}"; do
      if [ "$line" = "uses-permission: name='$name'" ]; then ok=true; fi
    done
    if [ "$ok" = false ]; then
      echo "::error file=$apk::$apk requests a permission: $line"
      status=1
    fi
  done

  manifest="$("$aapt2" dump xmltree --file AndroidManifest.xml "$apk")"
  names="$(printf '%s\n' "$manifest" | sed -n 's/.*android:name([^)]*)="\([^"]*\)".*/\1/p')"
  [ -n "$names" ] || { echo "::error file=$apk::$apk: no android:name found in the manifest dump"; status=1; continue; }
  for forbidden in "${forbidden_names[@]}"; do
    found="$(printf '%s\n' "$names" | awk -v p="$forbidden" 'index($0, p) == 1' | paste -sd ' ' -)"
    if [ -n "$found" ]; then
      echo "::error file=$apk::$apk has a forbidden manifest entry: $found"
      status=1
    fi
  done
done
[ "$standard" = false ] || usage # --standard with no APK after it

if [ "$status" -eq 0 ]; then
  echo "OK: no permissions besides the app's own $allowed_permission (and, in the standard edition," \
    "${standard_permissions[*]}), and no forbidden manifest entries."
fi
exit "$status"
