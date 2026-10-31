#!/usr/bin/env bash
# Fails if R8 removed the no-argument constructor of a Firebase component registrar.
#
# ML Kit creates its registrars by class name (listed in the merged manifest) with their
# no-argument constructor. If R8 removes that constructor, text recognition fails at runtime
# in release builds only. app/proguard-rules.pro keeps them; this check makes sure it still does.
#
# Usage: check-mlkit-registrars.sh <variant>...   e.g. standardRelease offlineRelease
set -euo pipefail

status=0
for variant in "$@"; do
  manifest=$(find app/build/intermediates/merged_manifest/"$variant" -name AndroidManifest.xml | head -n 1)
  mapping="app/build/outputs/mapping/$variant/mapping.txt"
  if [[ -z "$manifest" || ! -f "$mapping" ]]; then
    echo "::error::$variant: merged manifest or R8 mapping not found"
    status=1
    continue
  fi
  registrars=$(grep -o 'com\.google\.firebase\.components:[A-Za-z0-9_.$]*' "$manifest" | cut -d: -f2 | sort -u)
  if [[ -z "$registrars" ]]; then
    echo "::error::$variant: no component registrars found in $manifest"
    status=1
    continue
  fi
  for registrar in $registrars; do
    # The class block in mapping.txt starts with "<name> -> <obfuscated>:" and lists kept members.
    if awk -v cls="$registrar" '
        index($0, cls " -> ") == 1 { inside = 1; next }
        inside && /^[^ #]/ { exit }
        inside && /void <init>\(\)/ { found = 1; exit }
        END { exit !found }' "$mapping"; then
      echo "$variant: $registrar keeps its constructor"
    else
      echo "::error::$variant: R8 removed the no-argument constructor of $registrar (see app/proguard-rules.pro)"
      status=1
    fi
  done
done
exit "$status"
