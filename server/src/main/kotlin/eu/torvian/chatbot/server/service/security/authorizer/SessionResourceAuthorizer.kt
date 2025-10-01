package eu.torvian.chatbot.server.service.security.authorizer

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.server.data.dao.SessionOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.service.security.ResourceType

/**
 * Authorizer for session resources. Enforces that only the owner can access or modify a session.
 */
class SessionResourceAuthorizer(
    private val sessionOwnershipDao: SessionOwnershipDao
) : ResourceAuthorizer {
    override val resourceType: ResourceType = ResourceType.SESSION

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
            sessionOwnershipDao.getOwner(resourceId).bind()
        }
        ensure(ownerId == userId) {
            ResourceAuthorizerError.AccessDenied("User $userId is not the owner")
        }
    }
}
