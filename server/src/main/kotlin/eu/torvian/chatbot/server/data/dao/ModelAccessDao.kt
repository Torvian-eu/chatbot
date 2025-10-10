package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.GrantAccessError
import eu.torvian.chatbot.server.data.dao.error.RevokeAccessError
import eu.torvian.chatbot.server.data.entities.UserGroupEntity

/**
 * DAO for managing group access to LLM models with access modes.
 *
 * This DAO operates on the `llm_model_access` table which tracks which user groups
 * have which types of access (e.g., "read", "write") to models.
 */
interface ModelAccessDao {
    /**
     * Retrieves all user groups that have a specific access mode to a model.
     *
     * @param modelId ID of the model.
     * @param accessMode The access mode to query (e.g., "read", "write").
     * @return List of [UserGroupEntity] with the specified access; empty list if none.
     */
    suspend fun getAccessGroups(modelId: Long, accessMode: String): List<UserGroupEntity>

    /**
     * Retrieves all user groups that have any access mode to a model, grouped by access mode.
     *
     * @param modelId ID of the model.
     * @return A map where keys are access modes and values are lists of [UserGroupEntity] that have that access.
     */
    suspend fun getAccessGroups(modelId: Long): Map<String, List<UserGroupEntity>>

    /**
     * Checks if any of the given groups have a specific access mode to a model.
     *
     * @param modelId ID of the model.
     * @param groupIds List of group IDs to check.
     * @param accessMode The access mode to verify (e.g., "read", "write").
     * @return True if at least one of the groups has the specified access, false otherwise.
     */
    suspend fun hasAccess(modelId: Long, groupIds: List<Long>, accessMode: String): Boolean

    /**
     * Grants a specific access mode to a group for a model.
     *
     * @param modelId ID of the model.
     * @param groupId ID of the group to grant access to.
     * @param accessMode The access mode to grant (e.g., "read", "write").
     * @return Either [GrantAccessError] or Unit on success.
     */
    suspend fun grantAccess(modelId: Long, groupId: Long, accessMode: String): Either<GrantAccessError, Unit>

    /**
     * Revokes a specific access mode from a group for a model.
     *
     * @param modelId ID of the model.
     * @param groupId ID of the group to revoke access from.
     * @param accessMode The access mode to revoke (e.g., "read", "write").
     * @return Either [RevokeAccessError] or Unit on success.
     */
    suspend fun revokeAccess(modelId: Long, groupId: Long, accessMode: String): Either<RevokeAccessError, Unit>

    /**
     * Revokes all access from a group for a model.
     *
     * @param modelId ID of the model.
     * @param groupId ID of the group to revoke access from.
     * @return Either [RevokeAccessError] or Unit on success.
     */
    suspend fun revokeAllAccess(modelId: Long, groupId: Long): Either<RevokeAccessError, Unit>

    /**
     * Retrieves all model IDs that are accessible by any of the given groups with a specific access mode.
     *
     * @param groupIds List of group IDs to check.
     * @param accessMode The access mode to query (e.g., "read", "write").
     * @return List of model IDs accessible by the groups; empty list if none.
     */
    suspend fun getResourcesAccessibleByGroups(groupIds: List<Long>, accessMode: String): List<Long>
}
