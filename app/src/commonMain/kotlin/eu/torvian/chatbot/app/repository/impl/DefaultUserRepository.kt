package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.repository.UserRepository
import eu.torvian.chatbot.app.service.api.UserApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.Role
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.common.models.UserStatus
import eu.torvian.chatbot.common.models.UserWithDetails
import eu.torvian.chatbot.common.models.api.admin.AssignRoleRequest
import eu.torvian.chatbot.common.models.api.admin.ChangePasswordRequest
import eu.torvian.chatbot.common.models.api.admin.UpdateUserRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [UserRepository] that follows the Single Source of Truth principle.
 *
 * This repository maintains a single StateFlow of all users and provides operations
 * for user management. It delegates state management responsibility to consumers (ViewModels),
 * keeping the repository focused on data access and manipulation.
 *
 * @property userApi The API client for user management operations
 */
class DefaultUserRepository(
    private val userApi: UserApi
) : UserRepository {

    companion object {
        private val logger = kmpLogger<DefaultUserRepository>()
    }

    private val _users = MutableStateFlow<DataState<RepositoryError, List<UserWithDetails>>>(DataState.Idle)
    override val users: StateFlow<DataState<RepositoryError, List<UserWithDetails>>> = _users.asStateFlow()

    override suspend fun loadUsers(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_users.value.isLoading) return Unit.right()

        _users.update { DataState.Loading }

        return userApi.getAllUsersWithDetails().fold(
            ifLeft = { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load users")
                logger.warn("Failed to load users: ${repositoryError.message}")
                _users.update { DataState.Error(repositoryError) }
                repositoryError.left()
            },
            ifRight = { usersWithDetails ->
                _users.update { DataState.Success(usersWithDetails) }
                logger.debug("Successfully loaded ${usersWithDetails.size} users")
                Unit.right()
            }
        )
    }

    override suspend fun loadUserDetails(userId: Long): Either<RepositoryError, UserWithDetails> {
        logger.info("Loading details for user ID: $userId")
        return userApi.getUserWithDetails(userId).fold(
            ifLeft = { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load user details for ID: $userId")
                logger.warn("Failed to load user details for ID: $userId: ${repositoryError.message}")
                repositoryError.left()
            },
            ifRight = { userWithDetails ->
                logger.info("Successfully loaded details for user ID: $userId")
                // Update or insert the user in the main list
                updateUsersState { list ->
                    if (list.any { it.id == userWithDetails.id })
                        list.map { if (it.id == userWithDetails.id) userWithDetails else it }
                    else list + userWithDetails
                }
                userWithDetails.right()
            }
        )
    }

    override suspend fun updateUser(userId: Long, request: UpdateUserRequest): Either<RepositoryError, User> {
        logger.info("Updating user ID: $userId")
        return userApi.updateUser(userId, request).fold(
            ifLeft = { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to update user ID: $userId")
                logger.warn("Failed to update user ID: $userId: ${repositoryError.message}")
                repositoryError.left()
            },
            ifRight = { updatedUser ->
                logger.info("Successfully updated user ID: $userId")
                // Merge into UserWithDetails state preserving roles and groups
                updateUsersState { list ->
                    list.map { current ->
                        if (current.id == updatedUser.id) {
                            current.copy(
                                username = updatedUser.username,
                                email = updatedUser.email,
                                status = updatedUser.status
                            )
                        } else current
                    }
                }
                updatedUser.right()
            }
        )
    }

    override suspend fun updateUserStatus(userId: Long, status: UserStatus): Either<RepositoryError, User> {
        logger.info("Updating status for user ID: $userId to $status")
        return userApi.updateUserStatus(userId, status).fold(
            ifLeft = { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to update status for user ID: $userId")
                logger.warn("Failed to update status for user ID: $userId: ${repositoryError.message}")
                repositoryError.left()
            },
            ifRight = { updatedUser ->
                logger.info("Successfully updated status for user ID: $userId to ${updatedUser.status}")
                updateUsersState { list ->
                    list.map { current ->
                        if (current.id == updatedUser.id) current.copy(status = updatedUser.status) else current
                    }
                }
                updatedUser.right()
            }
        )
    }

    override suspend fun updatePasswordChangeRequired(
        userId: Long,
        requiresPasswordChange: Boolean
    ): Either<RepositoryError, User> {
        logger.info("Updating password change required flag for user ID: $userId to $requiresPasswordChange")
        return userApi.updatePasswordChangeRequired(userId, requiresPasswordChange).fold(
            ifLeft = { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to update password change required for user ID: $userId")
                logger.warn("Failed to update password change required for user ID: $userId: ${repositoryError.message}")
                repositoryError.left()
            },
            ifRight = { updatedUser ->
                logger.info("Successfully updated password change required flag for user ID: $userId to ${updatedUser.requiresPasswordChange}")
                updateUsersState { list ->
                    list.map { current ->
                        if (current.id == updatedUser.id) current.copy(requiresPasswordChange = updatedUser.requiresPasswordChange) else current
                    }
                }
                updatedUser.right()
            }
        )
    }

    override suspend fun deleteUser(userId: Long): Either<RepositoryError, Unit> {
        logger.info("Deleting user ID: $userId")
        return userApi.deleteUser(userId).fold(
            ifLeft = { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to delete user ID: $userId")
                logger.warn("Failed to delete user ID: $userId: ${repositoryError.message}")
                repositoryError.left()
            },
            ifRight = {
                logger.info("Successfully deleted user ID: $userId")
                updateUsersState { list ->
                    list.filterNot { it.id == userId }
                }
                Unit.right()
            }
        )
    }

    override suspend fun getUserRoles(userId: Long): Either<RepositoryError, List<Role>> {
        logger.info("Getting roles for user ID: $userId")
        return userApi.getUserRoles(userId).fold(
            ifLeft = { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to get roles for user ID: $userId")
                logger.warn("Failed to get roles for user ID: $userId: ${repositoryError.message}")
                repositoryError.left()
            },
            ifRight = { roles ->
                logger.debug("Successfully retrieved ${roles.size} roles for user ID: $userId")
                roles.right()
            }
        )
    }

    override suspend fun assignRoleToUser(userId: Long, role: Role): Either<RepositoryError, Unit> {
        logger.info("Assigning role '${role.name}' to user ID: $userId")
        return userApi.assignRoleToUser(userId, AssignRoleRequest(role.id)).fold(
            ifLeft = { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to assign role '${role.name}' to user ID: $userId")
                logger.warn("Failed to assign role '${role.name}' to user ID: $userId: ${repositoryError.message}")
                repositoryError.left()
            },
            ifRight = {
                logger.info("Successfully assigned role '${role.name}' to user ID: $userId")
                updateUsersState { list ->
                    list.map { current ->
                        if (current.id == userId) {
                            current.copy(roles = current.roles + role)
                        } else current
                    }
                }
                Unit.right()
            }
        )
    }

    override suspend fun revokeRoleFromUser(userId: Long, role: Role): Either<RepositoryError, Unit> {
        logger.info("Revoking role '${role.name}' from user ID: $userId")
        return userApi.revokeRoleFromUser(userId, role.id).fold(
            ifLeft = { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to revoke role '${role.name}' from user ID: $userId")
                logger.warn("Failed to revoke role '${role.name}' from user ID: $userId: ${repositoryError.message}")
                repositoryError.left()
            },
            ifRight = {
                logger.info("Successfully revoked role '${role.name}' from user ID: $userId")
                updateUsersState { list ->
                    list.map { current ->
                        if (current.id == userId) {
                            current.copy(roles = current.roles.filterNot { it.id == role.id })
                        } else current
                    }
                }
                Unit.right()
            }
        )
    }

    override suspend fun changeUserPassword(userId: Long, request: ChangePasswordRequest): Either<RepositoryError, Unit> {
        logger.info("Changing password for user ID: $userId")
        return userApi.changeUserPassword(userId, request).fold(
            ifLeft = { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to change password for user ID: $userId")
                logger.warn("Failed to change password for user ID: $userId: ${repositoryError.message}")
                repositoryError.left()
            },
            ifRight = {
                logger.info("Successfully changed password for user ID: $userId")
                Unit.right()
            }
        )
    }

    /**
     * Helper function to update the users state when it's in Success state.
     */
    private fun updateUsersState(transform: (List<UserWithDetails>) -> List<UserWithDetails>) {
        _users.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                is DataState.Idle -> DataState.Success(transform(emptyList()))
                else -> currentState
            }
        }
    }
}
