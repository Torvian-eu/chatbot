package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.security.AccountValidationPolicy
import eu.torvian.chatbot.common.security.UsernameValidator
import eu.torvian.chatbot.common.security.error.CharacterType
import eu.torvian.chatbot.common.security.error.PasswordValidationError
import eu.torvian.chatbot.server.data.dao.RoleDao
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserRoleAssignmentDao
import eu.torvian.chatbot.server.data.dao.error.RoleError
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.dao.error.UserRoleAssignmentError
import eu.torvian.chatbot.server.data.entities.mappers.toRole
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.*
import eu.torvian.chatbot.server.service.security.PasswordService
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock

/**
 * Implementation of [UserService] with secure user registration and admin operations.
 *
 * @param userDao DAO for user data access
 * @param passwordService Service for password hashing and validation
 * @param roleDao DAO for role data access
 * @param userRoleAssignmentDao DAO for user-role assignment data access
 * @param userGroupService Service for group operations
 * @param transactionScope Transaction scope for database operations
 * @param policy The account validation policy containing username and password rules
 * @param operatorToolDefinitionSeeder Seeder that grants each new user their own operator-tool
 *            instances (e.g. `spawn_agent`) immediately after registration.
 * @param serverBuiltInToolDefinitionSeeder Seeder that grants each new user their own server
 *            built-in tool instances (e.g. `list_agent_roles`) immediately after registration.
 */
