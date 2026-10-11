package com.saeed.llamachat.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.saeed.llamacpp.LlamaCppConfig
import com.saeed.llamacpp.LlamaCppEngine
import com.saeed.llamacpp.LlamaCppException
import com.saeed.llamacpp.LlamaCppMessage
import com.saeed.llamacpp.LlamaCppModelStore
import com.saeed.llamacpp.LlamaCppParams
import com.saeed.llamacpp.LlamaCppTokenSink
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** What the engine is currently doing, as far as the UI is concerned. */
sealed interface EngineState {
    object NoModel : EngineState
    data class Loading(val name: String) : EngineState
    data class Ready(val description: String) : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * The app's state holder: it owns a [LlamaCppEngine] from `:core:llamacpp` and every piece of
 * observable UI state, and it is the only place that decides what runs off the main thread.
 *
 * A process-wide object rather than a ViewModel: there is exactly one model session per
 * process and it must survive recomposition. All native work runs on a single background
 * thread, so a generation can never overlap a model load.
 *
 * The activity declares `configChanges` for rotation, so state is not lost on rotate. The
 * model is released deterministically in [shutdown], which the activity calls when it really
 * is finishing.
 */
object ChatController {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "llama-worker").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    /** The loaded model. `engine` below is the UI-facing state, not the engine itself. */
    private val llm = LlamaCppEngine()

    private var initialized = false
    private var modelStore: LlamaCppModelStore? = null
    private var settingsStore: SettingsStore? = null

    /** Kept only so [RunLog] can read the device's memory state. */
    private var appContext: Context? = null

    /** Set from the UI thread, read by the token sink on the worker thread. */
    @Volatile
    private var cancelRequested = false

    // ---- observable state -------------------------------------------------------------

