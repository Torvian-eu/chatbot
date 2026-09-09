package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.AgentRoleError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
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

    /**
     * Fully-populated execution context for handler-level tests: the handlers ignore the session
     * fields, so fixed non-null values keep the fixture simple while matching the real contract.
     */
    private fun context(userId: Long = this.userId): ToolCallExecutionContext =
        ToolCallExecutionContext(
            userId = userId,
            sessionId = 1L,
            sessionName = "Session",
            agentRoleId = 1L
        )

    /**
     * Creates a role fixture carrying the persisted state that the PATCH merge is tested against.
     *
     * @param modelId Persisted model id.
     * @param modelSettingsId Persisted settings id.
     * @param projectId Persisted project membership (null = unassociated).
     * @return The fixture role.
     */
    private fun sampleRole(
        modelId: Long? = 3L,
        modelSettingsId: Long? = 4L,
        projectId: Long? = null
    ) = AgentRoleDto(
        id = 1L,
        name = "writer",
        displayName = "Writer",
        description = "Writes code",
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        tools = setOf(5L, 6L),
        spawnableAgentRoleIds = setOf(2L),
        projectId = projectId,
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

        val result = tool.execute(buildJsonObject { put("name", "x") }, context())

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
                buildJsonObject { put("role_id", 1L); put("model_id", 3L); put("model_settings_id", 4L) },
                context()
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
            tool.execute(buildJsonObject { put("role_id", 1L); put("description", "Renamed description") }, context())
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

        val result = tool.execute(buildJsonObject { put("role_id", 99L); put("name", "x") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `project_id input overrides the persisted project membership`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole(projectId = 50L)
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns
            persisted.copy(projectId = 60L).right()
        val tool = UpdateAgentRoleTool(agentRoleService)

        assertSuccess(
            tool.execute(buildJsonObject { put("role_id", 1L); put("project_id", 60L) }, context())
        )

        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request -> request.projectId == 60L }
            )
        }
    }

    @Test
    fun `omitted project_id preserves the persisted project membership`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole(projectId = 50L)
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns persisted.right()
        val tool = UpdateAgentRoleTool(agentRoleService)

        assertSuccess(
            tool.execute(buildJsonObject { put("role_id", 1L); put("name", "renamed") }, context())
        )

        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> {
                    request -> request.projectId == 50L && request.name == "renamed"
                }
            )
        }
    }

    @Test
    fun `project_id 0 explicitly unassociates the role`() = runTest {
        // 0 is the LLM-facing sentinel for "clear the membership": project ids are always positive
        // database ids, so 0 unambiguously means unassociated rather than a real project.
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole(projectId = 50L)
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns
            persisted.copy(projectId = null).right()
        val tool = UpdateAgentRoleTool(agentRoleService)

        assertSuccess(
            tool.execute(buildJsonObject { put("role_id", 1L); put("project_id", 0L) }, context())
        )

        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request -> request.projectId == null }
            )
        }
    }

    @Test
    fun `explicit null project_id preserves the persisted project membership`() = runTest {
        // Like an omitted value, an explicitly-null project_id must not clear the membership; only
        // the 0 sentinel does. Keeps the patch contract documented alongside the sentinel test.
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole(projectId = 50L)
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns persisted.right()
        val tool = UpdateAgentRoleTool(agentRoleService)

        assertSuccess(
            tool.execute(
                buildJsonObject { put("role_id", 1L); put("project_id", JsonNull) },
                context()
            )
        )

        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request -> request.projectId == 50L }
            )
        }
    }

    @Test
    fun `rejects unknown parameters without touching the persisted role`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = UpdateAgentRoleTool(agentRoleService)

        val result = tool.execute(buildJsonObject { put("role_id", 1L); putJsonArray("tools_plus") { } }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'tools_plus'"))
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `rejects a disabled parameter as unknown`() = runTest {
        // Per Q4(a) the flag is read-only for the LLM: update_agent_role has no `disabled` parameter,
        // and a hallucinated one must be rejected like any other unknown argument (the dedicated
        // PUT /agent-roles/{id}/disabled endpoint is the only surface that changes the flag).
        val agentRoleService = mockk<AgentRoleService>()
        val tool = UpdateAgentRoleTool(agentRoleService)

        val result = tool.execute(buildJsonObject { put("role_id", 1L); put("disabled", true) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'disabled'"))
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `rejects a non-integer role_id`() = runTest {
        val tool = UpdateAgentRoleTool(mockk())

        val result = tool.execute(buildJsonObject { put("role_id", 1.5) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'role_id' must be an integer"))
    }
}
