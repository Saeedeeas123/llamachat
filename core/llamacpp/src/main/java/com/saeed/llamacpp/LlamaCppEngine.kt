package com.saeed.llamacpp

import android.os.Looper

/**
 * A single GGUF model plus its context, running entirely on-device.
 *
 * One engine owns one loaded model. Every call blocks until it returns, so callers must use a
 * background thread — [LlamaCppConfig.guardMainThread] enforces that by default rather than
 * letting an app freeze its UI.
 *
 * Generation is serialised (`@Synchronized`), which mirrors the native side: a llama.cpp
 * context is not safe to decode from two threads at once.
 */
class LlamaCppEngine : AutoCloseable {

    /** Opaque native session handle; 0 means nothing is loaded. */
    private var handle: Long = 0L

    private var guardMainThread: Boolean = true

    fun isLoaded(): Boolean = handle != 0L

    /** Description of the loaded model, or null when nothing is loaded. */
    fun description(): String? {
        val current = handle
        if (current == 0L) return null
        return runCatching { LlamaCppBridge.nativeDescribe(current) }.getOrNull()
    }

    /**
     * Loads a model, replacing whatever was loaded before.
     *
     * Blocking: seconds for a small model, much longer for a multi-gigabyte one. Call it from
     * a background thread.
     */
    @Synchronized
    @Throws(LlamaCppException::class)
    fun load(modelPath: String, config: LlamaCppConfig = LlamaCppConfig()) {
        guardMainThread = config.guardMainThread
        requireBackgroundThread("load()")

        LlamaCppModelValidator.validate(modelPath)

        // Release the previous model first so two models are never resident at once.
        unload()

        val created = try {
            LlamaCppBridge.nativeLoadModel(
                modelPath,
                config.contextSize,
                config.threads,
                config.gpuLayers,
                config.loadMode.nativeValue,
            )
        } catch (error: UnsatisfiedLinkError) {
            throw nativeUnavailable(error)
        } catch (error: RuntimeException) {
            throw LlamaCppException(
                LlamaCppException.Kind.LOAD_FAILED,
                error.message ?: "the model could not be loaded",
                error,
            )
        }

        if (created == 0L) {
            throw LlamaCppException(
                LlamaCppException.Kind.LOAD_FAILED,
                "llama.cpp returned no session for $modelPath",
            )
        }
        handle = created
    }

    /** Releases the model and its context. Safe to call when nothing is loaded. */
    @Synchronized
    fun unload() {
        val current = handle
        handle = 0L
        if (current != 0L) {
            runCatching { LlamaCppBridge.nativeFree(current) }
        }
    }

    /**
     * Renders [messages] through the model's chat template, prefills them, and generates a
     * reply.
     *
     * @param sink optional; receives each token's raw UTF-8 bytes and may return true to stop.
     * @return the complete assistant turn.
     */
    @Synchronized
    @Throws(LlamaCppException::class)
    fun generate(
        messages: List<LlamaCppMessage>,
        params: LlamaCppParams = LlamaCppParams(),
        sink: LlamaCppTokenSink? = null,
    ): String {
        requireBackgroundThread("generate()")

        if (handle == 0L) {
            throw LlamaCppException(
                LlamaCppException.Kind.NOT_LOADED,
                "no model is loaded; call load() first",
            )
        }
        if (messages.isEmpty()) {
            throw LlamaCppException(
                LlamaCppException.Kind.GENERATION_FAILED,
                "the conversation is empty, so there is nothing to answer",
            )
        }

        val roles = Array(messages.size) { messages[it].role }
        val contents = Array(messages.size) { messages[it].content }

        return try {
            LlamaCppBridge.nativeChat(
                handle,
                roles,
                contents,
                params.maxTokens,
                params.temperature,
                params.topK,
                params.topP,
                params.repeatPenalty,
                params.repeatLastN,
                params.seed,
                sink,
            )
        } catch (error: UnsatisfiedLinkError) {
            throw nativeUnavailable(error)
        } catch (error: RuntimeException) {
            val message = error.message ?: "generation failed"
            // The native side says "the conversation needs N tokens but the context holds M"
            // for this one case; everything else is a generic decode failure.
            val kind = if (message.contains("context holds")) {
                LlamaCppException.Kind.CONTEXT_TOO_SMALL
            } else {
                LlamaCppException.Kind.GENERATION_FAILED
            }
            throw LlamaCppException(kind, message, error)
        }
    }

    override fun close() = unload()

    private fun requireBackgroundThread(operation: String) {
        if (!guardMainThread) return
        val looper = Looper.myLooper() ?: return
        if (looper == Looper.getMainLooper()) {
            throw LlamaCppException(
                LlamaCppException.Kind.ON_MAIN_THREAD,
                "$operation blocks for seconds and must not run on the main thread",
            )
        }
    }

    private fun nativeUnavailable(error: Throwable) = LlamaCppException(
        LlamaCppException.Kind.NATIVE_UNAVAILABLE,
        "libllama_jni.so could not be loaded — the APK is probably missing its arm64-v8a " +
            "native library, or is running on an unsupported ABI",
        error,
    )

    companion object {
        /**
         * Also writes llama.cpp's own logging to [path] (appended), in addition to logcat.
         * Pass null to stop. Useful when a failure has to be diagnosed after the fact —
         * logcat rotates within minutes on a busy device.
         */
        fun setLogFile(path: String?) {
            runCatching { LlamaCppBridge.nativeSetLogFile(path) }
        }

        /** llama.cpp build string, or null when the native library cannot be loaded. */
        fun nativeVersionOrNull(): String? = try {
            LlamaCppBridge.nativeVersion()
        } catch (error: Throwable) {
            null
        }

        /** llama.cpp build string, or a typed [LlamaCppException] when unavailable. */
        @Throws(LlamaCppException::class)
        fun nativeVersion(): String = try {
            LlamaCppBridge.nativeVersion()
        } catch (error: Throwable) {
            throw LlamaCppException(
                LlamaCppException.Kind.NATIVE_UNAVAILABLE,
                "libllama_jni.so could not be loaded",
                error,
            )
        }
    }
}