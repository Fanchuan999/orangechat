package me.rerere.rikkahub.data.files

/** Pure selection rules for chat-media cleanup. Filesystem and database work live in the service. */
enum class ChatMediaCleanupMode { SAFE, PERMANENT }

data class ChatMediaItem(
    val relativePath: String,
    val mimeType: String,
    val createdAtMillis: Long,
    val sizeBytes: Long,
)

fun selectChatMediaCandidates(
    items: List<ChatMediaItem>,
    referencedChatPaths: Set<String>,
    protectedPaths: Set<String>,
    mode: ChatMediaCleanupMode,
    nowMillis: Long,
): List<ChatMediaItem> = items.filter { item ->
    item.mimeType.startsWith("image/") &&
        item.relativePath !in protectedPaths &&
        when (mode) {
            ChatMediaCleanupMode.SAFE -> item.relativePath !in referencedChatPaths
            ChatMediaCleanupMode.PERMANENT -> item.relativePath in referencedChatPaths
        }
}.sortedByDescending { it.createdAtMillis }

enum class ChatMediaTimeRange(val label: String, val maxAgeDays: Long?, val minAgeDays: Long? = null) {
    DAYS_7("近 7 天", 7),
    DAYS_30("近 30 天", 30),
    MONTHS_1_TO_3("1–3 个月", 90, 30),
    MONTHS_3_TO_12("3 个月–1 年", 365, 90),
    YEARS_1_PLUS("1 年以上", null, 365),
    ;

    fun includes(createdAtMillis: Long, nowMillis: Long): Boolean {
        val age = (nowMillis - createdAtMillis).coerceAtLeast(0L)
        return (minAgeDays == null || age >= minAgeDays * DAY_MILLIS) &&
            (maxAgeDays == null || age < maxAgeDays * DAY_MILLIS)
    }

    private companion object { const val DAY_MILLIS = 24L * 60 * 60 * 1000 }
}
