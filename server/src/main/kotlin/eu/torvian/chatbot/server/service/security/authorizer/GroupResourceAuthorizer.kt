package eu.torvian.chatbot.server.service.security.authorizer

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.server.data.dao.GroupOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.service.security.ResourceType

/**
 * Authorizer that enforces access rules for chat groups.
 *
 * Policy: the owner of a group has both read and write privileges. This
 * implementation resolves the owner using [GroupOwnershipDao.getOwner] and maps
 * DAO logical errors into [ResourceAuthorizerError]. Technical exceptions from
 * the DAO should propagate and be handled by the global error handler.
 */
class GroupResourceAuthorizer(
    private val groupOwnershipDao: GroupOwnershipDao,
) : ResourceAuthorizer {
    override val resourceType: ResourceType = ResourceType.GROUP

    override suspend fun requireAccess(
        userId: Long,
        resourceId: Long,
        accessMode: AccessMode
    ): Either<ResourceAuthorizerError, Unit> = either {
        val ownerId = withError({ err: GetOwnerError ->
            when (err) {
                is GetOwnerError.ResourceNotFound -> ResourceAuthorizerError.ResourceNotFound(resourceId)
            }
        }) {
            groupOwnershipDao.getOwner(resourceId).bind()
        }
        ensure(ownerId == userId) {
            ResourceAuthorizerError.AccessDenied("User $userId is not the owner")
        }
    }
}
