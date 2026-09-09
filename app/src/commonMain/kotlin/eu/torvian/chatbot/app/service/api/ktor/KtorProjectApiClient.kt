package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ProjectApi
import eu.torvian.chatbot.common.api.resources.ProjectResource
import eu.torvian.chatbot.common.models.api.project.CreateProjectRequest
import eu.torvian.chatbot.common.models.api.project.UpdateProjectRequest
import eu.torvian.chatbot.common.models.project.ProjectDto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor-based implementation of [ProjectApi] for the user-owned project endpoints.
 *
 * Uses Ktor Resources for type-safe URL construction (`/api/v1/projects`,
 * `/api/v1/projects/{projectId}`) and wraps every request in [BaseApiResourceClient.safeApiCall] so
 * failures surface as [ApiResourceError] values instead of exceptions.
 *
 * @property httpClient The authenticated Ktor [HttpClient] used for all requests.
 */
class KtorProjectApiClient(
    httpClient: HttpClient
) : BaseApiResourceClient(httpClient), ProjectApi {

    override suspend fun getAllProjects(): Either<ApiResourceError, List<ProjectDto>> =
        safeApiCall {
            client.get(ProjectResource()).body<List<ProjectDto>>()
        }

    override suspend fun getProjectById(projectId: Long): Either<ApiResourceError, ProjectDto> =
        safeApiCall {
            client.get(ProjectResource.ById(projectId = projectId)).body<ProjectDto>()
        }

    override suspend fun createProject(request: CreateProjectRequest): Either<ApiResourceError, ProjectDto> =
        safeApiCall {
            client.post(ProjectResource()) {
                setBody(request)
            }.body<ProjectDto>()
        }

    override suspend fun updateProject(projectId: Long, request: UpdateProjectRequest): Either<ApiResourceError, ProjectDto> =
        safeApiCall {
            client.put(ProjectResource.ById(projectId = projectId)) {
                setBody(request)
            }.body<ProjectDto>()
        }

    override suspend fun deleteProject(projectId: Long): Either<ApiResourceError, Unit> =
        safeApiCall {
            client.delete(ProjectResource.ById(projectId = projectId))
        }
}