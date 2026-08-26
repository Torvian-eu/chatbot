package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [UpdateAgentRoleTool].
 *
 * Covers the PATCH semantics (load persisted role, merge only the provided fields, full-replacement
 * update with the merged state), the ownership-checked load, and input validation.
 */
class UpdateAgentRoleToolTest {

    private val userId = 7L

    private fun sampleRole(
        modelId: Long? = 3L,
        modelSettingsId: Long? = 4L
    ) = AgentRoleDto(
        id = 1L,
        name = "writer",
        displayName = "Writer",
        description = "Writes code",
        modelId = modelId,
        modelSettingsId = modelSettingsId,
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
        val tool = UpdateAgentRoleTool(mockk())

        val result = tool.execute(userId, buildJsonObject { put("name", "x") })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: role_id"))
    }

    @Test
    fun `merges provided fields over the persisted role`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        // The persisted role was created model-less; the update sets model/settings later.
        val persisted = sampleRole(modelId = null, modelSettingsId = null)
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns
            sampleRole(modelId = 3L, modelSettingsId = 4L).right()
        val tool = UpdateAgentRoleTool(agentRoleService)

        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject { put("role_id", 1L); put("model_id", 3L); put("model_settings_id", 4L) }
            )
        )
        // The tool returns a concise one-line operation summary, not the full role JSON, to save tokens.
        assertTrue(output.contains("Updated agent role 'writer' (id: 1)"))
        assertTrue(!output.contains("\"id\":1"))

        // The merged update request preserves every omitted field: name, description, tools,
        // spawnable roles, and instructions are carried over from the persisted role.
        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request ->
                    request.name == persisted.name &&
                        request.displayName == persisted.displayName &&
                        request.description == persisted.description &&
                        request.modelId == 3L &&
                        request.modelSettingsId == 4L &&
                        request.toolIds == persisted.tools &&
                        request.spawnableAgentRoleIds == persisted.spawnableAgentRoleIds &&
                        request.instructions == persisted.instructions
                }
            )
        }
    }

    @Test
    fun `patches a single field without touching the rest`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns
            persisted.copy(description = "Renamed description").right()
        val tool = UpdateAgentRoleTool(agentRoleService)

        assertSuccess(
            tool.execute(userId, buildJsonObject { put("role_id", 1L); put("description", "Renamed description") })
        )

        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request ->
                    request.description == "Renamed description" &&
                        request.name == persisted.name &&
                        request.modelId == persisted.modelId &&
                        request.toolIds == persisted.tools
                }
            )
        }
    }

    @Test
    fun `collapses not-found and not-accessible`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 99L) } returns AgentRoleError.NotFound(99L).left()
        val tool = UpdateAgentRoleTool(agentRoleService)

        val result = tool.execute(userId, buildJsonObject { put("role_id", 99L); put("name", "x") })

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `rejects unknown parameters without touching the persisted role`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = UpdateAgentRoleTool(agentRoleService)

        val result = tool.execute(userId, buildJsonObject { put("role_id", 1L); putJsonArray("tools_plus") { } })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'tools_plus'"))
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `rejects a non-integer role_id`() = runTest {
        val tool = UpdateAgentRoleTool(mockk())

        val result = tool.execute(userId, buildJsonObject { put("role_id", 1.5) })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'role_id' must be an integer"))
    }
}
