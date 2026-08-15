package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.AgentRoleApi
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.common.api.resources.AgentRoleResource
import eu.torvian.chatbot.common.models.agent.AgentRoleDto
import eu.torvian.chatbot.common.models.api.agent.CreateAgentRoleRequest
import eu.torvian.chatbot.common.models.api.agent.UpdateAgentRoleRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor-based implementation of [AgentRoleApi] for the user-defined agent role endpoints.
 *
 * Uses Ktor Resources for type-safe URL construction (`/api/v1/agent-roles`, `/api/v1/agent-roles/{roleId}`)
 * and wraps every request in [BaseApiResourceClient.safeApiCall] so failures surface as
 * [ApiResourceError] values instead of exceptions.
 *
 * @property httpClient The authenticated Ktor [HttpClient] used for all requests.
 */
class KtorAgentRoleApiClient(
    httpClient: HttpClient
) : BaseApiResourceClient(httpClient), AgentRoleApi {

    override suspend fun getAllRoles(): Either<ApiResourceError, List<AgentRoleDto>> =
        safeApiCall {
            client.get(AgentRoleResource()).body<List<AgentRoleDto>>()
        }

    override suspend fun getRoleById(roleId: Long): Either<ApiResourceError, AgentRoleDto> =
        safeApiCall {
            client.get(AgentRoleResource.ById(roleId = roleId)).body<AgentRoleDto>()
        }

    override suspend fun createRole(request: CreateAgentRoleRequest): Either<ApiResourceError, AgentRoleDto> =
        safeApiCall {
            client.post(AgentRoleResource()) {
                setBody(request)
            }.body<AgentRoleDto>()
        }

    override suspend fun updateRole(roleId: Long, request: UpdateAgentRoleRequest): Either<ApiResourceError, AgentRoleDto> =
        safeApiCall {
            client.put(AgentRoleResource.ById(roleId = roleId)) {
                setBody(request)
            }.body<AgentRoleDto>()
        }

    override suspend fun deleteRole(roleId: Long): Either<ApiResourceError, Unit> =
        safeApiCall {
            client.delete(AgentRoleResource.ById(roleId = roleId))
        }
}
