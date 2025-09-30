package eu.torvian.chatbot.server.service.setup

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.PermissionSpec
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.tables.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.mindrot.jbcrypt.BCrypt

/**
 * Service responsible for performing initial database setup for the user accounts system.
 *
 * This service creates the essential data required for the multi-user system to function:
 * - The initial administrator user account with full system access
 * - Basic roles and permissions for the authorization system
 *
 * Note: All users automatically belong to the virtual "All Users" group which requires
 * no database storage or explicit membership management.
 */
class InitialSetupService(
    private val userDao: UserDao,
    private val transactionScope: TransactionScope
) {
    companion object {
        const val ALL_USERS_GROUP_NAME = "All Users"
        const val ADMIN_ROLE_NAME = "Admin"
        const val STANDARD_USER_ROLE_NAME = "StandardUser"
        const val DEFAULT_ADMIN_USERNAME = "admin"
        const val DEFAULT_ADMIN_PASSWORD = "admin123" // Should be changed on first login
    }

    /**
     * Performs the complete initial setup if the database is empty.
     * This should be called once when the application starts for the first time.
     *
     * @return Either an error message if setup fails, or the created admin user entity on success.
     */
    suspend fun performInitialSetup(): Either<String, UserEntity> =
        transactionScope.transaction {
            either {
                // Check if setup has already been performed
                val existingUsers = userDao.getAllUsers()
                if (existingUsers.isNotEmpty()) {
                    return@transaction existingUsers.first().right()
                }

                // 1. Create basic roles
                val adminRoleId = createRole(ADMIN_ROLE_NAME, "Administrator with full system access")
                val standardUserRoleId = createRole(STANDARD_USER_ROLE_NAME, "Standard user with basic access")

                // 2. Create basic permissions and assign to roles
                createBasicPermissions(adminRoleId, standardUserRoleId)

                // 3. Create the initial admin user
                val adminUser = createInitialAdminUser().bind()

                // 4. Assign admin role to the admin user
                assignRoleToUser(adminUser.id, adminRoleId)

                // Note: Admin user is automatically a member of the virtual "All Users" group

                adminUser
            }
        }


    /**
     * Creates a role with the given name and description.
     */
    private fun createRole(roleName: String, description: String): Long {
        val insertStatement = RolesTable.insert {
            it[name] = roleName
            it[RolesTable.description] = description
        }
        return insertStatement[RolesTable.id].value
    }

    /**
     * Creates basic permissions and assigns them to roles.
     */
    private fun createBasicPermissions(adminRoleId: Long, standardUserRoleId: Long) {
        // Create permissions using predefined PermissionSpec instances
        val manageUsersPermId = createPermission(CommonPermissions.MANAGE_USERS)
        val createPublicProviderPermId = createPermission(CommonPermissions.CREATE_PUBLIC_PROVIDER)
        val createPublicModelPermId = createPermission(CommonPermissions.CREATE_PUBLIC_MODEL)
        val createPublicSettingsPermId = createPermission(CommonPermissions.CREATE_PUBLIC_SETTINGS)

        // Assign all permissions to admin role
        assignPermissionToRole(adminRoleId, manageUsersPermId)
        assignPermissionToRole(adminRoleId, createPublicProviderPermId)
        assignPermissionToRole(adminRoleId, createPublicModelPermId)
        assignPermissionToRole(adminRoleId, createPublicSettingsPermId)

        // Standard users get no special permissions by default
        // standardUserRoleId parameter kept for future extensibility
    }

    /**
     * Creates a permission with the given action and subject.
     */
    private fun createPermission(permission: PermissionSpec): Long {
        val insertStatement = PermissionsTable.insert {
            it[PermissionsTable.action] = permission.action
            it[PermissionsTable.subject] = permission.subject
        }
        return insertStatement[PermissionsTable.id].value
    }

    /**
     * Assigns a permission to a role.
     */
    private fun assignPermissionToRole(roleId: Long, permissionId: Long) {
        RolePermissionsTable.insert {
            it[RolePermissionsTable.roleId] = roleId
            it[RolePermissionsTable.permissionId] = permissionId
        }
    }

    /**
     * Creates the initial administrator user account.
     */
    private suspend fun createInitialAdminUser(): Either<String, UserEntity> = either {
        val hashedPassword = BCrypt.hashpw(DEFAULT_ADMIN_PASSWORD, BCrypt.gensalt())

        userDao.insertUser(
            username = DEFAULT_ADMIN_USERNAME,
            passwordHash = hashedPassword,
            email = null
        ).mapLeft { error ->
            when (error) {
                is UserError.UsernameAlreadyExists -> "Admin username already exists"
                is UserError.EmailAlreadyExists -> "Admin email already exists"
                is UserError.UserNotFound -> "Unexpected error creating admin user"
                is UserError.UserNotFoundByUsername -> "Unexpected error creating admin user"
            }
        }.bind()
    }

    /**
     * Assigns a role to a user.
     */
    private fun assignRoleToUser(userId: Long, roleId: Long) {
        UserRoleAssignmentsTable.insert {
            it[UserRoleAssignmentsTable.userId] = userId
            it[UserRoleAssignmentsTable.roleId] = roleId
        }
    }

    /**
     * Checks if the initial setup has been completed by looking for existing users.
     */
    suspend fun isSetupComplete(): Boolean {
        return transactionScope.transaction {
            UsersTable.selectAll().count() > 0
        }
    }
}
