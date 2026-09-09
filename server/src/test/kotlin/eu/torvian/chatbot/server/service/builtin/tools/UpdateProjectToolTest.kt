package eu.torvian.chatbot.server.service.builtin.tools

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import eu.torvian.chatbot.server.service.builtin.ToolCallExecutionContext
import eu.torvian.chatbot.server.service.builtin.ServerBuiltInToolHandlerError
import eu.torvian.chatbot.server.service.core.ProjectService
import eu.torvian.chatbot.server.service.core.error.project.ProjectError
import eu.torvian.chatbot.server.service.core.error.project.UpdateProjectError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Unit tests for [UpdateProjectTool].
 *
 * Covers the PATCH semantics (load persisted project, merge only the provided fields, full
 * replacement update with the merged state), the ownership-checked load aborting before the
 * update, every update-error mapping, and input validation.
 */
class UpdateProjectToolTest {

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
     * Creates a project fixture carrying the persisted state that the PATCH merge is tested
     * against.
     *
     * @param name Persisted project name.
     * @param description Persisted description.
     * @param agentRoleIds Persisted member role ids.
     * @return The fixture project.
     */
    private fun sampleProject(
        name: String = "Acme Web App",
        description: String = "The flagship web application project",
        agentRoleIds: Set<Long> = setOf(5L, 6L)
    ) = ProjectDto(
        id = 1L,
        name = name,
        description = description,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        agentRoleIds = agentRoleIds
    )

    private fun assertSuccess(result: Either<ServerBuiltInToolHandlerError, String>): String {
        assertTrue(result.isRight(), "Expected success but got: ${result.leftOrNull()}")
        return assertNotNull(result.getOrNull())
    }

