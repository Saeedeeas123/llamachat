package com.saeed.llamachat.core

import android.content.Context
import com.saeed.llamacpp.LlamaCppLoadMode

/**
 * Generation settings.
 *
 * [contextSize], [threads] and [loadMode] are context-creation parameters: changing them only
 * takes effect the next time a model is loaded. Everything else is read fresh on every turn.
 */
data class GenSettings(
    val contextSize: Int = 2048,
    val threads: Int = 4,
    /**
     * RAM by default. AUTO memory-maps, and memory-mapped weights on FUSE-mounted shared
     * storage can be hundreds of times slower — see [LlamaCppLoadMode].
     */
    val loadMode: LlamaCppLoadMode = LlamaCppLoadMode.RAM,
    val maxTokens: Int = 256,
    val temperature: Float = 0.80f,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val repeatPenalty: Float = 1.10f,
    val repeatLastN: Int = 64,
    /** -1 means "pick a random seed for every turn". */
    val seed: Long = -1L,
    val systemPrompt: String = "You are a helpful assistant running entirely offline on this phone. Keep answers short.",
) {
    companion object {
        val DEFAULTS = GenSettings()
    }
}

/** Persists [GenSettings] in SharedPreferences. */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("llamachat_settings", Context.MODE_PRIVATE)

    fun load(): GenSettings {
        val d = GenSettings.DEFAULTS
        return GenSettings(
            contextSize = prefs.getInt("contextSize", d.contextSize),
            threads = prefs.getInt("threads", d.threads),
            loadMode = runCatching {
                LlamaCppLoadMode.valueOf(prefs.getString("loadMode", d.loadMode.name)!!)
            }.getOrDefault(d.loadMode),
            maxTokens = prefs.getInt("maxTokens", d.maxTokens),
            temperature = prefs.getFloat("temperature", d.temperature),
            topK = prefs.getInt("topK", d.topK),
            topP = prefs.getFloat("topP", d.topP),
            repeatPenalty = prefs.getFloat("repeatPenalty", d.repeatPenalty),
            repeatLastN = prefs.getInt("repeatLastN", d.repeatLastN),
            seed = prefs.getLong("seed", d.seed),
            systemPrompt = prefs.getString("systemPrompt", d.systemPrompt) ?: d.systemPrompt,
        )
    }

    fun save(settings: GenSettings) {
        prefs.edit()
            .putInt("contextSize", settings.contextSize)
            .putInt("threads", settings.threads)
            .putString("loadMode", settings.loadMode.name)
            .putInt("maxTokens", settings.maxTokens)
            .putFloat("temperature", settings.temperature)
            .putInt("topK", settings.topK)
            .putFloat("topP", settings.topP)
            .putFloat("repeatPenalty", settings.repeatPenalty)
            .putInt("repeatLastN", settings.repeatLastN)
            .putLong("seed", settings.seed)
            .putString("systemPrompt", settings.systemPrompt)
            .apply()
    }
}