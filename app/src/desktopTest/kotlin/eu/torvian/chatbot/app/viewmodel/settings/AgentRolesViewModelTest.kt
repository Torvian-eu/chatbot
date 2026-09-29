package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.viewModelScope
import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.AgentRoleDialogState
import eu.torvian.chatbot.app.domain.contracts.AgentRoleInstructionDraft
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.FormMode
import eu.torvian.chatbot.app.repository.*
import eu.torvian.chatbot.app.testutils.viewmodel.awaitLaunchedBy
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.InstructionSlot
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.instruction.CreateInstructionRequest
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import eu.torvian.chatbot.common.models.worker.WorkerDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Instant

/**
 * Tests for [AgentRolesViewModel]: form-draft to request mapping (preset-based, U-28/U-29/U-36),
 * save/delete flows, catalog loading and error notifications.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRolesViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: AgentRoleRepository
    private lateinit var instructionRepository: InstructionRepository
    private lateinit var presetRepository: ModelPresetRepository
    private lateinit var modelRepository: ModelRepository
    private lateinit var settingsRepository: ModelSettingsRepository
    private lateinit var toolRepository: ToolRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var workerRepository: WorkerRepository
    private lateinit var mcpServerRepository: LocalMCPServerRepository
    private lateinit var notificationService: NotificationService
    private lateinit var viewModel: AgentRolesViewModel

    // Held as test fields because the ViewModel captures the streams at construction, so re-stubbing a
    // flow afterwards would have no effect on its lookups.
    private lateinit var workersFlow: MutableStateFlow<DataState<RepositoryError, List<WorkerDto>>>
    private lateinit var serversFlow: MutableStateFlow<DataState<RepositoryError, List<LocalMCPServerDto>>>
    private lateinit var instructionsFlow: MutableStateFlow<DataState<RepositoryError, List<AgentInstructionDto>>>

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
            AgentInstructionDto(
                id = 1L,
                type = AgentInstructionTypes.ROLE,
                name = "Role",
                message = "You are a writer"
            )
        )
    )

    private fun worker(id: Long, displayName: String) = WorkerDto(
        id = id,
        workerUid = "worker-uid-$id",
        ownerUserId = 1L,
        displayName = displayName,
        certificateFingerprint = "fingerprint-$id",
        allowedScopes = emptyList(),
        createdAt = Instant.fromEpochSeconds(id)
    )

    private fun server(id: Long, name: String) = LocalMCPServerDto(
        id = id,
        userId = 1L,
        workerId = 1L,
        name = name,
        command = "mcp-server",
        createdAt = Instant.fromEpochSeconds(id),
        updatedAt = Instant.fromEpochSeconds(id)
    )

    @BeforeTest
    fun setup() {
        dispatcher = UnconfinedTestDispatcher()
        repository = mockk(relaxed = true)
        instructionRepository = mockk()
        presetRepository = mockk(relaxed = true)
        modelRepository = mockk(relaxed = true)
        settingsRepository = mockk(relaxed = true)
        toolRepository = mockk(relaxed = true)
        projectRepository = mockk(relaxed = true)
        workerRepository = mockk(relaxed = true)
        mcpServerRepository = mockk(relaxed = true)
        notificationService = mockk(relaxed = true)

        workersFlow = MutableStateFlow(DataState.Success(emptyList()))
        serversFlow = MutableStateFlow(DataState.Success(emptyList()))
        // The library stream feeds the role form's existing-instruction picker.
        instructionsFlow = MutableStateFlow(DataState.Success(emptyList()))

        every { repository.roles } returns MutableStateFlow(DataState.Success(emptyList()))
        every { presetRepository.presets } returns
            MutableStateFlow<DataState<RepositoryError, List<ModelPresetDto>>>(DataState.Success(emptyList()))
        every { modelRepository.models } returns MutableStateFlow(DataState.Success(emptyList()))
        every { settingsRepository.allSettings } returns MutableStateFlow(DataState.Success(emptyList()))
        every { toolRepository.tools } returns MutableStateFlow(DataState.Success(emptyList()))
        every { projectRepository.projects } returns MutableStateFlow(DataState.Success(emptyList()))
        every { workerRepository.workers } returns workersFlow
        every { mcpServerRepository.servers } returns serversFlow
        every { instructionRepository.instructions } returns instructionsFlow
        // Stub every catalog load: the ViewModel maps each result's Left branch to a notification, and
        // a relaxed mock would answer with a placeholder Either whose value cannot be mapped.
        coEvery { repository.loadRoles() } returns Either.Right(Unit)
        coEvery { presetRepository.loadPresets() } returns Either.Right(Unit)
        coEvery { modelRepository.loadModels() } returns Either.Right(Unit)
        coEvery { settingsRepository.loadAllSettings() } returns Either.Right(Unit)
        coEvery { toolRepository.loadTools() } returns Either.Right(Unit)
        coEvery { projectRepository.loadProjects() } returns Either.Right(Unit)
        coEvery { workerRepository.loadWorkers() } returns Either.Right(Unit)
        // The MCP-server load reports its outcome through its result as well as the `servers` stream.
        coEvery { mcpServerRepository.loadServers() } returns Either.Right(Unit)
        coEvery { instructionRepository.loadInstructions() } returns Either.Right(Unit)

        viewModel = AgentRolesViewModel(
            agentRoleRepository = repository,
            instructionRepository = instructionRepository,
            modelPresetRepository = presetRepository,
            modelRepository = modelRepository,
            modelSettingsRepository = settingsRepository,
            toolRepository = toolRepository,
            projectRepository = projectRepository,
            workerRepository = workerRepository,
            mcpServerRepository = mcpServerRepository,
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
        // parZip runs the nine loaders on Dispatchers.Default, outside this test's scheduler: await the
        // launched load so the verifications below observe completed calls instead of racing the pool.
        viewModel.viewModelScope.awaitLaunchedBy { viewModel.loadRolesAndCatalogs() }

        coVerify(exactly = 1) { repository.loadRoles() }
        coVerify(exactly = 1) { presetRepository.loadPresets() }
        coVerify(exactly = 1) { modelRepository.loadModels() }
        coVerify(exactly = 1) { settingsRepository.loadAllSettings() }
        coVerify(exactly = 1) { toolRepository.loadTools() }
        coVerify(exactly = 1) { projectRepository.loadProjects() }
        coVerify(exactly = 1) { workerRepository.loadWorkers() }
        coVerify(exactly = 1) { mcpServerRepository.loadServers() }
        coVerify(exactly = 1) { instructionRepository.loadInstructions() }
    }

    @Test
    fun `loadRolesAndCatalogs - instruction failure is reported without aborting the load`() =
        runTest(dispatcher) {
            val error = RepositoryError.OtherError("instruction load failed")
            coEvery { instructionRepository.loadInstructions() } returns Either.Left(error)

            viewModel.viewModelScope.awaitLaunchedBy { viewModel.loadRolesAndCatalogs() }

            coVerify { notificationService.repositoryError(error, "Failed to load instructions") }
            // The role catalog still ran: a failed library load only costs the picker its rows.
            coVerify(exactly = 1) { repository.loadRoles() }
        }

    @Test
    fun `instructionsState exposes the repository library stream`() = runTest(dispatcher) {
        val library = listOf(
            AgentInstructionDto(
                id = 1L,
                type = AgentInstructionTypes.CUSTOM,
                name = "Tone",
                message = "Be concise",
                linkedRoleIds = setOf(2L, 3L)
            )
        )

        instructionsFlow.value = DataState.Success(library)

        assertEquals(library, viewModel.instructionsState.value.dataOrNull)
    }

    @Test
    fun `loadRolesAndCatalogs - worker failure is reported without aborting the load`() = runTest(dispatcher) {
        val error = RepositoryError.OtherError("worker load failed")
        coEvery { workerRepository.loadWorkers() } returns Either.Left(error)

        viewModel.viewModelScope.awaitLaunchedBy { viewModel.loadRolesAndCatalogs() }

        coVerify { notificationService.repositoryError(error, "Failed to load workers") }
        // The other catalogs still ran: a worker failure only costs the sub-group labels.
        coVerify(exactly = 1) { mcpServerRepository.loadServers() }
    }

    @Test
    fun `loadRolesAndCatalogs - MCP server failure is reported from the load result`() = runTest(dispatcher) {
        val error = RepositoryError.OtherError("mcp load failed")
        coEvery { mcpServerRepository.loadServers() } returns Either.Left(error)

        viewModel.viewModelScope.awaitLaunchedBy { viewModel.loadRolesAndCatalogs() }

        coVerify(exactly = 1) { notificationService.repositoryError(error, "Failed to load MCP servers") }
    }

    @Test
    fun `loadRolesAndCatalogs - a successful MCP server load reports nothing`() = runTest(dispatcher) {
        viewModel.viewModelScope.awaitLaunchedBy { viewModel.loadRolesAndCatalogs() }

        coVerify(exactly = 0) { notificationService.repositoryError(any(), "Failed to load MCP servers") }
    }

    @Test
    fun `loadRolesAndCatalogs - an error left in the servers state is not re-reported`() = runTest(dispatcher) {
        // An error published by an earlier load must not be reported again by a later load that
        // succeeded or was skipped as a duplicate.
        serversFlow.value = DataState.Error(RepositoryError.OtherError("stale mcp load failure"))

        viewModel.viewModelScope.awaitLaunchedBy { viewModel.loadRolesAndCatalogs() }

        coVerify(exactly = 0) { notificationService.repositoryError(any(), "Failed to load MCP servers") }
    }

    @Test
    fun `label lookups expose raw worker and MCP server names including blank ones`() = runTest(dispatcher) {
        workersFlow.value = DataState.Success(listOf(worker(7L, "Worker A"), worker(8L, "   ")))
        serversFlow.value = DataState.Success(listOf(server(20L, "Files")))

        // Both lookups are WhileSubscribed and compute on the ViewModel's Main-dispatched scope, so a
        // Main-based collector drives them and hands the result back to the test coroutine. Blank
        // display names must survive: the grouping helper owns the fallback label.
        val workerNames = CompletableDeferred<Map<Long, String>>()
        val serverNames = CompletableDeferred<Map<Long, String>>()
        val collectorScope = CoroutineScope(Dispatchers.Main)
        collectorScope.launch {
            workerNames.complete(viewModel.workerDisplayNamesById.first { it.isNotEmpty() })
        }
        collectorScope.launch {
            serverNames.complete(viewModel.mcpServerNamesById.first { it.isNotEmpty() })
        }

        assertEquals(mapOf(7L to "Worker A", 8L to "   "), workerNames.await())
        assertEquals(mapOf(20L to "Files"), serverNames.await())

        collectorScope.cancel()
    }

    /**
     * Verifies that the drafts ride along as inline create specs in one request and the role form
     * writes no instruction rows of its own.
     */
    @Test
    fun `saveRole - add - sends one atomic request with inline create specs`() = runTest(dispatcher) {
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
                    AgentRoleInstructionDraft(
                        type = AgentInstructionTypes.ROLE,
                        name = "Role",
                        message = "You are a writer"
                    ),
                    AgentRoleInstructionDraft(
                        type = AgentInstructionTypes.CUSTOM,
                        name = "Tone",
                        message = "Be concise"
                    )
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
                            request.instructionSpecs == listOf(
                                InstructionSlot.Create(
                                    CreateInstructionRequest(
                                        type = AgentInstructionTypes.ROLE,
                                        name = "Role",
                                        message = "You are a writer"
                                    )
                                ),
                                InstructionSlot.Create(
                                    CreateInstructionRequest(
                                        type = AgentInstructionTypes.CUSTOM,
                                        name = "Tone",
                                        message = "Be concise"
                                    )
                                )
                            )
                }
            )
        }
        coVerify(exactly = 0) { instructionRepository.createInstruction(any()) }
        coVerify(exactly = 0) { instructionRepository.updateInstruction(any()) }
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

    /**
     * Verifies that an untouched stored draft becomes a link spec: no content write, no new row.
     */
    @Test
    fun `saveRole - edit - an untouched reported instruction becomes a link spec`() = runTest(dispatcher) {
        coEvery { repository.updateRole(7L, any()) } returns Either.Right(role(7, "writer-v2"))
        viewModel.startEditingRole(role(7, "writer"))

        viewModel.updateRoleForm { form -> form.copy(name = "writer-v2", modelPresetId = 4L) }

        viewModel.saveRole()

        // The draft's row is referenced unchanged: no content write, no new row.
        coVerify(exactly = 0) { instructionRepository.updateInstruction(any()) }
        coVerify(exactly = 0) { instructionRepository.createInstruction(any()) }
        coVerify(exactly = 1) {
            repository.updateRole(
                eq(7L),
                match<UpdateAgentRoleRequest> { request ->
                    request.name == "writer-v2" &&
                        request.modelPresetId == 4L &&
                        request.instructionSpecs == listOf(InstructionSlot.Link(1L))
                }
            )
        }
    }

    /**
     * Verifies that an edited stored draft becomes an update spec inside the one role request, so the
     * shared row is rewritten by the save itself and reaches every role linking it.
     */
    @Test
    fun `saveRole - edit - an edited instruction becomes an update spec`() = runTest(dispatcher) {
        coEvery { repository.updateRole(7L, any()) } returns Either.Right(role(7, "writer"))
        viewModel.startEditingRole(role(7, "writer"))

        viewModel.updateRoleForm { form ->
            form.copy(instructions = form.instructions.map { it.copy(message = "You are a poet") })
        }

        viewModel.saveRole()

        // The role form issues no instruction write of its own; the row is rewritten inline.
        coVerify(exactly = 0) { instructionRepository.updateInstruction(any()) }
        coVerify(exactly = 0) { instructionRepository.createInstruction(any()) }
        coVerify(exactly = 1) {
            repository.updateRole(
                eq(7L),
                match<UpdateAgentRoleRequest> { request ->
                    request.instructionSpecs == listOf(
                        InstructionSlot.Update(
                            UpdateInstructionRequest(
                                id = 1L,
                                type = AgentInstructionTypes.ROLE,
                                name = "Role",
                                message = "You are a poet"
                            )
                        )
                    )
                }
            )
        }
    }

    /**
     * Verifies that a failed save touches no instruction rows and a retry re-sends identical specs —
     * there are no partial ids to remember or de-duplicate.
     */
    @Test
    fun `a failed save persists nothing and a retry re-sends the same specs`() = runTest(dispatcher) {
        coEvery { repository.createRole(any()) } returns
            Either.Left(RepositoryError.OtherError("role save failed")) andThen
            Either.Right(role(10, "writer"))
        viewModel.startAddingNewRole()
        viewModel.updateRoleForm { form -> form.copy(name = "writer", modelPresetId = 3L) }

        viewModel.saveRole()

        coVerify(exactly = 0) { instructionRepository.createInstruction(any()) }
        coVerify(exactly = 0) { instructionRepository.updateInstruction(any()) }
        coVerify(exactly = 0) { instructionRepository.deleteInstruction(any()) }
        // Nothing was written anywhere, so the drafts stay as the user left them — unwritten and
        // id-less, which is now the correct steady state rather than lost bookkeeping.
        val failedDrafts = (viewModel.dialogState.value as AgentRoleDialogState.AddRole).formState.instructions
        assertTrue(failedDrafts.all { it.id == null })

        viewModel.saveRole()

        val captured = mutableListOf<CreateAgentRoleRequest>()
        coVerify(exactly = 2) { repository.createRole(capture(captured)) }
        // The retry re-sends the same slots verbatim instead of duplicating rows of a partial attempt.
        assertEquals(captured[0].instructionSpecs, captured[1].instructionSpecs)
        assertTrue(captured[0].instructionSpecs.isNotEmpty())
    }

    /**
     * Verifies that after a failed save there are no instruction writes or compensating deletes to
     * run: closing the dialog abandons a save that left nothing behind.
     */
    @Test
    fun `closing the dialog after a failed save leaves nothing to clean up`() = runTest(dispatcher) {
        coEvery { repository.createRole(any()) } returns Either.Left(RepositoryError.OtherError("role save failed"))
        viewModel.startAddingNewRole()
        viewModel.updateRoleForm { form -> form.copy(name = "writer", modelPresetId = 3L) }

        viewModel.saveRole()
        viewModel.cancelDialog()

        coVerify(exactly = 0) { instructionRepository.createInstruction(any()) }
        coVerify(exactly = 0) { instructionRepository.updateInstruction(any()) }
        coVerify(exactly = 0) { instructionRepository.deleteInstruction(any()) }
        coVerify(exactly = 0) { instructionRepository.loadInstructions() }
    }

    /**
     * Verifies that a second save while one is in flight is refused, and that the guard releases once
     * the save settles so a later save can start.
     */
    @Test
    fun `a second save while one is in flight is refused`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        coEvery { repository.createRole(any()) } coAnswers {
            gate.await()
            Either.Right(role(10, "writer"))
        }
        viewModel.startAddingNewRole()
        viewModel.updateRoleForm { form -> form.copy(name = "writer", modelPresetId = 3L) }

        viewModel.saveRole()
        viewModel.saveRole()

        coVerify(exactly = 1) { repository.createRole(any()) }
        assertTrue(viewModel.saving.value)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(false, viewModel.saving.value)

        // The guard released with the settled save, so a fresh save runs again.
        viewModel.startAddingNewRole()
        viewModel.updateRoleForm { form -> form.copy(name = "writer", modelPresetId = 3L) }
        viewModel.saveRole()
        coVerify(exactly = 2) { repository.createRole(any()) }
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

    @Test
    fun `saveRole - add - refreshes the instruction library once after the role write`() = runTest(dispatcher) {
        coEvery { repository.createRole(any()) } returns Either.Right(role(10, "writer"))
        viewModel.startAddingNewRole()
        viewModel.updateRoleForm { form -> form.copy(name = "writer", modelPresetId = 3L) }

        viewModel.saveRole()

        // The role write changed which roles link each instruction row, so the cached library's
        // `linkedRoleIds` (the usage the settings screens render) is refreshed exactly once.
        coVerify(exactly = 1) { instructionRepository.loadInstructions() }
    }

    @Test
    fun `saveRole - edit - refreshes the instruction library once after the role write`() = runTest(dispatcher) {
        coEvery { repository.updateRole(7L, any()) } returns Either.Right(role(7, "writer-v2"))
        viewModel.startEditingRole(role(7, "writer"))
        viewModel.updateRoleForm { form -> form.copy(name = "writer-v2", modelPresetId = 4L) }

        viewModel.saveRole()

        coVerify(exactly = 1) { instructionRepository.loadInstructions() }
    }

    @Test
    fun `deleteRole refreshes the instruction library once after success`() = runTest(dispatcher) {
        coEvery { repository.deleteRole(7L) } returns Either.Right(Unit)

        viewModel.deleteRole(7L)

        // The role's links cascade away with it, so the library's usage is stale until the refresh.
        coVerify(exactly = 1) { instructionRepository.loadInstructions() }
    }

    @Test
    fun `a failed role write does not refresh the instruction library`() = runTest(dispatcher) {
        coEvery { repository.createRole(any()) } returns Either.Left(RepositoryError.OtherError("write failed"))
        viewModel.startAddingNewRole()
        viewModel.updateRoleForm { form -> form.copy(name = "writer", modelPresetId = 3L) }

        viewModel.saveRole()

        // Nothing changed server-side, so an extra request would only cost a round trip.
        coVerify(exactly = 0) { instructionRepository.loadInstructions() }
    }

    /** Verifies a failed deletion leaves the cached library untouched, so no refresh is issued. */
    @Test
    fun `a failed deleteRole does not refresh the instruction library`() = runTest(dispatcher) {
        coEvery { repository.deleteRole(7L) } returns Either.Left(RepositoryError.OtherError("deletion failed"))

        viewModel.deleteRole(7L)

        // Nothing changed server-side, so an extra request would only cost a round trip.
        coVerify(exactly = 0) { instructionRepository.loadInstructions() }
    }

    @Test
    fun `a failed library refresh is reported but does not fail the role write`() = runTest(dispatcher) {
        val refreshError = RepositoryError.OtherError("refresh failed")
        coEvery { repository.createRole(any()) } returns Either.Right(role(10, "writer"))
        coEvery { instructionRepository.loadInstructions() } returns Either.Left(refreshError)
        viewModel.startAddingNewRole()
        viewModel.updateRoleForm { form -> form.copy(name = "writer", modelPresetId = 3L) }

        viewModel.saveRole()

        // The role is saved; only the usage information stays stale, which is announced.
        coVerify { notificationService.repositoryError(refreshError, "Failed to refresh instructions") }
        assertEquals(AgentRoleDialogState.None, viewModel.dialogState.value)
    }
}
