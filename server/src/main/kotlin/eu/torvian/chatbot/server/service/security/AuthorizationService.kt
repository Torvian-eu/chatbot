package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.PermissionSpec
import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.Role
import eu.torvian.chatbot.server.service.security.error.AuthorizationError
import eu.torvian.chatbot.server.service.security.error.ResourceAuthorizationError

/**
 * Service interface for authorization and permission checking.
 *
 * This service handles role-based access control (RBAC) by checking user permissions
 * and roles. It provides both query methods (has*) and enforcement methods (require*).
 */
interface AuthorizationService {
    /**
     * Checks if a user has a specific permission.
     *
     * This method aggregates permissions from all roles assigned to the user
     * and checks if any of them match the requested action-subject pair.
     *
     * @param userId The ID of the user to check
     * @param action The action to check (e.g., "manage", "create")
     * @param subject The subject/resource (e.g., "users", "public_provider")
     * @return true if the user has the permission, false otherwise
     */
    suspend fun hasPermission(userId: Long, action: String, subject: String): Boolean

    /**
     * Overload: Checks if a user has a specific permission based on a PermissionSpec.
     *
     * @param userId The ID of the user to check
     * @param permission The permission specification (action + subject)
     * @return true if the user has the permission, false otherwise
     */
    suspend fun hasPermission(userId: Long, permission: PermissionSpec): Boolean

    /**
     * Checks if a user has a specific role.
     *
     * @param userId The ID of the user to check
     * @param roleName The name of the role (e.g., "Admin", "StandardUser")
     * @return true if the user has the role, false otherwise
     */
    suspend fun hasRole(userId: Long, roleName: String): Boolean

    /**
     * Retrieves all roles assigned to a user.
     *
     * @param userId The ID of the user
     * @return List of [Role] objects assigned to the user; empty list if none
     */
    suspend fun getUserRoles(userId: Long): List<Role>

    /**
     * Retrieves all permissions for a user (aggregated from all their roles).
     *
     * This method collects all unique permissions from all roles the user has.
     * Duplicate permissions are automatically removed.
     *
     * @param userId The ID of the user
     * @return List of [Permission] objects the user has access to; empty list if none
     */
    suspend fun getUserPermissions(userId: Long): List<Permission>

    /**
     * Ensures a user has a specific permission, raising an error if not.
     *
     * This method should be used in API routes and service methods to enforce
     * authorization requirements. It will return an error that can be mapped
     * to an appropriate HTTP response.
     *
     * @param userId The ID of the user to check
     * @param action The action to check
     * @param subject The subject/resource
     * @return Either [AuthorizationError.PermissionDenied] if denied, or Unit if allowed
     */
    suspend fun requirePermission(
        userId: Long,
        action: String,
        subject: String
    ): Either<AuthorizationError.PermissionDenied, Unit>

    /**
     * Ensures a user has a specific permission, raising an error if not.
     *
     * This overload accepts a PermissionSpec for convenience when using predefined permission constants.
     *
     * @param userId The ID of the user to check
     * @param permission The permission specification (action + subject combination)
     * @return Either [AuthorizationError.PermissionDenied] if denied, or Unit if allowed
     */
    suspend fun requirePermission(
        userId: Long,
        permission: PermissionSpec
    ): Either<AuthorizationError.PermissionDenied, Unit>

    /**
     * Ensures a user has a specific role, raising an error if not.
     *
     * This method should be used when a specific role is required (not just permissions).
     * For example, checking if a user is an admin before allowing certain operations.
     *
     * @param userId The ID of the user to check
     * @param roleName The name of the role
     * @return Either [AuthorizationError.RoleRequired] if denied, or Unit if allowed
     */
    suspend fun requireRole(
        userId: Long,
        roleName: String
    ): Either<AuthorizationError.RoleRequired, Unit>

    /**
     * Require resource-level access for a specific mode (READ/WRITE).
     * This is resource-only; callers should combine with role checks when needed.
     *
     * @param userId The ID of the user to check
     * @param resourceType The type of the resource (e.g., "group", "session", "llm_model")
     * @param resourceId The ID of the resource
     * @param accessMode The access mode (READ/WRITE)
     * @return Either [ResourceAuthorizationError] if denied, or Unit if allowed
     */
    suspend fun requireAccess(
        userId: Long,
        resourceType: ResourceType,
        resourceId: Long,
        accessMode: AccessMode
    ): Either<ResourceAuthorizationError, Unit>
}
