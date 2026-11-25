#!/usr/bin/env bash
# Fails if R8 removed something ML Kit only reaches by name, which breaks it in release builds only:
#   - the no-argument constructor of a Firebase component registrar. ML Kit creates its registrars
#     (text recognition's, translation's, language identification's...: those listed in the merged
#     manifest) by class name with that constructor; without it text recognition and translating
#     fail at runtime.
#   - a Java method that ML Kit's native code calls by name (jni_members below). Without it, a
#     translation that fails, or any language detection, crashes the app.
# app/proguard-standard.pro keeps the constructors; ML Kit's own rule keeps the members its native
# code calls, which it annotates @UsedByNative. This check makes sure they still do.
#
# Usage: check-mlkit-registrars.sh <variant>...   e.g. standardRelease
set -euo pipefail

# "<class> <member as R8's mapping.txt writes it>". The class must keep its name too, unless R8
# removed it altogether (nothing in the build uses it, so no native code calls it either).
jni_members=(
  'com.google.mlkit.nl.translate.internal.TranslateJni java.lang.Exception newLoadingException(int)'
  'com.google.mlkit.nl.translate.internal.TranslateJni java.lang.Exception newTranslateException(int)'
  'com.google.mlkit.nl.languageid.IdentifiedLanguage void <init>(java.lang.String,float)'
)

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
      echo "::error::$variant: R8 removed the no-argument constructor of $registrar (see app/proguard-standard.pro)"
      status=1
    fi
  done
  for entry in "${jni_members[@]}"; do
    cls="${entry%% *}" member="${entry#* }"
    name="${member#* }"
    name="${name%%(*}"
    if ! awk -v cls="$cls" 'index($0, cls " -> ") == 1 { found = 1; exit } END { exit !found }' "$mapping"; then
      echo "$variant: $cls isn't in the build"
      continue
    fi
    # Kept under its own name: "<class> -> <class>:", then a member line (after any "1:8:" line
    # numbers) that starts with the member and ends with "-> <its name>".
    if awk -v cls="$cls" -v member="$member" -v name="$name" '
        index($0, cls " -> " cls ":") == 1 { inside = 1; next }
        inside && /^[^ #]/ { exit }
        inside {
          line = $0
          sub(/^ +/, "", line)
          sub(/^[0-9]+:[0-9]+:/, "", line)
          tail = " -> " name
          if (index(line, member) == 1 && substr(line, length(line) - length(tail) + 1) == tail) { found = 1; exit }
        }
        END { exit !found }' "$mapping"; then
      echo "$variant: $cls keeps $member for ML Kit's native code"
    else
      echo "::error::$variant: R8 removed or renamed $cls's $member, which ML Kit's native code calls by name (keep it in app/proguard-standard.pro)"
      status=1
    fi
  done
done
exit "$status"
