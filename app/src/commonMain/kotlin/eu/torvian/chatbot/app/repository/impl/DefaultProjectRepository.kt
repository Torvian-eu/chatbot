package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.ProjectRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.ProjectApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [ProjectRepository] backed by [ProjectApi].
 *
 * Maintains an in-memory [StateFlow] cache of the user's projects and refreshes it after every
 * successful CRUD operation so chat state and the management tab stay in sync automatically. The
 * repository deliberately depends only on [ProjectApi]: the reverse-direction refresh (roles →
 * projects) lives in [DefaultAgentRoleRepository], keeping this side cycle-free.
 *
 * @property projectApi The API client used for all project requests.
 */
class DefaultProjectRepository(
    private val projectApi: ProjectApi
) : ProjectRepository {

    companion object {
        private val logger = kmpLogger<DefaultProjectRepository>()
    }

    private val _projects = MutableStateFlow<DataState<RepositoryError, List<ProjectDto>>>(DataState.Idle)
    override val projects: StateFlow<DataState<RepositoryError, List<ProjectDto>>> = _projects.asStateFlow()

    override suspend fun loadProjects(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_projects.value.isLoading) return Unit.right()

        _projects.update { DataState.Loading }

        return projectApi.getAllProjects().fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load projects")
                logger.warn("Failed to load projects: ${repoError.message}")
                _projects.update { DataState.Error(repoError) }
                repoError.left()
            },
            ifRight = { projectList ->
                _projects.update { DataState.Success(projectList) }
                logger.debug("Successfully loaded ${projectList.size} projects")
                Unit.right()
            }
        )
    }

    override suspend fun loadProjectDetails(projectId: Long): Either<RepositoryError, ProjectDto> {
        logger.info("Loading details for project ID: $projectId")
        return projectApi.getProjectById(projectId).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to load details for project ID: $projectId")
                logger.warn("Failed to load details for project ID: $projectId: ${repoError.message}")
                repoError.left()
            },
            ifRight = { project ->
                logger.info("Successfully loaded details for project ID: $projectId")
                updateProjectsState { list ->
                    if (list.any { it.id == project.id }) list.map { if (it.id == project.id) project else it } else list + project
                }
                project.right()
            }
        )
    }

    override suspend fun createProject(request: CreateProjectRequest): Either<RepositoryError, ProjectDto> {
        logger.info("Creating new project: ${request.name}")

        return projectApi.createProject(request).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to create project '${request.name}'")
                logger.warn("Failed to create project '${request.name}': ${repoError.message}")
                repoError.left()
            },
            ifRight = { newProject ->
                logger.info("Successfully created project: ${newProject.name} with ID: ${newProject.id}")
                updateProjectsState { list -> list + newProject }
                newProject.right()
            }
        )
    }

    override suspend fun updateProject(projectId: Long, request: UpdateProjectRequest): Either<RepositoryError, ProjectDto> {
        logger.info("Updating project ID: $projectId")

        return projectApi.updateProject(projectId, request).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to update project ID: $projectId")
                logger.warn("Failed to update project ID: $projectId: ${repoError.message}")
                repoError.left()
            },
            ifRight = { updatedProject ->
                logger.info("Successfully updated project ID: $projectId")
                updateProjectsState { list ->
                    list.map { if (it.id == updatedProject.id) updatedProject else it }
                }
                updatedProject.right()
            }
        )
    }

    override suspend fun deleteProject(projectId: Long): Either<RepositoryError, Unit> {
        logger.info("Deleting project ID: $projectId")

        return projectApi.deleteProject(projectId).fold(
            ifLeft = { error ->
                val repoError = error.toRepositoryError("Failed to delete project ID: $projectId")
                logger.warn("Failed to delete project ID: $projectId: ${repoError.message}")
                repoError.left()
            },
            ifRight = {
                logger.info("Successfully deleted project ID: $projectId")
                updateProjectsState { list -> list.filterNot { it.id == projectId } }
                Unit.right()
            }
        )
    }

    /**
     * Helper to update the projects state when it's in Success or Idle state.
     */
    private fun updateProjectsState(transform: (List<ProjectDto>) -> List<ProjectDto>) {
        _projects.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                is DataState.Idle -> DataState.Success(transform(emptyList()))
                else -> currentState
            }
        }
    }
}