    var messages by mutableStateOf<List<ChatMessage>>(emptyList())
        private set
    var streamingText by mutableStateOf("")
        private set
    var generating by mutableStateOf(false)
        private set
    var engine by mutableStateOf<EngineState>(EngineState.NoModel)
        private set
    var settings by mutableStateOf(GenSettings.DEFAULTS)
        private set
    var loadedModelName by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)
        private set

    var models by mutableStateOf<List<LlamaCppModelStore.ModelFile>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var importing by mutableStateOf(false)
        private set
    var importProgress by mutableStateOf(0L)
        private set

    /** llama.cpp build string, or null when the native library could not be loaded. */
    var nativeVersion by mutableStateOf<String?>(null)
        private set
    var nativeLoadError by mutableStateOf<String?>(null)
        private set

    // ---- lifecycle --------------------------------------------------------------------

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val app = context.applicationContext
        appContext = app
        modelStore = LlamaCppModelStore(app)
        settingsStore = SettingsStore(app)
        settings = settingsStore?.load() ?: GenSettings.DEFAULTS

        // Start the durable log first: everything after this point leaves a record.
        RunLog.init(app)

        // Touching the bridge loads the shared library. A failure here (wrong ABI, missing
        // .so) must surface as a readable message rather than an app crash.
        nativeVersion = LlamaCppEngine.nativeVersionOrNull()
        if (nativeVersion == null) {
            nativeLoadError = "libllama_jni.so could not be loaded on this device"
            RunLog.log("FATAL: $nativeLoadError")
        }
    }

    fun shutdown() {
        llm.unload()
        engine = EngineState.NoModel
        loadedModelName = null
    }

    fun dismissNotice() {
        notice = null
    }

    // ---- settings ---------------------------------------------------------------------

    fun updateSettings(updated: GenSettings) {
        settings = updated
        settingsStore?.save(updated)
    }

    fun resetSettings() = updateSettings(GenSettings.DEFAULTS)

    // ---- models -----------------------------------------------------------------------

    fun hasAllFilesAccess(): Boolean = modelStore?.hasAllFilesAccess() ?: false

    fun allFilesAccessIntent() = modelStore?.allFilesAccessIntent()

    fun rescanModels() {
        val store = modelStore ?: return
        scanning = true
        worker.execute {
            val found = runCatching { store.scan() }.getOrDefault(emptyList())
            main.post {
                models = found
                scanning = false
            }
        }
    }

    fun importModel(uri: Uri) {
        val store = modelStore ?: return
        if (importing) return
        importing = true
        importProgress = 0L
        worker.execute {
            try {
                val file = store.importFromUri(uri) { written ->
                    main.post { importProgress = written }
                }
                main.post {
                    importing = false
                    notice = "Imported ${file.name}"
                }
            } catch (error: Throwable) {
                main.post {
                    importing = false
                    notice = error.message ?: "the file could not be imported"
                }
            }
            rescanModels()
        }
    }

    fun deleteModel(model: LlamaCppModelStore.ModelFile) {
        val store = modelStore ?: return
        worker.execute {
            val deleted = runCatching { store.delete(model) }.getOrDefault(false)
            main.post {
                notice = if (deleted) "Deleted ${model.name}" else "Could not delete ${model.name}"
            }
            rescanModels()
        }
    }

    // ---- engine -----------------------------------------------------------------------

    fun loadModel(path: String, displayName: String) {
        if (nativeLoadError != null) {
            notice = "The native library is not available: $nativeLoadError"
            return
        }
        // Context-creation parameters must not change mid-load, so capture them now.
        val config = LlamaCppConfig(
            contextSize = settings.contextSize,
            threads = settings.threads,
            gpuLayers = 0,
        )

        engine = EngineState.Loading(displayName)
        worker.execute {
            RunLog.log("load: $displayName (ctx=${config.contextSize}, threads=${config.threads})")
            appContext?.let { RunLog.memory(it, "before load") }
            try {
                llm.load(path, config)
                val description = llm.description() ?: displayName
                RunLog.log("load ok: $description")
                appContext?.let { RunLog.memory(it, "after load") }
                main.post {
                    loadedModelName = displayName
                    engine = EngineState.Ready(description)
                }
            } catch (error: LlamaCppException) {
                RunLog.log("load FAILED: ${error.kind} - ${error.message}")
                main.post {
                    loadedModelName = null
                    engine = EngineState.Failed(error.message ?: "the model could not be loaded")
                }
            }
        }
    }

    fun unloadModel() {
        engine = EngineState.NoModel
        loadedModelName = null
        worker.execute { llm.unload() }
    }

    fun newChat() {
        if (generating) return
        messages = emptyList()
        streamingText = ""
    }

    fun stopGenerating() {
        cancelRequested = true
    }

    // ---- generation -------------------------------------------------------------------

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || generating) return
        if (!llm.isLoaded()) {
            notice = "Load a model first."
            return
        }

        cancelRequested = false

        val history = messages + ChatMessage(Role.USER, prompt)
        val assistantIndex = history.size
        messages = history + ChatMessage(Role.ASSISTANT, "")
        streamingText = ""
        generating = true

        // What the model sees: the system prompt, then the whole conversation. Turns that
        // failed are left out — an error string is not something the model said.
        val conversation = ArrayList<LlamaCppMessage>(history.size + 1)
        val systemPrompt = settings.systemPrompt
        if (systemPrompt.isNotBlank()) {
            conversation.add(LlamaCppMessage.system(systemPrompt))
        }
        for (message in history) {
            if (message.role == Role.ASSISTANT && message.isError) continue
            conversation.add(LlamaCppMessage(message.role.wire, message.text))
        }

        val s = settings
        val params = LlamaCppParams(
            maxTokens = s.maxTokens,
            temperature = s.temperature,
            topK = s.topK,
            topP = s.topP,
            repeatPenalty = s.repeatPenalty,
            repeatLastN = s.repeatLastN,
            seed = s.seed,
        )

        worker.execute {
            RunLog.log(
                "generate: ${conversation.size} messages, maxTokens=${params.maxTokens}, " +
                    "temp=${params.temperature}, topK=${params.topK}, topP=${params.topP}, " +
                    "repeatPenalty=${params.repeatPenalty}",
            )
            appContext?.let { RunLog.memory(it, "before generate") }

            // Accumulates raw UTF-8 and re-decodes the whole stream, because one token can
            // end mid-character. The string returned by generate() is authoritative.
            var tokensSeen = 0
            val sink = object : LlamaCppTokenSink {
                private val buffer = ByteArrayOutputStream()
                override fun onToken(utf8: ByteArray): Boolean {
                    buffer.write(utf8)
                    tokensSeen++
                    val decoded = String(buffer.toByteArray(), Charsets.UTF_8)
                    main.post { streamingText = decoded }
                    return cancelRequested
                }
            }

            try {
                val reply = llm.generate(conversation, params, sink)
                RunLog.log("generate ok: $tokensSeen tokens streamed, ${reply.length} chars returned")
                appContext?.let { RunLog.memory(it, "after generate") }
                main.post {
                    replaceMessage(assistantIndex, ChatMessage(Role.ASSISTANT, reply))
                    streamingText = ""
                    generating = false
                }
            } catch (error: LlamaCppException) {
                RunLog.log("generate FAILED after $tokensSeen tokens: ${error.kind} - ${error.message}")
                appContext?.let { RunLog.memory(it, "after failure") }
                main.post {
                    replaceMessage(
                        assistantIndex,
                        ChatMessage(
                            Role.ASSISTANT,
                            error.message ?: "generation failed",
                            isError = true,
                        ),
                    )
                    streamingText = ""
                    generating = false
                }
            }
        }
    }

    private fun replaceMessage(index: Int, message: ChatMessage) {
        val current = messages
        if (index < 0 || index >= current.size) return
        messages = current.toMutableList().also { it[index] = message }
    }
}