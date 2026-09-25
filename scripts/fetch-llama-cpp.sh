#!/usr/bin/env bash
#
# Pins llama.cpp to the exact revision this project's JNI bridge was written
# against and checks it out under third_party/. Run once after cloning.

set -euo pipefail

LLAMA_REPO="${LLAMA_REPO:-https://github.com/ggml-org/llama.cpp.git}"
LLAMA_COMMIT="${LLAMA_COMMIT:-1ab7e5ad2d4e7295c94c3b966a3e0b70fa365865}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/llama.cpp"

if [[ ! -d "$DEST/.git" ]]; then
    echo "Cloning llama.cpp into $DEST"
    mkdir -p "$ROOT/third_party"
    git clone "$LLAMA_REPO" "$DEST"
fi

if git -C "$DEST" cat-file -e "$LLAMA_COMMIT^{commit}" 2>/dev/null; then
    echo "Revision $LLAMA_COMMIT is already available."
else
    echo "Fetching $LLAMA_COMMIT"
    git -C "$DEST" fetch origin "$LLAMA_COMMIT"
fi

echo "Checking out $LLAMA_COMMIT"
git -C "$DEST" checkout --detach "$LLAMA_COMMIT"

echo "llama.cpp ready at commit $LLAMA_COMMIT"
