package com.saeed.llamachat.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.util.Log
import com.saeed.llamacpp.LlamaCppEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app's own log — the only diagnostic channel that exists on this device.
 *
 * Saeed has no Android Studio and no access to logcat, tombstones or dropbox, and the agent
 * has no way to attach a debugger to a shipped APK. So the app records what it does, in detail,
 * and keeps it.
 *
 * The rules this implements (see the permanent hard rule in `user.md` §30):
 *
 *  - **Written as it happens, never only in memory.** Every line is appended and flushed
 *    immediately, so a process that dies mid-action still leaves everything up to that instant.
 *  - **One file per run, previous runs retained** (the newest [MAX_SESSIONS] are kept). Nothing
 *    is overwritten and nothing is deleted on exit.
 *  - **No permission required.** It goes to the app's own external files directory, and also to
 *    `Download/LlamaChat/logs/` when all-files access has been granted, so the file can be
 *    reached without a PC.
 *  - **Never fails silently.** If no file can be opened, [unavailableReason] is set and the log
 *    screen says so instead of showing an empty box.
 *
 * The engine's own logging (llama.cpp warnings, and the inference timing line) is routed into
 * the same file through [LlamaCppEngine.setLogFile].
 */
object RunLog {

    private const val TAG = "LlamaChat"
    private const val LOG_DIR_NAME = "logs"
    private const val MAX_SESSIONS = 10

    /** How many trailing lines the viewer reads. Logs are small; this just bounds the work. */
    private const val VIEW_LINES = 2000

    private val lineStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    @Volatile
    private var directory: File? = null

    @Volatile
    private var current: File? = null

    @Volatile
    private var started = false

    /** Set when the log could not be opened, so the UI can say so rather than show nothing. */
    @Volatile
    var unavailableReason: String? = null
        private set

    fun init(context: Context) {
        if (started) return
        started = true

        directory = resolveDirectory(context)
        val target = directory?.let { File(it, "run-${fileStamp.format(Date())}.log") }
        if (target != null && runCatching { target.appendText("") }.isSuccess) {
            current = target
            pruneOldSessions()
        } else {
            unavailableReason = "no writable location for the log"
        }

        // Give the engine the same destination so llama.cpp's own output survives too.
        LlamaCppEngine.setLogFile(current?.absolutePath)

        log("=== run started =================================================")
        log("app version  : ${versionName(context)}")
        log("device       : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        log("abi          : ${Build.SUPPORTED_ABIS.joinToString()}")
        log("llama.cpp    : ${LlamaCppEngine.nativeVersionOrNull() ?: "NATIVE LIBRARY UNAVAILABLE"}")
        log("log file     : ${current?.absolutePath ?: "UNAVAILABLE (${unavailableReason})"}")
        memory(context, "at startup")

        installCrashHandler()
    }

    /** Appends one line, flushed immediately. Also mirrored to logcat while it lasts. */
    fun log(message: String) {
        Log.i(TAG, message)
        val target = current ?: return
        runCatching { target.appendText("${lineStamp.format(Date())}  $message\n") }
    }

    /** Logs the app's own resident memory and the device's overall memory state. */
    fun memory(context: Context, label: String) {
        val rss = processResidentMb()
        val info = ActivityManager.MemoryInfo()
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getMemoryInfo(info)
        }
        log(
            "$label: app RSS ${if (rss >= 0) "$rss MB" else "unknown"}, " +
                "device ${info.availMem / MB} MB free of ${info.totalMem / MB} MB, " +
                "lowMemory=${info.lowMemory}",
        )
    }

    fun currentFile(): File? = current

    /** Previous runs, newest first. */
    fun sessions(): List<File> {
        val dir = directory ?: return emptyList()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /** Reads the tail of a session, for the viewer. */
    fun read(file: File?, maxLines: Int = VIEW_LINES): String {
        if (file == null) {
            return "No log file.\n\n${unavailableReason ?: "The log was never created."}"
        }
        return runCatching {
            val lines = file.readLines()
            if (lines.size <= maxLines) {
                lines.joinToString("\n")
            } else {
                "… ${lines.size - maxLines} earlier lines omitted …\n" +
                    lines.takeLast(maxLines).joinToString("\n")
            }
        }.getOrElse { error -> "Could not read ${file.name}: ${error.message}" }
    }

    /** Shares the tail of a session as plain text — no FileProvider needed. */
    fun shareIntent(file: File?): Intent {
        val text = read(file, 500)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "LlamaChat log — ${file?.name ?: "none"}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
    }

    private fun processResidentMb(): Long = runCatching {
        val fields = File("/proc/self/statm").readText().trim().split(" ")
        fields[1].toLong() * PAGE_SIZE / MB
    }.getOrDefault(-1L)

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Written and flushed before the process goes down.
            log("!!! UNCAUGHT EXCEPTION on thread '${thread.name}'")
            log("${error.javaClass.name}: ${error.message}")
            log(error.stackTraceToString())
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Public `Download/LlamaChat/logs/` first (easiest to reach from a file manager), then the
     * app's own external files directory, which needs no permission at all.
     */
    private fun resolveDirectory(context: Context): File? {
        val candidates = listOfNotNull(
            File(Environment.getExternalStorageDirectory(), "Download/LlamaChat/$LOG_DIR_NAME"),
            context.getExternalFilesDir(null)?.let { File(it, LOG_DIR_NAME) },
        )
        for (candidate in candidates) {
            if (!candidate.exists() && !candidate.mkdirs()) continue
            if (candidate.isDirectory && candidate.canWrite()) return candidate
        }
        return null
    }

    /** Keeps the newest [MAX_SESSIONS] runs and deletes only older ones. */
    private fun pruneOldSessions() {
        val all = sessions()
        if (all.size <= MAX_SESSIONS) return
        all.drop(MAX_SESSIONS).forEach { old -> runCatching { old.delete() } }
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private const val MB = 1024L * 1024L
    private const val PAGE_SIZE = 4096L
}