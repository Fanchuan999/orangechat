/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.gadgetbridge.GadgetbridgeReader
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

abstract class DaddyWidgetProvider(
    private val size: DaddyWidgetSize,
) : AppWidgetProvider(), KoinComponent {
    private val appScope: AppScope
        get() = getKoin().get()
    private val settingsStore: SettingsStore
        get() = getKoin().get()
    private val conversationRepository: ConversationRepository
        get() = getKoin().get()

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, manager, appWidgetIds)
        refresh(context, manager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, this::class.java))
            refresh(context, manager, ids)
        }
    }

    private fun refresh(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        if (appWidgetIds.isEmpty()) return
        val fallbackViews = DaddyWidgetRenderer.render(
            context.applicationContext,
            DaddyWidgetSnapshot.default(context.applicationContext.packageName),
            size,
        )
        appWidgetIds.forEach { id -> manager.updateAppWidget(id, fallbackViews) }
        appScope.launch(Dispatchers.IO) {
            val snapshot = runCatching { buildSnapshot(context.applicationContext) }
                .getOrElse { DaddyWidgetSnapshot.default(context.applicationContext.packageName) }
            val views = DaddyWidgetRenderer.render(context.applicationContext, snapshot, size)
            withContext(Dispatchers.Main) {
                appWidgetIds.forEach { id ->
                    manager.updateAppWidget(id, views)
                }
            }
        }
    }

    private suspend fun buildSnapshot(context: Context): DaddyWidgetSnapshot {
        val settings = settingsStore.settingsFlow.first()
        val widgetSetting = settings.companionSpaceSetting.widgetSetting
        val state = buildDaddyWidgetState(
            moodSetting = settings.companionMoodSetting,
            shortLine = widgetSetting.shortLine,
            recentReply = findWidgetRecentAssistantReply(
                primaryConversationId = settings.proactiveMessageSetting.primaryConversationId,
                fallbackAssistantId = settings.assistantId,
            ),
            stepsToday = runCatching { GadgetbridgeReader.readDailySummaries(days = 1).firstOrNull()?.steps }.getOrNull(),
            weather = null,
        )
        return DaddyWidgetSnapshot(
            enabled = widgetSetting.enabled,
            backgroundImageUri = widgetSetting.backgroundImageUri,
            state = state,
            packageName = context.packageName,
        )
    }

    private suspend fun findWidgetRecentAssistantReply(
        primaryConversationId: String,
        fallbackAssistantId: Uuid,
    ): String {
        primaryConversationId
            .takeIf(String::isNotBlank)
            ?.let { value -> runCatching { Uuid.parse(value) }.getOrNull() }
            ?.let { conversationId ->
                val reply = findRecentAssistantReplyInConversation(conversationId)
                if (reply.isNotBlank()) return reply
            }
        return findRecentAssistantReply(fallbackAssistantId)
    }

    private suspend fun findRecentAssistantReplyInConversation(conversationId: Uuid): String = runCatching {
        conversationRepository.getConversationById(conversationId)
            ?.messageNodes
            ?.asReversed()
            ?.asSequence()
            ?.mapNotNull { node -> node.messages.getOrNull(node.selectIndex) }
            ?.firstOrNull { it.role == MessageRole.ASSISTANT }
            ?.toText()
            .orEmpty()
    }.getOrDefault("")

    private suspend fun findRecentAssistantReply(assistantUuid: Uuid): String {
        return runCatching {
            conversationRepository.getRecentConversations(assistantUuid, limit = 3)
                .asSequence()
                .flatMap { it.messageNodes.asReversed().asSequence() }
                .mapNotNull { node -> node.messages.getOrNull(node.selectIndex) }
                .firstOrNull { it.role == MessageRole.ASSISTANT }
                ?.toText()
                .orEmpty()
        }.getOrDefault("")
    }

    companion object {
        const val ACTION_REFRESH = "me.rerere.orangechat.widget.REFRESH"

        fun refreshAll(context: Context) {
            listOf(
                DaddyWidget2x2Provider::class.java,
                DaddyWidget2x4Provider::class.java,
                DaddyWidget4x4Provider::class.java,
            ).forEach { provider ->
                context.sendBroadcast(Intent(context, provider).setAction(ACTION_REFRESH))
            }
        }
    }
}

class DaddyWidget2x2Provider : DaddyWidgetProvider(DaddyWidgetSize.Compact)
class DaddyWidget2x4Provider : DaddyWidgetProvider(DaddyWidgetSize.Medium)
class DaddyWidget4x4Provider : DaddyWidgetProvider(DaddyWidgetSize.Large)

enum class DaddyWidgetSize {
    Compact,
    Medium,
    Large,
}

data class DaddyWidgetSnapshot(
    val enabled: Boolean,
    val backgroundImageUri: String,
    val state: DaddyWidgetState,
    val packageName: String,
) {
    companion object {
        fun default(packageName: String): DaddyWidgetSnapshot = DaddyWidgetSnapshot(
            enabled = true,
            backgroundImageUri = "",
            state = buildDaddyWidgetState(
                moodSetting = me.rerere.rikkahub.data.datastore.CompanionMoodSetting(),
                shortLine = "我在这里。",
                recentReply = "等你打开主聊天窗，我会把最近回复放到这里。",
                stepsToday = null,
                weather = null,
            ),
            packageName = packageName,
        )
    }
}
