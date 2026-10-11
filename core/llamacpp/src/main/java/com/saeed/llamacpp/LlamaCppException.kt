package com.saeed.llamacpp

/**
 * Every failure this module can produce, with a message that says what to do about it.
 *
 * The native layer reports failures as plain strings; they are re-typed here so a caller can
 * branch on the cause instead of matching message text.
 */
class LlamaCppException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    enum class Kind {
        /** Nothing at the given path. */
        MODEL_MISSING,

        /** There is something there, but it cannot be read. */
        MODEL_UNREADABLE,

        /** The file exists but is not a GGUF file (wrong magic bytes). */
        MODEL_NOT_GGUF,

        /** No model is loaded, so there is nothing to generate with. */
        NOT_LOADED,

        /** llama.cpp refused to load the file. */
        LOAD_FAILED,

        /** The conversation does not fit in the configured context. */
        CONTEXT_TOO_SMALL,

        /** llama.cpp failed while decoding. */
        GENERATION_FAILED,

        /** Called from the main thread while the guard was on. */
        ON_MAIN_THREAD,

        /** libllama_jni.so could not be loaded (wrong ABI, or missing from the APK). */
        NATIVE_UNAVAILABLE,
    }
}