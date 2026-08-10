/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.ProactiveMessageSetting

/**
 * Night watch supplies an explicit conversation and must always win. All other trigger sources
 * share the user-selected primary conversation. Invalid restored IDs fail closed and let the
 * caller use its backwards-compatible recent-conversation fallback.
 */
internal fun requestedProactiveConversationId(
    explicitId: Uuid?,
    setting: ProactiveMessageSetting,
): Uuid? = explicitId ?: setting.primaryConversationId
    .takeIf(String::isNotBlank)
    ?.let { value -> runCatching { Uuid.parse(value) }.getOrNull() }
