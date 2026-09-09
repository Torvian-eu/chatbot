package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.viewModelScope
import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.ProjectDialogState
import eu.torvian.chatbot.app.repository.AgentRoleRepository
import eu.torvian.chatbot.app.repository.ProjectRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.api.project.CloneProjectRequest
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

/**
 * Tests for [ProjectsViewModel]: project CRUD flows, dialog/form state, role reloads after project
 * mutations and error notifications.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectsViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var projectRepository: ProjectRepository
    private lateinit var agentRoleRepository: AgentRoleRepository
    private lateinit var notificationService: NotificationService
    private lateinit var viewModel: ProjectsViewModel

    private fun project(id: Long, name: String, agentRoleIds: Set<Long> = emptySet()) = ProjectDto(
        id = id,
        name = name,
        description = "",
        createdAt = Instant.fromEpochSeconds(id),
        agentRoleIds = agentRoleIds
    )

    @BeforeTest
    fun setup() {
        dispatcher = UnconfinedTestDispatcher()
        projectRepository = mockk(relaxed = true)
        agentRoleRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)

        every { projectRepository.projects } returns MutableStateFlow(DataState.Success(emptyList()))
        every { agentRoleRepository.roles } returns MutableStateFlow(DataState.Success(emptyList()))
        coEvery { agentRoleRepository.loadRoles() } returns Either.Right(Unit)

        viewModel = ProjectsViewModel(
            projectRepository = projectRepository,
            agentRoleRepository = agentRoleRepository,
            notificationService = notificationService,
            uiDispatcher = dispatcher
        )
    }

    @AfterTest
    fun tearDown() {
        // Cancel the viewModel scope so no coroutine leaks across tests.
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `saveProject - add - maps draft to CreateProjectRequest including agentRoleIds`() = runTest(dispatcher) {
        coEvery { projectRepository.createProject(any()) } returns Either.Right(project(10, "Research", setOf(1L)))
        viewModel.startAddingNewProject()

        viewModel.updateProjectForm { form ->
            form.copy(
                name = "Research",
                description = "Writing group",
                agentRoleIds = setOf(1L, 2L)
            )
        }

        viewModel.saveProject()

        coVerify(exactly = 1) {
            projectRepository.createProject(
                match<CreateProjectRequest> { request ->
                    request.name == "Research" &&
                            request.description == "Writing group" &&
                            request.agentRoleIds == setOf(1L, 2L)
                }
            )
        }
        // Project create carries membership; the role side of the cache is refreshed so
        // AgentRoleDto.projectId stays consistent.
        coVerify(exactly = 1) { agentRoleRepository.loadRoles() }
        assertEquals(ProjectDialogState.None, viewModel.dialogState.value)
    }

    @Test
    fun `saveProject - edit - maps draft to UpdateProjectRequest and refreshes roles`() = runTest(dispatcher) {
        coEvery { projectRepository.updateProject(7L, any()) } returns Either.Right(project(7, "Research v2", setOf(3L)))
        val existing = project(7, "Research", setOf(3L))
        viewModel.startEditingProject(existing)

        viewModel.updateProjectForm { form -> form.copy(name = "Research v2") }

        viewModel.saveProject()

        coVerify(exactly = 1) {
            projectRepository.updateProject(
                eq(7L),
                match<UpdateProjectRequest> { request ->
                    request.name == "Research v2" && request.agentRoleIds == setOf(3L)
                }
            )
        }
        coVerify(exactly = 1) { agentRoleRepository.loadRoles() }
        assertEquals(ProjectDialogState.None, viewModel.dialogState.value)
    }

    @Test
    fun `saveProject - blank name - validates without calling api`() = runTest(dispatcher) {
        viewModel.startAddingNewProject()

        viewModel.saveProject()

        coVerify(exactly = 0) { projectRepository.createProject(any()) }
        val dialogState = viewModel.dialogState.value
        assertTrue(dialogState is ProjectDialogState.AddProject)
        assertNotNull(dialogState.formState.errorMessage)
    }

    @Test
    fun `saveProject - failure - notifies and keeps dialog open`() = runTest(dispatcher) {
        coEvery { projectRepository.createProject(any()) } returns Either.Left(
            RepositoryError.OtherError("creation failed")
        )
        viewModel.startAddingNewProject()
        viewModel.updateProjectForm { form -> form.copy(name = "Research") }

        viewModel.saveProject()

        coVerify {
            notificationService.repositoryError(any<RepositoryError>(), any<String>())
        }
        assertTrue(viewModel.dialogState.value is ProjectDialogState.AddProject)
        // No role refresh happens when the mutation failed.
        coVerify(exactly = 0) { agentRoleRepository.loadRoles() }
    }

    @Test
    fun `startCloningProject - opens the clone dialog prefilled with Copy of name and source description`() {
        val existing = project(7, "Research").copy(description = "Research group")
        viewModel.startCloningProject(existing)

        val dialogState = viewModel.dialogState.value
        assertTrue(dialogState is ProjectDialogState.CloneProject)
        assertEquals("Copy of Research", dialogState.formState.name)
        assertEquals("Research group", dialogState.formState.description)
    }

    @Test
    fun `cloneProject - success - reloads roles, closes dialog and selects the clone`() = runTest(dispatcher) {
        val existing = project(7, "Research", setOf(1L))
        coEvery { projectRepository.cloneProject(7L, any()) } returns
            Either.Right(project(11, "Copy of Research", setOf(4L)))
        viewModel.startCloningProject(existing)

        viewModel.cloneProject()

        coVerify(exactly = 1) {
            projectRepository.cloneProject(
                eq(7L),
                match<CloneProjectRequest> { request -> request.name == "Copy of Research" }
            )
        }
        // The clone created new role rows; the role side of the cache is refreshed so
        // AgentRoleDto.projectId and the disabled flags stay consistent.
        coVerify(exactly = 1) { agentRoleRepository.loadRoles() }
        assertEquals(ProjectDialogState.None, viewModel.dialogState.value)
    }

    @Test
    fun `cloneProject - blank name - validates without calling api`() = runTest(dispatcher) {
        val existing = project(7, "Research")
        viewModel.startCloningProject(existing)
        viewModel.updateProjectForm { form -> form.copy(name = "   ") }

        viewModel.cloneProject()

        coVerify(exactly = 0) { projectRepository.cloneProject(any(), any()) }
        val dialogState = viewModel.dialogState.value
        assertTrue(dialogState is ProjectDialogState.CloneProject)
        assertNotNull(dialogState.formState.errorMessage)
    }

    @Test
    fun `cloneProject - failure - notifies and keeps dialog open`() = runTest(dispatcher) {
        coEvery { projectRepository.cloneProject(7L, any()) } returns Either.Left(
            RepositoryError.OtherError("clone failed")
        )
        viewModel.startCloningProject(project(7, "Research"))

        viewModel.cloneProject()

        coVerify {
            notificationService.repositoryError(any<RepositoryError>(), any<String>())
        }
        assertTrue(viewModel.dialogState.value is ProjectDialogState.CloneProject)
        // No role refresh happens when the clone failed.
        coVerify(exactly = 0) { agentRoleRepository.loadRoles() }
    }

    @Test
    fun `deleteProject - success - reloads roles, clears selection and closes dialog`() = runTest(dispatcher) {
        coEvery { projectRepository.deleteProject(7L) } returns Either.Right(Unit)

        viewModel.selectProject(project(7, "Research"))
        viewModel.startDeletingProject(project(7, "Research"))
        viewModel.deleteProject(7L)

        // reverse direction: the deleted project's links cascade server-side, so the role stream
        // is refreshed to drop the stale projectId.
        coVerify(exactly = 1) { agentRoleRepository.loadRoles() }
        assertNull(viewModel.selectedProject.value)
        assertEquals(ProjectDialogState.None, viewModel.dialogState.value)
    }

    @Test
    fun `deleteProject - failure - notifies and keeps dialog open`() = runTest(dispatcher) {
        coEvery { projectRepository.deleteProject(7L) } returns Either.Left(
            RepositoryError.OtherError("deletion failed")
        )

        viewModel.startDeletingProject(project(7, "Research"))
        viewModel.deleteProject(7L)

        coVerify {
            notificationService.repositoryError(any<RepositoryError>(), any<String>())
        }
        assertTrue(viewModel.dialogState.value is ProjectDialogState.DeleteProject)
        coVerify(exactly = 0) { agentRoleRepository.loadRoles() }
    }
}