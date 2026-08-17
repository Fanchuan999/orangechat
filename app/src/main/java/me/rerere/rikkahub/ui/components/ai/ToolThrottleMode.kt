/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.data.model.Assistant

internal enum class ToolThrottleMode {
    OFF,
    SMART,
    MANUAL,
}

internal fun Assistant.toolThrottleMode(): ToolThrottleMode = when {
    manualToolSelectionEnabled -> ToolThrottleMode.MANUAL
    smartToolThrottlingEnabled -> ToolThrottleMode.SMART
    else -> ToolThrottleMode.OFF
}

internal fun Assistant.withToolThrottleMode(mode: ToolThrottleMode): Assistant = copy(
    smartToolThrottlingEnabled = mode == ToolThrottleMode.SMART,
    manualToolSelectionEnabled = mode == ToolThrottleMode.MANUAL,
)
