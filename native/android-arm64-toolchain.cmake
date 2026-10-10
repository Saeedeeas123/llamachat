# CMake toolchain file: cross-compile for Android arm64-v8a.
#
# WHY THIS FILE EXISTS
# --------------------
# The Android NDK installed on this machine (26.1.10909125) ships host binaries for
# linux-x86_64 ONLY (toolchains/llvm/prebuilt/linux-x86_64). This machine is aarch64, so
# the NDK's own toolchain file (build/cmake/android.toolchain.cmake) cannot be used and
# Gradle's externalNativeBuild cannot run either.
#
# The host's own clang is a cross-compiler by nature, so we point it at the NDK's aarch64
# sysroot and target triple directly. Everything else (headers, crt objects, libc++,
# stub system libraries) is taken from the NDK sysroot, which keeps the output a genuine
# Android/bionic shared object rather than a glibc one.

set(CMAKE_SYSTEM_NAME Linux)
set(CMAKE_SYSTEM_PROCESSOR aarch64)

set(LLAMA_ANDROID_NDK "/root/Android/ndk/26.1.10909125" CACHE PATH "Android NDK root")
set(LLAMA_ANDROID_API "26" CACHE STRING "Android API level to target")

set(_ndk_sysroot "${LLAMA_ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot")
set(_ndk_triple  "aarch64-linux-android${LLAMA_ANDROID_API}")

set(CMAKE_SYSROOT "${_ndk_sysroot}")

set(CMAKE_C_COMPILER   "/usr/bin/clang")
set(CMAKE_CXX_COMPILER "/usr/bin/clang++")
set(CMAKE_C_COMPILER_TARGET   "${_ndk_triple}")
set(CMAKE_CXX_COMPILER_TARGET "${_ndk_triple}")
set(CMAKE_AR      "/usr/bin/llvm-ar-18")
set(CMAKE_RANLIB  "/usr/bin/llvm-ranlib-18")
set(CMAKE_STRIP   "/usr/bin/llvm-strip-18")

set(CMAKE_FIND_ROOT_PATH "${_ndk_sysroot}")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)

# The host's clang does not know the NDK's libc++ header layout on its own, so add it.
# Without this, <string>/<vector> resolve to the host's libstdc++ headers, which is wrong
# for a bionic target.
set(CMAKE_CXX_FLAGS_INIT "-fPIC -isystem ${_ndk_sysroot}/usr/include/c++/v1")
set(CMAKE_C_FLAGS_INIT   "-fPIC")
