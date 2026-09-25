#!/usr/bin/env bash
#
# Downloads a Phi-3 GGUF file into models/.
#
# The default is the 4-bit quantisation of Phi-3-mini-4k-instruct: about 2.4 GB,
# and the smallest variant that still produces usable answers. Push it to a
# connected device with scripts/push-model.sh.
#
# Usage:
#   scripts/download-model.sh              # q4 (default, ~2.4 GB)
#   scripts/download-model.sh fp16         # fp16 (~7.6 GB, desktop only)

set -euo pipefail

VARIANT="${1:-q4}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/models"
REPO="microsoft/Phi-3-mini-4k-instruct-gguf"

case "$VARIANT" in
    q4)   FILE="Phi-3-mini-4k-instruct-q4.gguf" ;;
    fp16) FILE="Phi-3-mini-4k-instruct-fp16.gguf" ;;
    *)
        echo "Unknown variant '$VARIANT'. Use 'q4' or 'fp16'." >&2
        exit 1
        ;;
esac

mkdir -p "$DEST"

if [[ -f "$DEST/$FILE" ]]; then
    echo "$DEST/$FILE already exists, skipping download."
    exit 0
fi

URL="https://huggingface.co/$REPO/resolve/main/$FILE"
echo "Downloading $FILE from $REPO"
echo "(set HF_TOKEN if the download is rate limited)"
echo

# -C - resumes a previously interrupted download instead of restarting it.
CURL_ARGS=(-L --fail --retry 3 --retry-delay 2 -C - -o "$DEST/$FILE.part")
if [[ -n "${HF_TOKEN:-}" ]]; then
    CURL_ARGS+=(-H "Authorization: Bearer $HF_TOKEN")
fi

curl "${CURL_ARGS[@]}" "$URL"
mv "$DEST/$FILE.part" "$DEST/$FILE"

echo
echo "Saved to $DEST/$FILE"
echo "Next: scripts/push-model.sh"
