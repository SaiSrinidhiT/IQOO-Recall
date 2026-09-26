#!/usr/bin/env bash
# Downloads the Tesseract language data shipped in the APK (assets/tessdata) and verifies each file
# against its git blob hash on GitHub. Telugu uses tessdata_best: Tesseract is the only Telugu OCR in
# the app, so accuracy matters. English and Hindi use tessdata_fast: ML Kit already covers those scripts.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dest="$here/../app/src/main/assets/tessdata"
mkdir -p "$dest"

fetch() { # repo lang
  local repo="$1" lang="$2" out="$dest/$2.traineddata"
  local meta; meta="$(curl -fsS "https://api.github.com/repos/tesseract-ocr/$repo/contents/$lang.traineddata")"
  local sha; sha="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["sha"])' <<<"$meta")"
  curl -fsSL -o "$out" "https://raw.githubusercontent.com/tesseract-ocr/$repo/main/$lang.traineddata"
  # git blob hash = sha1("blob <size>\0" + content)
  local got; got="$(python3 - "$out" <<'EOF'
import hashlib, sys
data = open(sys.argv[1], "rb").read()
print(hashlib.sha1(b"blob %d\0" % len(data) + data).hexdigest())
EOF
)"
  if [[ "$got" != "$sha" ]]; then echo "hash mismatch for $lang ($repo): $got != $sha" >&2; exit 1; fi
  echo "OK $lang.traineddata ($repo, $(wc -c <"$out" | tr -d ' ') bytes)"
}

fetch tessdata_best tel
fetch tessdata_fast eng
fetch tessdata_fast hin
