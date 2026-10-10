package com.saeed.llamachat.core

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Holds the native session and all observable UI state.
 *
 * A process-wide object rather than a ViewModel: there is exactly one model session per
 * process, and it must survive recomposition and activity recreation. Every native call
 * runs on a single background thread, so native work is serialised by construction and a
 * generation can never overlap a model load.
 *
 * The activity declares `configChanges` for rotation, so state is not lost on rotate.
 * Native memory is released deterministically in [shutdown], which the activity calls when
 * it is really finishing.
 */
object ChatController {

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "llama-worker").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private var initialized = false
    private var modelStore: ModelStore? = null
    private var settingsStore: SettingsStore? = null

    /** Opaque native session handle; 0 means nothing is loaded. */
    private var handle: Long = 0L

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

    var models by mutableStateOf<List<ModelStore.ModelFile>>(emptyList())
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
        modelStore = ModelStore(app)
        settingsStore = SettingsStore(app)
        settings = settingsStore?.load() ?: GenSettings.DEFAULTS

        // Touching the bridge loads the shared library. A failure here (wrong ABI, missing
        // .so) must surface as a readable message instead of an app crash.
        try {
            nativeVersion = LlamaBridge.nativeVersion()
        } catch (t: Throwable) {
            nativeLoadError = t.message ?: t.toString()
        }
    }

    fun shutdown() {
        val current = handle
        handle = 0L
        if (current != 0L) {
            runCatching { LlamaBridge.nativeFree(current) }
        }
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
            } catch (t: Throwable) {
                main.post {
                    importing = false
                    notice = t.message ?: "the file could not be imported"
                }
            }
            rescanModels()
        }
    }

    fun deleteModel(model: ModelStore.ModelFile) {
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
        // Capture the context-creation parameters now: they must not change mid-load.
        val contextSize = settings.contextSize
        val threads = settings.threads

        engine = EngineState.Loading(displayName)
        worker.execute {
            val previous = handle
            handle = 0L
            if (previous != 0L) {
                runCatching { LlamaBridge.nativeFree(previous) }
            }
            try {
                val created = LlamaBridge.nativeLoadModel(path, contextSize, threads, 0)
                handle = created
                val description = LlamaBridge.nativeDescribe(created) ?: displayName
                main.post {
                    loadedModelName = displayName
                    engine = EngineState.Ready(description)
                }
            } catch (t: Throwable) {
                handle = 0L
                main.post {
                    loadedModelName = null
                    engine = EngineState.Failed(t.message ?: "the model could not be loaded")
                }
            }
        }
    }

    fun unloadModel() {
        val current = handle
        handle = 0L
        engine = EngineState.NoModel
        loadedModelName = null
        worker.execute {
            if (current != 0L) {
                runCatching { LlamaBridge.nativeFree(current) }
            }
        }
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
        if (handle == 0L) {
            notice = "Load a model first."
            return
        }

        cancelRequested = false

        // History sent to the model: everything already in the chat, plus this new turn.
        val history = messages + ChatMessage(Role.USER, prompt)
        val assistantIndex = history.size
        messages = history + ChatMessage(Role.ASSISTANT, "")
        streamingText = ""
        generating = true

        val roles = ArrayList<String>(history.size + 1)
        val contents = ArrayList<String>(history.size + 1)
        val systemPrompt = settings.systemPrompt
        if (systemPrompt.isNotBlank()) {
            roles.add(Role.SYSTEM.wire)
            contents.add(systemPrompt)
        }
        for (message in history) {
            if (message.role == Role.ASSISTANT && message.isError) continue
            roles.add(message.role.wire)
            contents.add(message.text)
        }

        val session = handle
        val s = settings

        worker.execute {
            // Accumulates raw UTF-8 and re-decodes the whole stream, because one token can
            // end mid-character. The final string returned by nativeChat is authoritative.
            val sink = object : LlamaBridge.TokenSink {
                private val buffer = ByteArrayOutputStream()
                override fun onToken(utf8: ByteArray): Boolean {
                    buffer.write(utf8)
                    val decoded = String(buffer.toByteArray(), Charsets.UTF_8)
                    main.post { streamingText = decoded }
                    return cancelRequested
                }
            }

            try {
                val reply = LlamaBridge.nativeChat(
                    session,
                    roles.toTypedArray(),
                    contents.toTypedArray(),
                    s.maxTokens,
                    s.temperature,
                    s.topK,
                    s.topP,
                    s.repeatPenalty,
                    s.repeatLastN,
                    s.seed,
                    sink,
                )
                main.post {
                    replaceMessage(assistantIndex, ChatMessage(Role.ASSISTANT, reply))
                    streamingText = ""
                    generating = false
                }
            } catch (t: Throwable) {
                main.post {
                    replaceMessage(
                        assistantIndex,
                        ChatMessage(Role.ASSISTANT, t.message ?: "generation failed", isError = true),
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