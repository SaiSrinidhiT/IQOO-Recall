#!/usr/bin/env bash
# Pushes on-device models to the app's external files directory (brief §3.3) and writes
# manifest.json with each entry's size, so the app can tell a partial push from a good one.
#
# Expected local layout (default ./models, override with $1):
#   models/siglip2_vision.tflite            SigLIP2 image encoder (AI Hub TFLite export)
#   models/nomic_embed_text.tflite          Nomic Embed Text v1.5 (AI Hub TFLite export)
#   models/qwen3_4b_instruct_2507/          AI Hub Genie bundle for Snapdragon 8 Elite Gen 5 (metadata.json + *.bin)
#   models/qwen3_1_7b/                      optional fallback bundle (same format, or a GGUF folder)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
source "$here/env.sh"
src="${1:-$here/../models}"
dest="/sdcard/Android/data/$APP_ID/files/models"

adb get-state >/dev/null || { echo "No device: connect the phone with USB debugging on." >&2; exit 1; }
adb shell mkdir -p "$dest"

manifest="$(mktemp)"
echo '{"files":{' >"$manifest"
first=1
for name in siglip2_vision.tflite nomic_embed_text.tflite qwen3_4b_instruct_2507 qwen3_1_7b; do
  path="$src/$name"
  [[ -e "$path" ]] || { echo "skip $name (not in $src)"; continue; }
  if [[ -d "$path" ]]; then bytes=$(find "$path" -type f -exec stat -f%z {} + | awk '{s+=$1} END {print s}')
  else bytes=$(stat -f%z "$path"); fi
  echo "push $name ($bytes bytes)"
  adb push "$path" "$dest/" >/dev/null
  [[ $first -eq 1 ]] || echo ',' >>"$manifest"
  printf '"%s": %s' "$name" "$bytes" >>"$manifest"
  first=0
done
echo '}}' >>"$manifest"
adb push "$manifest" "$dest/manifest.json" >/dev/null
rm -f "$manifest"
adb shell ls -la "$dest"
