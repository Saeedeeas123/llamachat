package com.saeed.llamachat.core

/**
 * Direct mapping of the native library's entry points (see native/llama_jni.cpp).
 *
 * Every function here blocks until it returns, so callers must run them off the main
 * thread. Loading a multi-gigabyte model and generating a reply both take seconds.
 */
object LlamaBridge {

    init {
        System.loadLibrary("llama_jni")
    }

    /**
     * Receives generated text as raw UTF-8 bytes.
     *
     * Bytes rather than String on purpose: a single token can end in the middle of a
     * multi-byte character, so the consumer has to decide how to decode the accumulated
     * stream. Returning true cancels generation after the current token.
     */
    interface TokenSink {
        fun onToken(utf8: ByteArray): Boolean
    }

    /** The llama.cpp build string the native library was compiled from. */
    external fun nativeVersion(): String

    /**
     * Loads a GGUF model and creates its context.
     * @return an opaque session handle, or throws RuntimeException with a readable message.
     */
    external fun nativeLoadModel(path: String, context: Int, threads: Int, gpuLayers: Int): Long

    /** Short human-readable description of the loaded model, or null for handle 0. */
    external fun nativeDescribe(handle: Long): String?

    /**
     * Renders the conversation through the model's chat template, prefills it and generates
     * up to [maxTokens] tokens.
     *
     * [roles] and [contents] are the full history, in order, and must be the same length.
     * The native side is stateless between calls: it clears its cache and re-renders the
     * whole conversation every time, so the returned reply is the complete assistant turn.
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
        sink: TokenSink?,
    ): String

    /** Releases the model, the context and all native memory behind [handle]. */
    external fun nativeFree(handle: Long)
}