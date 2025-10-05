package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.common.models.core.ChatGroup
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between chat groups and users.
 *
 * Operates on `chat_group_owners` (group_id, user_id).
 */
interface GroupOwnershipDao {
    /**
     * Retrieves all chat groups owned by the given user.
     *
     * @param userId ID of the user.
     * @return List of [ChatGroup] owned by the user; empty list if none.
     */
    suspend fun getAllGroupsForUser(userId: Long): List<ChatGroup>

    /**
     * Returns the user id of the owner for the specified group.
     *
     * @param groupId ID of the chat group.
     * @return Either [GetOwnerError.ResourceNotFound] or the owner's user id.
     */
    suspend fun getOwner(groupId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between a group and a user.
     *
     * @param groupId ID of the group.
     * @param userId ID of the user to set as owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(groupId: Long, userId: Long): Either<SetOwnerError, Unit>
}
