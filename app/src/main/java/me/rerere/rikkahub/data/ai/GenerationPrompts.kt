/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.utils.JsonInstantPretty
import me.rerere.rikkahub.utils.toLocalDate

internal fun buildMemoryPrompt(
    memories: List<AssistantMemory>,
    contentTokenBudget: Int = 0,
) = buildString {
    appendLine()
    append("**Memories**")
    appendLine()
    append("These are memories stored via the memory_tool that you can reference in future conversations.")
    appendLine()
    if (contentTokenBudget > 0) {
        append("Only the first memories that fit the user's estimated memory budget are included.")
        appendLine()
    }
    val contents = takePromptContentsWithinBudget(
        contents = memories.map { it.content },
        estimatedTokenBudget = contentTokenBudget,
    )
    val json = buildJsonArray {
        memories.zip(contents).forEach { (memory, content) ->
            add(buildJsonObject {
                put("id", memory.id)
                put("content", content)
            })
        }
    }
    append(JsonInstantPretty.encodeToString(json))
    appendLine()
}

internal fun buildExternalMemoryPrompt(
    recalledMemories: List<String>,
    contentTokenBudget: Int = 0,
): String {
    if (recalledMemories.isEmpty()) return ""
    val contents = takePromptContentsWithinBudget(
        contents = recalledMemories.reversed(),
        estimatedTokenBudget = contentTokenBudget,
    )
    return buildString {
        appendLine()
        appendLine("## 外置记忆库")
        if (contentTokenBudget > 0) {
            appendLine("以下内容受用户设置的估算 Token 预算限制。")
        }
        contents.forEachIndexed { index, memory ->
            appendLine("${index + 1}. $memory")
        }
    }
}

/**
 * Keeps whole prompt entries where possible and truncates only the final entry.
 *
 * Tokenizers differ by model, so this deliberately uses a conservative estimate: each non-ASCII character counts
 * as one token and every four ASCII characters count as one token. A budget of zero preserves every entry.
 */
internal fun takePromptContentsWithinBudget(
    contents: List<String>,
    estimatedTokenBudget: Int,
): List<String> {
    if (estimatedTokenBudget <= 0) return contents

    var remaining = estimatedTokenBudget
    return buildList {
        for (content in contents) {
            if (remaining <= 0) break
            val included = content.takeEstimatedTokens(remaining)
            add(included)
            remaining -= estimatePromptTokens(included)
        }
    }
}

internal fun estimatePromptTokens(content: String): Int {
    var tokens = 0
    var asciiRunLength = 0
    content.forEach { character ->
        if (character.code <= ASCII_MAX) {
            asciiRunLength++
            if ((asciiRunLength - 1) % ASCII_CHARS_PER_TOKEN == 0) tokens++
        } else {
            asciiRunLength = 0
            tokens++
        }
    }
    return tokens
}

private fun String.takeEstimatedTokens(estimatedTokenBudget: Int): String {
    if (estimatedTokenBudget <= 0 || isEmpty()) return ""
    if (estimatePromptTokens(this) <= estimatedTokenBudget) return this

    val contentBudget = (estimatedTokenBudget - 1).coerceAtLeast(1)
    val result = StringBuilder()
    var tokens = 0
    var asciiRunLength = 0
    for (character in this) {
        val nextTokens = if (character.code <= ASCII_MAX) {
            asciiRunLength++
            if ((asciiRunLength - 1) % ASCII_CHARS_PER_TOKEN == 0) tokens + 1 else tokens
        } else {
            asciiRunLength = 0
            tokens + 1
        }
        if (nextTokens > contentBudget) break
        tokens = nextTokens
        result.append(character)
    }
    return if (estimatedTokenBudget > 1) "$result…" else result.toString()
}

private const val ASCII_MAX = 0x7F
private const val ASCII_CHARS_PER_TOKEN = 4

internal suspend fun buildRecentChatsPrompt(
    assistant: Assistant,
    conversationRepo: ConversationRepository
): String {
    val recentConversations = conversationRepo.getRecentConversations(
        assistantId = assistant.id,
        limit = 10,
    )
    if (recentConversations.isNotEmpty()) {
        return buildString {
            appendLine()
            append("**Recent Chats**")
            appendLine()
            append("These are some of the user's recent conversations. You can use them to understand user preferences:")
            appendLine()
            val json = buildJsonArray {
                recentConversations.forEach { conversation ->
                    add(buildJsonObject {
                        put("title", conversation.title)
                        put("last_chat", conversation.updateAt.toLocalDate())
                    })
                }
            }
            append(JsonInstantPretty.encodeToString(json))
            appendLine()
        }
    }
    return ""
}
