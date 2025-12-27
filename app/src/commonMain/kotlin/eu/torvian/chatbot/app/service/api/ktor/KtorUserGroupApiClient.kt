package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.UserGroupApi
import eu.torvian.chatbot.common.api.resources.UserGroupResource
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserGroup
import eu.torvian.chatbot.common.models.api.admin.AddUserToGroupRequest
import eu.torvian.chatbot.common.models.api.admin.CreateUserGroupRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateUserGroupRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor-based implementation of [UserGroupApi] for communicating with user group management endpoints.
 *
 * This client uses Ktor's Resources plugin for type-safe HTTP requests and provides
 * comprehensive error handling using Arrow's Either type. All requests are authenticated
 * using JWT tokens managed by the HttpClient configuration.
 */
class KtorUserGroupApiClient(
    httpClient: HttpClient
) : BaseApiResourceClient(httpClient), UserGroupApi {

    override suspend fun getAllGroups(): Either<ApiResourceError, List<UserGroup>> =
        safeApiCall {
            client.get(UserGroupResource()).body<List<UserGroup>>()
        }

    override suspend fun getGroupById(groupId: Long): Either<ApiResourceError, UserGroup> =
        safeApiCall {
            client.get(UserGroupResource.ById(groupId = groupId)).body<UserGroup>()
        }

    override suspend fun createGroup(name: String, description: String?): Either<ApiResourceError, UserGroup> =
        safeApiCall {
            client.post(UserGroupResource()) {
                setBody(CreateUserGroupRequest(name, description))
            }.body<UserGroup>()
        }

    override suspend fun updateGroup(groupId: Long, name: String, description: String?): Either<ApiResourceError, Unit> =
        safeApiCall {
            client.put(UserGroupResource.ById(groupId = groupId)) {
                setBody(UpdateUserGroupRequest(name, description))
            }
        }

    override suspend fun deleteGroup(groupId: Long): Either<ApiResourceError, Unit> =
        safeApiCall {
            client.delete(UserGroupResource.ById(groupId = groupId))
        }

    override suspend fun getGroupMembers(groupId: Long): Either<ApiResourceError, List<User>> =
        safeApiCall {
            client.get(
                UserGroupResource.ById.Members(
                    parent = UserGroupResource.ById(groupId = groupId)
                )
            ).body<List<User>>()
        }

    override suspend fun addUserToGroup(groupId: Long, userId: Long): Either<ApiResourceError, Unit> =
        safeApiCall {
            client.post(
                UserGroupResource.ById.Members(
                    parent = UserGroupResource.ById(groupId = groupId)
                )
            ) {
                setBody(AddUserToGroupRequest(userId))
            }
        }

    override suspend fun removeUserFromGroup(groupId: Long, userId: Long): Either<ApiResourceError, Unit> =
        safeApiCall {
            client.delete(
                UserGroupResource.ById.Members.ByUserId(
                    parent = UserGroupResource.ById.Members(
                        parent = UserGroupResource.ById(groupId = groupId)
                    ),
                    userId = userId
                )
            )
        }
}

