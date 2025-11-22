package eu.torvian.chatbot.server.service.setup

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.withError
import arrow.core.right
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.CommonRoles
import eu.torvian.chatbot.common.api.CommonUserGroups
import eu.torvian.chatbot.common.api.PermissionSpec
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.tables.*
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.mindrot.jbcrypt.BCrypt

/**
 * Initializer responsible for setting up the user accounts system.
 *
 * This initializer creates the essential data required for the multi-user system to function:
 * - The initial administrator user account with full system access
 * - Basic roles and permissions for the authorization system
 * - The "All Users" group for public resource sharing
 */
class UserAccountInitializer(
    private val userDao: UserDao,
    private val userGroupService: UserGroupService,
    private val transactionScope: TransactionScope
) : DataInitializer {

    companion object {
        const val DEFAULT_ADMIN_USERNAME = "admin"
        const val DEFAULT_ADMIN_PASSWORD = "admin123" // Should be changed on first login
    }

    override val name: String = "User Account System"

    override suspend fun isInitialized(): Boolean {
        return transactionScope.transaction {
            UsersTable.selectAll().count() > 0
        }
    }

    override suspend fun initialize(): Either<String, Unit> =
        transactionScope.transaction {
            either {
                // Double-check if setup has already been performed
                val existingUsers = userDao.getAllUsers()
                if (existingUsers.isNotEmpty()) {
                    return@transaction Unit.right()
                }

                // 1. Create basic roles
                val adminRoleId = createRole(CommonRoles.ADMIN, "Administrator with full system access")
                val standardUserRoleId = createRole(CommonRoles.STANDARD_USER, "Standard user with basic access")

                // 2. Create basic permissions and assign to roles
                createBasicPermissions(adminRoleId, standardUserRoleId)

                // 3. Create the "All Users" group for public resource sharing
                val allUsersGroup = withError({ error ->
                    "Failed to create All Users group: $error"
                }) {
                    userGroupService.createGroup(
                        name = CommonUserGroups.ALL_USERS,
                        description = "Special group that automatically includes all users. Resources shared with this group are public."
                    ).bind()
                }

                // 4. Create the initial admin user
                val adminUser = createInitialAdminUser().bind()

                // 5. Assign admin role to the admin user
                assignRoleToUser(adminUser.id, adminRoleId)

                // 6. Add admin user to the "All Users" group
                userGroupService.addUserToGroup(adminUser.id, allUsersGroup.id)
                    .mapLeft { error -> "Failed to add admin to All Users group: $error" }.bind()
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
    @Suppress("UNUSED_PARAMETER")
    private fun createBasicPermissions(adminRoleId: Long, standardUserRoleId: Long) {
        // Create permissions using predefined PermissionSpec instances

        // --- Manage permissions ---
        val manageUsersPermId = createPermission(CommonPermissions.MANAGE_USERS)
        val manageRolesPermId = createPermission(CommonPermissions.MANAGE_ROLES)
        val managePermissionsPermId = createPermission(CommonPermissions.MANAGE_PERMISSIONS)
        val manageUserGroupsPermId = createPermission(CommonPermissions.MANAGE_USER_GROUPS)
        val manageLlmProvidersPermId = createPermission(CommonPermissions.MANAGE_LLM_PROVIDERS)
        val manageLlmModelsPermId = createPermission(CommonPermissions.MANAGE_LLM_MODELS)
        val manageLlmModelSettingsPermId = createPermission(CommonPermissions.MANAGE_LLM_MODEL_SETTINGS)

        // --- Create permissions ---
        createPermission(CommonPermissions.CREATE_LLM_PROVIDER)
        createPermission(CommonPermissions.CREATE_LLM_MODEL)
        createPermission(CommonPermissions.CREATE_LLM_MODEL_SETTINGS)

        // Assign manage permissions to admin role
        assignPermissionToRole(adminRoleId, manageUsersPermId)
        assignPermissionToRole(adminRoleId, manageRolesPermId)
        assignPermissionToRole(adminRoleId, managePermissionsPermId)
        assignPermissionToRole(adminRoleId, manageUserGroupsPermId)
        assignPermissionToRole(adminRoleId, manageLlmProvidersPermId)
        assignPermissionToRole(adminRoleId, manageLlmModelsPermId)
        assignPermissionToRole(adminRoleId, manageLlmModelSettingsPermId)
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
            email = null,
            status = UserStatus.ACTIVE,
            requiresPasswordChange = true  // Force password change on first login
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
}

