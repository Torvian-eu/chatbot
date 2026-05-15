package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.security.error.PasswordValidationError
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.service.core.error.auth.ChangeEmailError
import eu.torvian.chatbot.server.service.core.error.auth.ChangePasswordError
import eu.torvian.chatbot.server.service.security.error.CompleteRequiredPasswordChangeError
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Clock

/**
 * Implementation of [AccountManagementService] for user account management.
 *
 * Handles account-related operations including password and email changes.
 */
class AccountManagementServiceImpl(
    private val userDao: UserDao,
    private val passwordService: PasswordService,
    private val transactionScope: TransactionScope
) : AccountManagementService {

    companion object {
        private val logger: Logger = LogManager.getLogger(AccountManagementServiceImpl::class.java)
    }

    override suspend fun changePassword(
        userId: Long,
        currentPassword: String,
        newPassword: String,
        requesterIsRestricted: Boolean
    ): Either<ChangePasswordError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Changing password for user: $userId, restricted: $requesterIsRestricted")

                // 1. Check restriction - restricted sessions cannot change password
                ensure(!requesterIsRestricted) {
                    logger.warn("Restricted session attempted to change password")
                    raise(ChangePasswordError.InsufficientPermissions)
                }

                // 2. Verify current password
                val userEntity = withError({ _: UserError.UserNotFound ->
                    ChangePasswordError.UserNotFound(userId)
                }) {
                    userDao.getUserById(userId).bind()
                }

                // Verify the current password matches
                ensure(passwordService.verifyPassword(currentPassword, userEntity.passwordHash)) {
                    logger.warn("Invalid current password for user: $userId")
                    raise(ChangePasswordError.InvalidCurrentPassword)
                }

                // 3. Validate new password strength
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

                // Prevent reusing the current password
                ensure(!passwordService.verifyPassword(newPassword, userEntity.passwordHash)) {
                    logger.warn("User $userId attempted to reuse current password")
                    raise(ChangePasswordError.SameAsCurrentPassword)
                }

                // 4. Hash new password and update user record
                val hashedPassword = passwordService.hashPassword(newPassword)

                // Update user with new password and clear requiresPasswordChange flag
                val updatedUser = userEntity.copy(
                    passwordHash = hashedPassword,
                    requiresPasswordChange = false
                )
                // Use mapLeft to convert the error type
                userDao.updateUser(updatedUser).mapLeft { error ->
                    when (error) {
                        is UserError.UserNotFound -> ChangePasswordError.UserNotFound(userId)
                        else -> ChangePasswordError.UserNotFound(userId)
                    }
                }.bind()

                logger.info("Successfully changed password for user: $userId")
            }
        }

    override suspend fun completeRequiredPasswordChange(
        userId: Long,
        newPassword: String,
        requesterIsRestricted: Boolean
    ): Either<CompleteRequiredPasswordChangeError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Completing required password change for user: $userId, restricted: $requesterIsRestricted")

                // 1. Check restriction - restricted sessions cannot complete required password change
                ensure(!requesterIsRestricted) {
                    logger.warn("Restricted session attempted to complete required password change")
                    raise(CompleteRequiredPasswordChangeError.InsufficientPermissions)
                }

                // 2. Fetch user entity
                val userEntity = withError({ _: UserError.UserNotFound ->
                    CompleteRequiredPasswordChangeError.UserNotFound
                }) {
                    userDao.getUserById(userId).bind()
                }

                // 3. Check that password change is required for this user
                ensure(userEntity.requiresPasswordChange) {
                    logger.warn("User $userId attempted password change but it's not required")
                    raise(CompleteRequiredPasswordChangeError.PasswordChangeNotRequired)
                }

                // 4. Validate new password strength
                withError({ passwordError ->
                    when (passwordError) {
                        is PasswordValidationError.Empty ->
                            CompleteRequiredPasswordChangeError.WeakPassword("Password cannot be empty")

                        is PasswordValidationError.OnlyWhitespace ->
                            CompleteRequiredPasswordChangeError.WeakPassword("Password cannot contain only whitespace")

                        is PasswordValidationError.TooShort ->
                            CompleteRequiredPasswordChangeError.WeakPassword(
                                "Password must be at least ${passwordError.minLength} characters"
                            )

                        is PasswordValidationError.TooLong ->
                            CompleteRequiredPasswordChangeError.WeakPassword(
                                "Password must be no more than ${passwordError.maxLength} characters"
                            )

                        is PasswordValidationError.MissingCharacterTypes ->
                            CompleteRequiredPasswordChangeError.WeakPassword(
                                "Password must contain required character types"
                            )

                        is PasswordValidationError.TooCommon ->
                            CompleteRequiredPasswordChangeError.WeakPassword(passwordError.reason)
                    }
                }) {
                    passwordService.validatePasswordStrength(newPassword).bind()
                }

                // 5. Hash new password and update user record
                val hashedPassword = passwordService.hashPassword(newPassword)

                // Update user with new password and clear requiresPasswordChange flag
                val updatedUser = userEntity.copy(
                    passwordHash = hashedPassword,
                    requiresPasswordChange = false
                )
                // Use mapLeft to convert the error type
                userDao.updateUser(updatedUser).mapLeft { error ->
                    when (error) {
                        is UserError.UserNotFound -> CompleteRequiredPasswordChangeError.UserNotFound
                        else -> CompleteRequiredPasswordChangeError.UpdateFailed("Database update failed")
                    }
                }.bind()

                logger.info("Successfully completed required password change for user: $userId")
            }
        }

    override suspend fun changeEmail(
        userId: Long,
        currentPassword: String,
        newEmail: String,
        requesterIsRestricted: Boolean
    ): Either<ChangeEmailError, User> =
        transactionScope.transaction {
            either {
                logger.info("Changing email for user: $userId, restricted: $requesterIsRestricted")

                // 1. Check restriction - restricted sessions cannot change email
                ensure(!requesterIsRestricted) {
                    logger.warn("Restricted session attempted to change email")
                    raise(ChangeEmailError.InsufficientPermissions)
                }

                // 2. Validate email format
                val trimmedEmail = newEmail.trim()
                ensure(trimmedEmail.isNotEmpty()) {
                    logger.warn("Empty email provided for user: $userId")
                    raise(ChangeEmailError.InvalidEmailFormat("Email cannot be empty"))
                }
                // Basic email format validation
                val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
                ensure(emailRegex.matches(trimmedEmail)) {
                    logger.warn("Invalid email format for user: $userId, email: $trimmedEmail")
                    raise(ChangeEmailError.InvalidEmailFormat("Invalid email format"))
                }

                // 3. Check if email already exists (excluding current user)
                val emailExists = userDao.emailExists(trimmedEmail, userId)
                ensure(!emailExists) {
                    logger.warn("Email already exists: $trimmedEmail")
                    raise(ChangeEmailError.EmailAlreadyExists(trimmedEmail))
                }

                // 4. Fetch user entity
                val userEntity = withError({ _: UserError.UserNotFound ->
                    ChangeEmailError.UserNotFound(userId)
                }) {
                    userDao.getUserById(userId).bind()
                }

                // 5. Verify the current password matches
                ensure(passwordService.verifyPassword(currentPassword, userEntity.passwordHash)) {
                    logger.warn("Invalid current password for user: $userId")
                    raise(ChangeEmailError.InvalidCurrentPassword)
                }

                // 6. Update user with new email
                val updatedUser = userEntity.copy(
                    email = trimmedEmail,
                    updatedAt = Clock.System.now()
                )
                userDao.updateUser(updatedUser).mapLeft { error ->
                    when (error) {
                        is UserError.UserNotFound -> ChangeEmailError.UserNotFound(userId)
                        is UserError.EmailAlreadyExists -> ChangeEmailError.EmailAlreadyExists(trimmedEmail)
                        else -> ChangeEmailError.UserNotFound(userId)
                    }
                }.bind()

                logger.info("Successfully changed email for user: $userId")

                // 7. Return updated user
                updatedUser.toUser()
            }
        }
}



