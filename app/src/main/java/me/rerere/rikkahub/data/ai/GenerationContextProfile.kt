package me.rerere.rikkahub.data.ai

/**
 * Describes how much of the normal chat request surface is appropriate for a
 * given interaction. A voice call should retain identity and local continuity,
 * but it must not wait for tools, remote recall, or desktop-only conventions.
 */
enum class GenerationContextProfile(
    val maxRecentMessages: Int?,
    val allowsTools: Boolean,
    val allowsExternalMemoryRecall: Boolean,
    val allowsOperationalRules: Boolean,
) {
    Default(
        maxRecentMessages = null,
        allowsTools = true,
        allowsExternalMemoryRecall = true,
        allowsOperationalRules = true,
    ),
    VoiceCall(
        maxRecentMessages = 12,
        allowsTools = false,
        allowsExternalMemoryRecall = false,
        allowsOperationalRules = false,
    ),
}
