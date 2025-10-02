package eu.torvian.chatbot.server.service.core

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.service.core.error.group.CreateGroupError
import eu.torvian.chatbot.server.service.core.error.group.DeleteGroupError
import eu.torvian.chatbot.server.service.core.error.group.RenameGroupError

/**
 * Service interface for managing chat session groups.
 * Defines business logic for group creation, retrieval, renaming, and deletion.
 */
interface GroupService {
    /**
     * Retrieves a list of all chat groups owned by the specified user.
     * @param userId The ID of the user whose groups to retrieve.
     * @return A list of [ChatGroup] objects. Returns an empty list if no groups exist.
     */
    suspend fun getAllGroups(userId: Long): List<ChatGroup>

    /**
     * Creates a new chat group owned by the specified user.
     * @param userId The ID of the user who will own the group.
     * @param name The name for the new group. Must not be blank.
     * @return Either a [CreateGroupError] if the name is invalid or creation fails,
     *         or the newly created [ChatGroup].
     */
    suspend fun createGroup(userId: Long, name: String): Either<CreateGroupError, ChatGroup>

    /**
     * Renames an existing chat group. Authorization (ownership or grants) must be enforced by the caller.
     * @param id The ID of the group to rename.
     * @param newName The new name for the group. Must not be blank.
     * @return Either a [RenameGroupError] if the group is not found or the new name is invalid,
     *         or Unit if successful.
     */
    suspend fun renameGroup(id: Long, newName: String): Either<RenameGroupError, Unit>

    /**
     * Deletes a chat group by ID. Authorization (ownership or grants) must be enforced by the caller.
     * Sessions previously assigned to this group will become ungrouped.
     * @param id The ID of the group to delete.
     * @return Either a [DeleteGroupError] if the group doesn't exist, or Unit if successful.
     */
    suspend fun deleteGroup(id: Long): Either<DeleteGroupError, Unit>
}
