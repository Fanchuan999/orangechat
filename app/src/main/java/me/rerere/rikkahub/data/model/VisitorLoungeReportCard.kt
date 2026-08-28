package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.lounge.VisitorLoungeRedactor
import me.rerere.rikkahub.data.lounge.VisitorLoungeVisitStatus
import me.rerere.rikkahub.utils.JsonInstant

const val VISITOR_LOUNGE_REPORT_TOOL_NAME = "visitor_lounge_visit"

@Serializable
data class VisitorLoungeReportCard(
    val visitId: String,
    val friendDisplayName: String,
    val status: String,
    val summary: String,
)

fun VisitorLoungeReportCard.toToolPart(): UIMessagePart.Tool = UIMessagePart.Tool(
    toolCallId = "visitor_lounge_$visitId",
    toolName = VISITOR_LOUNGE_REPORT_TOOL_NAME,
    input = JsonInstant.encodeToString(
        copy(
            friendDisplayName = VisitorLoungeRedactor.redact(friendDisplayName).take(100),
            status = status.take(50),
            summary = VisitorLoungeRedactor.redact(summary).take(300),
        ),
    ),
    output = listOf(UIMessagePart.Text("会客室访问：$friendDisplayName · $status")),
)

fun UIMessage.isVisitorLoungeReportCard(): Boolean =
    role == MessageRole.ASSISTANT &&
        parts.size == 1 &&
        (parts.singleOrNull() as? UIMessagePart.Tool)?.toolName == VISITOR_LOUNGE_REPORT_TOOL_NAME

fun List<UIMessage>.withoutVisitorLoungeReportCards(): List<UIMessage> = filterNot(UIMessage::isVisitorLoungeReportCard)

fun UIMessagePart.Tool.toVisitorLoungeReportCard(): VisitorLoungeReportCard? {
    if (toolName != VISITOR_LOUNGE_REPORT_TOOL_NAME) return null
    return runCatching { JsonInstant.decodeFromString<VisitorLoungeReportCard>(input) }.getOrNull()
}
