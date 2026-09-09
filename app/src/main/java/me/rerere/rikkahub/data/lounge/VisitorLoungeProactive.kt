package me.rerere.rikkahub.data.lounge

import java.time.Duration
import java.time.Instant

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