class UserServiceImpl(
    private val userDao: UserDao,
    private val passwordService: PasswordService,
    private val roleDao: RoleDao,
    private val userRoleAssignmentDao: UserRoleAssignmentDao,
    private val userGroupService: UserGroupService,
    private val transactionScope: TransactionScope,
    private val policy: AccountValidationPolicy,
    private val operatorToolDefinitionSeeder: OperatorToolDefinitionSeeder,
    private val serverBuiltInToolDefinitionSeeder: ServerBuiltInToolDefinitionSeeder
) : UserService {

    companion object {
        private val logger: Logger = LogManager.getLogger(UserServiceImpl::class.java)
    }

    override suspend fun registerUser(
        username: String,
        password: String,
        email: String?
    ): Either<RegisterUserError, User> {
        logger.info("Registering new user: $username")

        return provisionUserAccount(
            username = username,
            password = password,
            email = email,
            status = UserStatus.DISABLED,
            requiresPasswordChange = false
        ).mapLeft(::toRegisterUserError)
    }

    override suspend fun createUser(
        username: String,
        password: String,
        email: String?,
        requiresPasswordChange: Boolean
    ): Either<CreateUserError, User> {
        logger.info("Creating user account: $username (requiresPasswordChange=$requiresPasswordChange)")

        return provisionUserAccount(
            username = username,
            password = password,
            email = email,
            status = UserStatus.ACTIVE,
            requiresPasswordChange = requiresPasswordChange
        ).mapLeft(::toCreateUserError)
    }

    /**
     * Runs the shared account provisioning pipeline: policy validation, password hashing, account
     * insert, "All Users" group membership, and per-user tool seeding inside one transaction so any
     * failure leaves no partially provisioned account behind.
     *
     * @param username Unique username for the new account
     * @param password Plaintext password (validated against the policy, then hashed)
     * @param email Optional email address (must be unique if provided)
     * @param status Initial account status chosen by the calling creation path
     * @param requiresPasswordChange Whether the user must set a new password on first login
     * @return Either [UserProvisioningError] on failure, or the newly created [User]
     */
    private suspend fun provisionUserAccount(
        username: String,
        password: String,
        email: String?,
        status: UserStatus,
        requiresPasswordChange: Boolean
    ): Either<UserProvisioningError, User> = transactionScope.transaction {
        either {
            // Validate username using the shared policy validator
            val usernameValidator = UsernameValidator(policy.usernameConfig)
            usernameValidator.validate(username)?.let { errorMessage ->
                raise(UserProvisioningError.InvalidInput(errorMessage))
            }

            ensure(email?.isBlank() != true) { UserProvisioningError.InvalidInput("Email cannot be blank if provided") }

            // Validate password strength (uses policy config via passwordService)
            withError(PasswordValidationError::toProvisioningError) {
                passwordService.validatePasswordStrength(password).bind()
            }

            // Hash the password
            val hashedPassword = passwordService.hashPassword(password)

            // Create the user account
            val newUser = withError({ userError ->
                when (userError) {
                    is UserError.UsernameAlreadyExists ->
                        UserProvisioningError.UsernameAlreadyExists(userError.username)

                    is UserError.EmailAlreadyExists ->
                        UserProvisioningError.EmailAlreadyExists(userError.email)

                    else -> UserProvisioningError.InvalidInput("Failed to create user account")
                }
            }) {
                userDao.insertUser(
                    username,
                    hashedPassword,
                    email,
                    status = status,
                    requiresPasswordChange = requiresPasswordChange
                ).bind()
            }

            // Add user to the "All Users" group
            val allUsersGroup = withError({ error ->
                logger.error("Failed to get All Users group for new user $username: $error")
                UserProvisioningError.GroupAssignmentFailed("Failed to add user to All Users group")
            }) {
                userGroupService.getAllUsersGroup().bind()
            }

            withError({ error ->
                logger.error("Failed to add user $username to All Users group: $error")
                UserProvisioningError.GroupAssignmentFailed("Failed to add user to All Users group")
            }) {
                userGroupService.addUserToGroup(newUser.id, allUsersGroup.id).bind()
            }

            // Seed the new user's operator-tool instances (e.g. spawn_agent) so their per-user tool
            // rows exist before they ever open a chat. The seeder joins the active provisioning
            // transaction, keeping account creation atomic.
            withError({ error ->
                logger.error("Failed to seed operator tools for new user $username: $error")
                UserProvisioningError.ToolProvisioningFailed("Failed to initialize user tool configuration")
            }) {
                operatorToolDefinitionSeeder.ensureForUser(newUser.id).bind()
            }

            // Seed the new user's server built-in tool instances (e.g. list_agent_roles) in the same
            // provisioning transaction, mirroring the operator-tool hook above.
            withError({ error ->
                logger.error("Failed to seed server built-in tools for new user $username: $error")
                UserProvisioningError.ToolProvisioningFailed("Failed to initialize user tool configuration")
            }) {
                serverBuiltInToolDefinitionSeeder.ensureForUser(newUser.id).bind()
            }

            logger.info("Successfully provisioned user: $username (ID: ${newUser.id}, status=$status)")
            newUser.toUser()
        }
    }

    override suspend fun getUserByUsername(username: String): Either<UserNotFoundError.ByUsername, User> =
        userDao.getUserByUsername(username).mapLeft { UserNotFoundError.ByUsername(username) }.map { it.toUser() }

    override suspend fun getUserById(id: Long): Either<UserNotFoundError.ById, User> =
        userDao.getUserById(id).mapLeft { UserNotFoundError.ById(id) }.map { it.toUser() }

    override suspend fun updateLastLogin(userId: Long): Either<UserNotFoundError.ById, Unit> =
        userDao.updateLastLogin(userId, Clock.System.now().toEpochMilliseconds())
            .mapLeft { UserNotFoundError.ById(userId) }

    override suspend fun getAllUsers(): List<User> =
        userDao.getAllUsers().map { it.toUser() }

    // --- Admin Operations ---

    override suspend fun getAllUsersWithDetails() = transactionScope.transaction {
        userDao.getAllUsersWithDetails()
    }

    override suspend fun getUserWithDetails(userId: Long) = transactionScope.transaction {
        userDao.getUserByIdWithDetails(userId)
            .mapLeft { UserNotFoundError.ById(userId) }
    }

    override suspend fun updateUserStatus(userId: Long, status: UserStatus, requestingUserId: Long) =
        transactionScope.transaction {
            either {
                logger.info("Updating status for user $userId to $status by requester $requestingUserId")

                // Prevent a user from modifying their own status (self-lockout)
                ensure(userId != requestingUserId) {
                    logger.warn("User $requestingUserId attempted to modify their own status")
                    UpdateUserError.CannotModifyOwnStatus(userId)
                }

                // Delegate to DAO and translate DAO errors into service errors
                withError({ _: UserError.UserNotFound ->
                    UpdateUserError.UserNotFound(userId)
                }) {
                    userDao.updateUserStatus(userId, status).bind()
                }
            }
        }

    override suspend fun updatePasswordChangeRequired(
        userId: Long,
        requiresPasswordChange: Boolean
    ): Either<UpdateUserError, User> =
        transactionScope.transaction {
            either {
                withError({ _: UserError.UserNotFound ->
                    UpdateUserError.UserNotFound(userId)
                }) {
                    userDao.updatePasswordChangeRequired(userId, requiresPasswordChange).bind()
                }
            }
        }

    override suspend fun updateUser(
        userId: Long,
        username: String,
        email: String?
    ): Either<UpdateUserError, User> = transactionScope.transaction {
        either {
            logger.info("Updating user $userId: username=$username, email=$email")

            // Validate input
            ensure(username.isNotBlank()) {
                UpdateUserError.InvalidInput("Username cannot be blank")
            }
            ensure(email?.isBlank() != true) {
                UpdateUserError.InvalidInput("Email cannot be blank if provided")
            }

            // Get existing user
            val existingUser = withError({ _: UserError.UserNotFound ->
                UpdateUserError.UserNotFound(userId)
            }) {
                userDao.getUserById(userId).bind()
            }

            // Update user
            val updatedUser = existingUser.copy(
                username = username,
                email = email
            )

            withError({ daoError ->
                when (daoError) {
                    is UserError.UserNotFound ->
                        UpdateUserError.UserNotFound(userId)

                    is UserError.UsernameAlreadyExists ->
                        UpdateUserError.UsernameAlreadyExists(username)

                    is UserError.EmailAlreadyExists ->
                        UpdateUserError.EmailAlreadyExists(email ?: "")

                    else -> UpdateUserError.InvalidInput("Failed to update user")
                }
            }) {
                userDao.updateUser(updatedUser).bind()
            }

            logger.info("Successfully updated user $userId")
            updatedUser.toUser()
        }
    }

    override suspend fun deleteUser(userId: Long): Either<DeleteUserError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Attempting to delete user $userId")

                // Check if user exists
                withError({ _: UserError.UserNotFound ->
                    DeleteUserError.UserNotFound(userId)
                }) {
                    userDao.getUserById(userId).bind()
                }

                // Check if this is the last admin
                val isLastAdmin = isLastAdmin(userId)
                ensure(!isLastAdmin) {
                    logger.warn("Cannot delete user $userId: last admin in system")
                    DeleteUserError.CannotDeleteLastAdmin(userId)
                }

                // Delete user
                withError({ _: UserError.UserNotFound ->
                    DeleteUserError.UserNotFound(userId)
                }) {
                    userDao.deleteUser(userId).bind()
                }

                logger.info("Successfully deleted user $userId")
            }
        }

    override suspend fun assignRoleToUser(
        userId: Long,
        roleId: Long
    ): Either<AssignRoleError, Unit> = transactionScope.transaction {
        either {
            logger.info("Assigning role $roleId to user $userId")

            withError({ daoError ->
                when (daoError) {
                    is UserRoleAssignmentError.ForeignKeyViolation ->
                        AssignRoleError.UserOrRoleNotFound(userId, roleId)

                    is UserRoleAssignmentError.AssignmentAlreadyExists ->
                        AssignRoleError.RoleAlreadyAssigned(userId, roleId)

                    is UserRoleAssignmentError.AssignmentNotFound ->
                        AssignRoleError.UserOrRoleNotFound(userId, roleId)
                }
            }) {
                userRoleAssignmentDao.assignRoleToUser(userId, roleId).bind()
            }

            logger.info("Successfully assigned role $roleId to user $userId")
        }
    }

    override suspend fun revokeRoleFromUser(
        userId: Long,
        roleId: Long
    ): Either<RevokeRoleError, Unit> = transactionScope.transaction {
        either {
            logger.info("Revoking role $roleId from user $userId")

            // Check if this is the admin role
            val role = withError({ _: RoleError.RoleNotFound ->
                RevokeRoleError.RoleNotFound(roleId)
            }) {
                roleDao.getRoleById(roleId).bind()
            }

            // If revoking admin role, check if this is the last admin
            if (role.name == "Admin") {
                val lastAdmin = isLastAdmin(userId)
                ensure(!lastAdmin) {
                    logger.warn("Cannot revoke admin role from user $userId: last admin")
                    RevokeRoleError.CannotRevokeLastAdminRole(userId)
                }
            }

            // Revoke role
            withError({ _: UserRoleAssignmentError.AssignmentNotFound ->
                RevokeRoleError.RoleNotAssigned(userId, roleId)
            }) {
                userRoleAssignmentDao.revokeRoleFromUser(userId, roleId).bind()
            }

            logger.info("Successfully revoked role $roleId from user $userId")
        }
    }

    override suspend fun getUserRoles(userId: Long) = transactionScope.transaction {
        logger.debug("Retrieving roles for user $userId")
        userRoleAssignmentDao.getRolesByUserId(userId)
            .map { it.toRole() }
    }

    override suspend fun changePassword(
        userId: Long,
        newPassword: String
    ): Either<ChangePasswordError, Unit> = transactionScope.transaction {
        either {
            logger.info("Changing password for user $userId")

            // Validate password strength
            withError({ passwordError ->
                when (passwordError) {
                    is PasswordValidationError.Empty ->
                        ChangePasswordError.InvalidPassword("Password cannot be empty")

                    is PasswordValidationError.OnlyWhitespace ->
                        ChangePasswordError.InvalidPassword("Password cannot contain only whitespace")

                    is PasswordValidationError.TooShort ->
                        ChangePasswordError.InvalidPassword(
                            "Password must be at least ${passwordError.minLength} characters"
                        )

                    is PasswordValidationError.TooLong ->
                        ChangePasswordError.InvalidPassword(
                            "Password must be no more than ${passwordError.maxLength} characters"
                        )

                    is PasswordValidationError.MissingCharacterTypes ->
                        ChangePasswordError.InvalidPassword(
                            "Password must contain required character types"
                        )

                    is PasswordValidationError.TooCommon ->
                        ChangePasswordError.InvalidPassword(passwordError.reason)
                }
            }) {
                passwordService.validatePasswordStrength(newPassword).bind()
            }

            // Get existing user
            val existingUser = withError({ _: UserError.UserNotFound ->
                ChangePasswordError.UserNotFound(userId)
            }) {
                userDao.getUserById(userId).bind()
            }

            // Prevent reusing the current password
            if (passwordService.verifyPassword(newPassword, existingUser.passwordHash)) {
                logger.warn("User $userId attempted to reuse current password")
                raise(ChangePasswordError.SameAsCurrentPassword)
            }

            // Hash new password
            val hashedPassword = passwordService.hashPassword(newPassword)

            // Update user with new password and clear requiresPasswordChange flag
            val updatedUser = existingUser.copy(
                passwordHash = hashedPassword,
                requiresPasswordChange = false  // Clear the flag after password change
            )
            withError({ _: UserError ->
                ChangePasswordError.UserNotFound(userId)
            }) {
                userDao.updateUser(updatedUser).bind()
            }

            logger.info("Successfully changed password for user $userId")
        }
    }

    /**
     * Helper method to check if a user is the last admin in the system.
     */
    private suspend fun isLastAdmin(userId: Long): Boolean {
        val adminRole = roleDao.getRoleByName("Admin").fold(
            { return false },
            { it }
        )
        val adminUserIds = userRoleAssignmentDao.getUserIdsByRoleId(adminRole.id)
        return adminUserIds.size == 1 && adminUserIds.contains(userId)
    }
}

