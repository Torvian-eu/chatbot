package eu.torvian.chatbot.app.repository

import arrow.core.Either
import eu.torvian.chatbot.app.domain.contracts.DataState
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.common.models.api.core.CreateGroupRequest
import eu.torvian.chatbot.common.models.api.core.RenameGroupRequest
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for managing chat session groups.
 *
 * This repository serves as the single source of truth for group data in the application,
 * providing reactive data streams through StateFlow and handling all group-related operations.
 * It abstracts the underlying API layer and provides comprehensive error handling through
 * the RepositoryError hierarchy.
 *
 * The repository maintains an internal cache of group data and automatically updates
 * all observers when changes occur, ensuring data consistency across the application.
 */
interface GroupRepository {
    
    /**
     * Reactive stream of all chat session groups.
     * 
     * This StateFlow provides real-time updates whenever the group data changes,
     * allowing ViewModels and other consumers to automatically react to data changes
     * without manual refresh operations.
     *
     * @return StateFlow containing the current state of all groups wrapped in DataState
     */
    val groups: StateFlow<DataState<RepositoryError, List<ChatGroup>>>

    /**
     * Loads all chat session groups from the backend.
     *
     * This operation fetches the latest group data and updates the internal StateFlow.
     * If a load operation is already in progress, this method returns immediately
     * without starting a duplicate operation.
     *
     * @return Either.Right with Unit on successful load, or Either.Left with RepositoryError on failure
     */
    suspend fun loadGroups(): Either<RepositoryError, Unit>

    /**
     * Creates a new chat session group.
     *
     * Upon successful creation, the new group is automatically added to the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param request The group creation request containing the name for the new group
     * @return Either.Right with the created ChatGroup on success, or Either.Left with RepositoryError on failure
     */
    suspend fun createGroup(request: CreateGroupRequest): Either<RepositoryError, ChatGroup>

    /**
     * Renames an existing chat session group.
     *
     * Upon successful update, the modified group replaces the existing one in the
     * internal StateFlow, triggering updates to all observers.
     *
     * @param groupId The unique identifier of the group to rename
     * @param request The rename request containing the new name
     * @return Either.Right with Unit on successful update, or Either.Left with RepositoryError on failure
     */
    suspend fun renameGroup(groupId: Long, request: RenameGroupRequest): Either<RepositoryError, Unit>

    /**
     * Deletes a chat session group.
     *
     * Sessions previously assigned to this group will become ungrouped by the backend.
     * Upon successful deletion, the group is automatically removed from the internal
     * StateFlow, triggering updates to all observers.
     *
     * @param groupId The unique identifier of the group to delete
     * @return Either.Right with Unit on successful deletion, or Either.Left with RepositoryError on failure
     */
    suspend fun deleteGroup(groupId: Long): Either<RepositoryError, Unit>
}
