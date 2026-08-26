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
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RemoveAgentRoleInstructionTool].
 *
 * Covers the load-remove-update flow (instruction at the given position is dropped, every other
 * field preserved), input validation (missing parameters, out-of-range position), and the
 * ownership-collapsed not-found.
 */
class RemoveAgentRoleInstructionToolTest {

    private val userId = 7L

    private fun sampleRole() = AgentRoleDto(
        id = 1L,
        name = "writer",
        displayName = "Writer",
        description = "Writes code",
        modelId = 3L,
        modelSettingsId = 4L,
        tools = setOf(5L, 6L),
        spawnableAgentRoleIds = setOf(2L),
        instructions = listOf(
            AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a writer."),
            AgentInstructionDto(AgentInstructionTypes.CUSTOM, "Style", "Write clean code."),
            AgentInstructionDto(AgentInstructionTypes.MAIN, "Context", "AGENTS.md content.")
        )
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `removes the instruction at the given position preserving every other field`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val persisted = sampleRole()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns persisted.right()
        coEvery { agentRoleService.updateRole(userId, 1L, any()) } returns persisted.right()
        val tool = RemoveAgentRoleInstructionTool(agentRoleService)

        val output = assertSuccess(
            tool.execute(
                userId,
                buildJsonObject {
                    put("role_id", 1L)
                    put("position", 1L)
                }
            )
        )

        // The tool returns a concise one-line operation summary naming the removed instruction,
        // not the full role JSON (the full role is available via read_agent_role).
        assertTrue(output.contains("Removed instruction (type=custom, name=Style) at 0-based position 1"))
        assertTrue(!output.contains("\"id\":1"))

        // The instruction at index 1 is dropped; the remaining two keep their order and every
        // other role field is carried over unchanged.
        coVerify(exactly = 1) {
            agentRoleService.updateRole(
                userId,
                1L,
                match<UpdateAgentRoleRequest> { request ->
                    request.instructions.size == 2 &&
                        request.instructions[0] == persisted.instructions[0] &&
                        request.instructions[1] == persisted.instructions[2] &&
                        request.name == persisted.name &&
                        request.modelId == persisted.modelId &&
                        request.toolIds == persisted.tools &&
                        request.spawnableAgentRoleIds == persisted.spawnableAgentRoleIds
                }
            )
        }
    }

    @Test
    fun `requires role_id and position`() = runTest {
        val tool = RemoveAgentRoleInstructionTool(mockk())

        val result = tool.execute(userId, buildJsonObject { put("role_id", 1L) })

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: position"))
    }

    @Test
    fun `rejects an out-of-range position without updating`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        // position == size is one past the last valid index; -1 is below the first.
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns sampleRole().right()
        val tool = RemoveAgentRoleInstructionTool(agentRoleService)

        val tooHigh = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                put("position", 3L)
            }
        )
        val tooHighError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(tooHigh.leftOrNull())
        assertTrue(tooHighError.message.contains("position"))

        val negative = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                put("position", -1L)
            }
        )
        val negativeError = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(negative.leftOrNull())
        assertTrue(negativeError.message.contains("position"))

        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `rejects removal from an empty instruction list with a clear message`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 1L) } returns
            sampleRole().copy(instructions = emptyList()).right()
        val tool = RemoveAgentRoleInstructionTool(agentRoleService)

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                put("position", 0L)
            }
        )

        // The empty list is special-cased: the generic bounds message would otherwise read
        // "between 0 and -1 (inclusive)", which is technically correct but confusing.
        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("has no instructions to remove"))
        assertTrue(!error.message.contains("-1"))
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `collapses not-found and not-accessible`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.getRoleById(userId, 99L) } returns AgentRoleError.NotFound(99L).left()
        val tool = RemoveAgentRoleInstructionTool(agentRoleService)

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 99L)
                put("position", 0L)
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }

    @Test
    fun `rejects unknown parameters without touching the persisted role`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = RemoveAgentRoleInstructionTool(agentRoleService)

        val result = tool.execute(
            userId,
            buildJsonObject {
                put("role_id", 1L)
                put("position", 0L)
                put("index", 0L)
            }
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'index'"))
        coVerify(exactly = 0) { agentRoleService.getRoleById(any(), any()) }
        coVerify(exactly = 0) { agentRoleService.updateRole(any(), any(), any()) }
    }
}
