package com.saeed.llamacpp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JVM tests for everything in this module that does not need the native library.
 *
 * The native path itself cannot be exercised here (no ARM64 llama.cpp in a JVM test); it is
 * verified on the host by native/linux_probe.cpp, which links the same llama_core.cpp that
 * goes into the shipped .so.
 */
class LlamaCppModelValidatorTest {

    private fun tempFile(name: String, bytes: ByteArray): File {
        // File.createTempFile rejects prefixes shorter than three characters.
        val file = File.createTempFile(name.padEnd(3, '_'), ".gguf")
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file
    }

    private fun ggufHeader(magic: ByteArray, version: Int, padding: Int = 32): ByteArray {
        val buffer = ByteBuffer.allocate(magic.size + 4 + padding).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(magic)
        buffer.putInt(version)
        return buffer.array()
    }

    private val realMagic = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF", verified on real models

    @Test
    fun `a missing file is reported as missing`() {
        val error = assertThrows(LlamaCppException::class.java) {
            LlamaCppModelValidator.validate("/definitely/not/here/model.gguf")
        }
        assertEquals(LlamaCppException.Kind.MODEL_MISSING, error.kind)
    }

    @Test
    fun `a directory is reported as unreadable rather than missing`() {
        val directory = File.createTempFile("dir", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        val error = assertThrows(LlamaCppException::class.java) {
            LlamaCppModelValidator.validate(directory.absolutePath)
        }
        assertEquals(LlamaCppException.Kind.MODEL_UNREADABLE, error.kind)
    }

    @Test
    fun `a file too small for a GGUF header is rejected`() {
        val file = tempFile("tiny", byteArrayOf(0x47, 0x47))
        val error = assertThrows(LlamaCppException::class.java) {
            LlamaCppModelValidator.validate(file.absolutePath)
        }
        assertEquals(LlamaCppException.Kind.MODEL_NOT_GGUF, error.kind)
    }

    @Test
    fun `a file with the wrong magic is rejected and the message shows what was found`() {
        // A real MediaPipe .task bundle starts with 00 00 50 4b ("PK" at offset 2), a shape
        // that has caused exactly this class of confusion before.
        val file = tempFile("wrongmagic", ggufHeader(byteArrayOf(0x00, 0x00, 0x50, 0x4b), 3))
        val error = assertThrows(LlamaCppException::class.java) {
            LlamaCppModelValidator.validate(file.absolutePath)
        }
        assertEquals(LlamaCppException.Kind.MODEL_NOT_GGUF, error.kind)
        assertTrue(error.message!!.contains("00 00 50 4b"))
    }

    @Test
    fun `a real GGUF header passes`() {
        val file = tempFile("good", ggufHeader(realMagic, 3))
        LlamaCppModelValidator.validate(file.absolutePath)
    }

    @Test
    fun `version 0 is rejected as invalid`() {
        val file = tempFile("v0", ggufHeader(realMagic, 0))
        val error = assertThrows(LlamaCppException::class.java) {
            LlamaCppModelValidator.validate(file.absolutePath)
        }
        assertEquals(LlamaCppException.Kind.MODEL_NOT_GGUF, error.kind)
    }

    @Test
    fun `a future GGUF version is NOT blocked by this module`() {
        // llama.cpp is the authority on which versions it can read. Guarding on "version <= 3"
        // here would silently break the module the day a v4 lands.
        val file = tempFile("v4", ggufHeader(realMagic, 4))
        LlamaCppModelValidator.validate(file.absolutePath)
    }
}

class LlamaCppMessageTest {

    @Test
    fun `role constants match what chat templates expect`() {
        assertEquals("system", LlamaCppMessage.SYSTEM)
        assertEquals("user", LlamaCppMessage.USER)
        assertEquals("assistant", LlamaCppMessage.ASSISTANT)
    }

    @Test
    fun `factories build messages with the right role`() {
        assertEquals(LlamaCppMessage("system", "s"), LlamaCppMessage.system("s"))
        assertEquals(LlamaCppMessage("user", "u"), LlamaCppMessage.user("u"))
        assertEquals(LlamaCppMessage("assistant", "a"), LlamaCppMessage.assistant("a"))
    }
}

class LlamaCppSettingsTest {

    @Test
    fun `defaults are usable as-is`() {
        val config = LlamaCppConfig()
        assertTrue(config.contextSize >= 64)
        assertTrue(config.threads >= 1)
        assertEquals(0, config.gpuLayers)
        assertTrue(config.guardMainThread)

        val params = LlamaCppParams()
        assertTrue(params.maxTokens >= 1)
        assertTrue(params.temperature >= 0f)
    }

    @Test
    fun `impossible settings are rejected at construction, not at load time`() {
        assertThrows(IllegalArgumentException::class.java) { LlamaCppConfig(contextSize = 10) }
        assertThrows(IllegalArgumentException::class.java) { LlamaCppConfig(threads = 0) }
        assertThrows(IllegalArgumentException::class.java) { LlamaCppConfig(gpuLayers = -1) }
        assertThrows(IllegalArgumentException::class.java) { LlamaCppParams(maxTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { LlamaCppParams(temperature = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { LlamaCppParams(repeatPenalty = 0f) }
    }
}
