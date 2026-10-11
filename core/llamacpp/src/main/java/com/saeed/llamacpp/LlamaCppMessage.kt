package com.saeed.llamacpp

/**
 * One turn of a conversation, in the shape a chat template expects.
 *
 * Roles are plain strings rather than an enum because the chat template is the model's, and
 * it decides which role names it understands ("system"/"user"/"assistant" is the common set).
 */
data class LlamaCppMessage(val role: String, val content: String) {

    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"

        fun system(content: String) = LlamaCppMessage(SYSTEM, content)
        fun user(content: String) = LlamaCppMessage(USER, content)
        fun assistant(content: String) = LlamaCppMessage(ASSISTANT, content)
    }
}