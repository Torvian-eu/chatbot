package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.project.CloneProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.service.core.error.project.CloneProjectError
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
 * Unit tests for [CloneProjectTool].
 *
 * Covers input validation (required `project_id` + `name`, optional `description`, unknown
 * parameters, accumulated errors), the mapping of the parsed input into a [CloneProjectRequest],
 * the mapping of every [CloneProjectError] to an LLM-readable handler error (including the
 * no-existence-leak `NotFoundOrNotAccessible`), and the full [ProjectDto] JSON output shape.
 */
class CloneProjectToolTest {

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

    private fun clonedProject(id: Long = 9L, name: String = "Copy of Acme Web App") = ProjectDto(
        id = id,
        name = name,
        description = "The flagship web application project",
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        agentRoleIds = setOf(30L, 31L)
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires the project id and name properties`() = runTest {
        val tool = CloneProjectTool(mockk(), json)

        val result = tool.execute(buildJsonObject { put("description", "no ids") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: project_id"))
        assertTrue(error.message.contains("Missing required argument: name"))
    }

    @Test
    fun `clones with required parameters and returns the full DTO JSON`() = runTest {
        val projectService = mockk<ProjectService>()
        coEvery { projectService.cloneProject(userId, 5L, any()) } returns clonedProject().right()
        val tool = CloneProjectTool(projectService, json)

        val output = assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("project_id", 5L)
                    put("name", "Copy of Acme Web App")
                },
                context()
            )
        )
        // clone_project returns the full project JSON, like create_project, so the LLM sees the new
        // project id and the new member role ids.
        val decoded = json.parseToJsonElement(output).jsonObject
        assertEquals(9L, decoded.getValue("id").jsonPrimitive.long)
        assertEquals("Copy of Acme Web App", decoded.getValue("name").jsonPrimitive.content)
        assertEquals(
            setOf(30L, 31L),
            decoded.getValue("agentRoleIds").jsonArray.map { it.jsonPrimitive.long }.toSet()
        )

        coVerify(exactly = 1) {
            projectService.cloneProject(
                userId,
                5L,
                match<CloneProjectRequest> { request ->
                    request.name == "Copy of Acme Web App" && request.description == null
                }
            )
        }
    }

    @Test
    fun `parses an optional description override into the request`() = runTest {
        val projectService = mockk<ProjectService>()
        coEvery { projectService.cloneProject(userId, 5L, any()) } returns clonedProject().right()
        val tool = CloneProjectTool(projectService, json)

        val input = buildJsonObject {
            put("project_id", 5L)
            put("name", "Copy of Acme Web App")
            put("description", "Refreshed copy")
        }
        tool.execute(input, context())

        coVerify(exactly = 1) {
            projectService.cloneProject(
                userId,
                5L,
                match<CloneProjectRequest> { request ->
                    request.name == "Copy of Acme Web App" && request.description == "Refreshed copy"
                }
            )
        }
    }

    /**
     * Verifies every clone-error mapping to its machine-readable handler shape, including the
     * no-existence-leak collapse of NotFound.
     */
    @Test
    fun `maps every clone error to a readable handler error`() = runTest {
        // NotFound collapses to NotFoundOrNotAccessible (no existence leak); the other errors map to
        // OperationFailed with the established project codes.
        val notFound = CloneProjectError.NotFound(5L)
        val operationFailures: Map<CloneProjectError, String> = mapOf(
            CloneProjectError.InvalidName("", "name must not be blank") to "invalid_name",
            CloneProjectError.NameAlreadyExists("Acme") to "name_already_exists",
            CloneProjectError.OwnerInsertFailed("constraint violation") to "owner_insert_failed"
        )

        val projectService = mockk<ProjectService>()
        coEvery { projectService.cloneProject(userId, 5L, any()) } returns notFound.left()
        val tool = CloneProjectTool(projectService, json)
        val notFoundResult = tool.execute(
            buildJsonObject {
                put("project_id", 5L)
                put("name", "Acme")
            },
            context()
        )
        val collapsed = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(notFoundResult.leftOrNull())
        assertTrue(collapsed.message.contains("5"))

        operationFailures.forEach { (serviceError, expectedCode) ->
            val failingService = mockk<ProjectService>()
            coEvery { failingService.cloneProject(userId, 5L, any()) } returns serviceError.left()
            val failingTool = CloneProjectTool(failingService, json)

            val result = failingTool.execute(
                buildJsonObject {
                    put("project_id", 5L)
                    put("name", "Acme")
                },
                context()
            )

            val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
            assertEquals(expectedCode, error.code)
        }
    }

    /**
     * Verifies that all issues are accumulated into one error so the LLM can fix them at once.
     */
    @Test
    fun `accumulates every validation error before failing`() = runTest {
        val tool = CloneProjectTool(mockk(), json)

        val result = tool.execute(
            buildJsonObject {
                put("project_id", "not-an-id")
                put("name", 123)
                put("unknown", true)
            },
            context()
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("3 error(s)"))
        assertTrue(error.message.contains("Argument 'project_id' must be an integer"))
        assertTrue(error.message.contains("Argument 'name' must be a string"))
        assertTrue(error.message.contains("Unknown parameter: 'unknown'"))
    }

    @Test
    fun `does not call the service on validation failure`() = runTest {
        val projectService = mockk<ProjectService>()
        val tool = CloneProjectTool(projectService, json)

        val result = tool.execute(buildJsonObject { put("project_id", 5L) }, context())

        assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        coVerify(exactly = 0) { projectService.cloneProject(any(), any(), any()) }
    }
}