# LlamaChat

A standalone Android chat app that runs **GGUF models locally with llama.cpp**. No network
permission is declared anywhere in the manifest: the model, the chat and the settings all
live on the phone.

Built as a self-contained module, following the `docs/android.md` path from the llama.cpp
repository (the "GUI binding" route) rather than the Termux or cross-compile-CLI routes.

## What it does

| Area | Behaviour |
|---|---|
| **Models** | Scans `Models`, `Download` and `Documents` (two levels deep) for `.gguf` files and loads them **in place**, so a 3 GB model is never duplicated. Also imports through the system file picker into app-private storage for users who do not want to grant all-files access. Shows size, path, and lets you delete imported files. |
| **Chat** | Streaming replies token by token, Stop button, new-chat button, copyable messages, and the model's own chat template applied per model. |
| **Settings** | Context size, CPU threads, max new tokens, temperature, top-K, top-P, repeat penalty, and a system prompt. Persisted across launches. |
| **Diagnostics** | The llama.cpp build string and any native-load failure are shown in Settings. |

## Architecture

```
app/src/main/java/com/saeed/llamachat/
  MainActivity.kt          single activity, three screens, no navigation dependency
  core/
    LlamaBridge.kt         external fun declarations + the streaming TokenSink interface
    ChatController.kt      owns the native session, one worker thread, all UI state
    ModelStore.kt          GGUF discovery, SAF import, deletion
    GenSettings.kt         settings model + SharedPreferences persistence
    ChatMessage.kt
  ui/                      ChatScreen, ModelsScreen, SettingsScreen, Theme

native/
  llama_core.h/.cpp        session lifecycle + generation loop  ← no JNI, no Android headers
  llama_jni.cpp            thin JNI adapter over llama_core
  linux_probe.cpp          host harness that runs llama_core against a real GGUF
  android-arm64-toolchain.cmake
  build-native.sh          builds app/src/main/jniLibs/arm64-v8a/libllama_jni.so
  verify-core-linux.sh     builds and runs the host probe
```

The native side is split in three on purpose. `llama_core.cpp` is the only file that
contains inference logic, and it compiles unchanged for both Android and the host. That is
what makes the behaviour verifiable without installing the APK — see *Verification* below.

### Why the `.so` is prebuilt instead of built by Gradle

The NDK on this machine ships **linux-x86_64 host binaries only**
(`ndk/26.1.10909125/toolchains/llvm/prebuilt/linux-x86_64`). This machine is aarch64, so
neither `externalNativeBuild` nor the NDK's own CMake toolchain can run here. The library is
therefore cross-compiled ahead of time by `native/build-native.sh` — which uses the host's
own clang (a cross-compiler by nature) pointed at the NDK's aarch64 sysroot — and shipped as
a prebuilt `jniLib`. `app/build.gradle.kts` fails the build with a clear message if the
`.so` is missing.

## Building

```bash
# 1. native library (needs a llama.cpp checkout; ~3 min)
LLAMA_SRC=/root/projects/llama.cpp bash native/build-native.sh

# 2. the APK (~2 min)
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-arm64 ./gradlew assembleDebug --no-daemon

# 3. hand-off
cp -f app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/llamachat-debug.apk
```

`local.properties` must contain `sdk.dir=/root/Android`. `gradle.properties` carries the
ARM64 `aapt2` override that AGP needs on this machine.

## Verification

`native/verify-core-linux.sh` links the **same** `llama_core.cpp` that goes into the Android
library into a host binary and runs it against a real GGUF. It checks that the model loads,
that the chat template renders *with* the assistant turn, that tokens stream through the
callback and stop at an end-of-generation token, and that repeated turns on one session keep
working.

```bash
bash native/verify-core-linux.sh /path/to/model.gguf --turns 12 --show-prompt
```

## Design notes worth knowing

- **The session is stateless between turns.** The KV cache is cleared and the whole
  conversation is re-rendered through the chat template on every message. That keeps editing
  the history or changing the system prompt from ever desynchronising the cache, and avoids
  the failure where a long-lived session stops answering after a handful of turns on a small
  context. The cost is re-prefilling the conversation each turn.
- **`add_ass = true` when applying the template.** Without the assistant turn the rendered
  prompt ends at the user's turn terminator, and a model then answers with that terminator
  itself — zero generated tokens.
- **`llama_sampler_sample()` already calls `llama_sampler_accept()`** in this llama.cpp
  revision; accepting the token again would corrupt the repetition-penalty history.
- **Tokens cross the JNI boundary as raw UTF-8 bytes**, never via `NewStringUTF`, because
  modified UTF-8 cannot represent 4-byte sequences and chat models emit emoji.
- **Context size and thread count are apply-on-load**; every other setting applies to the
  next message. The settings screen says so.

## Limits

- **CPU only.** The library is built with the CPU backend; `n_gpu_layers` is always 0.
  MediaTek/Adreno GPU offload is not wired up.
- **arm64-v8a only.** No `armeabi-v7a` or `x86_64` build.
- **No on-device prompt/KV cache persistence**, no grammar/JSON-mode, no vision models, and
  no LoRA adapters.
- **Gated models are not downloaded for you** — this app never uses the network. Copy the
  `.gguf` to the device yourself.
- A model that does not fit in RAM will be killed by the OS; there is no size guard beyond
  the context-size error message.
