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
 * Tests for [AgentRolesViewModel]: form-draft to request mapping, save/delete flows and error
 * notifications.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRolesViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: AgentRoleRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var settingsRepository: ModelSettingsRepository
    private lateinit var toolRepository: ToolRepository
    private lateinit var notificationService: NotificationService
    private lateinit var viewModel: AgentRolesViewModel

    private fun role(id: Long, name: String) = AgentRoleDto(
        id = id,
        name = name,
        displayName = null,
        description = "",
        modelId = 1L,
        modelSettingsId = 2L,
        tools = emptySet(),
        instructions = listOf(
            AgentInstructionDto(AgentInstructionTypes.ROLE, "Role", "You are a writer")
        )
    )

    @BeforeTest
    fun setup() {
        dispatcher = UnconfinedTestDispatcher()
        repository = mockk(relaxed = true)
        modelRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        toolRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)

        every { repository.roles } returns MutableStateFlow(DataState.Success(emptyList()))
        every { modelRepository.models } returns MutableStateFlow(DataState.Success(emptyList()))
        every { settingsRepository.allSettings } returns MutableStateFlow(DataState.Success(emptyList()))
        every { toolRepository.tools } returns MutableStateFlow(DataState.Success(emptyList()))

        viewModel = AgentRolesViewModel(
            agentRoleRepository = repository,
            modelRepository = modelRepository,
            modelSettingsRepository = settingsRepository,
            toolRepository = toolRepository,
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
    fun `saveRole - add - maps draft to CreateAgentRoleRequest`() = runTest(dispatcher) {
        coEvery { repository.createRole(any()) } returns Either.Right(role(10, "writer"))
        viewModel.startAddingNewRole()

        viewModel.updateRoleForm { form ->
            form.copy(
                name = "writer",
                displayName = "Writer",
                description = "Creative writing",
                modelId = 1L,
                modelSettingsId = 2L,
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
                            request.modelId == 1L &&
                            request.modelSettingsId == 2L &&
                            request.toolIds == setOf(10L, 20L) &&
                            request.instructions.size == 2 &&
                            request.instructions[1].type == AgentInstructionTypes.CUSTOM
                }
            )
        }
    }

    @Test
    fun `saveRole - edit - maps draft to UpdateAgentRoleRequest`() = runTest(dispatcher) {
        coEvery { repository.updateRole(7L, any()) } returns Either.Right(role(7, "writer-v2"))
        val existing = role(7, "writer")
        viewModel.startEditingRole(existing)

        viewModel.updateRoleForm { form ->
            form.copy(name = "writer-v2", modelId = 1L, modelSettingsId = 2L)
        }

        viewModel.saveRole()

        coVerify(exactly = 1) {
            repository.updateRole(
                eq(7L),
                match<UpdateAgentRoleRequest> { request -> request.name == "writer-v2" }
            )
        }
    }

    @Test
    fun `saveRole - missing model - validates without calling api`() = runTest(dispatcher) {
        viewModel.startAddingNewRole()
        viewModel.updateRoleForm { form -> form.copy(name = "incomplete") }

        viewModel.saveRole()

        coVerify(exactly = 0) { repository.createRole(any()) }
        val dialogState = viewModel.dialogState.value
        assertTrue(dialogState is AgentRoleDialogState.AddRole)
        assertNotNull(dialogState.formState.errorMessage)
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
    fun `form mode is preserved between add and edit`() = runTest(dispatcher) {
        viewModel.startAddingNewRole()
        val addForm = (viewModel.dialogState.value as AgentRoleDialogState.AddRole).formState
        assertEquals(FormMode.NEW, addForm.mode)

        viewModel.startEditingRole(role(9, "coder"))
        val editForm = (viewModel.dialogState.value as AgentRoleDialogState.EditRole).formState
        assertEquals(FormMode.EDIT, editForm.mode)
    }
}
