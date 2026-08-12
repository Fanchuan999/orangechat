package me.rerere.rikkahub.data.service

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.ChatMediaCleanupMode
import me.rerere.rikkahub.data.files.ChatMediaItem
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.files.selectChatMediaCandidates
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.io.File

const val DELETED_CHAT_IMAGE_URL = "daddy://deleted-chat-image"

data class ChatMediaStorageEntry(
    val id: Long,
    val relativePath: String,
    val displayName: String,
    val sizeBytes: Long,
    val createdAtMillis: Long,
)

data class ChatMediaCleanupResult(val deletedCount: Int, val deletedBytes: Long, val failures: Int)

/**
 * A deliberately narrow storage service: it only ever manages tracked `upload/` images.
 * Display assets and companion-space photos are marked protected before a candidate is shown.
 */
class ChatMediaStorageService(
    private val filesManager: FilesManager,
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
) {
    suspend fun scan(mode: ChatMediaCleanupMode): List<ChatMediaStorageEntry> = withContext(Dispatchers.IO) {
        val settings = settingsStore.settingsFlow.value
        val conversations = conversationRepository.getAllConversations().mapNotNull { summary ->
            conversationRepository.getConversationById(summary.id)
        }
        val referenced = conversations.flatMap { it.files }
            .mapNotNull { uri -> filesManager.relativePathForFileUri(uri.toString()) }
            .toSet()
        val protected = buildProtectedPaths(settings)
        selectChatMediaCandidates(
            items = filesManager.listChatMediaItems(),
            referencedChatPaths = referenced,
            protectedPaths = protected,
            mode = mode,
            nowMillis = System.currentTimeMillis(),
        ).mapNotNull { item ->
            filesManager.getByRelativePath(item.relativePath)?.let { entity ->
                ChatMediaStorageEntry(entity.id, entity.relativePath, entity.displayName, entity.sizeBytes, entity.createdAt)
            }
        }
    }

    suspend fun delete(selectedIds: Set<Long>, mode: ChatMediaCleanupMode): ChatMediaCleanupResult = withContext(Dispatchers.IO) {
        if (selectedIds.isEmpty()) return@withContext ChatMediaCleanupResult(0, 0, 0)
        val allowed = scan(mode).filter { it.id in selectedIds }
        if (mode == ChatMediaCleanupMode.PERMANENT) {
            val paths = allowed.map { it.relativePath }.toSet()
            replaceReferencedImages(paths)
        }
        var deleted = 0
        var bytes = 0L
        var failures = 0
        allowed.forEach { entry ->
            if (filesManager.delete(entry.id, deleteFromDisk = true)) {
                deleted++
                bytes += entry.sizeBytes
            } else failures++
        }
        ChatMediaCleanupResult(deleted, bytes, failures)
    }

    private suspend fun replaceReferencedImages(paths: Set<String>) {
        if (paths.isEmpty()) return
        conversationRepository.getAllConversations().forEach { summary ->
            val conversation = conversationRepository.getConversationById(summary.id) ?: return@forEach
            val updated = conversation.replaceImagePaths(paths, filesManager)
            if (updated != conversation) conversationRepository.updateConversation(updated)
        }
    }

    private fun buildProtectedPaths(settings: me.rerere.rikkahub.data.datastore.Settings): Set<String> = buildSet {
        fun protect(url: String?) { filesManager.relativePathForFileUri(url.orEmpty())?.let(::add) }
        protect((settings.displaySetting.userAvatar as? Avatar.Image)?.url)
        settings.assistants.forEach { assistant ->
            protect((assistant.avatar as? Avatar.Image)?.url)
            protect(assistant.background)
        }
        protect(settings.displaySetting.inputBackgroundPath)
        protect(settings.displaySetting.drawerBackgroundPath)
        protect(settings.displaySetting.userBubbleImagePath)
        protect(settings.displaySetting.assistantBubbleImagePath)
        settings.companionSpaceSetting.photos.forEach { protect(it.uri) }
    }
}

private fun Conversation.replaceImagePaths(paths: Set<String>, filesManager: FilesManager): Conversation {
    val nodes = messageNodes.map { node ->
        val messages = node.messages.map { message -> message.replaceImagePaths(paths, filesManager) }
        if (messages == node.messages) node else node.copy(messages = messages)
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

private fun UIMessage.replaceImagePaths(paths: Set<String>, filesManager: FilesManager): UIMessage {
    val parts = this.parts.map { part ->
        when (part) {
            is UIMessagePart.Image -> if (filesManager.relativePathForFileUri(part.url) in paths) part.copy(url = DELETED_CHAT_IMAGE_URL) else part
            is UIMessagePart.Tool -> {
                val output = part.output.map { outputPart ->
                    if (outputPart is UIMessagePart.Image && filesManager.relativePathForFileUri(outputPart.url) in paths) {
                        outputPart.copy(url = DELETED_CHAT_IMAGE_URL)
                    } else outputPart
                }
                if (output == part.output) part else part.copy(output = output)
            }
            else -> part
        }
    }
    return if (parts == this.parts) this else copy(parts = parts)
}
