package me.rerere.rikkahub.data.service

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.datastore.CompanionDeadline
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.withDeadline
import me.rerere.rikkahub.data.datastore.withTodoistSyncFailure
import me.rerere.rikkahub.data.datastore.withTodoistSyncSuccess
import me.rerere.rikkahub.data.datastore.withTodoistSyncUnknown
import kotlin.uuid.Uuid

/** A bounded, local-only preview of one deadline's Todoist task. */
data class TodoistDeadlineDraft(
    val deadlineId: String,
    val revision: Long,
    val operationKey: String,
    val previewHash: String,
    val content: String,
    val description: String,
    val dueAtEpochMillis: Long,
)

object TodoistDeadlineDrafts {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun create(deadline: CompanionDeadline, zone: ZoneId = ZoneId.systemDefault()): TodoistDeadlineDraft {
        val content = "${deadline.type.trim()}: ${deadline.title.trim()}".take(MAX_CONTENT_LENGTH)
        val steps = deadline.steps
            .sortedBy { it.order }
            .joinToString("\n") { step ->
                "- [${if (step.completed) "x" else " "}] ${step.title.trim()}"
            }
            .take(MAX_DESCRIPTION_LENGTH)
        val due = Instant.ofEpochMilli(deadline.dueAtEpochMillis)
            .atZone(zone)
            .format(dateFormatter)
        val description = buildString {
            append("截止：")
            append(due)
            if (deadline.note.isNotBlank()) {
                append("\n备注：")
                append(deadline.note.trim().take(MAX_NOTE_LENGTH))
            }
            if (steps.isNotBlank()) {
                append("\n拆解：\n")
                append(steps)
            }
        }.take(MAX_DESCRIPTION_LENGTH)
        val operationKey = "${deadline.id}:${deadline.draftRevision}"
        val hash = sha256("$operationKey\n$content\n$description\n${deadline.dueAtEpochMillis}")
        return TodoistDeadlineDraft(
            deadlineId = deadline.id.toString(),
            revision = deadline.draftRevision,
            operationKey = operationKey,
            previewHash = hash,
            content = content,
            description = description,
            dueAtEpochMillis = deadline.dueAtEpochMillis,
        )
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private const val MAX_CONTENT_LENGTH = 120
    private const val MAX_DESCRIPTION_LENGTH = 1_000
    private const val MAX_NOTE_LENGTH = 240
}

data class TodoistWriteResult(
    val taskId: String? = null,
    val errorCategory: TodoistWriteErrorCategory? = null,
)

enum class TodoistWriteErrorCategory {
    NOT_CONNECTED,
    TOOL_UNAVAILABLE,
    TOOL_SCHEMA_UNVERIFIED,
    REMOTE_REJECTED,
    RESULT_UNVERIFIABLE,
    NETWORK,
    UNKNOWN,
}

/**
 * A configured Todoist MCP tool together with the server it belongs to.
 *
 * Keeping this small type independent from [McpManager] makes the permission boundary testable
 * without connecting to a real MCP server.
 */
internal data class TodoistMcpToolCandidate(
    val serverId: Uuid,
    val serverName: String,
    val tool: McpTool,
)

/**
 * The only MCP contract that this feature is allowed to use for a Todoist write.
 *
 * A generic `create_task` name is not sufficient: an unverified tool can reject unknown fields,
 * or silently ignore an idempotency key and create duplicate tasks after a local crash/retry.
 */
internal data class TodoistTaskWriteContract(
    val serverId: Uuid,
    val toolName: String,
    private val acceptedFields: Set<String>,
) {
    fun accepts(field: String): Boolean = field in acceptedFields

    companion object {
        private val requiredFields = setOf(
            "content",
            "description",
            "due_datetime",
            "idempotency_key",
        )

        fun resolve(candidates: List<TodoistMcpToolCandidate>): TodoistTaskWriteContract? =
            candidates.firstNotNullOfOrNull { candidate ->
                val schema = candidate.tool.inputSchema as? InputSchema.Obj ?: return@firstNotNullOfOrNull null
                val supportedFields = schema.properties
                    .filter { (_, definition) ->
                        (definition as? JsonObject)
                            ?.get("type")
                            ?.let { it as? JsonPrimitive }
                            ?.content == "string"
                    }
                    .keys
                if (
                    candidate.serverName.contains("todoist", ignoreCase = true) &&
                    candidate.tool.name.equals("create_task", ignoreCase = true) &&
                    supportedFields.containsAll(requiredFields)
                ) {
                    TodoistTaskWriteContract(
                        serverId = candidate.serverId,
                        toolName = candidate.tool.name,
                        acceptedFields = supportedFields,
                    )
                } else {
                    null
                }
            }

        fun argumentsFor(draft: TodoistDeadlineDraft): JsonObject = buildJsonObject {
            put("content", draft.content)
            put("description", draft.description)
            put("due_datetime", Instant.ofEpochMilli(draft.dueAtEpochMillis).toString())
            put("idempotency_key", draft.operationKey)
        }
    }
}

/** MCP tools sometimes return JSON text rather than structured content, so accept Todoist's v1 IDs too. */
internal object TodoistTaskResultParser {
    private val quotedJsonId = Regex("\\\"id\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,128})\\\"")
    private val plainId = Regex("\\bid\\s*[=:]\\s*([A-Za-z0-9_-]{1,128})")

