#!/usr/bin/env bash
#
# Runs the real JNI layer under a plain JVM.
#
# WHY: native/linux_probe.cpp verifies llama_core, but it cannot reach the JNI marshalling or
# the per-token Java callback — the exact code an app crash would most likely live in. This
# script compiles the SAME native/llama_jni.cpp into a Linux shared object (with a stubbed
# <android/log.h>) and drives it from Java classes that carry the package, class and method
# names the JNI symbols expect.
#
# Needs a PIC build of llama.cpp:  native/build-linux-pic  (see the cmake line below).
#
# Usage:  native/jvm-harness.sh [model.gguf] [turns] [maxTokens]

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

LLAMA_SRC="${LLAMA_SRC:-/root/projects/llama.cpp}"
PIC_BUILD="$HERE/build-linux-pic"
JDK="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
OUT="$HERE/jvm-harness/out"

MODEL="${1:-/storage/emulated/0/Models/gemma-3-270m-it-qat-Q4_0.gguf}"
TURNS="${2:-4}"
MAXTOK="${3:-96}"

if [ ! -d "$PIC_BUILD" ]; then
    echo "== configuring a PIC build of llama.cpp (needed to link a shared object) =="
    cmake -S "$LLAMA_SRC" -B "$PIC_BUILD" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release \
        -DBUILD_SHARED_LIBS=OFF \
        -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
        -DLLAMA_BUILD_COMMON=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_TOOLS=OFF \
        -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_APP=OFF \
        -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF -DLLAMA_SUBPROCESS=OFF \
        -DGGML_OPENMP=OFF -DGGML_LLAMAFILE=OFF -DGGML_BACKEND_DL=OFF \
        -DGGML_BLAS=OFF -DGGML_RPC=OFF -DGGML_BUILD_TESTS=OFF -DGGML_BUILD_EXAMPLES=OFF
    cmake --build "$PIC_BUILD" --target llama -j "${JOBS:-6}"
fi

echo "== linking the JNI shared object for the host =="
mkdir -p "$OUT"
STATIC_LIBS="$(find "$PIC_BUILD" -name 'libllama.a' -o -name 'libggml*.a' | sort | tr '\n' ' ')"
clang++ -std=c++17 -O2 -fPIC -shared \
    -I"$HERE/hoststub" \
    -I"$JDK/include" -I"$JDK/include/linux" \
    -I"$LLAMA_SRC/include" -I"$LLAMA_SRC/ggml/include" \
    "$HERE/llama_core.cpp" "$HERE/llama_jni.cpp" \
    -Wl,--start-group $STATIC_LIBS -Wl,--end-group \
    -lpthread -lm -ldl \
    -o "$OUT/libllama_jni.so"

echo "== compiling the Java harness =="
"$JDK/bin/javac" -d "$OUT" $(find "$HERE/jvm-harness/src" -name '*.java')

echo "== running the harness against: $MODEL =="
"$JDK/bin/java" -Djava.library.path="$OUT" -cp "$OUT" com.saeed.llamacpp.HarnessMain "$MODEL" "$TURNS" "$MAXTOK"