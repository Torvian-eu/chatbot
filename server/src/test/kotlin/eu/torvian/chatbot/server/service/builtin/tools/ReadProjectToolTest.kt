package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.service.core.error.project.ProjectError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [ReadProjectTool].
 *
 * Covers the `project_id` validation, the ownership-checked lookup, the collapse of not-found and
 * not-accessible into one message, and the full [ProjectDto] output shape (including `createdAt`
 * and `agentRoleIds`).
 */
class ReadProjectToolTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

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

    private fun sampleProject(id: Long = 1L) = ProjectDto(
        id = id,
        name = "Acme Web App",
        description = "The flagship web application project",
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        agentRoleIds = setOf(5L, 6L)
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires the project_id property`() = runTest {
        val tool = ReadProjectTool(mockk(), json)

        val result = tool.execute(buildJsonObject { }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: project_id"))
    }

    @Test
    fun `rejects a non-integer project_id`() = runTest {
        val tool = ReadProjectTool(mockk(), json)

        val result = tool.execute(buildJsonObject { put("project_id", "abc") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'project_id' must be an integer"))
    }

    /**
     * Verifies that a foreign or nonexistent project collapses into one not-found message that
     * does not disclose existence (id-enumeration guard).
     */
    @Test
    fun `collapses not-found and not-accessible into one message`() = runTest {
        val projectService = mockk<ProjectService>()
        coEvery { projectService.getProjectById(userId, 99L) } returns ProjectError.NotFound(99L).left()
        val tool = ReadProjectTool(projectService, json)

        val result = tool.execute(buildJsonObject { put("project_id", 99L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
    }

    /**
     * Verifies that success returns the full project JSON with the same wire shape as the REST API.
     */
    @Test
    fun `returns the full project DTO on success`() = runTest {
        val projectService = mockk<ProjectService>()
        coEvery { projectService.getProjectById(userId, 1L) } returns sampleProject().right()
        val tool = ReadProjectTool(projectService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { put("project_id", 1L) }, context()))
        val decoded = json.parseToJsonElement(output).jsonObject

        assertEquals(1L, decoded.getValue("id").jsonPrimitive.long)
        assertEquals("Acme Web App", decoded.getValue("name").jsonPrimitive.content)
        assertEquals("The flagship web application project", decoded.getValue("description").jsonPrimitive.content)
        // createdAt is emitted in the REST API's ISO-8601 wire form (shared codec).
        assertEquals("2024-01-01T00:00:00Z", decoded.getValue("createdAt").jsonPrimitive.content)
        assertEquals(
            setOf(5L, 6L),
            decoded.getValue("agentRoleIds").jsonArray.map { it.jsonPrimitive.long }.toSet()
        )
    }

    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val projectService = mockk<ProjectService>()
        val tool = ReadProjectTool(projectService, json)

        val result = tool.execute(buildJsonObject { put("project_id", 1L); put("limit", 5L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'limit'"))
        coVerify(exactly = 0) { projectService.getProjectById(any(), any()) }
    }
}