package me.rerere.rikkahub.data.lounge

import java.time.Duration
import java.time.Instant

data class VisitorLoungeProactiveDirective(
    val friendId: String,
    val topic: String,
    val visibleText: String,
)

/**
 * Parses the intentionally narrow control marker used by the primary assistant after a normal reply.
 * The marker is removed before the reply stays in the ordinary conversation.
 */
object VisitorLoungeProactiveDirectiveParser {
    private val directiveRegex = Regex(
        """\[\[VISIT_LOUNGE:([A-Za-z0-9-]{1,80})\|([^\r\n\]]{1,500})\]\]""",
    )

    fun consume(text: String): VisitorLoungeProactiveDirective? {
        val match = directiveRegex.find(text) ?: return null
        val friendId = match.groupValues[1]
        val topic = match.groupValues[2].trim()
        if (topic.isBlank()) return null
        return VisitorLoungeProactiveDirective(
            friendId = friendId,
            topic = topic,
            visibleText = hideDirectiveMarkers(text),
        )
    }

    fun hideDirectiveMarkers(text: String): String = directiveRegex.replace(text, "").trimEnd()

    fun promptFor(friends: List<FriendPublicRecord>): String {
        val rooms = friends.joinToString(separator = "\n") { friend ->
            "- ${friend.id}: ${friend.displayName.replace(Regex("[\\r\\n]+"), " ").take(80)}"
        }
        return """
            会客室（仅内部控制）：下列房间的主人已明确允许你自主拜访。只有在确实有助于用户时，才可在正常回复的最后附加一条内部标记：
            [[VISIT_LOUNGE:朋友ID|简短且不含普通聊天原文的拜访话题]]
            标记不会展示给用户，且一次回复最多一个。不要提及标记、密钥、MCP 地址；若不需要拜访，完全不要输出标记。
            可用房间：
            $rooms
        """.trimIndent()
    }
}

enum class VisitorLoungeProactiveDecision {
    ALLOW,
    CONSENT_REQUIRED,
    FRIEND_COOLDOWN,
    GLOBAL_DAILY_LIMIT,
}

class VisitorLoungeProactivePolicy(
    private val friendCooldown: Duration = Duration.ofHours(3),
    private val dailyLimit: Int = 3,
) {
    fun evaluate(
        friend: FriendPublicRecord,
        visits: List<VisitorLoungeVisit>,
        now: Instant,
    ): VisitorLoungeProactiveDecision {
        if (friend.consent != VisitorLoungeConsent.ALLOW_PROACTIVE) {
            return VisitorLoungeProactiveDecision.CONSENT_REQUIRED
        }
        val proactiveVisits = visits.filter { visit ->
            visit.mode == VisitorLoungeVisitMode.PROACTIVE
        }
        val cooldownStart = now.minus(friendCooldown)
        if (proactiveVisits.any { visit ->
                visit.friendId == friend.id && !visit.startedAt.isBefore(cooldownStart)
            }
        ) {
            return VisitorLoungeProactiveDecision.FRIEND_COOLDOWN
        }
        val dailyStart = now.minus(Duration.ofHours(24))
        if (proactiveVisits.count { visit -> !visit.startedAt.isBefore(dailyStart) } >= dailyLimit) {
            return VisitorLoungeProactiveDecision.GLOBAL_DAILY_LIMIT
        }
        return VisitorLoungeProactiveDecision.ALLOW
    }
}
