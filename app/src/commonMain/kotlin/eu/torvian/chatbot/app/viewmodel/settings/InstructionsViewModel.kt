package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.contracts.AgentRoleFilter
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.InstructionLibraryFilter
import eu.torvian.chatbot.app.domain.contracts.InstructionsDialogState
import eu.torvian.chatbot.app.domain.contracts.filterBy
import eu.torvian.chatbot.app.domain.contracts.filterByProject
import eu.torvian.chatbot.app.domain.contracts.instructionFilterOptions
import eu.torvian.chatbot.app.repository.AgentRoleRepository
import eu.torvian.chatbot.app.repository.InstructionRepository
import eu.torvian.chatbot.app.repository.ProjectRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.matches
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.models.agent.AgentInstructionDto
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.instruction.UpdateInstructionRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for the Instructions settings category.
 *
 * The tab browses the user's instruction library, which is shared content: one row can serve many
 * agent roles, so the tab reports per row which roles link it and lets the user edit its content (which
 * reaches every linked role), unlink it from a role (the row survives) or delete it (which is allowed
 * only once no role links it). Assigning appends the instruction last in the targeted role's list.
 *
 * The repository stream holds the whole library and every row carries its linking role ids, so the
 * role, unassigned and project selections are applied to the delivered rows here: switching a filter
 * costs no request. A link change, however, rewrites those role ids, so the library is reloaded once per
 * mutation while the role stream refreshes itself from the mutation's own response.
 *
 * @property instructionRepository Repository backing the library rows, their deletion and reloads.
 * @property agentRoleRepository Repository owning the link mutations and the role catalog used to
 *            label the roles an instruction is linked to and to offer assignment targets.
 * @property projectRepository Repository providing the project catalog used to qualify role labels
 *            with their project name and to scope the library by project.
 * @property notificationService Service for error notifications.
 * @property uiDispatcher Dispatcher used for UI coroutines. Defaults to Main.
 */
