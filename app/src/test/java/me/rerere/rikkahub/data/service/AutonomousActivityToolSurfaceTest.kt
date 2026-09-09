package me.rerere.rikkahub.data.service

import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.mcp.McpTool
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.data.ai.tools.ToolNaming
import me.rerere.rikkahub.data.datastore.AutonomousActivitySetting
import me.rerere.rikkahub.data.datastore.AutonomousMcpToolPermission
import me.rerere.rikkahub.data.lounge.VisitorLoungeStartResult
import me.rerere.rikkahub.data.lounge.VisitorLoungeToolFriend
import me.rerere.rikkahub.data.lounge.VisitorLoungeToolGateway
import me.rerere.rikkahub.data.lounge.VisitorLoungeVisit
import me.rerere.rikkahub.data.lounge.VisitorLoungeVisitMode
import me.rerere.rikkahub.data.lounge.VisitorLoungeVisitStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AutonomousActivityToolSurfaceTest {
    @Test
    fun `only explicitly selected MCP tools are exposed with their schema and idle authorization`() = runBlocking {
        val serverId = Uuid.random()
        val selected = McpTool(name = "publish_post", inputSchema = schema(), needsApproval = true)
        val unselected = McpTool(name = "delete_post", inputSchema = schema(), needsApproval = false)
        val surface = builder(
            mcp = FakeMcpGateway(listOf(serverId to selected, serverId to unselected)),
        ).build(
            setting = AutonomousActivitySetting(
                enabled = true,
                allowedMcpTools = listOf(AutonomousMcpToolPermission(serverId.toString(), selected.name)),
            ),
            allowedMcpServerIds = setOf(serverId),
            sourceConversationId = "conversation-1",
            webTools = emptyList(),
        )

        val forumTool = surface.tools.single { it.name == ToolNaming.buildMcpToolName(serverId, selected.name) }

        assertEquals(selected.inputSchema, forumTool.parameters())
        assertFalse(forumTool.needsApproval)
        assertTrue(surface.tools.none { it.name == ToolNaming.buildMcpToolName(serverId, unselected.name) })
    }

    @Test
    fun `one idle opportunity cannot mix web forum and visitor lounge actions`() = runBlocking {
        val serverId = Uuid.random()
        val mcp = FakeMcpGateway(listOf(serverId to McpTool(name = "publish_post", inputSchema = schema())))
        val lounge = FakeLoungeGateway(listOf(VisitorLoungeToolFriend("friend-1", "Alice")))
        var webCalls = 0
        val webTool = Tool(
            name = "read_webpage",
            description = "Read public webpage",
            parameters = { schema() },
            execute = {
                webCalls += 1
                listOf(UIMessagePart.Text("read ok"))
            },
        )
        val surface = builder(mcp = mcp, lounge = lounge).build(
            setting = enabledFor(serverId, "publish_post"),
            allowedMcpServerIds = setOf(serverId),
            sourceConversationId = "conversation-1",
            webTools = listOf(webTool),
        )

        surface.tools.single { it.name == "read_webpage" }.execute(Json.parseToJsonElement("{}"))
        surface.tools.single { it.name == ToolNaming.buildMcpToolName(serverId, "publish_post") }
            .execute(Json.parseToJsonElement("{}"))
        surface.tools.single { it.name == "visit_visitor_lounge_proactive" }
            .execute(Json.parseToJsonElement("""{"friend_id":"friend-1","topic":"hello"}"""))

        assertEquals(1, webCalls)
        assertEquals(0, mcp.callCount)
        assertEquals(0, lounge.proactiveCallCount)
    }

    @Test
    fun `same forum family may use multiple selected tool steps`() = runBlocking {
        val serverId = Uuid.random()
        val mcp = FakeMcpGateway(listOf(serverId to McpTool(name = "publish_post", inputSchema = schema())))
        val surface = builder(mcp = mcp).build(
            setting = enabledFor(serverId, "publish_post"),
            allowedMcpServerIds = setOf(serverId),
            sourceConversationId = "conversation-1",
            webTools = emptyList(),
        )
        val forumTool = surface.tools.single { it.name == ToolNaming.buildMcpToolName(serverId, "publish_post") }

        forumTool.execute(Json.parseToJsonElement("{}"))
        forumTool.execute(Json.parseToJsonElement("{}"))

        assertEquals(2, mcp.callCount)
    }

    @Test
    fun `visitor lounge tool is absent without a policy eligible friend`() = runBlocking {
        val surface = builder(lounge = FakeLoungeGateway(emptyList())).build(
            setting = AutonomousActivitySetting(enabled = true),
            allowedMcpServerIds = emptySet(),
            sourceConversationId = "conversation-1",
            webTools = emptyList(),
        )

        assertTrue(surface.tools.none { it.name == "visit_visitor_lounge_proactive" })
    }

    @Test
    fun `a forum server that disconnects before execution is recorded as unavailable`() = runBlocking {
        val serverId = Uuid.random()
        val store = FakeActivityStore()
        val mcp = FakeMcpGateway(
            tools = listOf(serverId to McpTool(name = "publish_post", inputSchema = schema())),
            nextResult = AutonomousActivityMcpCallResult(
                parts = listOf(UIMessagePart.Text("The MCP server is not currently connected.")),
                isError = true,
                unavailable = true,
            ),
        )
        val surface = builder(mcp = mcp, store = store).build(
            setting = enabledFor(serverId, "publish_post"),
            allowedMcpServerIds = setOf(serverId),
            sourceConversationId = "conversation-1",
            webTools = emptyList(),
        )

        surface.tools.single { it.name == ToolNaming.buildMcpToolName(serverId, "publish_post") }
            .execute(Json.parseToJsonElement("{}"))

        assertEquals(AutonomousActivityStatus.UNAVAILABLE, store.records.value.single().status)
    }

    private fun builder(
        mcp: FakeMcpGateway = FakeMcpGateway(emptyList()),
        lounge: FakeLoungeGateway = FakeLoungeGateway(emptyList()),
        store: FakeActivityStore = FakeActivityStore(),
    ) = AutonomousActivityToolSurfaceBuilder(
        mcpGateway = mcp,
        visitorLoungeGateway = lounge,
        activityRepository = AutonomousActivityRepository(store),
    )

    private fun enabledFor(serverId: Uuid, toolName: String) = AutonomousActivitySetting(
        enabled = true,
        allowedMcpTools = listOf(AutonomousMcpToolPermission(serverId.toString(), toolName)),
    )

    private fun schema() = InputSchema.Obj(properties = buildJsonObject { put("body", "string") })

    private class FakeMcpGateway(
        private val tools: List<Pair<Uuid, McpTool>>,
        private val nextResult: AutonomousActivityMcpCallResult =
            AutonomousActivityMcpCallResult(parts = listOf(UIMessagePart.Text("forum ok")), isError = false),
    ) : AutonomousActivityMcpGateway {
        var callCount = 0

        override fun availableTools(serverIds: Set<Uuid>): List<Pair<Uuid, McpTool>> =
            tools.filter { (serverId, _) -> serverId in serverIds }

        override suspend fun callTool(
            serverId: Uuid,
            toolName: String,
            args: JsonObject,
        ): AutonomousActivityMcpCallResult {
            callCount += 1
            return nextResult
        }
    }

    private class FakeLoungeGateway(
        private val eligibleFriends: List<VisitorLoungeToolFriend>,
    ) : VisitorLoungeToolGateway {
        var proactiveCallCount = 0

        override suspend fun savedFriends(): List<VisitorLoungeToolFriend> = eligibleFriends

        override suspend fun proactiveFriends(): List<VisitorLoungeToolFriend> = eligibleFriends

        override suspend fun startManual(
            sourceConversationId: String?,
            friendId: String,
            topic: String,
        ): VisitorLoungeStartResult = error("not used")

        override suspend fun startProactive(
            sourceConversationId: String,
            friendId: String,
            topic: String,
        ): VisitorLoungeStartResult {
            proactiveCallCount += 1
            return VisitorLoungeStartResult.Started(
                VisitorLoungeVisit(
                    id = "visit-1",
                    friendId = friendId,
                    sourceConversationId = sourceConversationId,
                    mode = VisitorLoungeVisitMode.PROACTIVE,
                    status = VisitorLoungeVisitStatus.QUEUED,
                    topic = topic,
                    startedAt = Instant.ofEpochMilli(1_000),
                ),
            )
        }
    }

    private class FakeActivityStore : AutonomousActivityRecordStore {
        val records = MutableStateFlow<List<AutonomousActivityRecord>>(emptyList())

        override fun observeRecent(limit: Int): Flow<List<AutonomousActivityRecord>> = records

        override suspend fun insert(record: AutonomousActivityRecord) {
            records.value = records.value + record
        }

        override suspend fun trimToLatest(keep: Int) = Unit
    }
}
