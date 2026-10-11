package com.saeed.llamacpp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import java.io.File

/**
 * Finds and manages GGUF files on the device.
 *
 * Two paths exist on purpose:
 *
 *  - Models already on shared storage are loaded **in place**. llama.cpp needs a real
 *    filesystem path, and copying a multi-gigabyte file into app storage would double its
 *    footprint. This path needs MANAGE_EXTERNAL_STORAGE in the consuming app's manifest.
 *  - Models chosen through the system file picker are **copied** into app-private storage.
 *    That works with no permission at all.
 *
 * Optional: an app that manages its own model files can ignore this class entirely and hand
 * [LlamaCppEngine.load] a path it obtained some other way.
 */
class LlamaCppModelStore(private val context: Context) {

    data class ModelFile(val file: File, val imported: Boolean) {
        val name: String get() = file.name
        val sizeBytes: Long get() = file.length()
        val path: String get() = file.absolutePath
    }

    private val importedDir: File
        get() = File(context.filesDir, IMPORT_DIR).apply { if (!exists()) mkdirs() }

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    /** Intent for the system screen where the user grants all-files access. */
    fun allFilesAccessIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))

    fun importedModels(): List<ModelFile> =
        importedDir.listFiles { file -> file.isFile && isGguf(file) }
            ?.map { ModelFile(it, imported = true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /**
     * Blocking directory walk. Run it off the main thread.
     *
     * The search is deliberately bounded: a handful of well-known folders, two levels deep,
     * with the system's own data directories skipped. Walking all of shared storage takes
     * minutes and is never what the user means.
     */
    fun scan(): List<ModelFile> {
        val found = LinkedHashMap<String, ModelFile>()
        importedModels().forEach { found[it.path] = it }

        if (!hasAllFilesAccess()) {
            return found.values.sortedBy { it.name.lowercase() }
        }

        val roots = listOfNotNull(
            Environment.getExternalStorageDirectory(),
            context.getExternalFilesDir(null),
        )
        val knownFolders = listOf(
            "Models", "models", "Model", "gguf", "GGUF",
            "Download", "Downloads", "Documents",
        )

        for (root in roots) {
            for (name in knownFolders) {
                val directory = File(root, name)
                if (directory.isDirectory) {
                    walk(directory, 0, found)
                }
            }
        }

        return found.values.sortedBy { it.name.lowercase() }
    }

    private fun walk(directory: File, depth: Int, out: MutableMap<String, ModelFile>) {
        if (depth > MAX_DEPTH || out.size >= MAX_RESULTS) return

        val children = directory.listFiles() ?: return
        for (child in children) {
            if (out.size >= MAX_RESULTS) return
            when {
                child.isDirectory -> {
                    // "Android" holds app-private data and is never interesting here.
                    if (!child.name.startsWith(".") && child.name != "Android") {
                        walk(child, depth + 1, out)
                    }
                }

                child.isFile && isGguf(child) -> {
                    val imported = child.absolutePath.startsWith(importedDir.absolutePath)
                    out[child.absolutePath] = ModelFile(child, imported)
                }
            }
        }
    }

    /** Display name of a `content://` URI, as reported by the document provider. */
    fun displayName(uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (error: Throwable) {
        null
    }

    /**
     * Copies a picked document into app-private storage.
     *
     * Writes to a `.part` file first and renames at the end, so an interrupted copy can never
     * leave something that looks like a usable model.
     */
    fun importFromUri(uri: Uri, onProgress: (Long) -> Unit = {}): File {
        val rawName = displayName(uri) ?: uri.lastPathSegment ?: "model.gguf"
        val safeName = sanitize(rawName)
        val target = File(
            importedDir,
            if (safeName.lowercase().endsWith(GGUF_EXTENSION)) safeName else "$safeName$GGUF_EXTENSION",
        )
        val part = File(importedDir, "${target.name}.part")

        val source = context.contentResolver.openInputStream(uri)
            ?: throw LlamaCppException(
                LlamaCppException.Kind.MODEL_UNREADABLE,
                "the selected file could not be opened",
            )

        source.use { input ->
            part.outputStream().use { output ->
                val buffer = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                    onProgress(total)
                }
            }
        }

        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        return target
    }

    fun delete(model: ModelFile): Boolean = model.file.delete()

    private fun isGguf(file: File) = file.name.lowercase().endsWith(GGUF_EXTENSION)

    /** Keeps a provider-supplied name from escaping the models directory. */
    private fun sanitize(name: String): String = name
        .map { if (it == '/' || it == '\\' || it == ':' || it == '\u0000') '_' else it }
        .joinToString("")
        .trim()
        .ifEmpty { "model$GGUF_EXTENSION" }

    private companion object {
        const val IMPORT_DIR = "llamacpp-models"
        const val GGUF_EXTENSION = ".gguf"
        const val MAX_DEPTH = 2
        const val MAX_RESULTS = 300
    }
}