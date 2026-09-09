package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.ProjectService
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
 * Unit tests for [ListProjectsTool].
 *
 * Covers the complete [ProjectDto] projection (including `createdAt` and `agentRoleIds`), the
 * empty-list case, the user-scoping description, and strict rejection of input parameters.
 */
class ListProjectsToolTest {

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

    /**
     * Creates a project fixture with configurable properties.
     *
     * @param id Project identifier.
     * @param name Project name.
     * @param description Free-form description.
     * @param createdAt Creation timestamp.
     * @param agentRoleIds Member agent-role identifiers.
     * @return A project DTO suitable for list-tool assertions.
     */
    private fun sampleProject(
        id: Long = 1L,
        name: String = "Acme Web App",
        description: String = "The flagship web application project",
        createdAt: Instant = Instant.parse("2024-01-01T00:00:00Z"),
        agentRoleIds: Set<Long> = setOf(5L, 6L)
    ) = ProjectDto(
        id = id,
        name = name,
        description = description,
        createdAt = createdAt,
        agentRoleIds = agentRoleIds
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    /**
     * Verifies that every project is emitted with exactly the [ProjectDto] wire shape, including
     * the ISO-8601 creation time and the member role ids.
     */
    @Test
    fun `returns all project DTO properties for every project`() = runTest {
        val projectService = mockk<ProjectService>()
        coEvery { projectService.getAllProjectsForUser(userId) } returns listOf(
            sampleProject(
                id = 1L,
                name = "Acme Web App",
                description = "The flagship web application project",
                agentRoleIds = setOf(5L, 6L)
            ),
            sampleProject(
                id = 2L,
                name = "Acme Mobile App",
                description = "",
                agentRoleIds = emptySet()
            )
        )
        val tool = ListProjectsTool(projectService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { }, context()))
        val projects = json.parseToJsonElement(output).jsonArray
        val expectedKeys = setOf("id", "name", "description", "createdAt", "agentRoleIds")

        assertEquals(2, projects.size)

        val first = projects[0].jsonObject
        assertEquals(expectedKeys, first.keys)
        assertEquals(1L, first.getValue("id").jsonPrimitive.long)
        assertEquals("Acme Web App", first.getValue("name").jsonPrimitive.content)
        assertEquals("The flagship web application project", first.getValue("description").jsonPrimitive.content)
        // createdAt is emitted in the REST API's ISO-8601 wire form (shared codec).
        assertEquals("2024-01-01T00:00:00Z", first.getValue("createdAt").jsonPrimitive.content)
        assertEquals(
            setOf(5L, 6L),
            first.getValue("agentRoleIds").jsonArray.map { it.jsonPrimitive.long }.toSet()
        )

        val second = projects[1].jsonObject
        assertEquals(expectedKeys, second.keys)
        assertEquals(2L, second.getValue("id").jsonPrimitive.long)
        assertEquals("Acme Mobile App", second.getValue("name").jsonPrimitive.content)
        assertEquals("", second.getValue("description").jsonPrimitive.content)
        assertEquals(emptyList(), second.getValue("agentRoleIds").jsonArray)
    }

    /**
     * Verifies that no projects are still represented by the exact empty JSON array.
     */
    @Test
    fun `returns an empty array when the user has no projects`() = runTest {
        val projectService = mockk<ProjectService>()
        coEvery { projectService.getAllProjectsForUser(userId) } returns emptyList()
        val tool = ListProjectsTool(projectService, json)

        val output = assertSuccess(tool.execute(buildJsonObject { }, context()))

        assertEquals("[]", output)
    }

    /**
     * Verifies that the tool description documents user scoping and the returned properties.
     */
    @Test
    fun `describes user scoping and output properties`() {
        val tool = ListProjectsTool(mockk(), json)

        assertTrue(tool.description.contains("owned by the current user"))
        assertTrue(tool.description.contains("agent role ids"))
    }

    /**
     * Verifies that unknown parameters fail validation before the user-scoped service is called.
     */
    @Test
    fun `rejects unknown parameters without calling the service`() = runTest {
        val projectService = mockk<ProjectService>()
        val tool = ListProjectsTool(projectService, json)

        val result = tool.execute(buildJsonObject { put("foo", "bar") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'foo'"))
        coVerify(exactly = 0) { projectService.getAllProjectsForUser(any()) }
    }
}