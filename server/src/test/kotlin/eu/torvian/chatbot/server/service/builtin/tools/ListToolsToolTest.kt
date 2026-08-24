package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolCatalog
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import eu.torvian.chatbot.common.models.tool.ToolSummary
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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [ListToolsTool].
 *
 * Covers the slim [ToolSummary] output shape (id, name, description, type, isEnabled — without
 * config or schema payloads) and the strict rejection of any input parameter (the tool accepts
 * none).
 */
class ListToolsToolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userId = 7L

    private val now = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun sampleServerBuiltInTool(
        id: Long = 50L,
        name: String = ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME
    ) = ServerBuiltInToolDefinition(
        id = id,
        name = name,
        description = "desc",
        config = buildJsonObject { },
        inputSchema = buildJsonObject { put("type", "object") },
        outputSchema = null,
        isEnabled = true,
        createdAt = now,
        updatedAt = now,
        userId = userId
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `returns slim summaries of the user's tools`() = runTest {
        val toolService = mockk<ToolService>()
        coEvery { toolService.getToolsForUser(userId) } returns listOf(sampleServerBuiltInTool())
        val tool = ListToolsTool(toolService, json)

        val output = assertSuccess(tool.execute(userId, buildJsonObject { }))
        val decoded = json.decodeFromString<List<ToolSummary>>(output)

        assertEquals(1, decoded.size)
        val summary = decoded.first()
        assertEquals(50L, summary.id)
        assertEquals(ServerBuiltInToolCatalog.LIST_AGENT_ROLES_NAME, summary.name)
        assertEquals("desc", summary.description)
        assertTrue(summary.isEnabled)
        // Slim summary: no config/schema payloads are exposed.
        assertTrue(!output.contains("inputSchema"))
        assertTrue(!output.contains("config"))
    }

    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val toolService = mockk<ToolService>()
        val tool = ListToolsTool(toolService, json)

        val result = tool.execute(userId, buildJsonObject { put("include_disabled", true) })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'include_disabled'"))
        coVerify(exactly = 0) { toolService.getToolsForUser(any()) }
    }
}
