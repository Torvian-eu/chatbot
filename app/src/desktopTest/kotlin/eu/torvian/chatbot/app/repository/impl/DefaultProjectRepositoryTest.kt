package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ProjectApi
import eu.torvian.chatbot.common.models.api.project.CloneProjectRequest
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [DefaultProjectRepository]: CRUD operations must keep the reactive [DataState] in sync.
 */
class DefaultProjectRepositoryTest {

    private lateinit var api: ProjectApi
    private lateinit var repository: DefaultProjectRepository

    private fun project(id: Long, name: String, agentRoleIds: Set<Long> = emptySet()) = ProjectDto(
        id = id,
        name = name,
        description = "",
        createdAt = Instant.fromEpochSeconds(id),
        agentRoleIds = agentRoleIds
    )

    @BeforeTest
    fun setup() {
        api = mockk()
        repository = DefaultProjectRepository(api)
    }

    @Test
    fun `loadProjects - success updates state`() = runTest {
        val projects = listOf(project(1, "Research"), project(2, "Writing"))
        coEvery { api.getAllProjects() } returns Either.Right(projects)

        val result = repository.loadProjects()

        assertTrue(result.isRight())
        val state = repository.projects.value
        assertTrue(state is DataState.Success)
        assertEquals(2, state.data.size)
    }

    @Test
    fun `loadProjects - failure updates state to error`() = runTest {
        coEvery { api.getAllProjects() } returns Either.Left(
            ApiResourceError.UnknownError("boom", null)
        )

        val result = repository.loadProjects()

        assertTrue(result.isLeft())
        assertTrue(repository.projects.value is DataState.Error)
    }

    @Test
    fun `loadProjectDetails - upserts an existing project and appends a new one`() = runTest {
        coEvery { api.getAllProjects() } returns Either.Right(listOf(project(1, "Research")))
        repository.loadProjects()

        // Existing entry is replaced with the fresh detail.
        val refreshed = project(1, "Research v2", agentRoleIds = setOf(5L))
        coEvery { api.getProjectById(1L) } returns Either.Right(refreshed)
        repository.loadProjectDetails(1L)
        assertEquals("Research v2", repository.projects.value.dataOrNull?.single()?.name)
        assertEquals(setOf(5L), repository.projects.value.dataOrNull?.single()?.agentRoleIds)

        // Unknown entry is appended.
        val added = project(3, "New")
        coEvery { api.getProjectById(3L) } returns Either.Right(added)
        repository.loadProjectDetails(3L)
        assertEquals(2, repository.projects.value.dataOrNull?.size)
    }

    @Test
    fun `createProject - appends to state with agentRoleIds`() = runTest {
        coEvery { api.getAllProjects() } returns Either.Right(emptyList())
        repository.loadProjects()

        val created = project(10, "Translation", agentRoleIds = setOf(1L, 2L))
        coEvery { api.createProject(any()) } returns Either.Right(created)

        val result = repository.createProject(
            CreateProjectRequest(name = "Translation", agentRoleIds = setOf(1L, 2L))
        )

        assertTrue(result.isRight())
        val state = repository.projects.value
        assertTrue(state is DataState.Success)
        assertEquals(listOf("Translation"), state.data.map { it.name })
        assertEquals(setOf(1L, 2L), state.data.single().agentRoleIds)
    }

    @Test
    fun `updateProject - replaces entry in state`() = runTest {
        coEvery { api.getAllProjects() } returns Either.Right(listOf(project(1, "Research")))
        repository.loadProjects()

        val updated = project(1, "Research v2")
        coEvery { api.updateProject(1L, any()) } returns Either.Right(updated)

        val result = repository.updateProject(
            1L,
            UpdateProjectRequest(name = "Research v2")
        )

        assertTrue(result.isRight())
        val state = repository.projects.value
        assertTrue(state is DataState.Success)
        assertEquals("Research v2", state.data.single().name)
    }

    @Test
    fun `deleteProject - removes entry from state`() = runTest {
        coEvery { api.getAllProjects() } returns Either.Right(listOf(project(1, "Research"), project(2, "Writing")))
        repository.loadProjects()

        coEvery { api.deleteProject(1L) } returns Either.Right(Unit)

        val result = repository.deleteProject(1L)

        assertTrue(result.isRight())
        val state = repository.projects.value
        assertTrue(state is DataState.Success)
        assertEquals(1, state.data.size)
        assertEquals("Writing", state.data.single().name)
    }

    @Test
    fun `cloneProject - appends the clone to state with the copied role ids`() = runTest {
        coEvery { api.getAllProjects() } returns Either.Right(listOf(project(1, "Research")))
        repository.loadProjects()

        val cloned = project(10, "Copy of Research", agentRoleIds = setOf(3L, 4L))
        coEvery { api.cloneProject(1L, any()) } returns Either.Right(cloned)

        val result = repository.cloneProject(1L, CloneProjectRequest(name = "Copy of Research"))

        assertTrue(result.isRight())
        val state = repository.projects.value
        assertTrue(state is DataState.Success)
        assertEquals(2, state.data.size)
        assertEquals("Copy of Research", state.data.last().name)
        assertEquals(setOf(3L, 4L), state.data.last().agentRoleIds)
    }

    @Test
    fun `cloneProject - failed clone leaves state unchanged`() = runTest {
        coEvery { api.getAllProjects() } returns Either.Right(emptyList())
        repository.loadProjects()

        coEvery { api.cloneProject(1L, any()) } returns Either.Left(
            ApiResourceError.UnknownError("boom", null)
        )

        repository.cloneProject(1L, CloneProjectRequest(name = "X"))

        assertTrue(repository.projects.value is DataState.Success)
        assertEquals(0, repository.projects.value.dataOrNull?.size)
    }

    @Test
    fun `failed create leaves state unchanged`() = runTest {
        coEvery { api.getAllProjects() } returns Either.Right(emptyList())
        repository.loadProjects()

        coEvery { api.createProject(any()) } returns Either.Left(
            ApiResourceError.UnknownError("boom", null)
        )

        repository.createProject(CreateProjectRequest(name = "X"))

        assertTrue(repository.projects.value is DataState.Success)
        assertEquals(0, repository.projects.value.dataOrNull?.size)
    }
}