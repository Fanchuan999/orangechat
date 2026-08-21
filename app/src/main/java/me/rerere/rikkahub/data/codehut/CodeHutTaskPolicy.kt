/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.codehut

object CodeHutTaskPolicy {
    private const val MAX_TASK_CHARS = 8_000
    private const val MAX_PATH_CHARS = 512
    private const val MAX_CONSTRAINT_CHARS = 2_000
    private val forbiddenDaddyContext = Regex(
        "(?i)(ombre|conversation|assistant|lorebook|prompt\\s+injection|" +
            "external\\s+memory|full\\s+chat\\s+history|" +
            "(?:mood|desire|proactive)\\s+(?:state|context|message))",
    )

    fun createTicket(
        taskText: String,
        selectedFiles: List<String>,
        workingDirectory: String = ".",
        constraints: List<String> = emptyList(),
    ): TaskTicket {
        val normalizedTask = taskText.trim()
        require(normalizedTask.isNotEmpty()) { "taskText must not be blank" }
        require(normalizedTask.length <= MAX_TASK_CHARS) { "taskText is too long" }
        require(!forbiddenDaddyContext.containsMatchIn(normalizedTask)) {
            "taskText contains Daddy conversation context"
        }

        val normalizedFiles = selectedFiles.map(::normalizeRelativePath).distinct()
        require(normalizedFiles.isNotEmpty()) { "at least one selected file is required" }

        val normalizedDirectory = normalizeRelativePath(workingDirectory)
        val normalizedConstraints = constraints.map { constraint ->
            val value = constraint.trim()
            require(value.isNotEmpty()) { "constraints must not contain blank values" }
            require(value.length <= MAX_CONSTRAINT_CHARS) { "constraint is too long" }
            require(!forbiddenDaddyContext.containsMatchIn(value)) {
                "constraint contains Daddy conversation context"
            }
            value
        }.distinct()

        return TaskTicket(
            taskText = normalizedTask,
            selectedFiles = normalizedFiles,
            workingDirectory = normalizedDirectory,
            constraints = normalizedConstraints,
        )
    }

    fun validate(ticket: TaskTicket): TaskTicket = createTicket(
        taskText = ticket.taskText,
        selectedFiles = ticket.selectedFiles,
        workingDirectory = ticket.workingDirectory,
        constraints = ticket.constraints,
    )

    private fun normalizeRelativePath(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        require(normalized.isNotEmpty()) { "path must not be blank" }
        require(normalized.length <= MAX_PATH_CHARS) { "path is too long" }
        require(!normalized.startsWith('/')) { "absolute paths are not allowed" }
        require(!normalized.matches(Regex("^[A-Za-z]:($|/)"))) {
            "absolute paths are not allowed"
        }
        require(normalized.split('/').none { it == ".." }) {
            "parent paths are not allowed"
        }
        return normalized
    }
}
