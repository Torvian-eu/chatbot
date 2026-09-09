package eu.torvian.chatbot.app.viewmodel.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import arrow.fx.coroutines.parZip
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.FormMode
import eu.torvian.chatbot.app.domain.contracts.ProjectDialogState
import eu.torvian.chatbot.app.domain.contracts.ProjectFormState
import eu.torvian.chatbot.app.domain.contracts.createEmptyProjectForm
import eu.torvian.chatbot.app.domain.contracts.toEditFormState
import eu.torvian.chatbot.app.repository.AgentRoleRepository
import eu.torvian.chatbot.app.repository.ProjectRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.app.viewmodel.common.NotificationService
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.project.CloneProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Manages the UI state and logic for the Projects settings category.
 *
 * The ViewModel owns the project list, the selected project (master-detail), and the dialog/form
 * state, and observes the agent-role repository so the detail panel can render member roles and the
 * form can offer a role multi-select.
 *
 * Cache consistency: every successful project create/update/delete also refreshes the role stream;
 * the refresh is unidirectional and cycle-free (projects→roles is triggered here, roles→projects
 * lives in [eu.torvian.chatbot.app.repository.impl.DefaultAgentRoleRepository]).
 *
 * @property projectRepository Repository for project CRUD and the reactive project list.
 * @property agentRoleRepository Repository of agent roles (member-role lookups + the form's role
 *            multi-select, plus the reverse-cache refresh after project CRUD).
 * @property notificationService Service for error/success notifications.
 * @property uiDispatcher Dispatcher used for UI coroutines. Defaults to Main.
 */
