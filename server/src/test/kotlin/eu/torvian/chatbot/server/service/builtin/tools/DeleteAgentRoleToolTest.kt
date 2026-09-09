package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.AgentRoleService
import eu.torvian.chatbot.server.service.core.error.agent.DeleteAgentRoleError
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
 * Unit tests for [DeleteAgentRoleTool].
 *
 * Covers the `role_id` validation, the success confirmation mentioning the role id, the collapse
 * of not-found/not-accessible (enforced by the role service, surfaced through the tool) into one
 * message, and strict rejection of unknown parameters without touching the service.
 */
class DeleteAgentRoleToolTest {

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

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires the role_id property`() = runTest {
        val tool = DeleteAgentRoleTool(mockk())

        val result = tool.execute(buildJsonObject { }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: role_id"))
    }

    @Test
    fun `rejects a non-integer role_id`() = runTest {
        val tool = DeleteAgentRoleTool(mockk())

        val result = tool.execute(buildJsonObject { put("role_id", "abc") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'role_id' must be an integer"))
    }

    /**
     * Verifies that success returns a plain-text confirmation that mentions the role id.
     */
    @Test
    fun `returns a confirmation mentioning the role id on success`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.deleteRole(userId, 7L) } returns Unit.right()
        val tool = DeleteAgentRoleTool(agentRoleService)

        val output = assertSuccess(tool.execute(buildJsonObject { put("role_id", 7L) }, context()))

        assertTrue(output.contains("Deleted agent role (id: 7)"))
        coVerify(exactly = 1) { agentRoleService.deleteRole(userId, 7L) }
    }

    /**
     * Verifies that a foreign or nonexistent role collapses into one not-found message that does
     * not disclose existence (the role service collapses both cases; the tool surfaces them as one).
     */
    @Test
    fun `collapses not-found and not-accessible into one message`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        coEvery { agentRoleService.deleteRole(userId, 99L) } returns DeleteAgentRoleError.NotFound(99L).left()
        val tool = DeleteAgentRoleTool(agentRoleService)

        val result = tool.execute(buildJsonObject { put("role_id", 99L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
    }

    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val agentRoleService = mockk<AgentRoleService>()
        val tool = DeleteAgentRoleTool(agentRoleService)

        val result = tool.execute(buildJsonObject { put("role_id", 1L); put("cascade", true) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'cascade'"))
        coVerify(exactly = 0) { agentRoleService.deleteRole(any(), any()) }
    }
}