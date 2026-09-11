package me.rerere.rikkahub.data.service

import me.rerere.rikkahub.data.datastore.AutonomousActivitySetting

enum class AutonomousActivityFamily {
    WEB,
    FORUM,
}

class AutonomousActivityFamilyGuard {
    private var claimedFamily: AutonomousActivityFamily? = null

    fun tryClaim(family: AutonomousActivityFamily): Boolean {
        val current = claimedFamily
        return when {
            current == null -> {
                claimedFamily = family
                true
            }

            current == family -> true
            else -> false
        }
    }
}

object AutonomousActivityPolicy {
    fun allowsMcpTool(
        setting: AutonomousActivitySetting,
        serverId: String,
        toolName: String,
    ): Boolean {
        if (!setting.enabled) return false
        val normalizedServerId = serverId.trim()
        val normalizedToolName = toolName.trim()
        if (normalizedServerId.isBlank() || normalizedToolName.isBlank()) return false
        return normalizedServerId !in setting.normalizedDisabledMcpServerIds()
    }
}
