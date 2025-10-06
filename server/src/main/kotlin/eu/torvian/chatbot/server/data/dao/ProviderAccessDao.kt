package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.GrantAccessError
import eu.torvian.chatbot.server.data.dao.error.RevokeAccessError
import eu.torvian.chatbot.server.data.entities.UserGroupEntity

/**
 * DAO for managing group access to LLM providers with access modes.
 *
 * This DAO operates on the `llm_provider_access` table which tracks which user groups
 * have which types of access (e.g., "read", "write") to providers.
 */
interface ProviderAccessDao {
    /**
     * Retrieves all user groups that have a specific access mode to a provider.
     *
     * @param providerId ID of the provider.
     * @param accessMode The access mode to query (e.g., "read", "write").
     * @return List of [UserGroupEntity] with the specified access; empty list if none.
     */
    suspend fun getAccessGroups(providerId: Long, accessMode: String): List<UserGroupEntity>

    /**
     * Checks if any of the given groups have a specific access mode to a provider.
     *
     * @param providerId ID of the provider.
     * @param groupIds List of group IDs to check.
     * @param accessMode The access mode to verify (e.g., "read", "write").
     * @return True if at least one of the groups has the specified access, false otherwise.
     */
    suspend fun hasAccess(providerId: Long, groupIds: List<Long>, accessMode: String): Boolean

    /**
     * Grants a specific access mode to a group for a provider.
     *
     * @param providerId ID of the provider.
     * @param groupId ID of the group to grant access to.
     * @param accessMode The access mode to grant (e.g., "read", "write").
     * @return Either [GrantAccessError] or Unit on success.
     */
    suspend fun grantAccess(providerId: Long, groupId: Long, accessMode: String): Either<GrantAccessError, Unit>

    /**
     * Revokes a specific access mode from a group for a provider.
     *
     * @param providerId ID of the provider.
     * @param groupId ID of the group to revoke access from.
     * @param accessMode The access mode to revoke (e.g., "read", "write").
     * @return Either [RevokeAccessError] or Unit on success.
     */
    suspend fun revokeAccess(providerId: Long, groupId: Long, accessMode: String): Either<RevokeAccessError, Unit>

    /**
     * Retrieves all provider IDs that are accessible by any of the given groups with a specific access mode.
     *
     * @param groupIds List of group IDs to check.
     * @param accessMode The access mode to query (e.g., "read", "write").
     * @return List of provider IDs accessible by the groups; empty list if none.
     */
    suspend fun getResourcesAccessibleByGroups(groupIds: List<Long>, accessMode: String): List<Long>
}

