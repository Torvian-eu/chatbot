package eu.torvian.chatbot.server.data.dao

import arrow.core.Either
import eu.torvian.chatbot.server.data.dao.error.PermissionError
import eu.torvian.chatbot.server.data.entities.PermissionEntity

/**
 * Data Access Object for managing permissions.
 *
 * This DAO provides CRUD operations for permissions, which define specific
 * actions that can be performed on resources (action-subject pattern).
 */
interface PermissionDao {
    /**
     * Retrieves all permissions in the system.
     *
     * @return List of all [PermissionEntity] objects; empty list if no permissions exist.
     */
    suspend fun getAllPermissions(): List<PermissionEntity>

    /**
     * Retrieves a permission by its unique ID.
     *
     * @param id The unique identifier of the permission.
     * @return Either [PermissionError.PermissionNotFound] if not found, or the [PermissionEntity].
     */
    suspend fun getPermissionById(id: Long): Either<PermissionError.PermissionNotFound, PermissionEntity>

    /**
     * Retrieves a permission by its action and subject.
     *
     * @param action The action (e.g., "manage", "create", "delete").
     * @param subject The subject (e.g., "users", "public_provider").
     * @return Either [PermissionError.PermissionNotFound] if not found, or the [PermissionEntity].
     */
    suspend fun getPermissionByActionAndSubject(
        action: String,
        subject: String
    ): Either<PermissionError.PermissionNotFound, PermissionEntity>

    /**
     * Creates a new permission.
     *
     * @param action The action being permitted.
     * @param subject The subject/resource the action applies to.
     * @return Either [PermissionError.PermissionAlreadyExists] if duplicate, or the new [PermissionEntity].
     */
    suspend fun insertPermission(
        action: String,
        subject: String
    ): Either<PermissionError.PermissionAlreadyExists, PermissionEntity>

    /**
     * Deletes a permission by ID.
     *
     * @param id The unique identifier of the permission to delete.
     * @return Either [PermissionError.PermissionNotFound] if not found, or Unit on success.
     */
    suspend fun deletePermission(id: Long): Either<PermissionError.PermissionNotFound, Unit>

    /**
     * Retrieves all permissions assigned to a specific role.
     *
     * @param roleId The ID of the role.
     * @return List of [PermissionEntity] objects assigned to the role; empty list if none.
     */
    suspend fun getPermissionsByRoleId(roleId: Long): List<PermissionEntity>
}

