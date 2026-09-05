package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
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

/**
 * Unit tests for [ReadAgentRoleTool].
 *
 * Covers the `role_id` validation, the ownership-checked lookup, the collapse of not-found and
 * not-accessible into one message, and the full [AgentRoleDto] output shape.
 */
class ReadAgentRoleToolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userId = 7L

    private fun sampleRole(id: Long = 1L) = AgentRoleDto(
        id = id,
        name = "writer",
        displayName = "Writer",
        description = "Writes code",
        modelId = 3L,
        modelSettingsId = 4L,
        tools = setOf(5L, 6L),
        spawnableAgentRoleIds = setOf(2L),
        instructions = listOf(
            AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a writer.")
        )
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires the role_id property`() = runTest {
        val tool = ReadAgentRoleTool(mockk(), json)

        val result = tool.execute(userId, buildJsonObject { })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: role_id"))
    }

    @Test
    fun `rejects a non-integer role_id`() = runTest {
        val tool = ReadAgentRoleTool(mockk(), json)

        val result = tool.execute(userId, buildJsonObject { put("role_id", "abc") })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'role_id' must be an integer"))
    }

    @Test
    fun `collapses not-found and not-accessible into one message`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 99L) } returns AgentRoleError.NotFound(99L).left()
        val tool = ReadAgentRoleTool(agentRoleService, json)

        val result = tool.execute(userId, buildJsonObject { put("role_id", 99L) })

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
    }

    @Test
    fun `returns the full role DTO on success`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns sampleRole().right()
        val tool = ReadAgentRoleTool(agentRoleService, json)

        val output = assertSuccess(tool.execute(userId, buildJsonObject { put("role_id", 1L) }))
        val decoded = json.decodeFromString(AgentRoleDto.serializer(), output)

        assertEquals(1L, decoded.id)
        assertEquals("writer", decoded.name)
        assertEquals(setOf(5L, 6L), decoded.tools)
        assertEquals(1, decoded.instructions.size)
        // The DTO serialization carries the per-user disabled flag (read-only exposure).
        assertTrue(output.contains("\"disabled\":false"), "disabled must be present in the output JSON")
    }

    @Test
    fun `returns a disabled role flag in the output`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns sampleRole().copy(disabled = true).right()
        val tool = ReadAgentRoleTool(agentRoleService, json)

        val output = assertSuccess(tool.execute(userId, buildJsonObject { put("role_id", 1L) }))
        val decoded = json.decodeFromString(AgentRoleDto.serializer(), output)

        assertTrue(decoded.disabled)
    }

    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = ReadAgentRoleTool(agentRoleService, json)

        val result = tool.execute(userId, buildJsonObject { put("role_id", 1L); put("limit", 5L) })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'limit'"))
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
    }
}
