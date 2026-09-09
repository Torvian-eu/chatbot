package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.service.core.error.project.DeleteProjectError
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
 * Unit tests for [DeleteProjectTool].
 *
 * Covers the `project_id` validation, the success confirmation mentioning the project id, the
 * collapse of not-found/not-accessible into one message, and strict rejection of unknown
 * parameters without touching the service.
 */
class DeleteProjectToolTest {

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
    fun `requires the project_id property`() = runTest {
        val tool = DeleteProjectTool(mockk())

        val result = tool.execute(buildJsonObject { }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: project_id"))
    }

    @Test
    fun `rejects a non-integer project_id`() = runTest {
        val tool = DeleteProjectTool(mockk())

        val result = tool.execute(buildJsonObject { put("project_id", "abc") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'project_id' must be an integer"))
    }

    /**
     * Verifies that success returns a plain-text confirmation that mentions the project id.
     */
    @Test
    fun `returns a confirmation mentioning the project id on success`() = runTest {
        val projectService = mockk<ProjectService>()
        coEvery { projectService.deleteProject(userId, 7L) } returns Unit.right()
        val tool = DeleteProjectTool(projectService)

        val output = assertSuccess(tool.execute(buildJsonObject { put("project_id", 7L) }, context()))

        assertTrue(output.contains("Deleted project (id: 7)"))
        coVerify(exactly = 1) { projectService.deleteProject(userId, 7L) }
    }

    /**
     * Verifies that a foreign or nonexistent project collapses into one not-found message that
     * does not disclose existence (id-enumeration guard).
     */
    @Test
    fun `collapses not-found and not-accessible into one message`() = runTest {
        val projectService = mockk<ProjectService>()
        coEvery { projectService.deleteProject(userId, 99L) } returns DeleteProjectError.NotFound(99L).left()
        val tool = DeleteProjectTool(projectService)

        val result = tool.execute(buildJsonObject { put("project_id", 99L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
    }

    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val projectService = mockk<ProjectService>()
        val tool = DeleteProjectTool(projectService)

        val result = tool.execute(buildJsonObject { put("project_id", 1L); put("cascade", true) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'cascade'"))
        coVerify(exactly = 0) { projectService.deleteProject(any(), any()) }
    }
}