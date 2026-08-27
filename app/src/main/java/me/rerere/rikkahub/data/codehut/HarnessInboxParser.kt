/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

enum class HarnessInboxTaskState(val wireName: String) {
    QUEUED("queued"),
    RUNNING("running"),
    UNKNOWN("unknown"),
}

data class HarnessInboxTask(
    val taskId: String,
    val title: String,
    val createdAt: String,
    val state: HarnessInboxTaskState,
    val statusSummary: String = "",
)

data class HarnessInboxEvent(
    val type: String,
    val text: String = "",
)

/**
 * Parses only the event sequence confirmed by the local Harness smoke test.
 * An unrecognised event never upgrades a task to a confirmation state.
 */
class HarnessInboxParser {
    fun parse(events: List<HarnessInboxEvent>): List<HarnessInboxTask> {
        val parsed = mutableListOf<MutableTask>()
        var turnOpen = false
        var current: MutableTask? = null

        events.forEach { event ->
            when (event.type) {
                "turn/start" -> turnOpen = true
                "user/message" -> parseHeader(event.text)?.let { header ->
                    current = MutableTask(
                        taskId = header.taskId,
                        title = header.title,
                        createdAt = header.createdAt,
                        state = if (turnOpen) HarnessInboxTaskState.RUNNING else HarnessInboxTaskState.QUEUED,
                    ).also(parsed::add)
                }

                "assistant/message" -> current?.summary = event.text.compact(160)
                "turn/end" -> {
                    if (current?.state == HarnessInboxTaskState.RUNNING) current?.finished = true
                    turnOpen = false
                }
            }
        }

        return parsed
            .filterNot { it.finished }
            .takeLast(MAX_ACTIVE_TASKS)
            .map { it.toImmutable() }
    }

    private fun parseHeader(text: String): TaskHeader? {
        if (!text.contains("【Daddy任务】")) return null
        val id = TASK_ID.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (id.isBlank()) return null
        return TaskHeader(
            taskId = id,
            createdAt = CREATED_AT.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty(),
            title = TITLE.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                .ifBlank { "Daddy 转达的任务" }
                .take(80),
        )
    }

    private data class TaskHeader(val taskId: String, val createdAt: String, val title: String)

    private data class MutableTask(
        val taskId: String,
        val title: String,
        val createdAt: String,
        val state: HarnessInboxTaskState,
        var summary: String = "",
        var finished: Boolean = false,
    ) {
        fun toImmutable() = HarnessInboxTask(taskId, title, createdAt, state, summary)
    }

    private companion object {
        const val MAX_ACTIVE_TASKS = 20
        val TASK_ID = Regex("(?:^|\\n)task_id:\\s*([^\\r\\n]+)")
        val CREATED_AT = Regex("(?:^|\\n)created_at:\\s*([^\\r\\n]+)")
        val TITLE = Regex("(?:^|\\n)title:\\s*([^\\r\\n]+)")
    }
}

private fun String.compact(limit: Int): String = trim().replace(Regex("\\s+"), " ").take(limit)
