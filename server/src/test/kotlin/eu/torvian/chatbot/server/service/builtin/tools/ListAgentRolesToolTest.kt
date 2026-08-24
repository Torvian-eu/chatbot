package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.AgentRoleService
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
 * Unit tests for [ListAgentRolesTool].
 *
 * Covers the AgentRoleSummary-shaped output (id, name, displayName, description), the empty-list
 * case, and the strict rejection of any input parameter (the tool accepts none).
 */
class ListAgentRolesToolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val userId = 7L

    private fun sampleRole(
        id: Long = 1L,
        name: String = "writer",
        displayName: String? = "Writer",
        description: String = "Writes code"
    ) = AgentRoleDto(
        id = id,
        name = name,
        displayName = displayName,
        description = description,
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
    fun `returns AgentRoleSummary-shaped output for the current user's roles`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getAllRolesForUser(userId) } returns
            listOf(sampleRole(), sampleRole(id = 2L, name = "editor", displayName = null, description = "Edits"))
        val tool = ListAgentRolesTool(agentRoleService, json)

        val output = assertSuccess(tool.execute(userId, buildJsonObject { }))

        assertTrue(output.contains("\"id\":1"))
        assertTrue(output.contains("\"name\":\"writer\""))
        assertTrue(output.contains("\"displayName\":\"Writer\""))
        assertTrue(output.contains("\"description\":\"Writes code\""))
        // Nullable display name stays explicit in the wire shape...
        assertTrue(output.contains("\"displayName\":null"))
        // ...and only summary fields are exposed: no model/settings/tools/instructions leak.
        assertTrue(!output.contains("modelId"))
        assertTrue(!output.contains("spawnableAgentRoleIds"))
    }

    @Test
    fun `returns an empty array when the user has no roles`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getAllRolesForUser(userId) } returns emptyList()
        val tool = ListAgentRolesTool(agentRoleService, json)

        val output = assertSuccess(tool.execute(userId, buildJsonObject { }))

        assertEquals("[]", output)
    }

    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = ListAgentRolesTool(agentRoleService, json)

        val result = tool.execute(userId, buildJsonObject { put("foo", "bar") })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'foo'"))
        coVerify(exactly = 0) { agentRoleService.getAllRolesForUser(any()) }
    }
}