class ProjectsViewModel(
    private val projectRepository: ProjectRepository,
    private val agentRoleRepository: AgentRoleRepository,
    private val notificationService: NotificationService,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    companion object {
        private val logger = kmpLogger<ProjectsViewModel>()
    }

    private val userSelectedProjectId = MutableStateFlow<Long?>(null)
    private val _dialogState = MutableStateFlow<ProjectDialogState>(ProjectDialogState.None)

    /** Reactive stream of all projects owned by the current user. */
    val projectsState: StateFlow<DataState<RepositoryError, List<ProjectDto>>> = projectRepository.projects

    /** Reactive stream of all agent roles owned by the current user (unfiltered). */
    val rolesState: StateFlow<DataState<RepositoryError, List<AgentRoleDto>>> = agentRoleRepository.roles

    /** The project selected in the master-detail UI, or null when on the list page. */
    val selectedProject: StateFlow<ProjectDto?> = combine(
        projectsState.map { it.dataOrNull },
        userSelectedProjectId
    ) { projects, selectedId ->
        projects?.find { it.id == selectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    /** Agent-role lookup map for the detail panel's member-role rendering. */
    val rolesById: StateFlow<Map<Long, AgentRoleDto>> =
        rolesState.map { it.dataOrNull?.associateBy { role -> role.id } ?: emptyMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    /** The current dialog state for the tab. */
    val dialogState: StateFlow<ProjectDialogState> = _dialogState.asStateFlow()

    /**
     * Loads the project list and the role catalog in parallel.
     *
     * Roles are needed both for the detail panel (member lookups) and for the form's role
     * multi-select, so the catalog reloads whenever the tab enters.
     */
    fun loadProjectsAndRoles() {
        viewModelScope.launch(uiDispatcher) {
            parZip(
                { projectRepository.loadProjects() },
                { agentRoleRepository.loadRoles() }
            ) { projectsResult, rolesResult ->
                projectsResult.mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load projects"
                    )
                }
                rolesResult.mapLeft { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to load agent roles"
                    )
                }
            }
        }
    }

    /**
     * Selects a project for the master-detail view, or clears selection when null.
     */
    fun selectProject(project: ProjectDto?) {
        userSelectedProjectId.value = project?.id
    }

    /**
     * Opens the add-project form dialog with a fresh draft.
     */
    fun startAddingNewProject() {
        _dialogState.value = ProjectDialogState.AddProject(
            formState = createEmptyProjectForm()
        )
    }

    /**
     * Opens the edit-project form dialog pre-filled from [project].
     */
    fun startEditingProject(project: ProjectDto) {
        _dialogState.value = ProjectDialogState.EditProject(
            project = project,
            formState = project.toEditFormState()
        )
    }

    /**
     * Opens the delete-project confirmation dialog for [project].
     */
    fun startDeletingProject(project: ProjectDto) {
        _dialogState.value = ProjectDialogState.DeleteProject(project)
    }

    /**
     * Opens the clone-project dialog for [project].
     *
     * The name is prefilled with `Copy of <name>` and the description with the source's description
     * (Q4-A), so confirming without edits clones under a sibling name; the user may adjust either.
     */
    fun startCloningProject(project: ProjectDto) {
        _dialogState.value = ProjectDialogState.CloneProject(
            project = project,
            formState = ProjectFormState(
                mode = FormMode.NEW,
                name = "Copy of ${project.name}",
                description = project.description
            )
        )
    }

    /**
     * Applies an update function to the active form draft (add, edit or clone dialog).
     */
    fun updateProjectForm(update: (ProjectFormState) -> ProjectFormState) {
        _dialogState.update { dialogState ->
            when (dialogState) {
                is ProjectDialogState.AddProject -> dialogState.copy(formState = update(dialogState.formState))
                is ProjectDialogState.EditProject -> dialogState.copy(formState = update(dialogState.formState))
                is ProjectDialogState.CloneProject -> dialogState.copy(formState = update(dialogState.formState))
                else -> dialogState
            }
        }
    }

    /**
     * Saves the active form draft: creates a new project for the add dialog, or replaces the
     * configuration for the edit dialog.
     */
    fun saveProject() {
        when (val dialogState = _dialogState.value) {
            is ProjectDialogState.AddProject -> saveNewProject(dialogState.formState)
            is ProjectDialogState.EditProject -> saveEditedProject(dialogState)
            else -> return
        }
    }

    /**
     * Deletes a project and closes the confirmation dialog.
     */
    fun deleteProject(projectId: Long) {
        viewModelScope.launch(uiDispatcher) {
            projectRepository.deleteProject(projectId)
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to delete project"
                        )
                    },
                    ifRight = {
                        // The deleted project's role links cascade server-side; refresh the role
                        // stream so ProjectDto.agentRoleIds/AgentRoleDto.projectId stay consistent
                        // (this direction is triggered here, keeping the dependency cycle-free).
                        agentRoleRepository.loadRoles()
                        // If the deleted project was open in the detail page, fall back to the list.
                        if (userSelectedProjectId.value == projectId) {
                            userSelectedProjectId.value = null
                        }
                        cancelDialog()
                    }
                )
        }
    }

    /**
     * Cancels any dialog (form or confirmation).
     */
    fun cancelDialog() {
        _dialogState.value = ProjectDialogState.None
    }

    /**
     * Clones the source project of the active clone dialog under the dialog's name.
     *
     * Validates the name (same rule as create/edit via [ProjectFormState.validate]), then calls the
     * repository. After a successful clone the role stream is refreshed (the clone created new role
     * rows whose `projectId` and `disabled` state must be visible client-side), the dialog closes and
     * the cloned project is selected, mirroring the create-project refresh behavior.
     */
    fun cloneProject() {
        val dialogState = _dialogState.value
        if (dialogState !is ProjectDialogState.CloneProject) return

        val formState = dialogState.formState
        val validationError = formState.validate()
        if (validationError != null) {
            updateProjectForm { it.withError(validationError) }
            return
        }
        viewModelScope.launch(uiDispatcher) {
            projectRepository.cloneProject(
                projectId = dialogState.project.id,
                request = CloneProjectRequest(
                    name = formState.name.trim(),
                    description = formState.description.trim()
                )
            ).fold(
                ifLeft = { error ->
                    notificationService.repositoryError(
                        error = error,
                        shortMessage = "Failed to clone project"
                    )
                    updateProjectForm { it.withError("Error cloning project: ${error.message}") }
                },
                ifRight = { clonedProject ->
                    // The clone created new role rows server-side; refresh the role side of the cache
                    // so AgentRoleDto.projectId and the per-user disabled flags stay consistent.
                    agentRoleRepository.loadRoles()
                    cancelDialog()
                    selectProject(clonedProject)
                }
            )
        }
    }

    private fun saveNewProject(formState: ProjectFormState) {
        val validationError = formState.validate()
        if (validationError != null) {
            updateProjectForm { it.withError(validationError) }
            return
        }
        viewModelScope.launch(uiDispatcher) {
            projectRepository.createProject(formState.toCreateRequest())
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to create project"
                        )
                        updateProjectForm { it.withError("Error creating project: ${error.message}") }
                    },
                    ifRight = { createdProject ->
                        // The new project may carry role membership; refresh the role side of the
                        // cache so AgentRoleDto.projectId stays consistent.
                        agentRoleRepository.loadRoles()
                        cancelDialog()
                        selectProject(createdProject)
                    }
                )
        }
    }

    private fun saveEditedProject(dialogState: ProjectDialogState.EditProject) {
        val formState = dialogState.formState
        val validationError = formState.validate()
        if (validationError != null) {
            updateProjectForm { it.withError(validationError) }
            return
        }
        viewModelScope.launch(uiDispatcher) {
            projectRepository.updateProject(dialogState.project.id, formState.toUpdateRequest())
                .fold(
                    ifLeft = { error ->
                        notificationService.repositoryError(
                            error = error,
                            shortMessage = "Failed to update project"
                        )
                        updateProjectForm { it.withError("Error updating project: ${error.message}") }
                    },
                    ifRight = { updatedProject ->
                        // The update may have changed the membership; refresh the role side of the
                        // cache so AgentRoleDto.projectId stays consistent.
                        agentRoleRepository.loadRoles()
                        cancelDialog()
                        selectProject(updatedProject)
                    }
                )
        }
    }
}