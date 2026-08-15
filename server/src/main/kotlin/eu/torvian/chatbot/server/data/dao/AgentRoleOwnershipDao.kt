package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between agent roles and users.
 *
 * The DAO operates on the table `agent_role_owners` (role_id, user_id), mirroring the
 * `chat_session_owners` family: a role has exactly one owner (role_id is the primary key).
 */
interface AgentRoleOwnershipDao {
    /**
     * Returns the user id owning the given agent role.
     *
     * @param roleId ID of the agent role.
     * @return Either [GetOwnerError.ResourceNotFound] if no such role/owner exists, or the owner's user id.
     */
    suspend fun getOwner(roleId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between the agent role and a user.
     *
     * @param roleId ID of the agent role to own.
     * @param userId ID of the user to become the owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(roleId: Long, userId: Long): Either<SetOwnerError, Unit>
}
