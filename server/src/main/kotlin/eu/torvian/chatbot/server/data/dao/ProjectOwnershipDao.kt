package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError

/**
 * DAO for managing ownership links between projects and users.
 *
 * Operates on `project_owners` (project_id PK, user_id), mirroring the `agent_role_owners` family:
 * a project has exactly one owner (project_id is the primary key).
 */
interface ProjectOwnershipDao {

    /**
     * Returns the user id owning the given project.
     *
     * @param projectId ID of the project.
     * @return Either [GetOwnerError.ResourceNotFound] if no such project/owner exists, or the owner's user id.
     */
    suspend fun getOwner(projectId: Long): Either<GetOwnerError, Long>

    /**
     * Creates an ownership link between the project and a user.
     *
     * @param projectId ID of the project to own.
     * @param userId ID of the user to become the owner.
     * @return Either [SetOwnerError] or Unit on success.
     */
    suspend fun setOwner(projectId: Long, userId: Long): Either<SetOwnerError, Unit>
}