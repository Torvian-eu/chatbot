package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.PermissionSpec
import eu.torvian.chatbot.common.models.user.Permission
import eu.torvian.chatbot.common.models.user.Role
import eu.torvian.chatbot.server.data.dao.PermissionDao
import eu.torvian.chatbot.server.data.dao.UserRoleAssignmentDao
import eu.torvian.chatbot.server.data.entities.mappers.toPermission
import eu.torvian.chatbot.server.data.entities.mappers.toRole
import eu.torvian.chatbot.server.service.security.authorizer.ResourceAuthorizer
import eu.torvian.chatbot.server.service.security.authorizer.ResourceAuthorizerError
import eu.torvian.chatbot.server.service.security.error.AuthorizationError
import eu.torvian.chatbot.server.service.security.error.ResourceAuthorizationError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [AuthorizationService] with role-based access control.
 *
 * This implementation provides authorization functionality by:
 * - Aggregating permissions from all user roles
 * - Checking permissions against action-subject pairs
 * - Enforcing authorization requirements with detailed error reporting
 * - Logging all authorization checks for security auditing
 */
class AuthorizationServiceImpl(
    private val authorizers: Map<ResourceType, ResourceAuthorizer>,
    private val userRoleAssignmentDao: UserRoleAssignmentDao,
    private val permissionDao: PermissionDao,
    private val transactionScope: TransactionScope
) : AuthorizationService {

    companion object {
        private val logger: Logger = LogManager.getLogger(AuthorizationServiceImpl::class.java)
    }

    override suspend fun hasPermission(userId: Long, action: String, subject: String): Boolean =
        transactionScope.transaction {
            logger.debug("Checking permission for user $userId: $action on $subject")

            // Get all roles for the user
            val roles = userRoleAssignmentDao.getRolesByUserId(userId)

            if (roles.isEmpty()) {
                logger.debug("User $userId has no roles assigned")
                return@transaction false
            }

            // Get all permissions for those roles
            val permissions = roles.flatMap { role ->
                permissionDao.getPermissionsByRoleId(role.id)
            }

            // Check if any permission matches the requested action-subject pair
            val hasPermission = permissions.any {
                it.action == action && it.subject == subject
            }

            if (hasPermission) {
                logger.debug("User $userId has permission: $action on $subject")
            } else {
                logger.debug("User $userId does NOT have permission: $action on $subject")
            }

            hasPermission
        }

    override suspend fun hasPermission(userId: Long, permission: PermissionSpec): Boolean {
        return hasPermission(userId, permission.action, permission.subject)
    }

    override suspend fun hasAnyPermission(userId: Long, vararg permissions: PermissionSpec): Boolean =
        transactionScope.transaction {
            logger.debug("Checking if user $userId has any of ${permissions.size} permission(s)")

            if (permissions.isEmpty()) {
                logger.debug("No permissions requested; returning false")
                return@transaction false
            }

            // Fetch roles once
            val roles = userRoleAssignmentDao.getRolesByUserId(userId)

            if (roles.isEmpty()) {
                logger.debug("User $userId has no roles assigned")
                return@transaction false
            }

            // Fetch permissions for all roles once
            val permissionsFromRoles = roles.flatMap { role ->
                permissionDao.getPermissionsByRoleId(role.id)
            }

            if (permissionsFromRoles.isEmpty()) {
                logger.debug("User $userId has no permissions from roles")
                return@transaction false
            }

            // Build a set of (action, subject) for fast membership checks
            val permissionSet = permissionsFromRoles.map { it.action to it.subject }.toSet()

            val result = permissions.any { spec ->
                permissionSet.contains(spec.action to spec.subject)
            }

            if (result) {
                logger.debug("User $userId has at least one of the requested permissions")
            } else {
                logger.debug("User $userId does NOT have any of the requested permissions")
            }

            result
        }

    override suspend fun hasAllPermissions(userId: Long, permissions: List<PermissionSpec>): Boolean =
        transactionScope.transaction {
            logger.debug("Checking if user $userId has all of ${permissions.size} permission(s)")

            if (permissions.isEmpty()) {
                logger.debug("No permissions requested; returning false")
                return@transaction false
            }

            // Fetch roles once
            val roles = userRoleAssignmentDao.getRolesByUserId(userId)

            if (roles.isEmpty()) {
                logger.debug("User $userId has no roles assigned")
                return@transaction false
            }

            // Fetch permissions for all roles once
            val permissionsFromRoles = roles.flatMap { role ->
                permissionDao.getPermissionsByRoleId(role.id)
            }

            if (permissionsFromRoles.isEmpty()) {
                logger.debug("User $userId has no permissions from roles")
                return@transaction false
            }

            // Build a set of (action, subject) for fast membership checks
            val permissionSet = permissionsFromRoles.map { it.action to it.subject }.toSet()

            val result = permissions.all { spec ->
                permissionSet.contains(spec.action to spec.subject)
            }

            if (result) {
                logger.debug("User $userId has all of the requested permissions")
            } else {
                logger.debug("User $userId does NOT have all of the requested permissions")
            }

            result
        }

    override suspend fun requirePermission(
        userId: Long,
        action: String,
        subject: String
    ): Either<AuthorizationError.PermissionDenied, Unit> =
        transactionScope.transaction {
            either {
                logger.debug("Requiring permission for user $userId: $action on $subject")

                val hasPermission = hasPermission(userId, action, subject)

                ensure(hasPermission) {
                    logger.warn("Permission denied for user $userId: $action on $subject")
                    AuthorizationError.PermissionDenied(userId, action, subject)
                }

                logger.debug("Permission granted for user $userId: $action on $subject")
            }
        }

    override suspend fun requirePermission(
        userId: Long,
        permission: PermissionSpec
    ): Either<AuthorizationError.PermissionDenied, Unit> =
        requirePermission(userId, permission.action, permission.subject)

    override suspend fun requireAnyPermission(
        userId: Long,
        vararg permissions: PermissionSpec
    ): Either<AuthorizationError.AnyPermissionDenied, Unit> = either {
        logger.debug("Requiring any of ${permissions.size} permission(s) for user $userId")

        val hasAnyPermission = hasAnyPermission(userId, *permissions)

        ensure(hasAnyPermission) {
            logger.warn("User $userId does not have any of the required permissions")
            AuthorizationError.AnyPermissionDenied(userId, permissions.toList())
        }

        logger.debug("User $userId has at least one of the required permissions")
    }


    override suspend fun requireAllPermissions(
        userId: Long,
        permissions: List<PermissionSpec>
    ): Either<AuthorizationError.AllPermissionsDenied, Unit> = either {
        logger.debug("Requiring all of ${permissions.size} permission(s) for user $userId")

        val hasAllPermissions = hasAllPermissions(userId, permissions)

        ensure(hasAllPermissions) {
            logger.warn("User $userId does not have all of the required permissions")
            AuthorizationError.AllPermissionsDenied(userId, permissions)
        }

        logger.debug("User $userId has all of the required permissions")
    }


    override suspend fun hasRole(userId: Long, roleName: String): Boolean =
        transactionScope.transaction {
            logger.debug("Checking if user $userId has role: $roleName")

            val roles = userRoleAssignmentDao.getRolesByUserId(userId)
            val hasRole = roles.any { it.name == roleName }

            if (hasRole) {
                logger.debug("User $userId has role: $roleName")
            } else {
                logger.debug("User $userId does NOT have role: $roleName")
            }

            hasRole
        }

    override suspend fun getUserRoles(userId: Long): List<Role> =
        transactionScope.transaction {
            logger.debug("Retrieving roles for user $userId")

            val roles = userRoleAssignmentDao.getRolesByUserId(userId)
                .map { it.toRole() }

            logger.debug("User $userId has ${roles.size} role(s): ${roles.joinToString { it.name }}")
            roles
        }

    override suspend fun getUserPermissions(userId: Long): List<Permission> =
        transactionScope.transaction {
            logger.debug("Retrieving permissions for user $userId")

            // Get all roles for the user
            val roles = userRoleAssignmentDao.getRolesByUserId(userId)

            if (roles.isEmpty()) {
                logger.debug("User $userId has no roles, therefore no permissions")
                return@transaction emptyList()
            }

            // Get all permissions for those roles and remove duplicates
            val permissions = roles.flatMap { role ->
                permissionDao.getPermissionsByRoleId(role.id)
            }
                .distinctBy { it.id } // Remove duplicate permissions
                .map { it.toPermission() }

            logger.debug("User $userId has ${permissions.size} unique permission(s)")
            permissions
        }


    override suspend fun requireRole(
        userId: Long,
        roleName: String
    ): Either<AuthorizationError.RoleRequired, Unit> =
        transactionScope.transaction {
            either {
                logger.debug("Requiring role for user $userId: $roleName")

                val hasRole = hasRole(userId, roleName)

                ensure(hasRole) {
                    logger.warn("Role required but not assigned for user $userId: $roleName")
                    AuthorizationError.RoleRequired(userId, roleName)
                }

                logger.debug("Role requirement satisfied for user $userId: $roleName")
            }
        }

    override suspend fun requireAccess(
        userId: Long,
        resourceType: ResourceType,
        resourceId: Long,
        accessMode: AccessMode
    ): Either<ResourceAuthorizationError, Unit> =
        either {
            logger.debug(
                "Requiring access for user {} to {}/{} with mode {}",
                userId,
                resourceType,
                resourceId,
                accessMode
            )

            // Ensure we have an authorizer for the resource type; raise ResourceAuthorizationError if not
            val authorizer = authorizers[resourceType]
            ensure(authorizer != null) {
                logger.warn("No authorizer for resource type: $resourceType")
                ResourceAuthorizationError.UnsupportedResourceType(resourceType)
            }

            // Map any ResourceAuthorizerError into ResourceAuthorizationError
            withError({ rae: ResourceAuthorizerError ->
                when (rae) {
                    is ResourceAuthorizerError.ResourceNotFound -> ResourceAuthorizationError.ResourceNotFound(
                        resourceType,
                        resourceId
                    )

                    is ResourceAuthorizerError.AccessDenied -> ResourceAuthorizationError.AccessDenied(
                        userId,
                        resourceType,
                        resourceId,
                        accessMode
                    )
                }
            }) {
                authorizer.requireAccess(userId, resourceId, accessMode).bind()
            }

            logger.debug("Access requirement satisfied for user $userId to $resourceType/$resourceId")
        }
}
