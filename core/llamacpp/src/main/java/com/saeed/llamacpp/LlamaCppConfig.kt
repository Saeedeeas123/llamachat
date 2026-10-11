package com.saeed.llamacpp

/**
 * Context-creation settings.
 *
 * These are fixed for the lifetime of a loaded model — changing them means loading again,
 * because they size the KV cache and the compute buffers.
 */
data class LlamaCppConfig(
    /** Text context in tokens. Must be large enough to hold the whole conversation. */
    val contextSize: Int = 2048,
    /** Generation threads. Typically the number of big cores, not all cores. */
    val threads: Int = 4,
    /** Layers to offload to a GPU. Always 0 for this build: it is CPU-only. */
    val gpuLayers: Int = 0,
    /**
     * Refuse [LlamaCppEngine.load] and [LlamaCppEngine.generate] on the main thread.
     * Both block for seconds, so calling them there freezes the UI. Turn this off only in
     * tests, where the main thread is the only thread.
     */
    val guardMainThread: Boolean = true,
) {
    init {
        require(contextSize >= 64) { "contextSize must be at least 64, was $contextSize" }
        require(threads >= 1) { "threads must be at least 1, was $threads" }
        require(gpuLayers >= 0) { "gpuLayers cannot be negative, was $gpuLayers" }
    }
}

/**
 * Per-generation sampling settings. Read fresh on every turn, so a change applies to the
 * very next message without reloading the model.
 */
data class LlamaCppParams(
    val maxTokens: Int = 256,
    /** 0 makes the sampler greedy (always the most likely token). */
    val temperature: Float = 0.80f,
    /** 0 disables the top-K filter. */
    val topK: Int = 40,
    /** 1.0 disables the top-P filter. */
    val topP: Float = 0.95f,
    /** 1.0 disables the repetition penalty. */
    val repeatPenalty: Float = 1.10f,
    /** How many recent tokens the repetition penalty looks at; 0 disables it. */
    val repeatLastN: Int = 64,
    /** Negative means "pick a random seed for every turn". */
    val seed: Long = -1L,
) {
    init {
        require(maxTokens >= 1) { "maxTokens must be at least 1, was $maxTokens" }
        require(temperature >= 0f) { "temperature cannot be negative, was $temperature" }
        require(repeatPenalty > 0f) { "repeatPenalty must be greater than 0, was $repeatPenalty" }
    }
}