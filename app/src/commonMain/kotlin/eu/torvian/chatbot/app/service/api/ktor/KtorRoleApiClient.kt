package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.RoleApi
import eu.torvian.chatbot.common.api.resources.RoleResource
import eu.torvian.chatbot.common.models.Role
import eu.torvian.chatbot.common.models.api.admin.CreateRoleRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateRoleRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor-based implementation of [RoleApi] for communicating with role management endpoints.
 *
 * This client uses Ktor's Resources plugin for type-safe HTTP requests and provides
 * comprehensive error handling using Arrow's Either type. All requests are authenticated
 * using JWT tokens managed by the HttpClient configuration.
 */
class KtorRoleApiClient(
    httpClient: HttpClient
) : BaseApiResourceClient(httpClient), RoleApi {

    override suspend fun getAllRoles(): Either<ApiResourceError, List<Role>> =
        safeApiCall {
            client.get(RoleResource()).body<List<Role>>()
        }

    override suspend fun getRoleById(id: Long): Either<ApiResourceError, Role> =
        safeApiCall {
            client.get(RoleResource.ById(roleId = id)).body<Role>()
        }

    override suspend fun createRole(request: CreateRoleRequest): Either<ApiResourceError, Role> =
        safeApiCall {
            client.post(RoleResource()) {
                setBody(request)
            }.body<Role>()
        }

    override suspend fun updateRole(id: Long, request: UpdateRoleRequest): Either<ApiResourceError, Role> =
        safeApiCall {
            client.put(RoleResource.ById(roleId = id)) {
                setBody(request)
            }.body<Role>()
        }

    override suspend fun deleteRole(id: Long): Either<ApiResourceError, Unit> =
        safeApiCall {
            client.delete(RoleResource.ById(roleId = id))
        }
}
