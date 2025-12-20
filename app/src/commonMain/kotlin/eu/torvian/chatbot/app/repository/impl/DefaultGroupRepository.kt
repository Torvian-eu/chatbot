package eu.torvian.chatbot.app.repository.impl

import arrow.core.Either
import arrow.core.right
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.app.repository.GroupRepository
import eu.torvian.chatbot.app.repository.RepositoryError
import eu.torvian.chatbot.app.repository.toRepositoryError
import eu.torvian.chatbot.app.service.api.GroupApi
import eu.torvian.chatbot.app.utils.misc.kmpLogger
import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.common.models.api.core.CreateGroupRequest
import eu.torvian.chatbot.common.models.api.core.RenameGroupRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Default implementation of [GroupRepository] that manages chat session groups.
 *
 * This repository maintains an internal cache of group data using [MutableStateFlow] and
 * provides reactive updates to all observers. It delegates API operations to the injected
 * [GroupApi] and handles comprehensive error management through [RepositoryError].
 *
 * The repository ensures data consistency by automatically updating the internal StateFlow
 * whenever successful CRUD operations occur, eliminating the need for manual cache invalidation.
 *
 * @property groupApi The API client for group-related operations
 */
class DefaultGroupRepository(
    private val groupApi: GroupApi
) : GroupRepository {

    companion object {
        private val logger = kmpLogger<DefaultGroupRepository>()
    }

    private val _groups = MutableStateFlow<DataState<RepositoryError, List<ChatGroup>>>(DataState.Idle)
    override val groups: StateFlow<DataState<RepositoryError, List<ChatGroup>>> = _groups.asStateFlow()

    override suspend fun loadGroups(): Either<RepositoryError, Unit> {
        // Prevent duplicate loading operations
        if (_groups.value.isLoading) return Unit.right()

        _groups.update { DataState.Loading }

        return groupApi.getAllGroups()
            .map { groupList ->
                _groups.update { DataState.Success(groupList) }
            }
            .mapLeft { apiResourceError ->
                val repositoryError = apiResourceError.toRepositoryError("Failed to load groups")
                _groups.update { DataState.Error(repositoryError) }
                repositoryError
            }
    }

    override suspend fun createGroup(request: CreateGroupRequest): Either<RepositoryError, ChatGroup> {
        return groupApi.createGroup(request)
            .map { newGroup ->
                // Add the new group to the internal list
                updateGroupsState { currentGroups ->
                    currentGroups + newGroup
                }
                newGroup
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to create group")
            }
    }

    override suspend fun renameGroup(groupId: Long, request: RenameGroupRequest): Either<RepositoryError, Unit> {
        return groupApi.renameGroup(groupId, request)
            .map {
                // Update the group in the internal list
                updateGroupsState { currentGroups ->
                    currentGroups.map { group ->
                        if (group.id == groupId) {
                            group.copy(name = request.name)
                        } else {
                            group
                        }
                    }
                }
            }
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to rename group")
            }
    }

    override suspend fun deleteGroup(groupId: Long): Either<RepositoryError, Unit> {
        return groupApi.deleteGroup(groupId)
            .mapLeft { apiResourceError ->
                apiResourceError.toRepositoryError("Failed to delete group")
            }
            .onRight {
                // invalidate cache to trigger reload
                _groups.update { DataState.Idle }
            }
    }

    /**
     * Updates the internal StateFlow of groups using the provided transformation.
     * @param transform Lambda that transforms the current list of groups
     */
    private fun updateGroupsState(transform: (List<ChatGroup>) -> List<ChatGroup>) {
        _groups.update { currentState ->
            when (currentState) {
                is DataState.Success -> DataState.Success(transform(currentState.data))
                is DataState.Idle -> DataState.Success(transform(emptyList()))
                else -> {
                    logger.warn("Tried to update groups but they're not in Success state")
                    currentState
                }
            }
        }
    }
}
