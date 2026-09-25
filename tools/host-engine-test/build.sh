#!/usr/bin/env bash
#
# Compiles and runs the engine self-test on the host (x86-64 Linux) against the
# real llama.cpp built from third_party/. This exercises the exact
# llama_engine.cpp the Android app ships, so a failure here is a real defect.
#
# Usage:
#   tools/host-engine-test/build.sh [path/to/model.gguf]

set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

PROJECT_ROOT="$(cd ../.. && pwd)"
LLAMA_DIR="$PROJECT_ROOT/third_party/llama.cpp"
CPP_DIR="$PROJECT_ROOT/app/src/main/cpp"
MODEL="${1:-$PROJECT_ROOT/models/Phi-3-mini-4k-instruct-q4.gguf}"
BUILD="$PWD/build"
mkdir -p "$BUILD"

if [[ ! -f "$MODEL" ]]; then
    echo "Model not found: $MODEL" >&2
    echo "Run scripts/download-model.sh first, or pass a path as the first argument." >&2
    exit 1
fi

if [[ ! -f "$LLAMA_DIR/CMakeLists.txt" ]]; then
    echo "llama.cpp sources not found at $LLAMA_DIR" >&2
    echo "Run scripts/fetch-llama-cpp.sh first." >&2
    exit 1
fi

# --- 1. Build llama.cpp for the host ---------------------------------------
if [[ ! -f "$BUILD/llama/src/libllama.a" ]]; then
    echo "Configuring llama.cpp for host build..."
    cmake -S "$LLAMA_DIR" -B "$BUILD/llama" \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF \
        -DLLAMA_BUILD_TESTS=OFF \
        -DLLAMA_BUILD_SERVER=OFF \
        -DLLAMA_CURL=OFF \
        -DGGML_NATIVE=ON \
        > "$BUILD/llama-configure.log" 2>&1

    echo "Building llama.cpp (this takes a few minutes)..."
    cmake --build "$BUILD/llama" --target llama ggml ggml-base ggml-cpu \
        -j "$(nproc)" > "$BUILD/llama-build.log" 2>&1
fi

# --- 2. Compile the engine + test with the android/log.h shim --------------
# ggml-cpu is built with OpenMP on the host, so -fopenmp is required at link.
echo "Compiling engine_test..."
c++ -std=c++17 -O2 -g \
    -I"$PWD/shim" \
    -I"$CPP_DIR" \
    -I"$LLAMA_DIR/include" \
    -I"$LLAMA_DIR/ggml/include" \
    -o "$BUILD/engine_test" \
    engine_test.cpp "$CPP_DIR/llama_engine.cpp" \
    -Wl,--whole-archive \
    -L"$BUILD/llama/src" -lllama \
    -L"$BUILD/llama/ggml/src" -lggml -lggml-cpu \
    -L"$BUILD/llama/ggml/src" -lggml-base \
    -Wl,--no-whole-archive \
    -pthread -fopenmp -ldl -lm

# --- 3. Run ----------------------------------------------------------------
echo
echo "=== running engine_test against $(basename "$MODEL") ==="
echo
exec "$BUILD/engine_test" "$MODEL"
