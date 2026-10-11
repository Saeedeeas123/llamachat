package com.saeed.llamacpp

import java.io.File
import java.io.RandomAccessFile

/**
 * Pre-flight checks on a model file, run before the native layer sees it.
 *
 * WHY THIS EXISTS: when llama.cpp is handed something that is not a GGUF file it returns a
 * null model plus a log line. By the time that reaches a caller it is indistinguishable from
 * "out of memory", "unsupported architecture" or "corrupt download". Checking the cheap,
 * unambiguous things here turns the common failure into a specific, actionable error.
 *
 * Deliberately free of Android imports so it can be exercised by plain JVM unit tests.
 */
object LlamaCppModelValidator {

    /** GGUF files start with the ASCII magic "GGUF" at offset 0 (verified against real files). */
    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46)

    /** magic(4) + version(4) + tensor_count(8) + metadata_kv_count(8). */
    private const val HEADER_SIZE = 24L

    @Throws(LlamaCppException::class)
    fun validate(path: String) {
        val file = File(path)

        if (!file.exists()) {
            throw LlamaCppException(
                LlamaCppException.Kind.MODEL_MISSING,
                "there is no model file at $path",
            )
        }
        if (!file.isFile) {
            throw LlamaCppException(
                LlamaCppException.Kind.MODEL_UNREADABLE,
                "$path is not a regular file",
            )
        }
        if (!file.canRead()) {
            throw LlamaCppException(
                LlamaCppException.Kind.MODEL_UNREADABLE,
                "$path exists but cannot be read",
            )
        }
        if (file.length() < HEADER_SIZE) {
            throw LlamaCppException(
                LlamaCppException.Kind.MODEL_NOT_GGUF,
                "$path is only ${file.length()} bytes, too small to hold a GGUF header",
            )
        }

        val header = readHeader(file)
        val magic = header.copyOfRange(0, 4)
        if (!magic.contentEquals(GGUF_MAGIC)) {
            throw LlamaCppException(
                LlamaCppException.Kind.MODEL_NOT_GGUF,
                "$path is not a GGUF file: the first bytes are ${hex(magic)}, expected 47 47 55 46 (\"GGUF\")",
            )
        }

        // Only version 0 is rejected. Later versions must not be blocked by this module —
        // llama.cpp itself is the authority on which versions it can read.
        val version = readVersion(header)
        if (version == 0L) {
            throw LlamaCppException(
                LlamaCppException.Kind.MODEL_NOT_GGUF,
                "$path declares GGUF version 0, which is not a valid GGUF file",
            )
        }
    }

    private fun readHeader(file: File): ByteArray = RandomAccessFile(file, "r").use { stream ->
        val header = ByteArray(HEADER_SIZE.toInt())
        stream.readFully(header)
        header
    }

    private fun readVersion(header: ByteArray): Long {
        var version = 0L
        for (index in 7 downTo 4) {
            version = (version shl 8) or (header[index].toLong() and 0xFF)
        }
        return version
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02x", it) }
}