package com.saeed.llamacpp

/**
 * Receives generated text as it is produced, and is the cancellation mechanism.
 *
 * Bytes, not String, on purpose: one token can end in the middle of a multi-byte character,
 * so the consumer decides how to decode the accumulated stream. A sink that wants text should
 * accumulate the bytes and decode the whole buffer, which is always correct.
 *
 * Returning **true cancels generation** after the current token — that is how a UI's Stop
 * button is wired. The bytes for that token have already been delivered.
 */
fun interface LlamaCppTokenSink {
    fun onToken(utf8: ByteArray): Boolean
}