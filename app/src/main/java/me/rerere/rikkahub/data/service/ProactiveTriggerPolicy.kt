package me.rerere.rikkahub.data.service

import java.security.MessageDigest

internal enum class ProactiveTriggerKind {
    Scheduled,
    Aggressive,
    NightWatch,
    Explore,
}

internal const val PROACTIVE_REPLY_DUPLICATE_WINDOW_MS = 15 * 60_000L

/** A running night watch owns automatic outreach until it expires or the user disarms it. */
internal fun shouldSuppressForNightWatch(
    kind: ProactiveTriggerKind,
    nightWatchArmed: Boolean,
): Boolean = nightWatchArmed && kind != ProactiveTriggerKind.NightWatch

/** Stores only a normalized digest, never the proactive message text itself. */
internal fun proactiveReplyFingerprint(text: String): String {
    val normalized = text.trim().lowercase().replace(Regex("\\s+"), " ")
    return MessageDigest.getInstance("SHA-256")
        .digest(normalized.encodeToByteArray())
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

internal fun shouldSuppressDuplicateProactiveReply(
    previousFingerprint: String?,
    previousAtMillis: Long,
    candidate: String,
    nowMillis: Long,
): Boolean {
    if (previousFingerprint.isNullOrBlank() || previousAtMillis <= 0L) return false
    if (nowMillis - previousAtMillis !in 0 until PROACTIVE_REPLY_DUPLICATE_WINDOW_MS) return false
    return previousFingerprint == proactiveReplyFingerprint(candidate)
}
