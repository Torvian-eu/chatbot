package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.viewModelScope
import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.AgentRoleDialogState
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.FormMode
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
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

/**
 * Tests for [AgentRolesViewModel]: form-draft to request mapping (preset-based, U-28/U-29/U-36),
 * save/delete flows, catalog loading and error notifications.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRolesViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: AgentRoleRepository
    private lateinit var presetRepository: ModelPresetRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var settingsRepository: ModelSettingsRepository
    private lateinit var toolRepository: ToolRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var notificationService: NotificationService
    private lateinit var viewModel: AgentRolesViewModel

    private fun role(id: Long, name: String, modelPresetId: Long? = 3L) = AgentRoleDto(
        id = id,
        name = name,
        displayName = null,
        description = "",
        modelId = 1L,
        modelSettingsId = 2L,
        modelPresetId = modelPresetId,
        tools = emptySet(),
        instructions = listOf(
            AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a writer")
        )
    )

    @BeforeTest
    fun setup() {
        dispatcher = UnconfinedTestDispatcher()
        repository = mockk(relaxed = true)
        presetRepository = mockk(relaxed = true)
        modelRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        toolRepository = mockk(relaxed = true)
        projectRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)

        every { repository.roles } returns MutableStateFlow(DataState.Success(emptyList()))
        every { presetRepository.presets } returns
            MutableStateFlow<DataState<RepositoryError, List<ModelPresetDto>>>(DataState.Success(emptyList()))
        every { modelRepository.models } returns MutableStateFlow(DataState.Success(emptyList()))
        every { settingsRepository.allSettings } returns MutableStateFlow(DataState.Success(emptyList()))
        every { toolRepository.tools } returns MutableStateFlow(DataState.Success(emptyList()))
        every { projectRepository.projects } returns MutableStateFlow(DataState.Success(emptyList()))
        // Stub every catalog load: the ViewModel maps each result's Left branch to a notification, and
        // a relaxed mock would answer with a placeholder Either whose value cannot be mapped.
        coEvery { repository.loadRoles() } returns Either.Right(Unit)
        coEvery { presetRepository.loadPresets() } returns Either.Right(Unit)
        coEvery { modelRepository.loadModels() } returns Either.Right(Unit)
        coEvery { settingsRepository.loadAllSettings() } returns Either.Right(Unit)
        coEvery { toolRepository.loadTools() } returns Either.Right(Unit)
        coEvery { projectRepository.loadProjects() } returns Either.Right(Unit)

        viewModel = AgentRolesViewModel(
            agentRoleRepository = repository,
            modelPresetRepository = presetRepository,
            modelRepository = modelRepository,
            modelSettingsRepository = settingsRepository,
            toolRepository = toolRepository,
            projectRepository = projectRepository,
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
    fun `loadRolesAndCatalogs - also loads the preset catalog`() = runTest(dispatcher) {
        viewModel.loadRolesAndCatalogs()

        coVerify(exactly = 1) { repository.loadRoles() }
        coVerify(exactly = 1) { presetRepository.loadPresets() }
        coVerify(exactly = 1) { modelRepository.loadModels() }
        coVerify(exactly = 1) { settingsRepository.loadAllSettings() }
        coVerify(exactly = 1) { toolRepository.loadTools() }
        coVerify(exactly = 1) { projectRepository.loadProjects() }
    }

    @Test
    fun `saveRole - add - maps draft to CreateAgentRoleRequest`() = runTest(dispatcher) {
        coEvery { repository.createRole(any()) } returns Either.Right(role(10, "writer"))
        viewModel.startAddingNewRole()

        viewModel.updateRoleForm { form ->
            form.copy(
                name = "writer",
                displayName = "Writer",
                description = "Creative writing",
                modelPresetId = 3L,
                toolIds = setOf(10L, 20L),
                instructions = listOf(
                    AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a writer"),
                    AgentInstructionDto(AgentInstructionTypes.CUSTOM, "Tone", "Be concise")
                )
            )
        }

        viewModel.saveRole()

        coVerify(exactly = 1) {
            repository.createRole(
                match<CreateAgentRoleRequest> { request ->
                    request.name == "writer" &&
                            request.displayName == "Writer" &&
                            request.modelPresetId == 3L &&
                            request.toolIds == setOf(10L, 20L) &&
                            request.instructions.size == 2 &&
                            request.instructions[1].type == AgentInstructionTypes.CUSTOM
                }
            )
        }
    }

    @Test
    fun `saveRole - add - a preset-less draft still reaches the repository`() = runTest(dispatcher) {
        // U-36/RQ-2: the preset is optional on the client too, so the save must not be blocked and
        // must send a null preset id.
        coEvery { repository.createRole(any()) } returns Either.Right(role(10, "incomplete", modelPresetId = null))
        viewModel.startAddingNewRole()
        viewModel.updateRoleForm { form -> form.copy(name = "incomplete") }

        viewModel.saveRole()

        coVerify(exactly = 1) {
            repository.createRole(
                match<CreateAgentRoleRequest> { request ->
                    request.name == "incomplete" && request.modelPresetId == null
                }
            )
        }
        assertEquals(AgentRoleDialogState.None, viewModel.dialogState.value)
    }

    @Test
    fun `saveRole - blank name - validates without calling the api`() = runTest(dispatcher) {
        viewModel.startAddingNewRole()

        viewModel.saveRole()

        coVerify(exactly = 0) { repository.createRole(any()) }
        val dialogState = viewModel.dialogState.value
        assertTrue(dialogState is AgentRoleDialogState.AddRole)
        assertNotNull(dialogState.formState.errorMessage)
    }

    @Test
    fun `saveRole - edit - maps draft to UpdateAgentRoleRequest`() = runTest(dispatcher) {
        coEvery { repository.updateRole(7L, any()) } returns Either.Right(role(7, "writer-v2"))
        val existing = role(7, "writer")
        viewModel.startEditingRole(existing)

        viewModel.updateRoleForm { form ->
            form.copy(name = "writer-v2", modelPresetId = 4L)
        }

        viewModel.saveRole()

        coVerify(exactly = 1) {
            repository.updateRole(
                eq(7L),
                match<UpdateAgentRoleRequest> { request ->
                    request.name == "writer-v2" && request.modelPresetId == 4L
                }
            )
        }
    }

    @Test
    fun `saveRole - edit - detaching the preset sends a null preset id`() = runTest(dispatcher) {
        coEvery { repository.updateRole(7L, any()) } returns Either.Right(role(7, "writer-v2", modelPresetId = null))
        viewModel.startEditingRole(role(7, "writer"))

        viewModel.updateRoleForm { form -> form.copy(name = "writer-v2", modelPresetId = null) }

        viewModel.saveRole()

        coVerify(exactly = 1) {
            repository.updateRole(
                eq(7L),
                match<UpdateAgentRoleRequest> { request -> request.modelPresetId == null }
            )
        }
    }

    @Test
    fun `startEditingRole - the draft carries the role's preset reference`() = runTest(dispatcher) {
        viewModel.startEditingRole(role(9, "coder"))

        val form = (viewModel.dialogState.value as AgentRoleDialogState.EditRole).formState
        assertEquals(3L, form.modelPresetId)
    }

    @Test
    fun `deleteRole - success - clears selection and closes dialog`() = runTest(dispatcher) {
        coEvery { repository.deleteRole(7L) } returns Either.Right(Unit)

        viewModel.selectRole(role(7, "writer"))
        viewModel.startDeletingRole(role(7, "writer"))
        viewModel.deleteRole(7L)

        assertNull(viewModel.selectedRole.value)
        assertEquals(AgentRoleDialogState.None, viewModel.dialogState.value)
    }

    @Test
    fun `deleteRole - failure - notifies and keeps dialog open`() = runTest(dispatcher) {
        coEvery { repository.deleteRole(7L) } returns Either.Left(
            RepositoryError.OtherError("deletion failed")
        )

        viewModel.startDeletingRole(role(7, "writer"))
        viewModel.deleteRole(7L)

        coVerify {
            notificationService.repositoryError(any<RepositoryError>(), any<String>())
        }
        assertTrue(viewModel.dialogState.value is AgentRoleDialogState.DeleteRole)
    }

    @Test
    fun `setRoleDisabled - routes the flipped state to the repository`() = runTest(dispatcher) {
        val enabled = role(7, "writer")
        val disabled = enabled.copy(disabled = true)
        coEvery { repository.setRoleDisabled(7L, true) } returns Either.Right(disabled)

        viewModel.setRoleDisabled(enabled)

        coVerify(exactly = 1) { repository.setRoleDisabled(7L, true) }
        coVerify(exactly = 0) { notificationService.repositoryError(any<RepositoryError>(), any<String>()) }
    }

    @Test
    fun `setRoleDisabled - failure notifies without changing state`() = runTest(dispatcher) {
        val disabled = role(7, "writer").copy(disabled = true)
        coEvery { repository.setRoleDisabled(7L, false) } returns Either.Left(
            RepositoryError.OtherError("toggle failed")
        )

        viewModel.setRoleDisabled(disabled)

        coVerify(exactly = 1) { repository.setRoleDisabled(7L, false) }
        coVerify {
            notificationService.repositoryError(any<RepositoryError>(), any<String>())
        }
    }

    @Test
    fun `saveRole - add - carries projectId in the create request`() = runTest(dispatcher) {
        coEvery { repository.createRole(any()) } returns Either.Right(role(10, "writer"))
        viewModel.startAddingNewRole()

        viewModel.updateRoleForm { form ->
            form.copy(
                name = "writer",
                modelPresetId = 3L,
                projectId = 50L
            )
        }

        viewModel.saveRole()

        coVerify(exactly = 1) {
            repository.createRole(
                match<CreateAgentRoleRequest> { request -> request.projectId == 50L }
            )
        }
    }

    @Test
    fun `startAddingNewRole - fresh form has no project membership`() = runTest(dispatcher) {
        viewModel.startAddingNewRole()
        val form = (viewModel.dialogState.value as AgentRoleDialogState.AddRole).formState
        assertEquals(null, form.projectId)
    }

    @Test
    fun `form mode is preserved between add and edit`() = runTest(dispatcher) {
        viewModel.startAddingNewRole()
        val addForm = (viewModel.dialogState.value as AgentRoleDialogState.AddRole).formState
        assertEquals(FormMode.NEW, addForm.mode)

        viewModel.startEditingRole(role(9, "coder"))
        val editForm = (viewModel.dialogState.value as AgentRoleDialogState.EditRole).formState
        assertEquals(FormMode.EDIT, editForm.mode)
    }
}
