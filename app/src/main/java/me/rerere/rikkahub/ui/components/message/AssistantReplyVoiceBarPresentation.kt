package me.rerere.rikkahub.ui.components.message

import me.rerere.ai.core.MessageRole

internal object AssistantReplyVoiceBarPresentation {
    fun shouldShowVoiceBar(
        enabled: Boolean,
        role: MessageRole,
        text: String,
        loading: Boolean,
    ): Boolean = enabled && role == MessageRole.ASSISTANT && text.isNotBlank() && !loading

    fun estimatedDurationSeconds(text: String): Int =
        (text.trim().length / 4).coerceIn(1, 59)
}
