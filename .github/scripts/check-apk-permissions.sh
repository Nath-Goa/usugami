#!/usr/bin/env bash
# Fails if any of the given APKs requests a network or advertising-ID permission.
# Usugami promises "no internet permission"; this keeps a library update from quietly adding one.
#
#   .github/scripts/check-apk-permissions.sh app.apk [more.apk ...]
#
# Uses aapt2 from the newest Android build-tools in $ANDROID_HOME, or $AAPT2 if set.
set -euo pipefail

[ "$#" -gt 0 ] || { echo "usage: $0 APK..." >&2; exit 2; }

forbidden=(
  android.permission.INTERNET
  android.permission.ACCESS_NETWORK_STATE
  com.google.android.gms.permission.AD_ID
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
for apk in "$@"; do
  [ -f "$apk" ] || { echo "::error::$apk not found"; status=1; continue; }
  perms="$("$aapt2" dump permissions "$apk")"
  echo "$apk"
  printf '%s\n' "$perms" | sed 's/^/  /'
  for permission in "${forbidden[@]}"; do
    if printf '%s\n' "$perms" | grep -Fq "name='$permission'"; then
      echo "::error file=$apk::$apk requests $permission"
      status=1
    fi
  done
done

if [ "$status" -eq 0 ]; then
  echo "OK: no INTERNET, ACCESS_NETWORK_STATE or AD_ID permission requested."
fi
exit "$status"