/**
 * Logical failure modes of the shared account provisioning pipeline, independent of the
 * calling creation path.
 */
private sealed interface UserProvisioningError {
    /**
     * Input rejected by the account validation policy.
     *
     * @property reason Description of what input was invalid
     */
    data class InvalidInput(val reason: String) : UserProvisioningError

    /**
     * Password rejected by the strength policy.
     *
     * @property reason Description of why the password is too weak
     */
    data class PasswordTooWeak(val reason: String) : UserProvisioningError

    /**
     * Username uniqueness constraint violated.
     *
     * @property username The username that already exists
     */
    data class UsernameAlreadyExists(val username: String) : UserProvisioningError

    /**
     * Email uniqueness constraint violated.
     *
     * @property email The email that already exists
     */
    data class EmailAlreadyExists(val email: String) : UserProvisioningError

    /**
     * "All Users" group lookup or membership assignment failed.
     *
     * @property reason Description of the failure
     */
    data class GroupAssignmentFailed(val reason: String) : UserProvisioningError

    /**
     * Per-user operator or server built-in tool seeding failed.
     *
     * @property reason Description of the failure
     */
    data class ToolProvisioningFailed(val reason: String) : UserProvisioningError
}

/**
 * Converts a password strength violation into the provisioning error family while keeping the
 * user-facing reason strings identical across all account creation paths.
 *
 * @receiver The password validation failure to convert
 * @return The corresponding [UserProvisioningError.PasswordTooWeak]
 */
