package eu.torvian.chatbot.app.compose.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import eu.torvian.chatbot.app.domain.contracts.ProjectFormState
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.viewmodel.settings.ProjectsViewModel
import eu.torvian.chatbot.common.models.project.ProjectDto
import org.koin.compose.viewmodel.koinViewModel

/**
 * Route composable for the Projects settings category.
 *
 * The route keeps the ViewModel wiring and breadcrumb updates together so the visible page stays
 * separate from the underlying project-data selection. Selection state is owned by the
 * [ProjectsViewModel]; this route only observes it to decide between the list and detail pages.
 *
 * @param authState Authentication context (currently unused by the projects tab; projects are
 *   ownership-based per user).
 * @param modifier Modifier applied to the presentational tab.
 * @param viewModel Projects ViewModel resolved from Koin.
 * @param categoryResetSignal Incremented when the user re-selects this category in the sidebar;
 *   triggers a reset to the list view.
 * @param onBreadcrumbsChanged Callback used by the settings shell to reflect the current Projects
 *   page in the breadcrumb trail.
 */
@Composable
fun ProjectsTabRoute(
    authState: AuthState.Authenticated,
    modifier: Modifier = Modifier,
    viewModel: ProjectsViewModel = koinViewModel(),
    categoryResetSignal: Int = 0,
    onBreadcrumbsChanged: (List<String>) -> Unit = {}
) {
    // Tab-local initial load of projects plus the role catalog the detail panel and form need.
    LaunchedEffect(Unit) {
        viewModel.loadProjectsAndRoles()
    }

    // Reset to list view when the category is re-selected in the sidebar.
    LaunchedEffect(categoryResetSignal) {
        if (categoryResetSignal > 0) {
            viewModel.selectProject(null)
        }
    }

    val projectsState by viewModel.projectsState.collectAsState()
    val selectedProject by viewModel.selectedProject.collectAsState()
    val dialogState by viewModel.dialogState.collectAsState()
    val rolesState by viewModel.rolesState.collectAsState()
    val rolesById by viewModel.rolesById.collectAsState()

    // If a project disappears while its detail page is open, fall back to the list page.
    val projects = projectsState.dataOrNull
    val selectedProjectForFallback = selectedProject
    LaunchedEffect(projects, selectedProjectForFallback) {
        if (projects != null && selectedProjectForFallback != null && projects.none { it.id == selectedProjectForFallback.id }) {
            viewModel.selectProject(null)
        }
    }

    val breadcrumbs = selectedProject?.let {
        listOf(
            "Settings",
            SettingsCategory.Projects.displayLabel,
            it.name
        )
    } ?: listOf("Settings", SettingsCategory.Projects.displayLabel)

    LaunchedEffect(breadcrumbs) {
        onBreadcrumbsChanged(breadcrumbs)
    }

    val state = ProjectsTabState(
        projectsUiState = projectsState,
        selectedProject = selectedProject,
        dialogState = dialogState,
        roles = rolesState.dataOrNull.orEmpty(),
        rolesById = rolesById
    )

    val actions = object : ProjectsTabActions {
        override fun onLoadProjectsAndRoles() = viewModel.loadProjectsAndRoles()
        override fun onSelectProject(project: ProjectDto?) = viewModel.selectProject(project)
        override fun onStartAddingNewProject() = viewModel.startAddingNewProject()
        override fun onStartEditingProject(project: ProjectDto) = viewModel.startEditingProject(project)
        override fun onStartCloningProject(project: ProjectDto) = viewModel.startCloningProject(project)
        override fun onCloneProject() = viewModel.cloneProject()
        override fun onStartDeletingProject(project: ProjectDto) = viewModel.startDeletingProject(project)
        override fun onUpdateProjectForm(update: (ProjectFormState) -> ProjectFormState) =
            viewModel.updateProjectForm(update)
        override fun onSaveProject() = viewModel.saveProject()
        override fun onDeleteProject(projectId: Long) = viewModel.deleteProject(projectId)
        override fun onCancelDialog() = viewModel.cancelDialog()
    }

    ProjectsTab(
        state = state,
        actions = actions,
        onOpenProjectDetails = { project -> viewModel.selectProject(project) },
        onBackToProjectList = { viewModel.selectProject(null) },
        modifier = modifier
    )
}