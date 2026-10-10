package com.saeed.llamachat.core

/** Chat roles as the model's chat template expects them. */
enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
}

data class ChatMessage(
    val role: Role,
    val text: String,
    /** True when [text] carries a failure message rather than model output. */
    val isError: Boolean = false,
)