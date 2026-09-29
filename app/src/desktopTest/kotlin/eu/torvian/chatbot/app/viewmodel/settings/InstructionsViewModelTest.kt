package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.viewModelScope
import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.AgentRoleFilter
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.InstructionLibraryFilter
import eu.torvian.chatbot.app.domain.contracts.InstructionsDialogState
import eu.torvian.chatbot.app.repository.AgentRoleRepository
import eu.torvian.chatbot.app.repository.InstructionRepository
import eu.torvian.chatbot.app.repository.ProjectRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.testutils.viewmodel.awaitLaunchedBy
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentInstructionTypes
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Tests for [InstructionsViewModel]: the parallel load, the selection modes applied to the loaded
 * library, the content edit, the delete flow (including the stale-cache conflict) and the two link
 * flows that refresh the library's usage afterwards.
 *
 * The ViewModel's derived streams compute only while subscribed, so a test keeps one subscriber alive
 * (which is also what makes their current value observable) and then awaits the value it expects.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstructionsViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var instructionRepository: InstructionRepository
    private lateinit var agentRoleRepository: AgentRoleRepository
    private lateinit var projectRepository: ProjectRepository
    private lateinit var notificationService: NotificationService
    private lateinit var viewModel: InstructionsViewModel

    private lateinit var instructionsFlow: MutableStateFlow<DataState<RepositoryError, List<AgentInstructionDto>>>
    private lateinit var rolesFlow: MutableStateFlow<DataState<RepositoryError, List<AgentRoleDto>>>
    private lateinit var projectsFlow: MutableStateFlow<DataState<RepositoryError, List<ProjectDto>>>

    private fun instruction(
        id: Long,
        name: String,
        linkedRoleIds: Set<Long> = emptySet(),
        type: String = AgentInstructionTypes.CUSTOM,
        message: String = "Be concise",
        custom: JsonObject? = null
    ) = AgentInstructionDto(
        id = id,
        type = type,
        name = name,
        message = message,
        custom = custom,
        linkedRoleIds = linkedRoleIds
    )

    private fun role(id: Long, name: String, displayName: String? = null, projectId: Long? = null) = AgentRoleDto(
        id = id,
        name = name,
        displayName = displayName,
        modelId = 1L,
        modelSettingsId = 2L,
        projectId = projectId
    )

    private fun project(id: Long, name: String) = ProjectDto(
        id = id,
        name = name,
        description = "",
        createdAt = Instant.fromEpochSeconds(id),
        agentRoleIds = emptySet()
    )

    @BeforeTest
    fun setup() {
        dispatcher = UnconfinedTestDispatcher()
        instructionRepository = mockk()
        agentRoleRepository = mockk()
        projectRepository = mockk()
        notificationService = mockk(relaxed = true)

        instructionsFlow = MutableStateFlow(DataState.Success(emptyList()))
        rolesFlow = MutableStateFlow(DataState.Success(emptyList()))
        projectsFlow = MutableStateFlow(DataState.Success(emptyList()))

        every { instructionRepository.instructions } returns instructionsFlow
        every { agentRoleRepository.roles } returns rolesFlow
        every { projectRepository.projects } returns projectsFlow
        coEvery { instructionRepository.loadInstructions() } returns Either.Right(Unit)
        coEvery { instructionRepository.deleteInstruction(any()) } returns Either.Right(Unit)
        coEvery { agentRoleRepository.loadRoles() } returns Either.Right(Unit)
        coEvery { projectRepository.loadProjects() } returns Either.Right(Unit)
        coEvery { agentRoleRepository.assignInstruction(any(), any()) } returns Either.Right(role(1L, "writer"))
        coEvery { agentRoleRepository.unassignInstruction(any(), any()) } returns Either.Right(role(1L, "writer"))

        viewModel = InstructionsViewModel(
            instructionRepository = instructionRepository,
            agentRoleRepository = agentRoleRepository,
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

    /**
     * Keeps [flow] subscribed on the real Main dispatcher for the duration of [block], so the derived
     * value stays computed and a test can await the value it expects.
     *
     * @param flow The derived stream to observe.
     * @param block Assertions and interactions, with [awaitValue] awaiting the expected value.
     */
    private suspend fun <T> withSubscription(
        flow: StateFlow<T>,
        block: suspend (awaitValue: suspend ((T) -> Boolean) -> T) -> Unit
    ) {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            flow.collect { /* keep the shared stream hot for the whole test */ }
        }
        try {
            block { predicate -> flow.first(predicate) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `loadInstructionsAndRoles loads the library, the role catalog and the project catalog`() =
        runTest(dispatcher) {
            viewModel.viewModelScope.awaitLaunchedBy { viewModel.loadInstructionsAndRoles() }

            coVerify(exactly = 1) { instructionRepository.loadInstructions() }
            coVerify(exactly = 1) { agentRoleRepository.loadRoles() }
            coVerify(exactly = 1) { projectRepository.loadProjects() }
        }

    @Test
    fun `loadInstructionsAndRoles reports a library failure without aborting the role load`() =
        runTest(dispatcher) {
            val error = RepositoryError.OtherError("library failed")
            coEvery { instructionRepository.loadInstructions() } returns Either.Left(error)

            viewModel.viewModelScope.awaitLaunchedBy { viewModel.loadInstructionsAndRoles() }

            coVerify { notificationService.repositoryError(error, "Failed to load instructions") }
            // The usage labels still resolve, so the role catalog load must not be skipped.
            coVerify(exactly = 1) { agentRoleRepository.loadRoles() }
        }

    @Test
    fun `loadInstructionsAndRoles reports a role failure without aborting the library load`() =
        runTest(dispatcher) {
            val error = RepositoryError.OtherError("roles failed")
            coEvery { agentRoleRepository.loadRoles() } returns Either.Left(error)

            viewModel.viewModelScope.awaitLaunchedBy { viewModel.loadInstructionsAndRoles() }

            coVerify { notificationService.repositoryError(error, "Failed to load agent roles") }
            coVerify(exactly = 1) { instructionRepository.loadInstructions() }
        }

    @Test
    fun `loadInstructionsAndRoles reports a project failure without aborting the other loads`() =
        runTest(dispatcher) {
            val error = RepositoryError.OtherError("projects failed")
            coEvery { projectRepository.loadProjects() } returns Either.Left(error)

            viewModel.viewModelScope.awaitLaunchedBy { viewModel.loadInstructionsAndRoles() }

            coVerify { notificationService.repositoryError(error, "Failed to load projects") }
            // A failed project load only degrades the labels; the library and the roles stay usable.
            coVerify(exactly = 1) { instructionRepository.loadInstructions() }
            coVerify(exactly = 1) { agentRoleRepository.loadRoles() }
        }

    @Test
    fun `the selection modes narrow the loaded library without another request`() = runTest(dispatcher) {
        instructionsFlow.value = DataState.Success(
            listOf(
                instruction(1L, "Tone", linkedRoleIds = setOf(2L, 7L)),
                instruction(2L, "Style", linkedRoleIds = setOf(7L)),
                instruction(3L, "Available agents", type = AgentInstructionTypes.SPAWNABLE_AGENTS, message = "")
            )
        )

        withSubscription(viewModel.instructionsUiState) { awaitValue ->
            // With no selection every row of the library is listed, marker rows included.
            assertEquals(
                listOf(1L, 2L, 3L),
                awaitValue { it.dataOrNull?.map { row -> row.id } == listOf(1L, 2L, 3L) }.dataOrNull?.map { it.id }
            )

            // One role's rows only.
            viewModel.setFilter(InstructionLibraryFilter.ByRole(2L))
            assertEquals(
                listOf(1L),
                awaitValue { it.dataOrNull?.map { row -> row.id } == listOf(1L) }.dataOrNull?.map { it.id }
            )

            // Rows that no role links.
            viewModel.setFilter(InstructionLibraryFilter.Unassigned)
            assertEquals(
                listOf(3L),
                awaitValue { it.dataOrNull?.map { row -> row.id } == listOf(3L) }.dataOrNull?.map { it.id }
            )

            viewModel.setFilter(InstructionLibraryFilter.All)
            assertEquals(
                listOf(1L, 2L, 3L),
                awaitValue { it.dataOrNull?.map { row -> row.id } == listOf(1L, 2L, 3L) }.dataOrNull?.map { it.id }
            )
        }

        // Every mode is served from the loaded rows, so switching one costs no request.
        coVerify(exactly = 0) { instructionRepository.loadInstructions() }
    }

    @Test
    fun `the active filter hides a selection it excludes`() = runTest(dispatcher) {
        val rows = listOf(
            instruction(1L, "Tone", linkedRoleIds = setOf(7L)),
            instruction(2L, "Style", linkedRoleIds = setOf(2L))
        )
        instructionsFlow.value = DataState.Success(rows)

        withSubscription(viewModel.selectedInstruction) { awaitValue ->
            viewModel.selectInstruction(rows[0])
            assertEquals(1L, awaitValue { it?.id == 1L }?.id)

            // The open row is not linked by the selected role, so the detail page closes instead of
            // showing a row the narrowed list no longer holds.
            viewModel.setFilter(InstructionLibraryFilter.ByRole(2L))
            assertNull(awaitValue { it == null })
        }
    }

    @Test
    fun `selecting null returns to the list page`() = runTest(dispatcher) {
        val rows = listOf(instruction(1L, "Tone"))
        instructionsFlow.value = DataState.Success(rows)

        withSubscription(viewModel.selectedInstruction) { awaitValue ->
            viewModel.selectInstruction(rows[0])
            assertEquals(1L, awaitValue { it?.id == 1L }?.id)

            viewModel.selectInstruction(null)
            assertNull(awaitValue { it == null })
        }
    }

    @Test
    fun `rolesById resolves the catalog for usage labels`() = runTest(dispatcher) {
        val catalog = listOf(role(7L, "coder", displayName = "Coder"))
        rolesFlow.value = DataState.Success(catalog)

        withSubscription(viewModel.rolesById) { awaitValue ->
            assertEquals(listOf(7L), awaitValue { it.isNotEmpty() }.keys.toList())
        }
    }

    @Test
    fun `deleteInstruction forwards the id, closes the dialog and clears the open detail page`() =
        runTest(dispatcher) {
            val rows = listOf(instruction(5L, "Tone"))
            instructionsFlow.value = DataState.Success(rows)
            viewModel.startDeletingInstruction(rows.single())

            withSubscription(viewModel.selectedInstruction) { awaitValue ->
                viewModel.selectInstruction(rows.single())
                assertEquals(5L, awaitValue { it?.id == 5L }?.id)

                viewModel.deleteInstruction(5L)
                advanceUntilIdle()

                // The deleted row cannot stay open in the detail page, and the confirmation closes.
                assertNull(awaitValue { it == null })
                assertEquals(InstructionsDialogState.None, viewModel.dialogState.value)
            }

            coVerify(exactly = 1) { instructionRepository.deleteInstruction(5L) }
        }

    @Test
    fun `deleteInstruction reports a failure and keeps the confirmation dialog open`() = runTest(dispatcher) {
        val rows = listOf(instruction(5L, "Tone"))
        val error = RepositoryError.OtherError("delete failed")
        coEvery { instructionRepository.deleteInstruction(5L) } returns Either.Left(error)
        viewModel.startDeletingInstruction(rows.single())

        viewModel.deleteInstruction(5L)
        advanceUntilIdle()

        coVerify { notificationService.repositoryError(error, "Failed to delete instruction") }
        // The dialog stays for a retry instead of pretending the row is gone.
        assertEquals(InstructionsDialogState.DeleteInstruction(rows.single()), viewModel.dialogState.value)
    }

    @Test
    fun `deleteInstruction treats a resource-in-use conflict as a stale cache and closes the dialog`() =
        runTest(dispatcher) {
            val rows = listOf(instruction(5L, "Tone"))
            val error = RepositoryError.DataFetchError(
                apiResourceError = ApiResourceError.ServerError(
                    apiError = ApiError(
                        statusCode = 409,
                        code = CommonApiErrorCodes.RESOURCE_IN_USE.code,
                        message = "Instruction is still used by agent roles",
                        details = mapOf("instructionId" to "5", "roleIds" to "7")
                    )
                ),
                contextMessage = "Failed to delete instruction ID: 5"
            )
            coEvery { instructionRepository.deleteInstruction(5L) } returns Either.Left(error)
            viewModel.startDeletingInstruction(rows.single())

            viewModel.deleteInstruction(5L)
            advanceUntilIdle()

            coVerify { notificationService.repositoryError(error, "Failed to delete instruction") }
            // The cached usage was stale, so the library is re-read and the dialog closes: a retry
            // cannot succeed while the row is still linked.
            coVerify(exactly = 1) { instructionRepository.loadInstructions() }
            assertEquals(InstructionsDialogState.None, viewModel.dialogState.value)
        }

    @Test
    fun `startEditingInstruction prefills the form with the stored content`() {
        val row = instruction(5L, "Tone", message = "Be concise")

        viewModel.startEditingInstruction(row)

        assertEquals(
            InstructionsDialogState.EditInstruction(row, name = "Tone", message = "Be concise"),
            viewModel.dialogState.value
        )
    }

    @Test
    fun `an edited label and text become saveable and describe the intended write`() {
        val row = instruction(5L, "Tone")
        viewModel.startEditingInstruction(row)

        // The untouched form has nothing to write, so it cannot be saved yet.
        assertEquals(false, (viewModel.dialogState.value as InstructionsDialogState.EditInstruction).canSave)

        viewModel.editInstructionName("Tone v2")
        viewModel.editInstructionMessage("Be brief")

        assertEquals(
            InstructionsDialogState.EditInstruction(row, name = "Tone v2", message = "Be brief"),
            viewModel.dialogState.value
        )
        assertEquals(true, (viewModel.dialogState.value as InstructionsDialogState.EditInstruction).canSave)
    }

    @Test
    fun `saveInstructionEdit writes the edited content and closes the form`() = runTest(dispatcher) {
        val row = instruction(
            id = 5L,
            name = "Tone",
            linkedRoleIds = setOf(7L),
            type = AgentInstructionTypes.MODEL_SPECIFIC,
            custom = buildJsonObject { put("modelId", 3L) }
        )
        val request = slot<UpdateInstructionRequest>()
        coEvery { instructionRepository.updateInstruction(capture(request)) } returns
            Either.Right(row.copy(name = "Tone v2"))
        viewModel.startEditingInstruction(row)
        viewModel.editInstructionName("Tone v2")

        viewModel.saveInstructionEdit()
        advanceUntilIdle()

        // The kind and the target model travel along unchanged: an edit rewrites content only.
        assertEquals(
            UpdateInstructionRequest(
                id = 5L,
                type = AgentInstructionTypes.MODEL_SPECIFIC,
                name = "Tone v2",
                message = "Be concise",
                custom = buildJsonObject { put("modelId", 3L) }
            ),
            request.captured
        )
        assertEquals(InstructionsDialogState.None, viewModel.dialogState.value)
    }

    @Test
    fun `saveInstructionEdit reports a failure and keeps the form open`() = runTest(dispatcher) {
        val row = instruction(5L, "Tone")
        val error = RepositoryError.OtherError("update failed")
        coEvery { instructionRepository.updateInstruction(any()) } returns Either.Left(error)
        viewModel.startEditingInstruction(row)
        viewModel.editInstructionName("Tone v2")

        viewModel.saveInstructionEdit()
        advanceUntilIdle()

        coVerify { notificationService.repositoryError(error, "Failed to update instruction") }
        // The entered content survives the failure so the user can retry or fix it.
        assertEquals(
            InstructionsDialogState.EditInstruction(row, name = "Tone v2", message = "Be concise"),
            viewModel.dialogState.value
        )
    }

    @Test
    fun `saveInstructionEdit sends nothing for a blank label or an unchanged row`() = runTest(dispatcher) {
        val row = instruction(5L, "Tone")
        viewModel.startEditingInstruction(row)

        // Nothing was edited yet.
        viewModel.saveInstructionEdit()

        viewModel.editInstructionName("   ")
        viewModel.saveInstructionEdit()
        advanceUntilIdle()

        coVerify(exactly = 0) { instructionRepository.updateInstruction(any()) }
    }

    @Test
    fun `an edit arriving after the form closed does not reopen it`() = runTest(dispatcher) {
        viewModel.startEditingInstruction(instruction(5L, "Tone"))
        viewModel.cancelDialog()

        viewModel.editInstructionName("Style")

        assertEquals(InstructionsDialogState.None, viewModel.dialogState.value)
    }

    @Test
    fun `assignToRole links the selected row and reloads the library once`() = runTest(dispatcher) {
        val rows = listOf(instruction(5L, "Tone"))
        instructionsFlow.value = DataState.Success(rows)
        viewModel.selectInstruction(rows.single())

        viewModel.assignToRole(7L)
        advanceUntilIdle()

        coVerify(exactly = 1) { agentRoleRepository.assignInstruction(7L, 5L) }
        // The link changed the row's linking roles, which is what the usage list renders.
        coVerify(exactly = 1) { instructionRepository.loadInstructions() }
    }

    @Test
    fun `assignToRole without a selection does nothing`() = runTest(dispatcher) {
        viewModel.assignToRole(7L)
        advanceUntilIdle()

        coVerify(exactly = 0) { agentRoleRepository.assignInstruction(any(), any()) }
        coVerify(exactly = 0) { instructionRepository.loadInstructions() }
    }

    @Test
    fun `assignToRole reports a failure without touching the library`() = runTest(dispatcher) {
        val rows = listOf(instruction(5L, "Tone"))
        val error = RepositoryError.OtherError("assign failed")
        coEvery { agentRoleRepository.assignInstruction(7L, 5L) } returns Either.Left(error)
        viewModel.selectInstruction(rows.single())

        viewModel.assignToRole(7L)
        advanceUntilIdle()

        coVerify { notificationService.repositoryError(error, "Failed to assign instruction") }
        coVerify(exactly = 0) { instructionRepository.loadInstructions() }
    }

    @Test
    fun `unassignFromRole unlinks the selected row, keeps it in the library and reloads the usage`() =
        runTest(dispatcher) {
            val rows = listOf(instruction(5L, "Tone", linkedRoleIds = setOf(7L)))
            instructionsFlow.value = DataState.Success(rows)
            viewModel.selectInstruction(rows.single())

            viewModel.unassignFromRole(7L)
            advanceUntilIdle()

            coVerify(exactly = 1) { agentRoleRepository.unassignInstruction(7L, 5L) }
            coVerify(exactly = 1) { instructionRepository.loadInstructions() }
            // Unlinking never deletes the row: only the explicit delete does.
            coVerify(exactly = 0) { instructionRepository.deleteInstruction(any()) }
        }

    @Test
    fun `unassignFromRole reports a failure without touching the library`() = runTest(dispatcher) {
        val rows = listOf(instruction(5L, "Tone", linkedRoleIds = setOf(7L)))
        val error = RepositoryError.OtherError("unassign failed")
        coEvery { agentRoleRepository.unassignInstruction(7L, 5L) } returns Either.Left(error)
        viewModel.selectInstruction(rows.single())

        viewModel.unassignFromRole(7L)
        advanceUntilIdle()

        coVerify { notificationService.repositoryError(error, "Failed to unassign instruction") }
        coVerify(exactly = 0) { instructionRepository.loadInstructions() }
    }

    @Test
    fun `a failed usage refresh is reported without failing the link change`() = runTest(dispatcher) {
        val rows = listOf(instruction(5L, "Tone"))
        val error = RepositoryError.OtherError("refresh failed")
        instructionsFlow.value = DataState.Success(rows)
        coEvery { instructionRepository.loadInstructions() } returns Either.Left(error)
        viewModel.selectInstruction(rows.single())

        viewModel.assignToRole(7L)
        advanceUntilIdle()

        // The link was written; only the reported usage stays stale, which is announced.
        coVerify(exactly = 1) { agentRoleRepository.assignInstruction(7L, 5L) }
        coVerify { notificationService.repositoryError(error, "Failed to refresh instructions") }
    }

    @Test
    fun `the initial selection mode is the whole library`() {
        assertEquals(InstructionLibraryFilter.All, viewModel.filter.value)
    }

    @Test
    fun `the initial project scope is all projects`() {
        assertEquals(AgentRoleFilter.AllProjects, viewModel.projectFilter.value)
    }

    @Test
    fun `projectsById resolves the catalog for project labels`() = runTest(dispatcher) {
        projectsFlow.value = DataState.Success(listOf(project(1L, "Marketing")))

        withSubscription(viewModel.projectsById) { awaitValue ->
            assertEquals(listOf(1L), awaitValue { it.isNotEmpty() }.keys.toList())
        }
    }

    @Test
    fun `the project filter narrows the library with no further request`() = runTest(dispatcher) {
        rolesFlow.value = DataState.Success(
            listOf(role(2L, "writer", projectId = 1L), role(7L, "coder", projectId = 2L))
        )
        projectsFlow.value = DataState.Success(listOf(project(1L, "Marketing"), project(2L, "Engineering")))
        instructionsFlow.value = DataState.Success(
            listOf(
                instruction(1L, "Tone", linkedRoleIds = setOf(2L)),
                instruction(2L, "Style", linkedRoleIds = setOf(7L)),
                instruction(3L, "Orphan")
            )
        )

        withSubscription(viewModel.instructionsUiState) { awaitValue ->
            assertEquals(
                listOf(1L, 2L, 3L),
                awaitValue { it.dataOrNull?.size == 3 }.dataOrNull?.map { it.id }
            )

            viewModel.setProjectFilter(AgentRoleFilter.Project(1L))
            assertEquals(
                listOf(1L),
                awaitValue { it.dataOrNull?.map { row -> row.id } == listOf(1L) }.dataOrNull?.map { it.id }
            )

            // The role selection ANDs onto the project scope: role 7 sits in the other project.
            viewModel.setFilter(InstructionLibraryFilter.ByRole(7L))
            assertEquals(
                emptyList(),
                awaitValue { it.dataOrNull?.isEmpty() == true }.dataOrNull?.map { it.id }
            )
        }

        coVerify(exactly = 0) { instructionRepository.loadInstructions() }
    }

    @Test
    fun `setProjectFilter resets a role selection that falls out of scope`() = runTest(dispatcher) {
        rolesFlow.value = DataState.Success(
            listOf(role(2L, "writer", projectId = 1L), role(7L, "coder", projectId = 2L))
        )
        projectsFlow.value = DataState.Success(listOf(project(1L, "Marketing"), project(2L, "Engineering")))

        // Role 7 belongs to Engineering, so a switch to Marketing must drop the impossible selection.
        viewModel.setFilter(InstructionLibraryFilter.ByRole(7L))
        viewModel.setProjectFilter(AgentRoleFilter.Project(1L))
        assertEquals(InstructionLibraryFilter.All, viewModel.filter.value)

        // The unassigned mode can never match a concrete scope either.
        viewModel.setFilter(InstructionLibraryFilter.Unassigned)
        viewModel.setProjectFilter(AgentRoleFilter.Project(1L))
        assertEquals(InstructionLibraryFilter.All, viewModel.filter.value)

        // A role inside the scope survives the narrowing.
        viewModel.setFilter(InstructionLibraryFilter.ByRole(2L))
        viewModel.setProjectFilter(AgentRoleFilter.Project(1L))
        assertEquals(InstructionLibraryFilter.ByRole(2L), viewModel.filter.value)
    }
}
