package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.UserGroupRepository
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.UserGroupApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [UserGroupRepository] that manages user groups.
 *
 * This repository maintains an internal cache of group data using [MutableStateFlow] and
 * provides reactive updates to all observers. It delegates API operations to the injected
 * [UserGroupApi] and handles comprehensive error management through [RepositoryError].
 *
 * The repository ensures data consistency by automatically updating the internal StateFlow
 * whenever successful CRUD operations occur, eliminating the need for manual cache invalidation.
 *
 * @property userGroupApi The API client for user group-related operations
 */
class DefaultUserGroupRepository(
    private val userGroupApi: UserGroupApi
) : UserGroupRepository {

    companion object {
        private val logger = kmpLogger<DefaultUserGroupRepository>()
    }

    private val _groups = MutableStateFlow<DataState<RepositoryError, List<UserGroup>>>(DataState.Idle)
    override val groups: StateFlow<DataState<RepositoryError, List<UserGroup>>> = _groups.asStateFlow()

    override suspend fun loadGroups(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_groups.value.isLoading) return Unit.right()

        _groups.update { DataState.Loading }

        return userGroupApi.getAllGroups()
            .map { groupList ->
                _groups.update { DataState.Success(groupList) }
            }
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load user groups")
                _groups.update { DataState.Error(repositoryError) }
                repositoryError
            }
    }

    override suspend fun getGroupById(groupId: Long): Either<RepositoryError, UserGroup> {
        return userGroupApi.getGroupById(groupId)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to get user group")
            }
    }

    override suspend fun createGroup(name: String, description: String?): Either<RepositoryError, UserGroup> {
        return userGroupApi.createGroup(name, description)
            .map { newGroup ->
                // Add the new group to the internal list
                updateGroupsState { currentGroups ->
                    currentGroups + newGroup
                }
                newGroup
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to create user group")
            }
    }

    override suspend fun updateGroup(groupId: Long, name: String, description: String?): Either<RepositoryError, Unit> {
        return userGroupApi.updateGroup(groupId, name, description)
            .map {
                // Update the group in the internal list
                updateGroupsState { currentGroups ->
                    currentGroups.map { group ->
                        if (group.id == groupId) {
                            group.copy(
                                name = name,
                                description = description
                            )
                        } else {
                            group
                        }
                    }
                }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to update user group")
            }
    }

    override suspend fun deleteGroup(groupId: Long): Either<RepositoryError, Unit> {
        return userGroupApi.deleteGroup(groupId)
            .map {
                // Remove the group from the internal list
                updateGroupsState { currentGroups ->
                    currentGroups.filter { it.id != groupId }
                }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to delete user group")
            }
    }

    override suspend fun getGroupMembers(groupId: Long): Either<RepositoryError, List<User>> {
        return userGroupApi.getGroupMembers(groupId)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to get group members")
            }
    }

    override suspend fun addUserToGroup(groupId: Long, userId: Long): Either<RepositoryError, Unit> {
        return userGroupApi.addUserToGroup(groupId, userId)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to add user to group")
            }
    }

    override suspend fun removeUserFromGroup(groupId: Long, userId: Long): Either<RepositoryError, Unit> {
        return userGroupApi.removeUserFromGroup(groupId, userId)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to remove user from group")
            }
    }

    /**
     * Helper function to update the groups state with a transformation function.
     *
     * This function only updates the state if it's currently in Success state,
     * preserving the reactive nature of the repository.
     *
     * @param transform Function to transform the current list of groups
     */
    private fun updateGroupsState(transform: (List<UserGroup>) -> List<UserGroup>) {
        _groups.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                else -> currentState
            }
        }
    }
}