class InstructionsViewModel(
    private val instructionRepository: InstructionRepository,
    private val agentRoleRepository: AgentRoleRepository,
    private val projectRepository: ProjectRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val selectedInstructionId = MutableStateFlow<Long?>(null)
    private val _filter = MutableStateFlow<InstructionLibraryFilter>(InstructionLibraryFilter.All)
    private val _projectFilter = MutableStateFlow<AgentRoleFilter>(AgentRoleFilter.AllProjects)
    private val _dialogState = MutableStateFlow<InstructionsDialogState>(InstructionsDialogState.None)

    /** The active library selection mode (all rows, one role's rows, or the unassigned rows). */
    val filter: StateFlow<InstructionLibraryFilter> = _filter.asStateFlow()

    /** The active project scope, narrowing the visible rows and the role filter options. */
    val projectFilter: StateFlow<AgentRoleFilter> = _projectFilter.asStateFlow()

    /** Reactive stream of the user's agent roles, used for usage labels and assignment targets. */
    val rolesState: StateFlow<DataState<RepositoryError, List<AgentRoleDto>>> = agentRoleRepository.roles

    /** Role lookup map resolving an instruction's linking role ids into labels. */
    val rolesById: StateFlow<Map<Long, AgentRoleDto>> = agentRoleRepository.roles
        .map { it.dataOrNull?.associateBy { role -> role.id } ?: emptyMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    /**
     * Reactive stream of the user's projects, used to qualify role labels and to scope the library.
     *
     * The stream is the shared repository instance, so project renames and deletions made anywhere in
     * the app reach the tab without a reload of its own.
     */
    val projectsState: StateFlow<DataState<RepositoryError, List<ProjectDto>>> = projectRepository.projects

    /** Project lookup map resolving a role's project id into its name. */
    val projectsById: StateFlow<Map<Long, ProjectDto>> = projectRepository.projects
        .map { it.dataOrNull?.associateBy { project -> project.id } ?: emptyMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    /**
     * The library narrowed to the active [filter] and [projectFilter].
     *
     * The two filters are AND-composed pure narrowings of the delivered rows, so switching either costs
     * no request. Load and error states pass through unchanged, so a failed load is still reported by
     * the tab instead of looking like an empty library.
     */
    val instructionsUiState: StateFlow<DataState<RepositoryError, List<AgentInstructionDto>>> =
        combine(
            instructionRepository.instructions,
            _filter,
            _projectFilter,
            rolesById,
            projectsById
        ) { state, filter, projectFilter, roles, projects ->
            when (state) {
                is DataState.Success -> DataState.Success(
                    state.data.filterBy(filter).filterByProject(projectFilter, roles, projects)
                )

                is DataState.Error -> state
                is DataState.Loading -> state
                is DataState.Idle -> state
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), DataState.Idle)

    /**
     * The instruction open in the master-detail view, or null on the list page.
     *
     * Resolved from the filtered rows, so a selection the active filter excludes (or a row deleted
     * here or elsewhere) closes the detail page instead of showing a row the list does not contain.
     */
    val selectedInstruction: StateFlow<AgentInstructionDto?> = combine(
        instructionsUiState.map { it.dataOrNull },
        selectedInstructionId
    ) { instructions, selectedId ->
        instructions?.find { it.id == selectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** The current dialog state for the tab. */
    val dialogState: StateFlow<InstructionsDialogState> = _dialogState.asStateFlow()

    /**
     * Loads the instruction library, the role catalog and the project catalog in parallel.
     *
     * All three loaders are non-fatal: a failure is reported through a notification while the others
     * stay usable. A failed project load degrades every role label to "No project" instead of hiding
     * links, and the library or the role catalog may fall back to id-based labels or an empty list.
     * The project load is issued on every entry because the shared stream starts empty on a cold start;
     * the repository de-duplicates a load that is already in flight.
     */
    fun loadInstructionsAndRoles() {
        viewModelScope.launch(uiDispatcher) {
            parZip(
                { instructionRepository.loadInstructions() },
                { agentRoleRepository.loadRoles() },
                { projectRepository.loadProjects() }
            ) { instructionsResult, rolesResult, projectsResult ->
                instructionsResult.mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load instructions"
                    )
                }
                rolesResult.mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load agent roles"
                    )
                }
                projectsResult.mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load projects"
                    )
                }
            }
        }
    }

    /**
     * Selects an instruction for the master-detail view, or clears the selection when null.
     *
     * @param instruction The row to open, or null to return to the list page.
     */
    fun selectInstruction(instruction: AgentInstructionDto?) {
        selectedInstructionId.value = instruction?.id
    }

    /**
     * Applies a library selection mode.
     *
     * The mode is applied to the already loaded rows, so this is a pure state change with no request.
     *
     * @param filter The selection mode to apply.
     */
    fun setFilter(filter: InstructionLibraryFilter) {
        _filter.value = filter
    }

    /**
     * Applies a project scope to the library.
     *
     * The scope narrows the role options, so a role selection that falls outside it (or the unassigned
     * selection under a concrete scope) is reset to [InstructionLibraryFilter.All]; leaving it would
     * present a combination that can never match. The narrowing is a pure state change: no request is
     * issued, the loaded rows are re-narrowed in place.
     *
     * @param filter The project scope to apply.
     */
    fun setProjectFilter(filter: AgentRoleFilter) {
        _projectFilter.value = filter

        val current = _filter.value
        val stillAvailable = when (current) {
            InstructionLibraryFilter.All -> true
            InstructionLibraryFilter.Unassigned -> filter == AgentRoleFilter.AllProjects
            is InstructionLibraryFilter.ByRole -> current in instructionFilterOptions(
                roles = rolesState.value.dataOrNull.orEmpty(),
                project = filter,
                projectsById = projectsState.value.dataOrNull.orEmpty().associateBy { it.id }
            )
        }
        if (!stillAvailable) _filter.value = InstructionLibraryFilter.All
    }

    /**
     * Opens the delete confirmation dialog for [instruction].
     *
     * @param instruction The row about to be deleted.
     */
    fun startDeletingInstruction(instruction: AgentInstructionDto) {
        _dialogState.value = InstructionsDialogState.DeleteInstruction(instruction)
    }

    /**
     * Deletes an instruction and closes the confirmation dialog.
     *
     * The server refuses a row an agent role still links, so a `resource-in-use` failure means this
     * app's cached usage was stale: the notification reports it, the library is re-read to show the
     * real linking roles and the dialog closes, because retrying cannot succeed. Any other failure
     * keeps the dialog open for a retry.
     *
     * @param instructionId The row to delete.
     */
    fun deleteInstruction(instructionId: Long) {
        viewModelScope.launch(uiDispatcher) {
            instructionRepository.deleteInstruction(instructionId).fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to delete instruction"
                    )
                    if (error.matches(CommonApiErrorCodes.RESOURCE_IN_USE)) {
                        // The row gained a link elsewhere; the cached usage is stale, so re-read it
                        // and close the dialog instead of inviting a retry that must fail again.
                        refreshLibrary()
                        cancelDialog()
                    }
                },
                ifRight = {
                    // The row left the library, so an open detail page for it must close.
                    if (selectedInstructionId.value == instructionId) {
                        selectedInstructionId.value = null
                    }
                    cancelDialog()
                }
            )
        }
    }

    /**
     * Opens the content editor for [instruction], prefilled with its stored label and text.
     *
     * @param instruction The row to edit.
     */
    fun startEditingInstruction(instruction: AgentInstructionDto) {
        _dialogState.value = InstructionsDialogState.EditInstruction(instruction)
    }

    /**
     * Applies an edited label to the open edit form.
     *
     * @param name The label the user typed.
     */
    fun editInstructionName(name: String) {
        updateEditInstruction { it.copy(name = name) }
    }

    /**
     * Applies an edited text to the open edit form.
     *
     * @param message The text the user typed.
     */
    fun editInstructionMessage(message: String) {
        updateEditInstruction { it.copy(message = message) }
    }

    /**
     * Saves the open edit form, replacing the row's content.
     *
     * The row is shared, so the write reaches every role that links it; the server's answer replaces the
     * cached row (including its linking roles), so no reload is needed. A failure keeps the form open for
     * a retry, because the entered content would be lost otherwise.
     */
    fun saveInstructionEdit() {
        val edit = _dialogState.value as? InstructionsDialogState.EditInstruction ?: return
        if (!edit.canSave) return

        viewModelScope.launch(uiDispatcher) {
            instructionRepository.updateInstruction(
                UpdateInstructionRequest(
                    id = edit.instruction.id,
                    type = edit.instruction.type,
                    name = edit.name,
                    message = edit.message,
                    // The kind and the target model survive an edit; only the content changes here.
                    custom = edit.instruction.custom
                )
            ).fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to update instruction"
                    )
                },
                ifRight = { cancelDialog() }
            )
        }
    }

    /**
     * Links the selected instruction to [roleId], appending it last in that role's list.
     *
     * @param roleId The role to assign the selected instruction to.
     */
    fun assignToRole(roleId: Long) {
        val instructionId = selectedInstructionId.value ?: return
        viewModelScope.launch(uiDispatcher) {
            agentRoleRepository.assignInstruction(roleId, instructionId).fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to assign instruction"
                    )
                },
                // The role echo updated the role stream inside the repository; the library's linking
                // role ids still have to be re-read, because they are what the usage list renders.
                ifRight = { refreshLibrary() }
            )
        }
    }

    /**
     * Removes the selected instruction's link from [roleId]; the instruction row itself survives.
     *
     * @param roleId The role to unassign the selected instruction from.
     */
    fun unassignFromRole(roleId: Long) {
        val instructionId = selectedInstructionId.value ?: return
        viewModelScope.launch(uiDispatcher) {
            agentRoleRepository.unassignInstruction(roleId, instructionId).fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to unassign instruction"
                    )
                },
                // See [assignToRole]: one reload per mutation keeps the reported usage accurate.
                ifRight = { refreshLibrary() }
            )
        }
    }

    /**
     * Cancels any dialog.
     */
    fun cancelDialog() {
        _dialogState.value = InstructionsDialogState.None
    }

    /**
     * Applies [transform] to the open edit form, leaving any other dialog untouched.
     *
     * A keystroke can arrive after the form closed (a dismissal racing the last input), and editing a
     * different dialog must never resurrect the form, so the state is replaced only while it is an edit.
     *
     * @param transform Rewrites the open edit form.
     */
    private fun updateEditInstruction(
        transform: (InstructionsDialogState.EditInstruction) -> InstructionsDialogState.EditInstruction
    ) {
        _dialogState.update { current ->
            if (current is InstructionsDialogState.EditInstruction) transform(current) else current
        }
    }

    /**
     * Re-reads the library after a link change, so the reported linking roles stay accurate.
     *
     * A failure only costs the refresh (the link itself was written), so it is reported as a
     * notification instead of an error state that would hide the library.
     */
    private suspend fun refreshLibrary() {
        instructionRepository.loadInstructions().mapLeft { error ->
            notificationService.repositoryError(
                error = error,
                shortMessage = "Failed to refresh instructions"
            )
        }
    }
}
