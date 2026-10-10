package com.saeed.llamachat.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import java.io.File

/**
 * Finds and manages GGUF model files.
 *
 * Two loading paths exist on purpose:
 *
 *  - Models already on shared storage are loaded **in place**. llama.cpp needs a real
 *    filesystem path, and copying a multi-gigabyte file into app storage would double its
 *    footprint. This path needs MANAGE_EXTERNAL_STORAGE.
 *  - Models chosen through the system file picker are **copied** into app-private storage.
 *    That works with no permission at all, and is the fallback when the user does not want
 *    to hand out all-files access.
 */
class ModelStore(private val context: Context) {

    data class ModelFile(val file: File, val imported: Boolean) {
        val name: String get() = file.name
        val sizeBytes: Long get() = file.length()
        val path: String get() = file.absolutePath
    }

    private val importedDir: File
        get() = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    fun allFilesAccessIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))

    fun importedModels(): List<ModelFile> =
        importedDir.listFiles { f -> f.isFile && isGguf(f) }
            ?.map { ModelFile(it, true) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    /**
     * Blocking directory walk. Run it off the main thread.
     *
     * The search is deliberately bounded: a handful of well-known folders, two levels deep,
     * with the system's own data directories skipped. Scanning the whole of shared storage
     * would take minutes and is never what the user means.
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
        val knownNames = listOf("Models", "models", "Model", "gguf", "GGUF", "Download", "Downloads", "Documents")

        for (root in roots) {
            for (name in knownNames) {
                val dir = File(root, name)
                if (dir.isDirectory) walk(dir, 0, MAX_DEPTH, found)
            }
        }

        return found.values.sortedBy { it.name.lowercase() }
    }

    private fun walk(dir: File, depth: Int, maxDepth: Int, out: MutableMap<String, ModelFile>) {
        if (depth > maxDepth || out.size >= MAX_RESULTS) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (out.size >= MAX_RESULTS) return
            when {
                child.isDirectory -> {
                    // "Android" holds app data and is never interesting; hidden dirs are noise.
                    if (!child.name.startsWith(".") && child.name != "Android") {
                        walk(child, depth + 1, maxDepth, out)
                    }
                }
                child.isFile && isGguf(child) -> {
                    out[child.absolutePath] = ModelFile(child, child.absolutePath.startsWith(importedDir.absolutePath))
                }
            }
        }
    }

    /** Display name of a content:// URI, as reported by the document provider. */
    fun displayName(uri: Uri): String? = try {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (t: Throwable) {
        null
    }

    /**
     * Copies a picked document into app-private storage.
     * Writes to a `.part` file first so an interrupted copy can never look like a model.
     */
    fun importFromUri(uri: Uri, onProgress: (Long) -> Unit): File {
        val rawName = displayName(uri) ?: uri.lastPathSegment ?: "model.gguf"
        val safeName = sanitize(rawName)
        val target = File(importedDir, if (safeName.lowercase().endsWith(".gguf")) safeName else "$safeName.gguf")
        val part = File(importedDir, "${target.name}.part")

        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("the selected file could not be opened")
        input.use { source ->
            part.outputStream().use { sink ->
                val buffer = ByteArray(1 shl 20)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    sink.write(buffer, 0, read)
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

    private fun isGguf(file: File) = file.name.lowercase().endsWith(".gguf")

    /** Keeps a provider-supplied name from escaping the models directory. */
    private fun sanitize(name: String): String {
        val cleaned = name
            .map { if (it == '/' || it == '\\' || it == ':' || it == '\u0000') '_' else it }
            .joinToString("")
            .trim()
        return cleaned.ifEmpty { "model.gguf" }
    }

    private companion object {
        const val MAX_DEPTH = 2
        const val MAX_RESULTS = 300
    }
}