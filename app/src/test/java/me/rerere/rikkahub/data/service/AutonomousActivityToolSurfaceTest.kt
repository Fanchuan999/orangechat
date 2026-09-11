package me.rerere.rikkahub.data.service

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AutonomousActivityToolSurfaceTest {
    @Test
    fun `all tools from an enabled configured MCP server are exposed with their schema and idle authorization`() = runBlocking {
        val serverId = Uuid.random()
        val publishPost = McpTool(name = "publish_post", inputSchema = schema(), needsApproval = true)
        val deletePost = McpTool(name = "delete_post", inputSchema = schema(), needsApproval = false)
        val surface = builder(
            mcp = FakeMcpGateway(listOf(serverId to publishPost, serverId to deletePost)),
        ).build(
            setting = AutonomousActivitySetting(enabled = true),
            allowedMcpServerIds = setOf(serverId),
            webTools = emptyList(),
        )

        val publishTool = surface.tools.single { it.name == ToolNaming.buildMcpToolName(serverId, publishPost.name) }
        val deleteTool = surface.tools.single { it.name == ToolNaming.buildMcpToolName(serverId, deletePost.name) }

        assertEquals(publishPost.inputSchema, publishTool.parameters())
        assertFalse(publishTool.needsApproval)
        assertEquals(deletePost.inputSchema, deleteTool.parameters())
        assertFalse(deleteTool.needsApproval)
    }

    @Test
    fun `idle surface exposes a selected visitor MCP tool like any other configured MCP tool`() = runBlocking {
        val serverId = Uuid.random()
        val visitorMcpTool = McpTool(name = "talk_to_host", inputSchema = schema())
        val surface = builder(mcp = FakeMcpGateway(listOf(serverId to visitorMcpTool))).build(
            setting = enabledFor(serverId, visitorMcpTool.name),
            allowedMcpServerIds = setOf(serverId),
            webTools = emptyList(),
        )

        assertEquals(
            listOf(ToolNaming.buildMcpToolName(serverId, visitorMcpTool.name)),
            surface.tools.map(Tool::name),
        )
    }

    @Test
    fun `one idle opportunity cannot mix web and configured MCP actions`() = runBlocking {
        val serverId = Uuid.random()
        val mcp = FakeMcpGateway(listOf(serverId to McpTool(name = "publish_post", inputSchema = schema())))
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
        val surface = builder(mcp = mcp).build(
            setting = enabledFor(serverId, "publish_post"),
            allowedMcpServerIds = setOf(serverId),
            webTools = listOf(webTool),
        )

        surface.tools.single { it.name == "read_webpage" }.execute(Json.parseToJsonElement("{}"))
        surface.tools.single { it.name == ToolNaming.buildMcpToolName(serverId, "publish_post") }
            .execute(Json.parseToJsonElement("{}"))

        assertEquals(1, webCalls)
        assertEquals(0, mcp.callCount)
    }

    @Test
    fun `same forum family may use multiple selected tool steps`() = runBlocking {
        val serverId = Uuid.random()
        val mcp = FakeMcpGateway(listOf(serverId to McpTool(name = "publish_post", inputSchema = schema())))
        val surface = builder(mcp = mcp).build(
            setting = enabledFor(serverId, "publish_post"),
            allowedMcpServerIds = setOf(serverId),
            webTools = emptyList(),
        )
        val forumTool = surface.tools.single { it.name == ToolNaming.buildMcpToolName(serverId, "publish_post") }

        forumTool.execute(Json.parseToJsonElement("{}"))
        forumTool.execute(Json.parseToJsonElement("{}"))

        assertEquals(2, mcp.callCount)
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
            webTools = emptyList(),
        )

        surface.tools.single { it.name == ToolNaming.buildMcpToolName(serverId, "publish_post") }
            .execute(Json.parseToJsonElement("{}"))

        assertEquals(AutonomousActivityStatus.UNAVAILABLE, store.records.value.single().status)
    }

    private fun builder(
        mcp: FakeMcpGateway = FakeMcpGateway(emptyList()),
        store: FakeActivityStore = FakeActivityStore(),
    ) = AutonomousActivityToolSurfaceBuilder(
        mcpGateway = mcp,
        activityRepository = AutonomousActivityRepository(store),
    )

    private fun enabledFor(serverId: Uuid, toolName: String) = AutonomousActivitySetting(enabled = true)

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

    private class FakeActivityStore : AutonomousActivityRecordStore {
        val records = MutableStateFlow<List<AutonomousActivityRecord>>(emptyList())

        override fun observeRecent(limit: Int): Flow<List<AutonomousActivityRecord>> = records

        override suspend fun insert(record: AutonomousActivityRecord) {
            records.value = records.value + record
        }

        override suspend fun trimToLatest(keep: Int) = Unit
    }
}
