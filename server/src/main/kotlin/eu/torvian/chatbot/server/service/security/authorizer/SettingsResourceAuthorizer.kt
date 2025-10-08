package eu.torvian.chatbot.server.service.security.authorizer

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.server.data.dao.SettingsAccessDao
import eu.torvian.chatbot.server.data.dao.SettingsOwnershipDao
import eu.torvian.chatbot.server.data.dao.UserGroupDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.service.security.ResourceType

/**
 * Authorizer for model settings resources.
 *
 * This authorizer enforces access control for model settings using a two-tier model:
 * 1. **Ownership**: The owner of settings has full access (read and write).
 * 2. **Group-based access**: Non-owners can access settings if they belong to a user group
 *    that has been granted the requested access mode.
 *
 * The authorizer checks ownership first (fast path). If the user is not the owner,
 * it queries the user's groups and checks if any of those groups have the requested
 * access mode to the settings.
 */
class SettingsResourceAuthorizer(
    private val settingsOwnershipDao: SettingsOwnershipDao,
    private val settingsAccessDao: SettingsAccessDao,
    private val userGroupDao: UserGroupDao
) : ResourceAuthorizer {
    override val resourceType: ResourceType = ResourceType.SETTINGS

    override suspend fun requireAccess(
        userId: Long,
        resourceId: Long,
        accessMode: AccessMode
    ): Either<ResourceAuthorizerError, Unit> = either {
        // Fast path: Check if user owns the resource (owners have all access)
        val ownerId = withError({ error: GetOwnerError ->
            when (error) {
                is GetOwnerError.ResourceNotFound -> ResourceAuthorizerError.ResourceNotFound(resourceId)
            }
        }) {
            settingsOwnershipDao.getOwner(resourceId).bind()
        }

        if (ownerId == userId) {
            return@either // User is the owner, access granted.
        }

        // Slow path: Check if user has access through group membership
        val userGroups = userGroupDao.getGroupsForUser(userId)
        val hasAccess = settingsAccessDao.hasAccess(
            settingsId = resourceId,
            groupIds = userGroups.map { it.id },
            accessMode = accessMode.key
        )

        ensure(hasAccess) {
            ResourceAuthorizerError.AccessDenied(
                "User $userId does not have ${accessMode.key} access to settings $resourceId"
            )
        }
    }
}