private fun PasswordValidationError.toProvisioningError(): UserProvisioningError.PasswordTooWeak = when (this) {
    is PasswordValidationError.Empty ->
        UserProvisioningError.PasswordTooWeak("Password cannot be empty")

    is PasswordValidationError.OnlyWhitespace ->
        UserProvisioningError.PasswordTooWeak("Password cannot contain only whitespace")

    is PasswordValidationError.TooShort ->
        UserProvisioningError.PasswordTooWeak("Password must be at least $minLength characters long")

    is PasswordValidationError.TooLong ->
        UserProvisioningError.PasswordTooWeak("Password must be no more than $maxLength characters long")

    is PasswordValidationError.MissingCharacterTypes ->
        UserProvisioningError.PasswordTooWeak(
            "Password must contain: ${
                missingTypes.joinToString(", ") { characterType ->
                    when (characterType) {
                        CharacterType.UPPERCASE -> "uppercase letters"
                        CharacterType.LOWERCASE -> "lowercase letters"
                        CharacterType.DIGITS -> "digits"
                        CharacterType.SPECIAL_CHARACTERS -> "special characters"
                    }
                }
            }"
        )

    is PasswordValidationError.TooCommon ->
        UserProvisioningError.PasswordTooWeak(reason)
}

/**
 * Maps provisioning failures onto the self-registration error contract. Group and tool seeding
 * failures keep their canonical self-registration messages regardless of provisioning details.
 *
 * @param error The provisioning failure to convert
 * @return The corresponding [RegisterUserError]
 */
