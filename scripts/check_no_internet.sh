#!/usr/bin/env bash
# Fails if an APK requests android.permission.INTERNET (including permissions merged in by libraries).
# Usage: scripts/check_no_internet.sh [path/to.apk]   (default: the release APK)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/env.sh"

apk="${1:-$here/../app/build/outputs/apk/release/app-release.apk}"
[[ -f "$apk" ]] || { echo "APK not found: $apk" >&2; exit 2; }

bt="$(ls "$ANDROID_HOME/build-tools" | sort -t. -k1,1n -k2,2n -k3,3n | tail -1)"
perms="$("$ANDROID_HOME/build-tools/$bt/aapt2" dump permissions "$apk")"
echo "$perms"

if grep -q "android.permission.INTERNET" <<<"$perms"; then
  echo "FAIL: $(basename "$apk") requests android.permission.INTERNET" >&2
  exit 1
fi
echo "OK: $(basename "$apk") does not request android.permission.INTERNET"
