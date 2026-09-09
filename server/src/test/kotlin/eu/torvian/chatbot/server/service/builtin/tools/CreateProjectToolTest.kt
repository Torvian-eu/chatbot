package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.service.core.error.project.CreateProjectError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [CreateProjectTool].
 *
 * Covers input validation (required `name`, optional `description`/`agent_role_ids`, unknown
 * parameters, accumulated errors), the mapping of the parsed input into a [CreateProjectRequest]
 * (including the empty defaults), the mapping of every [CreateProjectError] to an LLM-readable
 * handler error, and the full [ProjectDto] JSON output shape.
 */
class CreateProjectToolTest {

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

    private fun createdProject(id: Long = 9L, name: String = "Acme Web App") = ProjectDto(
        id = id,
        name = name,
        description = "The flagship web application project",
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        agentRoleIds = setOf(5L, 6L)
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires the name property`() = runTest {
        val tool = CreateProjectTool(mockk(), json)

        val result = tool.execute(buildJsonObject { put("description", "no name") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: name"))
    }

    /**
     * Verifies the empty defaults (Q2-A: omitted `agent_role_ids` → empty set) and that success
     * returns the full project JSON (explicit request), including the server-generated id,
     * creation time, and attached member role ids.
     */
    @Test
    fun `creates a project with default optional fields and returns the full DTO JSON`() = runTest {
        val projectService = mockk<ProjectService>()
        coEvery { projectService.createProject(userId, any()) } returns createdProject().right()
        val tool = CreateProjectTool(projectService, json)

        val output = assertSuccess(
            tool.execute(buildJsonObject { put("name", "Acme Web App") }, context())
        )
        // create_project returns the full project JSON (explicit request) unlike the other mutating
        // tools that return one-line summaries.
        val decoded = json.parseToJsonElement(output).jsonObject
        assertEquals(9L, decoded.getValue("id").jsonPrimitive.long)
        assertEquals("Acme Web App", decoded.getValue("name").jsonPrimitive.content)
        assertEquals("2024-01-01T00:00:00Z", decoded.getValue("createdAt").jsonPrimitive.content)
        assertEquals(
            setOf(5L, 6L),
            decoded.getValue("agentRoleIds").jsonArray.map { it.jsonPrimitive.long }.toSet()
        )

        coVerify(exactly = 1) {
            projectService.createProject(
                userId,
                match<CreateProjectRequest> { request ->
                    request.name == "Acme Web App" &&
                        request.description == "" &&
                        request.agentRoleIds.isEmpty()
                }
            )
        }
    }

    /**
     * Verifies that present optional values are parsed and forwarded into the request.
     */
    @Test
    fun `parses optional description and agent role ids into the request`() = runTest {
        val projectService = mockk<ProjectService>()
        coEvery { projectService.createProject(userId, any()) } returns createdProject().right()
        val tool = CreateProjectTool(projectService, json)

        val input = buildJsonObject {
            put("name", "Acme Web App")
            put("description", "The flagship web application project")
            putJsonArray("agent_role_ids") { add(JsonPrimitive(5L)); add(JsonPrimitive(6L)) }
        }
        tool.execute(input, context())

        coVerify(exactly = 1) {
            projectService.createProject(
                userId,
                match<CreateProjectRequest> { request ->
                    request.name == "Acme Web App" &&
                        request.description == "The flagship web application project" &&
                        request.agentRoleIds == setOf(5L, 6L)
                }
            )
        }
    }

    /**
     * Verifies every create-error mapping to its machine-readable handler code.
     */
    @Test
    fun `maps every create error to a readable handler error`() = runTest {
        val cases = mapOf(
            CreateProjectError.InvalidName("", "name must not be blank") to "invalid_name",
            CreateProjectError.NameAlreadyExists("Acme") to "name_already_exists",
            CreateProjectError.RoleNotFound(42L) to "role_not_found",
            CreateProjectError.RoleInAnotherProject(42L) to "role_in_another_project",
            CreateProjectError.OwnerInsertFailed("constraint violation") to "owner_insert_failed"
        )
        cases.forEach { (serviceError, expectedCode) ->
            val projectService = mockk<ProjectService>()
            coEvery { projectService.createProject(userId, any()) } returns serviceError.left()
            val tool = CreateProjectTool(projectService, json)

            val result = tool.execute(buildJsonObject { put("name", "Acme") }, context())

            val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
            assertEquals(expectedCode, error.code)
        }
    }

    /**
     * Verifies that all issues are accumulated into one error so the LLM can fix them at once.
     */
    @Test
    fun `accumulates every validation error before failing`() = runTest {
        val tool = CreateProjectTool(mockk(), json)

        val result = tool.execute(
            buildJsonObject {
                put("name", 123)
                put("agent_role_ids", "oops")
                put("unknown", true)
            },
            context()
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("3 error(s)"))
        assertTrue(error.message.contains("Argument 'name' must be a string"))
        assertTrue(error.message.contains("Argument 'agent_role_ids' must be an array of integers"))
        assertTrue(error.message.contains("Unknown parameter: 'unknown'"))
    }

    @Test
    fun `rejects a non-integer role id inside the agent_role_ids array`() = runTest {
        val tool = CreateProjectTool(mockk(), json)

        val result = tool.execute(
            buildJsonObject {
                put("name", "Acme")
                putJsonArray("agent_role_ids") { add(JsonPrimitive("not-an-id")) }
            },
            context()
        )

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'agent_role_ids[0]' must be an integer"))
    }

    @Test
    fun `does not call the service on validation failure`() = runTest {
        val projectService = mockk<ProjectService>()
        val tool = CreateProjectTool(projectService, json)

        val result = tool.execute(buildJsonObject { put("unknown", true) }, context())

        assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        coVerify(exactly = 0) { projectService.createProject(any(), any()) }
    }
}