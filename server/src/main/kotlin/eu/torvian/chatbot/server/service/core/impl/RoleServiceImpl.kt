package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.CommonRoles
import eu.torvian.chatbot.common.models.user.Role
import eu.torvian.chatbot.server.data.dao.RoleDao
import eu.torvian.chatbot.server.data.dao.UserRoleAssignmentDao
import eu.torvian.chatbot.server.data.dao.error.RoleError
import eu.torvian.chatbot.server.data.entities.mappers.toRole
import eu.torvian.chatbot.server.service.core.RoleService
import eu.torvian.chatbot.server.service.core.error.auth.CreateRoleError
import eu.torvian.chatbot.server.service.core.error.auth.DeleteRoleError
import eu.torvian.chatbot.server.service.core.error.auth.RoleNotFoundError
import eu.torvian.chatbot.server.service.core.error.auth.UpdateRoleError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [RoleService] providing role management operations with business logic protection.
 *
 * This implementation ensures that system roles (Admin, StandardUser) are protected from
 * deletion and name changes, while allowing other operations. It uses Arrow's Raise pattern
 * for clean error handling and transaction scopes for data consistency.
 */
class RoleServiceImpl(
    private val roleDao: RoleDao,
    private val userRoleAssignmentDao: UserRoleAssignmentDao,
    private val transactionScope: TransactionScope
) : RoleService {

    companion object {
        private val logger: Logger = LogManager.getLogger(RoleServiceImpl::class.java)

        /**
         * Set of system role names that are protected from deletion and name changes.
         */
        private val PROTECTED_SYSTEM_ROLES = setOf(CommonRoles.ADMIN, CommonRoles.STANDARD_USER)
    }

    override suspend fun getAllRoles(): List<Role> = transactionScope.transaction {
        logger.debug("Retrieving all roles")
        roleDao.getAllRoles().map { it.toRole() }
    }

    override suspend fun getRoleById(id: Long): Either<RoleNotFoundError.ById, Role> =
        transactionScope.transaction {
            either {
                logger.debug("Retrieving role by ID: $id")

                val roleEntity = withError({ _: RoleError.RoleNotFound ->
                    RoleNotFoundError.ById(id)
                }) {
                    roleDao.getRoleById(id).bind()
                }

                roleEntity.toRole()
            }
        }

    override suspend fun createRole(
        name: String,
        description: String?
    ): Either<CreateRoleError, Role> = transactionScope.transaction {
        either {
            logger.info("Creating new role: $name")

            // Validate role name
            ensure(name.isNotBlank()) {
                CreateRoleError.InvalidRoleName(name, "Role name cannot be blank")
            }

            ensure(name.length <= 50) {
                CreateRoleError.InvalidRoleName(name, "Role name cannot exceed 50 characters")
            }

            // Check for system role name conflicts
            ensure(!PROTECTED_SYSTEM_ROLES.contains(name)) {
                CreateRoleError.InvalidRoleName(name, "Cannot create role with system role name")
            }

            val roleEntity = withError({ _: RoleError.RoleNameAlreadyExists ->
                CreateRoleError.RoleNameAlreadyExists(name)
            }) {
                roleDao.insertRole(name, description).bind()
            }

            logger.info("Successfully created role: $name with ID: ${roleEntity.id}")
            roleEntity.toRole()
        }
    }

    override suspend fun updateRole(
        id: Long,
        name: String,
        description: String?
    ): Either<UpdateRoleError, Role> = transactionScope.transaction {
        either {
            logger.info("Updating role ID: $id with name: $name")

            // Validate role name
            ensure(name.isNotBlank()) {
                UpdateRoleError.InvalidRoleName(name, "Role name cannot be blank")
            }

            ensure(name.length <= 50) {
                UpdateRoleError.InvalidRoleName(name, "Role name cannot exceed 50 characters")
            }

            // Get existing role
            val existingRole = withError({ _: RoleError.RoleNotFound ->
                UpdateRoleError.RoleNotFound(id)
            }) {
                roleDao.getRoleById(id).bind()
            }

            // Protect system roles from name changes
            if (PROTECTED_SYSTEM_ROLES.contains(existingRole.name) && name != existingRole.name) {
                raise(UpdateRoleError.SystemRoleNameProtected(existingRole.name))
            }

            // Check for name conflicts if name is being changed
            if (name != existingRole.name) {
                roleDao.getRoleByName(name).fold(
                    ifLeft = {
                        // Name is available, proceed
                    },
                    ifRight = {
                        // Name already exists
                        raise(UpdateRoleError.RoleNameAlreadyExists(name))
                    }
                )
            }

            // Create updated role entity
            val updatedRole = existingRole.copy(name = name, description = description)

            withError({ error: RoleError ->
                when (error) {
                    is RoleError.RoleNameAlreadyExists -> UpdateRoleError.RoleNameAlreadyExists(name)
                    is RoleError.RoleNotFound -> UpdateRoleError.RoleNotFound(id)
                    is RoleError.RoleNotFoundByName -> UpdateRoleError.RoleNotFound(id)
                }
            }) {
                roleDao.updateRole(updatedRole).bind()
            }

            logger.info("Successfully updated role ID: $id")
            updatedRole.toRole()
        }
    }

    override suspend fun deleteRole(id: Long): Either<DeleteRoleError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Attempting to delete role ID: $id")

                // Get existing role to check if it's a system role
                val existingRole = withError({ _: RoleError.RoleNotFound ->
                    DeleteRoleError.RoleNotFound(id)
                }) {
                    roleDao.getRoleById(id).bind()
                }

                // Protect system roles from deletion
                ensure(!PROTECTED_SYSTEM_ROLES.contains(existingRole.name)) {
                    DeleteRoleError.SystemRoleProtected(existingRole.name)
                }

                // Check if role is assigned to any users
                val userIds = userRoleAssignmentDao.getUserIdsByRoleId(id)
                ensure(userIds.isEmpty()) {
                    DeleteRoleError.RoleInUse(id, userIds.size)
                }

                // Delete the role
                withError({ _: RoleError.RoleNotFound ->
                    DeleteRoleError.RoleNotFound(id)
                }) {
                    roleDao.deleteRole(id).bind()
                }

                logger.info("Successfully deleted role ID: $id")
            }
        }
}
