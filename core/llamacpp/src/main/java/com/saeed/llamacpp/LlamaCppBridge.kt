package com.saeed.llamacpp

/**
 * The JNI surface of `libllama_jni.so` (implemented in `native/llama_jni.cpp`).
 *
 * LOW-LEVEL: prefer [LlamaCppEngine], which adds validation, typing and the main-thread
 * guard. This object is public only because the JNI symbol names must match these exact
 * class and method names — Kotlin would mangle `internal` declarations and break the lookup.
 */
object LlamaCppBridge {

    init {
        System.loadLibrary("llama_jni")
    }

    /** The llama.cpp build string this library was compiled from. */
    external fun nativeVersion(): String

    /**
     * Also writes llama.cpp's own logging to [path] (appended), in addition to logcat.
     * Pass null to stop. logcat rotates within minutes on a busy device, so a file is the
     * only reliable way to keep the engine's diagnostics for later.
     */
    external fun nativeSetLogFile(path: String?)

    /**
     * Loads a GGUF model and creates its context.
     * @return an opaque session handle; throws RuntimeException with a readable message.
     */
    external fun nativeLoadModel(path: String, context: Int, threads: Int, gpuLayers: Int): Long

    /** Human-readable description of the loaded model, or null for handle 0. */
    external fun nativeDescribe(handle: Long): String?

    /**
     * Renders the conversation through the model's chat template, prefills it, and generates
     * up to [maxTokens] tokens.
     *
     * [roles] and [contents] are the full history in order and must be the same length. The
     * native side is stateless between calls: it clears its cache and re-renders the whole
     * conversation every time, so the return value is a complete assistant turn.
     */
    external fun nativeChat(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        maxTokens: Int,
        temperature: Float,
        topK: Int,
        topP: Float,
        repeatPenalty: Float,
        repeatLastN: Int,
        seed: Long,
        sink: LlamaCppTokenSink?,
    ): String

    /** Releases the model, the context and all native memory behind [handle]. */
    external fun nativeFree(handle: Long)
}