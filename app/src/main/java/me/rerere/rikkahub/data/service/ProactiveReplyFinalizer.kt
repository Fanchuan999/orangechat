/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.Context
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.onGenerationFinish
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import kotlin.time.Clock

internal suspend fun finalizeProactiveReply(
    message: UIMessage,
    finishTransform: suspend (List<UIMessage>) -> List<UIMessage>,
): UIMessage {
    val finished = finishTransform(listOf(message)).single()
    val now = Clock.System.now()
    return finished.copy(
        parts = finished.parts.map { part ->
            if (part is UIMessagePart.Reasoning && part.finishedAt == null) {
                part.copy(finishedAt = now)
            } else {
                part
            }
        },
    )
}

internal suspend fun finalizeProactiveReply(
    message: UIMessage,
    transformers: List<OutputMessageTransformer>,
    context: Context,
    model: Model,
    assistant: Assistant,
    settings: Settings,
): UIMessage = finalizeProactiveReply(message) { messages ->
    messages.onGenerationFinish(
        transformers = transformers,
        context = context,
        model = model,
        assistant = assistant,
        settings = settings,
    )
}

internal fun proactiveVisibleReplyText(message: UIMessage): String =
    message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
