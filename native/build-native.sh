#!/usr/bin/env bash
#
# Builds app/src/main/jniLibs/arm64-v8a/libllama_jni.so from the upstream llama.cpp tree.
#
# WHY A SCRIPT INSTEAD OF GRADLE externalNativeBuild
# --------------------------------------------------
# The NDK on this machine ships linux-x86_64 host binaries only, so AGP's NDK integration
# cannot run here. The .so is therefore cross-compiled ahead of time and shipped as a
# prebuilt jniLib, which keeps the Gradle build fully offline and reproducible.
#
# Usage:
#   native/build-native.sh              # configure + build + link
#   JOBS=4 native/build-native.sh       # limit parallelism
#   LLAMA_SRC=/path/to/llama.cpp native/build-native.sh

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

LLAMA_SRC="${LLAMA_SRC:-/root/projects/llama.cpp}"
NDK="${LLAMA_ANDROID_NDK:-/root/Android/ndk/26.1.10909125}"
API="${LLAMA_ANDROID_API:-26}"
JOBS="${JOBS:-6}"

SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
TRIPLE="aarch64-linux-android${API}"
BUILD_DIR="$HERE/build-android"
OUT_DIR="$ROOT/app/src/main/jniLibs/arm64-v8a"
OUT_SO="$OUT_DIR/libllama_jni.so"

if [ ! -f "$LLAMA_SRC/include/llama.h" ]; then
    echo "ERROR: no llama.cpp source at $LLAMA_SRC" >&2
    exit 1
fi
if [ ! -d "$SYSROOT" ]; then
    echo "ERROR: no NDK sysroot at $SYSROOT" >&2
    exit 1
fi

echo "== configuring llama.cpp for $TRIPLE =="
cmake -S "$LLAMA_SRC" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$HERE/android-arm64-toolchain.cmake" \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_SHARED_LIBS=OFF \
    -DLLAMA_BUILD_COMMON=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_APP=OFF \
    -DLLAMA_BUILD_MTMD=OFF \
    -DLLAMA_BUILD_UI=OFF \
    -DLLAMA_OPENSSL=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_SUBPROCESS=OFF \
    -DLLAMA_LLGUIDANCE=OFF \
    -DGGML_NATIVE=OFF \
    -DGGML_OPENMP=OFF \
    -DGGML_LLAMAFILE=OFF \
    -DGGML_CPU_KLEIDIAI=OFF \
    -DGGML_BACKEND_DL=OFF \
    -DGGML_BLAS=OFF \
    -DGGML_RPC=OFF \
    -DGGML_BUILD_TESTS=OFF \
    -DGGML_BUILD_EXAMPLES=OFF

echo "== building libllama =="
cmake --build "$BUILD_DIR" --target llama -j "$JOBS"

echo "== linking libllama_jni.so =="
STATIC_LIBS="$(find "$BUILD_DIR" -name 'libllama.a' -o -name 'libggml*.a' | sort | tr '\n' ' ')"
echo "static libs: $STATIC_LIBS"

mkdir -p "$OUT_DIR"
# --start-group/--end-group removes any dependency on the order of the static archives.
clang++ \
    --target="$TRIPLE" \
    --sysroot="$SYSROOT" \
    -std=c++17 -O2 -fPIC -shared \
    -fvisibility=default \
    -static-libstdc++ \
    -I"$LLAMA_SRC/include" \
    -I"$LLAMA_SRC/ggml/include" \
    "$HERE/llama_core.cpp" \
    "$HERE/llama_jni.cpp" \
    -Wl,--start-group $STATIC_LIBS -Wl,--end-group \
    -llog -lm -ldl \
    -o "$OUT_SO"

echo "== stripping debug symbols =="
# The NDK's own strip tool is x86_64-only and cannot run here, so use the host's.
# --strip-unneeded keeps the dynamic symbol table, i.e. the JNI entry points survive.
/usr/bin/llvm-strip-18 --strip-unneeded "$OUT_SO"

echo "== result =="
ls -la "$OUT_SO"
echo "--- ELF header ---"
readelf -h "$OUT_SO" | grep -E 'Class|Machine|Type'
echo "--- dynamic dependencies (must be Android libs, never glibc) ---"
readelf -d "$OUT_SO" | grep -E 'NEEDED|SONAME'
echo "--- exported JNI entry points ---"
readelf --dyn-syms -W "$OUT_SO" | grep 'Java_com_saeed_llamachat' || true
