package eu.torvian.chatbot.server.service.authorizer

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.data.dao.GroupOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError

/**
 * Authorizer that enforces access rules for chat groups.
 *
 * Policy: the owner of a group has both read and write privileges. This
 * implementation resolves the owner using [GroupOwnershipDao.getOwner] and maps
 * DAO logical errors into [ResourceAuthorizerError]. Technical exceptions from
 * the DAO should propagate and be handled by the global error handler.
 */
class GroupAuthorizer(
    private val groupOwnershipDao: GroupOwnershipDao,
) : ResourceAuthorizer {
    override val resourceType: String = "group"

    override suspend fun requireAccess(
        userId: Long,
        resourceId: Long,
        accessMode: AccessMode
    ): Either<ResourceAuthorizerError, Unit> =
        groupOwnershipDao.getOwner(resourceId).mapLeft { daoErr ->
            when (daoErr) {
                is GetOwnerError.ResourceNotFound -> ResourceAuthorizerError.ResourceNotFound(resourceId)
            }
        }.flatMap { ownerId ->
            if (ownerId == userId) Unit.right() else ResourceAuthorizerError.AccessDenied("User is not the owner")
                .left()
        }
}