    @Test
    fun `requires the project_id property`() = runTest {
        val tool = UpdateProjectTool(mockk())

        val result = tool.execute(buildJsonObject { put("name", "x") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Missing required argument: project_id"))
    }

    @Test
    fun `rejects a non-integer project_id`() = runTest {
        val tool = UpdateProjectTool(mockk())

        val result = tool.execute(buildJsonObject { put("project_id", 1.5) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Argument 'project_id' must be an integer"))
    }

    /**
     * Verifies the patch merge: provided fields replace, omitted fields (here `agent_role_ids`)
     * are carried over from the persisted project, and the tool returns a one-line summary that
     * mentions the project id and (when known) the name.
     */
    @Test
    fun `merges provided fields over the persisted project`() = runTest {
        val projectService = mockk<ProjectService>()
        val persisted = sampleProject()
        coEvery { projectService.getProjectById(userId, 1L) } returns persisted.right()
        coEvery { projectService.updateProject(userId, 1L, any()) } returns
            persisted.copy(name = "Renamed", description = "New description").right()
        val tool = UpdateProjectTool(projectService)

        val output = assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("project_id", 1L)
                    put("name", "Renamed")
                    put("description", "New description")
                },
                context()
            )
        )
        // The tool returns a concise one-line operation summary, not the full project JSON.
        assertTrue(output.contains("Updated project 'Renamed' (id: 1)"))
        assertTrue(!output.contains("\"id\":"))

        coVerify(exactly = 1) {
            projectService.updateProject(
                userId,
                1L,
                match<UpdateProjectRequest> { request ->
                    request.name == "Renamed" &&
                        request.description == "New description" &&
                        request.agentRoleIds == persisted.agentRoleIds
                }
            )
        }
    }

    /**
     * Verifies that a single provided field is patched while every other field is preserved.
     */
    @Test
    fun `omitted fields preserve the persisted values`() = runTest {
        val projectService = mockk<ProjectService>()
        val persisted = sampleProject()
        coEvery { projectService.getProjectById(userId, 1L) } returns persisted.right()
        coEvery { projectService.updateProject(userId, 1L, any()) } returns
            persisted.copy(description = "Renamed description").right()
        val tool = UpdateProjectTool(projectService)

        assertSuccess(
            tool.execute(
                buildJsonObject { put("project_id", 1L); put("description", "Renamed description") },
                context()
            )
        )

        coVerify(exactly = 1) {
            projectService.updateProject(
                userId,
                1L,
                match<UpdateProjectRequest> { request ->
                    request.description == "Renamed description" &&
                        request.name == persisted.name &&
                        request.agentRoleIds == persisted.agentRoleIds
                }
            )
        }
    }

    /**
     * Verifies that explicitly-null fields, like omitted ones, preserve the persisted values.
     */
    @Test
    fun `explicit null fields preserve the persisted values`() = runTest {
        val projectService = mockk<ProjectService>()
        val persisted = sampleProject()
        coEvery { projectService.getProjectById(userId, 1L) } returns persisted.right()
        coEvery { projectService.updateProject(userId, 1L, any()) } returns persisted.right()
        val tool = UpdateProjectTool(projectService)

        assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("project_id", 1L)
                    put("name", JsonNull)
                    put("description", JsonNull)
                    put("agent_role_ids", JsonNull)
                },
                context()
            )
        )

        coVerify(exactly = 1) {
            projectService.updateProject(
                userId,
                1L,
                match<UpdateProjectRequest> { request ->
                    request.name == persisted.name &&
                        request.description == persisted.description &&
                        request.agentRoleIds == persisted.agentRoleIds
                }
            )
        }
    }

    /**
     * Verifies that an explicit empty string (description) and an empty array (agent_role_ids)
     * clear the fields, per the PATCH contract.
     */
    @Test
    fun `explicit empty string and empty array clear the fields`() = runTest {
        val projectService = mockk<ProjectService>()
        val persisted = sampleProject()
        coEvery { projectService.getProjectById(userId, 1L) } returns persisted.right()
        coEvery { projectService.updateProject(userId, 1L, any()) } returns
            persisted.copy(description = "", agentRoleIds = emptySet()).right()
        val tool = UpdateProjectTool(projectService)

        assertSuccess(
            tool.execute(
                buildJsonObject {
                    put("project_id", 1L)
                    put("description", "")
                    putJsonArray("agent_role_ids") { }
                },
                context()
            )
        )

        coVerify(exactly = 1) {
            projectService.updateProject(
                userId,
                1L,
                match<UpdateProjectRequest> { request ->
                    request.description == "" &&
                        request.agentRoleIds.isEmpty() &&
                        request.name == persisted.name
                }
            )
        }
    }

    /**
     * Verifies that a load not-found aborts before the update is ever called.
     */
    @Test
    fun `collapses a load not-found before calling updateProject`() = runTest {
        val projectService = mockk<ProjectService>()
        coEvery { projectService.getProjectById(userId, 99L) } returns ProjectError.NotFound(99L).left()
        val tool = UpdateProjectTool(projectService)

        val result = tool.execute(buildJsonObject { put("project_id", 99L); put("name", "x") }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
        assertTrue(error.message.contains("not found or not accessible by the current user"))
        coVerify(exactly = 0) { projectService.updateProject(any(), any(), any()) }
    }

    /**
     * Verifies the mapping of the update-service failures: the not-found variant collapses into
     * NotFoundOrNotAccessible and the validation variants surface machine-readable codes.
     */
    @Test
    fun `maps every update error to a readable handler error`() = runTest {
        val persisted = sampleProject()
        val cases = mapOf(
            UpdateProjectError.NotFound(99L) to "NotFoundOrNotAccessible",
            UpdateProjectError.InvalidName("", "name must not be blank") to "invalid_name",
            UpdateProjectError.NameAlreadyExists("Acme") to "name_already_exists",
            UpdateProjectError.RoleNotFound(42L) to "role_not_found",
            UpdateProjectError.RoleInAnotherProject(42L) to "role_in_another_project"
        )
        cases.forEach { (serviceError, expected) ->
            val projectService = mockk<ProjectService>()
            coEvery { projectService.getProjectById(userId, 1L) } returns persisted.right()
            coEvery { projectService.updateProject(userId, 1L, any()) } returns serviceError.left()
            val tool = UpdateProjectTool(projectService)

            val result = tool.execute(buildJsonObject { put("project_id", 1L) }, context())

            if (expected == "NotFoundOrNotAccessible") {
                assertIs<ServerBuiltInToolHandlerError.NotFoundOrNotAccessible>(result.leftOrNull())
            } else {
                val error = assertIs<ServerBuiltInToolHandlerError.OperationFailed>(result.leftOrNull())
                assertEquals(expected, error.code)
            }
        }
    }

    @Test
    fun `rejects unknown parameters without touching the persisted project`() = runTest {
        val projectService = mockk<ProjectService>()
        val tool = UpdateProjectTool(projectService)

        val result = tool.execute(buildJsonObject { put("project_id", 1L); put("roles", 5L) }, context())

        val error = assertIs<ServerBuiltInToolHandlerError.InvalidInput>(result.leftOrNull())
        assertTrue(error.message.contains("Unknown parameter: 'roles'"))
        coVerify(exactly = 0) { projectService.getProjectById(any(), any()) }
        coVerify(exactly = 0) { projectService.updateProject(any(), any(), any()) }
    }
}