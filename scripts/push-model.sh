#!/usr/bin/env bash
#
# Copies a downloaded GGUF into the app's private storage on a connected device
# or emulator. Handy for testing; end users import the file through the app's
# own file picker instead.
#
# Requires a debuggable build, because it uses `run-as`.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PACKAGE="com.phi3chat"
MODEL="${1:-}"

if [[ -z "$MODEL" ]]; then
    MODEL="$(find "$ROOT/models" -name '*.gguf' -type f 2>/dev/null | head -n 1 || true)"
fi

if [[ -z "$MODEL" || ! -f "$MODEL" ]]; then
    echo "No GGUF file found. Run scripts/download-model.sh first, or pass a path." >&2
    exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
    echo "adb is not on PATH. Install Android platform-tools." >&2
    exit 1
fi

if [[ "$(adb devices | grep -c 'device$' || true)" -eq 0 ]]; then
    echo "No device or emulator connected." >&2
    exit 1
fi

BASENAME="$(basename "$MODEL")"
SIZE="$(du -h "$MODEL" | cut -f1)"
echo "Pushing $BASENAME ($SIZE) to $PACKAGE"

# Stage in /data/local/tmp (world-writable), then pipe through run-as to reach
# the app's private directory, which adb push cannot write to directly.
adb push "$MODEL" "/data/local/tmp/$BASENAME"
adb shell "run-as $PACKAGE mkdir -p files/models"
adb shell "cat /data/local/tmp/$BASENAME | run-as $PACKAGE sh -c 'cat > files/models/$BASENAME'"
adb shell "rm -f /data/local/tmp/$BASENAME"

echo "Done. The file now appears in Settings -> Model inside the app."
