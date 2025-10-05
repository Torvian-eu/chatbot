package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.UserApi
import eu.torvian.chatbot.common.api.resources.UserResource
import eu.torvian.chatbot.common.api.resources.UserResource.UsersDetailed
import eu.torvian.chatbot.common.api.resources.UserResource.ById
import eu.torvian.chatbot.common.api.resources.UserResource.ById.Password
import eu.torvian.chatbot.common.api.resources.UserResource.ById.PasswordChangeRequired
import eu.torvian.chatbot.common.api.resources.UserResource.ById.Roles
import eu.torvian.chatbot.common.api.resources.UserResource.ById.Roles.ByRoleId
import eu.torvian.chatbot.common.api.resources.UserResource.ById.Status
import eu.torvian.chatbot.common.api.resources.UserResource.ById.UserDetailed
import eu.torvian.chatbot.common.models.Role
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.common.models.UserStatus
import eu.torvian.chatbot.common.models.UserWithDetails
import eu.torvian.chatbot.common.models.api.admin.AssignRoleRequest
import eu.torvian.chatbot.common.models.api.admin.ChangePasswordRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateUserRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateUserStatusRequest
import eu.torvian.chatbot.common.models.api.admin.UpdatePasswordChangeRequiredRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [UserApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's user management endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorUserApiClient(client: HttpClient) : BaseApiResourceClient(client), UserApi {

    override suspend fun getAllUsers(): Either<ApiResourceError, List<User>> {
        return safeApiCall {
            client.get(UserResource()).body<List<User>>()
        }
    }

    override suspend fun getUserById(userId: Long): Either<ApiResourceError, User> {
        return safeApiCall {
            client.get(ById(userId = userId)).body<User>()
        }
    }

    override suspend fun getAllUsersWithDetails(): Either<ApiResourceError, List<UserWithDetails>> {
        return safeApiCall {
            client.get(UsersDetailed()).body<List<UserWithDetails>>()
        }
    }

    override suspend fun getUserWithDetails(userId: Long): Either<ApiResourceError, UserWithDetails> {
        return safeApiCall {
            client.get(UserDetailed(ById(userId = userId))).body<UserWithDetails>()
        }
    }

    override suspend fun updateUser(userId: Long, request: UpdateUserRequest): Either<ApiResourceError, User> {
        return safeApiCall {
            client.put(ById(userId = userId)) {
                setBody(request)
            }.body<User>()
        }
    }

    override suspend fun updateUserStatus(userId: Long, status: UserStatus): Either<ApiResourceError, User> {
        return safeApiCall {
            client.put(Status(ById(userId = userId))) {
                setBody(UpdateUserStatusRequest(status))
            }.body<User>()
        }
    }

    override suspend fun updatePasswordChangeRequired(
        userId: Long,
        requiresPasswordChange: Boolean
    ): Either<ApiResourceError, User> {
        return safeApiCall {
            client.put(PasswordChangeRequired(ById(userId = userId))) {
                setBody(UpdatePasswordChangeRequiredRequest(requiresPasswordChange))
            }.body<User>()
        }
    }

    override suspend fun deleteUser(userId: Long): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.delete(ById(userId = userId)).body<Unit>()
        }
    }

    override suspend fun getUserRoles(userId: Long): Either<ApiResourceError, List<Role>> {
        return safeApiCall {
            client.get(Roles(ById(userId = userId))).body<List<Role>>()
        }
    }

    override suspend fun assignRoleToUser(userId: Long, request: AssignRoleRequest): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.post(Roles(ById(userId = userId))) {
                setBody(request)
            }.body<Unit>()
        }
    }

    override suspend fun revokeRoleFromUser(userId: Long, roleId: Long): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.delete(ByRoleId(Roles(ById(userId = userId)), roleId)).body<Unit>()
        }
    }

    override suspend fun changeUserPassword(userId: Long, request: ChangePasswordRequest): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.put(Password(ById(userId = userId))) {
                setBody(request)
            }.body<Unit>()
        }
    }
}
