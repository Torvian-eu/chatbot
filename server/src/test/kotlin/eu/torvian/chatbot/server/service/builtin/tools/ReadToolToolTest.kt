package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.ToolService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [ReadToolTool].
 *
 * Covers the `tool_id` validation, the ownership check against the user's full tool set (covering
 * MCP, worker built-in, operator, and server built-in tools), the collapse of not-found and
 * not-accessible into one message, and the full polymorphic [ToolDefinition] output.
 */
class ReadToolToolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userId = 7L

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires the tool_id property`() = runTest {
        val tool = ReadToolTool(mockk(), json)

        val result = tool.execute(userId, buildJsonObject { })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: tool_id"))
    }

    @Test
    fun `collapses not-found and not-accessible`() = runTest {
        val toolService = mockk<ToolService>()
        val owned = ServerBuiltInToolDefinition(
            id = 50L,
            name = ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME,
            description = "desc",
            config = buildJsonObject { },
            inputSchema = buildJsonObject { put("type", "object") },
            outputSchema = null,
            isEnabled = true,
            createdAt = now,
            updatedAt = now,
            userId = userId,
            builtInToolName = ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME
        )
        coEvery { toolService.getToolsForUser(userId) } returns listOf(owned)
        val tool = ReadToolTool(toolService, json)

        val result = tool.execute(userId, buildJsonObject { put("tool_id", 999L) })

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
    }

    @Test
    fun `returns the full polymorphic definition for an owned tool`() = runTest {
        val toolService = mockk<ToolService>()
        val operatorTool = OperatorToolDefinition(
            id = 70L,
            name = "spawn_agent",
            description = "Spawns an agent",
            config = buildJsonObject { },
            inputSchema = buildJsonObject { put("type", "object") },
            outputSchema = null,
            isEnabled = true,
            createdAt = now,
            updatedAt = now,
            userId = userId
        )
        coEvery { toolService.getToolsForUser(userId) } returns listOf(operatorTool)
        val tool = ReadToolTool(toolService, json)

        val output = assertSuccess(tool.execute(userId, buildJsonObject { put("tool_id", 70L) }))

        // The full polymorphic definition is emitted through the sealed ToolDefinition serializer
        // (same contract as the REST tool routes) and carries the subtype fields.
        assertTrue(output.contains("OperatorToolDefinition"))
        assertTrue(output.contains("\"userId\":$userId"))
    }

    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val toolService = mockk<ToolService>()
        val tool = ReadToolTool(toolService, json)

        val result = tool.execute(userId, buildJsonObject { put("tool_id", 1L); put("expand", true) })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'expand'"))
        coVerify(exactly = 0) { toolService.getToolsForUser(any()) }
    }
}
