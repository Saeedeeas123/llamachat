#!/usr/bin/env bash
#
# Builds and runs the host (glibc) verification probe.
#
# WHY: the APK cannot be installed by the agent on this device, so "it compiled" is weak
# evidence. This links the very same native/llama_core.cpp that goes into the Android
# library into a plain Linux binary and runs it against a real GGUF, which exercises the
# chat-template rendering, tokenisation, sampling loop, streaming callback and
# end-of-generation handling for real.
#
# Usage:
#   native/verify-core-linux.sh [model.gguf] [extra probe args...]
# Defaults to a small model if one is present in ~/Models-style locations.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

LLAMA_SRC="${LLAMA_SRC:-/root/projects/llama.cpp}"
BUILD_DIR="$HERE/build-linux"
JOBS="${JOBS:-6}"

MODEL="${1:-}"
if [ -n "$MODEL" ]; then
    shift
else
    MODEL="$(find /storage/emulated/0/Models -maxdepth 2 -name '*.gguf' -size -300M 2>/dev/null | head -1 || true)"
fi
if [ -z "$MODEL" ] || [ ! -f "$MODEL" ]; then
    echo "ERROR: no GGUF model found; pass one explicitly" >&2
    exit 1
fi

echo "== configuring llama.cpp for the host =="
cmake -S "$LLAMA_SRC" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DLLAMA_BUILD_COMMON=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_SUBPROCESS=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DGGML_BLAS=OFF \
    -DGGML_RPC=OFF \
    -DGGML_BUILD_TESTS=OFF \
    -DGGML_BUILD_EXAMPLES=OFF

echo "== building libllama =="
cmake --build "$BUILD_DIR" --target llama -j "$JOBS"

echo "== linking the probe =="
STATIC_LIBS="$(find "$BUILD_DIR" -name 'libllama.a' -o -name 'libggml*.a' | sort | tr '\n' ' ')"
clang++ -std=c++17 -O2 \
    -I"$LLAMA_SRC/include" -I"$LLAMA_SRC/ggml/include" \
    "$HERE/llama_core.cpp" "$HERE/linux_probe.cpp" \
    -Wl,--start-group $STATIC_LIBS -Wl,--end-group \
    -lpthread -lm -ldl \
    -o "$HERE/llama_probe"

echo "== running the probe against: $MODEL =="
"$HERE/llama_probe" "$MODEL" "$@"