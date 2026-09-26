#!/usr/bin/env bash
# Pushes the synthetic seed documents (tools/make_seed_docs.py) into the phone's gallery and asks
# MediaStore to scan them. The seed set is fake data only; never push real personal documents.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/env.sh"
src="$here/../tools/out/seed"
dest="/sdcard/Pictures/RecallSeed"

[[ -d "$src" ]] || { echo "Run: .venv/bin/python tools/make_seed_docs.py" >&2; exit 1; }
adb get-state >/dev/null || { echo "No device connected." >&2; exit 1; }
adb shell mkdir -p "$dest"
for f in "$src"/*.jpg "$src"/*.png; do
  [[ -e "$f" ]] || continue
  adb push "$f" "$dest/" >/dev/null
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$dest/$(basename "$f")" >/dev/null 2>&1 || true
  echo "pushed $(basename "$f")"
done
# Files written through /sdcard are normally indexed on close; this nudges a rescan where supported.
adb shell content call --uri content://media --method scan_volume --arg external_primary >/dev/null 2>&1 || true
