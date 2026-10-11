package com.saeed.llamachat.core

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.util.Log
import com.saeed.llamacpp.LlamaCppEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * An append-only record of what the app and the engine actually did, written to a file.
 *
 * WHY THIS EXISTS: when an app exits during generation, logcat on a busy device has usually
 * rotated past the event by the time anyone looks, and Android only keeps a tombstone for
 * signal crashes — a process killed by the system leaves nothing at all. This file keeps the
 * evidence: every engine event, the app's own resident memory at each step, llama.cpp's own
 * logging, and any uncaught exception with its stack trace.
 *
 * The file lives in Download/LlamaChat/ when all-files access is granted (so it is easy to
 * find and share), and in the app's external files directory otherwise.
 */
object RunLog {

    private const val TAG = "LlamaChat"
    private const val FILE_NAME = "llamachat-run.log"

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var file: File? = null

    @Volatile
    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true

        val target = resolveFile(context)
        file = target
        // Give the engine the same destination, so llama.cpp's own warnings survive too.
        LlamaCppEngine.setLogFile(target?.absolutePath)

        log("=== run started ===============================================")
        log("app version   : ${versionName(context)}")
        log("llama.cpp     : ${LlamaCppEngine.nativeVersionOrNull() ?: "NATIVE LIBRARY UNAVAILABLE"}")
        log("log file      : ${target?.absolutePath ?: "NOT WRITABLE - file logging disabled"}")
        memory(context, "at startup")

        installCrashHandler()
    }

    fun log(message: String) {
        Log.i(TAG, message)
        val target = file ?: return
        runCatching { target.appendText("${stamp.format(Date())}  $message\n") }
    }

    /** Logs both the app's own resident memory and the device's overall memory state. */
    fun memory(context: Context, label: String) {
        val rss = processResidentMb()
        val info = ActivityManager.MemoryInfo()
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getMemoryInfo(info)
        }
        log(
            "$label: app RSS ${if (rss >= 0) "$rss MB" else "unknown"}, " +
                "device available ${info.availMem / MB} MB of ${info.totalMem / MB} MB, " +
                "lowMemory=${info.lowMemory}",
        )
    }

    /** The app's own resident set size, which is what the system actually measures. */
    private fun processResidentMb(): Long = try {
        val fields = File("/proc/self/statm").readText().trim().split(" ")
        fields[1].toLong() * PAGE_SIZE / MB
    } catch (error: Throwable) {
        -1L
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log("!!! UNCAUGHT EXCEPTION on thread '${thread.name}'")
            log("${error.javaClass.name}: ${error.message}")
            log(error.stackTraceToString())
            previous?.uncaughtException(thread, error)
        }
    }

    private fun resolveFile(context: Context): File? {
        val candidates = listOf(
            File(Environment.getExternalStorageDirectory(), "Download/LlamaChat"),
            context.getExternalFilesDir(null),
        )
        for (directory in candidates) {
            if (directory == null) continue
            if (!directory.exists() && !directory.mkdirs()) continue
            val candidate = File(directory, FILE_NAME)
            // appendText("") creates the file, so success here proves it is writable.
            if (runCatching { candidate.appendText("") }.isSuccess) return candidate
        }
        return null
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (error: Throwable) {
        "?"
    }

    private const val MB = 1024L * 1024L
    private const val PAGE_SIZE = 4096L
}