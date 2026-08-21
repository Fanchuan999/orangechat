/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class TaskTicket(
    val taskText: String,
    val selectedFiles: List<String>,
    val workingDirectory: String = ".",
    val constraints: List<String> = emptyList(),
) {
    val prompt: String
        get() = buildString {
            appendLine("Task:")
            appendLine(taskText)
            appendLine("Working directory:")
            appendLine(workingDirectory)
            appendLine("Explicit files:")
            selectedFiles.forEach(::appendLine)
            if (constraints.isNotEmpty()) {
                appendLine("Constraints:")
                constraints.forEach(::appendLine)
            }
        }.trim()
}

@Serializable
enum class CodeHutTaskStatus {
    DRAFT,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNSUPPORTED,
}

@Serializable
data class TaskResultSummary(
    val summary: String,
    val changedFiles: List<String> = emptyList(),
    val verification: String = "",
) {
    fun capped(maxChars: Int): TaskResultSummary {
        require(maxChars > 0) { "maxChars must be positive" }
        return copy(
            summary = summary.take(maxChars),
            changedFiles = changedFiles.take(MAX_CHANGED_FILES).map { it.take(MAX_FILE_CHARS) },
            verification = verification.take(maxChars),
        )
    }

    private companion object {
        const val MAX_CHANGED_FILES = 64
        const val MAX_FILE_CHARS = 512
    }
}

@Serializable
data class CodeHutTask(
    val id: Uuid = Uuid.random(),
    val ticket: TaskTicket,
    val bindingOverrideId: Uuid? = null,
    val status: CodeHutTaskStatus = CodeHutTaskStatus.DRAFT,
    val harnessSessionId: String? = null,
    val result: TaskResultSummary? = null,
)