private fun toRegisterUserError(error: UserProvisioningError): RegisterUserError = when (error) {
    is UserProvisioningError.UsernameAlreadyExists -> RegisterUserError.UsernameAlreadyExists(error.username)

    is UserProvisioningError.EmailAlreadyExists -> RegisterUserError.EmailAlreadyExists(error.email)

    is UserProvisioningError.InvalidInput -> RegisterUserError.InvalidInput(error.reason)

    is UserProvisioningError.PasswordTooWeak -> RegisterUserError.PasswordTooWeak(error.reason)

    is UserProvisioningError.GroupAssignmentFailed ->
        RegisterUserError.GroupAssignmentFailed("Failed to add user to All Users group")

    is UserProvisioningError.ToolProvisioningFailed ->
        RegisterUserError.InvalidInput("Failed to initialize user tool configuration")
}

/**
 * Maps provisioning failures onto the admin-creation error contract; group and tool seeding
 * failures surface as a single provisioning failure.
 *
 * @param error The provisioning failure to convert
 * @return The corresponding [CreateUserError]
 */
private fun toCreateUserError(error: UserProvisioningError): CreateUserError = when (error) {
    is UserProvisioningError.UsernameAlreadyExists -> CreateUserError.UsernameAlreadyExists(error.username)

    is UserProvisioningError.EmailAlreadyExists -> CreateUserError.EmailAlreadyExists(error.email)

    is UserProvisioningError.InvalidInput -> CreateUserError.InvalidInput(error.reason)

    is UserProvisioningError.PasswordTooWeak -> CreateUserError.PasswordTooWeak(error.reason)

    is UserProvisioningError.GroupAssignmentFailed -> CreateUserError.ProvisioningFailed(error.reason)

    is UserProvisioningError.ToolProvisioningFailed -> CreateUserError.ProvisioningFailed(error.reason)
}
