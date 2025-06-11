package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.ChatGroup
import eu.torvian.chatbot.server.data.dao.error.GroupError

/**
 * Data Access Object for ChatGroup entities.
 */
interface GroupDao {
    /**
     * Retrieves a list of all chat groups.
     * @return A list of [ChatGroup] objects. Returns an empty list if no groups exist.
     */
    suspend fun getAllGroups(): List<ChatGroup>

    /**
     * Retrieves a single chat group by its ID.
     * @param id The ID of the group to retrieve.
     * @return [Either] a [GroupError.GroupNotFound] if the group doesn't exist, or the [ChatGroup].
     */
    suspend fun getGroupById(id: Long): Either<GroupError.GroupNotFound, ChatGroup>

    /**
     * Inserts a new chat group record into the database.
     * @param name The name for the new group.
     * @return The newly created [ChatGroup]. Unexpected errors propagate as exceptions.
     */
    suspend fun insertGroup(name: String): ChatGroup

    /**
     * Renames an existing chat group.
     * @param id The ID of the group to rename.
     * @param newName The new name for the group.
     * @return [Either] a [GroupError.GroupNotFound] if the group doesn't exist, or [Unit] on success.
     */
    suspend fun renameGroup(id: Long, newName: String): Either<GroupError, Unit>

    /**
     * Deletes a chat group by ID.
     * @param id The ID of the group to delete.
     * @return [Either] a [GroupError.GroupNotFound] if the group doesn't exist, or [Unit] on success.
     */
    suspend fun deleteGroup(id: Long): Either<GroupError.GroupNotFound, Unit>
}
