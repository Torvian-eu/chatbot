package eu.torvian.chatbot.app.compose.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.torvian.chatbot.app.compose.common.ErrorStateDisplay
import eu.torvian.chatbot.app.compose.common.LoadingStateDisplay
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.domain.contracts.ProjectDialogState
import eu.torvian.chatbot.app.domain.contracts.ProjectFormState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.project.ProjectDto

/**
 * State contract for the Projects tab.
 *
 * @property projectsUiState Load state of the user's project list.
 * @property selectedProject The project open in the master-detail view, or null on the list page.
 * @property dialogState The active dialog (add/edit/delete form or confirmation).
 * @property roles All agent roles owned by the user, for the form's role multi-select.
 * @property rolesById Role lookup map for the detail panel's member-role rendering.
 */
data class ProjectsTabState(
    val projectsUiState: DataState<RepositoryError, List<ProjectDto>>,
    val selectedProject: ProjectDto?,
    val dialogState: ProjectDialogState,
    val roles: List<AgentRoleDto> = emptyList(),
    val rolesById: Map<Long, AgentRoleDto> = emptyMap()
)

/**
 * Action callbacks for the Projects tab.
 */
interface ProjectsTabActions {
    /** Reloads projects and the role catalog in parallel. */
    fun onLoadProjectsAndRoles()

    /** Selects a project for the master-detail view, or clears selection when null. */
    fun onSelectProject(project: ProjectDto?)

    /** Opens the add-project form dialog. */
    fun onStartAddingNewProject()

    /** Opens the edit-project form dialog for [project]. */
    fun onStartEditingProject(project: ProjectDto)

    /** Opens the delete-project confirmation dialog for [project]. */
    fun onStartDeletingProject(project: ProjectDto)

    /** Applies an update function to the active form draft. */
    fun onUpdateProjectForm(update: (ProjectFormState) -> ProjectFormState)

    /** Saves the active form draft (create or update). */
    fun onSaveProject()

    /** Deletes a project by id. */
    fun onDeleteProject(projectId: Long)

    /** Cancels any dialog (form or confirmation). */
    fun onCancelDialog()
}

/**
 * Projects management tab with separate list and detail pages.
 *
 * The tab stays presentational: it switches between list/detail while the route owns page
 * navigation state and the ViewModel owns dialogs and form state.
 *
 * @param state Current Projects tab state from the route.
 * @param actions ViewModel-forwarding actions for project CRUD flows.
 * @param onOpenProjectDetails Callback invoked when the user opens a project detail page.
 * @param onBackToProjectList Callback invoked when the user returns to the project list.
 * @param modifier Modifier applied to the tab container.
 */
@Composable
fun ProjectsTab(
    state: ProjectsTabState,
    actions: ProjectsTabActions,
    onOpenProjectDetails: (ProjectDto) -> Unit,
    onBackToProjectList: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (val uiState = state.projectsUiState) {
            is DataState.Loading -> {
                LoadingStateDisplay(
                    message = "Loading projects...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Error -> {
                ErrorStateDisplay(
                    title = "Failed to load projects",
                    error = uiState.error,
                    onRetry = { actions.onLoadProjectsAndRoles() },
                    modifier = Modifier.fillMaxSize()
                )
            }

            is DataState.Success -> {
                val projects = uiState.data
                val selectedProject = state.selectedProject

                if (selectedProject != null) {
                    ProjectDetailPage(
                        project = selectedProject,
                        rolesById = state.rolesById,
                        onBackToList = onBackToProjectList,
                        onEdit = { actions.onStartEditingProject(it) },
                        onDelete = { actions.onStartDeletingProject(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    ProjectListPage(
                        projects = projects,
                        onProjectSelected = { project -> onOpenProjectDetails(project) },
                        onAddNewProject = { actions.onStartAddingNewProject() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            is DataState.Idle -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Projects will appear here.",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { actions.onLoadProjectsAndRoles() }) {
                            Text("Load Projects")
                        }
                    }
                }
            }
        }
    }

    ProjectDialogs(
        dialogState = state.dialogState,
        actions = actions,
        roles = state.roles
    )
}