    fun extractTaskId(parts: List<UIMessagePart>): String? {
        val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }
        return quotedJsonId.find(text)?.groupValues?.getOrNull(1)
            ?: plainId.find(text)?.groupValues?.getOrNull(1)
    }
}

class TodoistMcpGateway(
    private val mcpManager: McpManager,
    private val settingsStore: SettingsStore,
) {
    suspend fun createTask(draft: TodoistDeadlineDraft): TodoistWriteResult {
        val settings = settingsStore.settingsFlow.first()
        val candidates = mcpManager.getAllAvailableTools()
            .mapNotNull { (serverId, tool) ->
                val server = settings.mcpServers.firstOrNull { it.id == serverId }
                    ?: return@mapNotNull null
                TodoistMcpToolCandidate(
                    serverId = serverId,
                    serverName = server.commonOptions.name,
                    tool = tool,
                )
            }
        val todoistCandidates = candidates.filter {
            it.serverName.contains("todoist", ignoreCase = true)
        }
        if (todoistCandidates.isEmpty()) {
            return TodoistWriteResult(errorCategory = TodoistWriteErrorCategory.TOOL_UNAVAILABLE)
        }
        val contract = TodoistTaskWriteContract.resolve(todoistCandidates)
            ?: return TodoistWriteResult(errorCategory = TodoistWriteErrorCategory.TOOL_SCHEMA_UNVERIFIED)
        if (!mcpManager.isClientConnected(contract.serverId)) {
            return TodoistWriteResult(errorCategory = TodoistWriteErrorCategory.NOT_CONNECTED)
        }
        val args = TodoistTaskWriteContract.argumentsFor(draft)
        return try {
            val result = mcpManager.callToolDetailed(
                serverId = contract.serverId,
                toolName = contract.toolName,
                args = args,
                redactArgumentsInLog = true,
                requireExistingConnection = true,
            )
            if (result.isError) {
                return if (result.unavailable) {
                    TodoistWriteResult(errorCategory = TodoistWriteErrorCategory.NOT_CONNECTED)
                } else {
                    TodoistWriteResult(errorCategory = TodoistWriteErrorCategory.REMOTE_REJECTED)
                }
            }
            TodoistTaskResultParser.extractTaskId(result.parts)?.let { TodoistWriteResult(taskId = it) }
                ?: TodoistWriteResult(errorCategory = TodoistWriteErrorCategory.RESULT_UNVERIFIABLE)
        } catch (_: java.io.IOException) {
            TodoistWriteResult(errorCategory = TodoistWriteErrorCategory.NETWORK)
        } catch (_: Exception) {
            TodoistWriteResult(errorCategory = TodoistWriteErrorCategory.UNKNOWN)
        }
    }

}

class TodoistDeadlineSyncService(
    private val settingsStore: SettingsStore,
    private val gateway: TodoistMcpGateway,
) {
    private val mutex = Mutex()

    suspend fun sync(
        deadlineId: Uuid,
        expectedRevision: Long,
        expectedPreviewHash: String,
    ): TodoistSyncResult = mutex.withLock {
        val deadline = settingsStore.settingsFlow.first().companionSpaceSetting.deadlines
            .firstOrNull { it.id == deadlineId }
            ?: return@withLock TodoistSyncResult.StaleDraft
        val draft = TodoistDeadlineDrafts.create(deadline)
        if (draft.revision != expectedRevision || draft.previewHash != expectedPreviewHash) {
            return@withLock TodoistSyncResult.StaleDraft
        }
        if (!TodoistDraftGate.canWrite(deadline)) return@withLock TodoistSyncResult.NotConfirmed
        deadline.todoistTaskId?.let { return@withLock TodoistSyncResult.Synced(it) }

        val result = gateway.createTask(draft)
        val current = settingsStore.settingsFlow.first().companionSpaceSetting.deadlines
            .firstOrNull { it.id == deadlineId }
            ?: return@withLock TodoistSyncResult.StaleDraft
        if (current.draftRevision != expectedRevision) return@withLock TodoistSyncResult.StaleDraft
        return@withLock if (result.taskId != null) {
            settingsStore.update { settings ->
                settings.copy(
                    companionSpaceSetting = settings.companionSpaceSetting.withDeadline(
                        current.withTodoistSyncSuccess(result.taskId),
                    ),
                )
            }
            TodoistSyncResult.Synced(result.taskId)
        } else if (result.errorCategory == TodoistWriteErrorCategory.RESULT_UNVERIFIABLE) {
            settingsStore.update { settings ->
                settings.copy(
                    companionSpaceSetting = settings.companionSpaceSetting.withDeadline(
                        current.withTodoistSyncUnknown(),
                    ),
                )
            }
            TodoistSyncResult.Failed(result.errorCategory)
        } else {
            settingsStore.update { settings ->
                settings.copy(
                    companionSpaceSetting = settings.companionSpaceSetting.withDeadline(
                        current.withTodoistSyncFailure(),
                    ),
                )
            }
            TodoistSyncResult.Failed(result.errorCategory ?: TodoistWriteErrorCategory.UNKNOWN)
        }
    }
}

sealed interface TodoistSyncResult {
    data class Synced(val taskId: String) : TodoistSyncResult
    data class Failed(val category: TodoistWriteErrorCategory) : TodoistSyncResult
    data object NotConfirmed : TodoistSyncResult
    data object StaleDraft : TodoistSyncResult
}
