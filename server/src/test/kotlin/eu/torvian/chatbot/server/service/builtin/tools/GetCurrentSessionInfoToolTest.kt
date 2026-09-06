package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [GetCurrentSessionInfoTool].
 *
 * Covers the parameterless validation, the session/role identity output shape (including the
 * display-name omission rule), the explicit not-found/not-accessible error when the session's
 * selected role cannot be resolved, and the absence of the deferred `message_turn_count`/
 * `token_count` keys. The execution context is fully populated (see [ToolCallExecutionContext]), so
 * a missing session cannot occur by construction.
 */
class GetCurrentSessionInfoToolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userId = 7L

    /**
     * Fully-populated execution context carrying the session and role identity; tests override the
     * agent role id to exercise the not-found/not-accessible failure path.
     *
     * @param sessionId Session id to place in the context.
     * @param sessionName Session name to place in the context.
     * @param agentRoleId Agent role id to place in the context.
     * @return The context passed to [GetCurrentSessionInfoTool.execute].
     */
    private fun context(
        sessionId: Long = 1L,
        sessionName: String = "Session",
        agentRoleId: Long = 7L
    ) = ToolCallExecutionContext(
        userId = userId,
        sessionId = sessionId,
        sessionName = sessionName,
        agentRoleId = agentRoleId
    )

    private fun sampleRole(
        id: Long = 7L,
        name: String = "writer",
        displayName: String? = "Writer"
    ) = AgentRoleDto(
        id = id,
        name = name,
        displayName = displayName,
        description = "Writes code",
        modelId = 3L,
        modelSettingsId = 4L,
        tools = setOf(5L, 6L),
        spawnableAgentRoleIds = setOf(2L),
        instructions = emptyList()
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `rejects unknown parameters without consulting the role service`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = GetCurrentSessionInfoTool(agentRoleService, json)

        val result = tool.execute(buildJsonObject { put("verbose", true) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'verbose'"))
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
    }

    @Test
    fun `returns the session and selected role identity including the display name`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 7L) } returns sampleRole().right()
        val tool = GetCurrentSessionInfoTool(agentRoleService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { }, context()))
        val decoded = json.parseToJsonElement(output).jsonObject

        assertEquals(1L, decoded.getValue("session_id").jsonPrimitive.long)
        assertEquals("Session", decoded.getValue("session_name").jsonPrimitive.content)
        assertEquals(7L, decoded.getValue("agent_role_id").jsonPrimitive.long)
        assertEquals("writer", decoded.getValue("agent_role_name").jsonPrimitive.content)
        assertEquals("Writer", decoded.getValue("agent_role_display_name").jsonPrimitive.content)
        coVerify(exactly = 1) { agentRoleService.getRoleById(userId, 7L) }
    }

    @Test
    fun `omits the display name when it is null`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 7L) } returns sampleRole(displayName = null).right()
        val tool = GetCurrentSessionInfoTool(agentRoleService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { }, context()))
        val decoded = json.parseToJsonElement(output).jsonObject

        assertEquals(7L, decoded.getValue("agent_role_id").jsonPrimitive.long)
        assertEquals("writer", decoded.getValue("agent_role_name").jsonPrimitive.content)
        assertTrue(!decoded.containsKey("agent_role_display_name"), "null display name must be omitted")
    }

    @Test
    fun `omits the display name when it is blank`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 7L) } returns sampleRole(displayName = "   ").right()
        val tool = GetCurrentSessionInfoTool(agentRoleService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { }, context()))
        val decoded = json.parseToJsonElement(output).jsonObject

        assertEquals("writer", decoded.getValue("agent_role_name").jsonPrimitive.content)
        assertTrue(!decoded.containsKey("agent_role_display_name"), "blank display name must be omitted")
    }

    @Test
    fun `fails when the referenced role is missing or not accessible`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 99L) } returns AgentRoleError.NotFound(99L).left()
        val tool = GetCurrentSessionInfoTool(agentRoleService, json)

        val result = tool.execute(buildJsonObject { }, context(agentRoleId = 99L))

        // The tool reports incomplete role identity explicitly instead of emitting nulls: this tool
        // is only meaningful inside a real session with a resolvable role.
        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
        coVerify(exactly = 1) { agentRoleService.getRoleById(userId, 99L) }
    }

    @Test
    fun `does not emit the deferred message_turn_count or token_count keys`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 7L) } returns sampleRole().right()
        val tool = GetCurrentSessionInfoTool(agentRoleService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { }, context()))
        val decoded = json.parseToJsonElement(output).jsonObject

        assertTrue(!decoded.containsKey("message_turn_count"))
        assertTrue(!decoded.containsKey("token_count"))
    }
}