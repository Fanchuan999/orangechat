package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

internal data class NightWatchEvidence(
    val history: List<UIMessage>,
    val lastUserText: String,
)

/**
 * Builds a small, auditable evidence window for night-watch generations. Tool output, reasoning,
 * attachments and system messages are excluded so the model cannot mistake them for user words.
 */
internal fun nightWatchEvidence(
    messages: List<UIMessage>,
    limit: Int = 12,
): NightWatchEvidence {
    val history = messages.mapNotNull { message ->
        if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) {
            null
        } else {
            val textParts = message.parts.filterIsInstance<UIMessagePart.Text>()
                .filter { it.text.isNotBlank() }
            message.takeIf { textParts.isNotEmpty() }?.copy(parts = textParts)
        }
    }.takeLast(limit.coerceAtLeast(1))

    val lastUserText = history.asReversed()
        .firstOrNull { it.role == MessageRole.USER }
        ?.parts
        ?.filterIsInstance<UIMessagePart.Text>()
        ?.joinToString("\n") { it.text }
        ?.trim()
        .orEmpty()

    return NightWatchEvidence(history = history, lastUserText = lastUserText)
}
