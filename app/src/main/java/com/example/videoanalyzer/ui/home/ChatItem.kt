package com.example.videoanalyzer.ui.home

/** Who said a message in the chat. */
enum class ChatRole { USER, ASSISTANT }

/** One row in the chat history. */
data class ChatItem(
    val role: ChatRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    /** Set when an item is in-flight (e.g. assistant typing). */
    val isPending: Boolean = false,
)