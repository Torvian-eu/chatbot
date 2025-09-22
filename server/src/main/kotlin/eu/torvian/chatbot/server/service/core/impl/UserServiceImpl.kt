package eu.torvian.chatbot.server.service.core.impl

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.common.models.User
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.RegisterUserError
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError
import eu.torvian.chatbot.server.service.security.PasswordService
import eu.torvian.chatbot.common.security.error.PasswordValidationError
import eu.torvian.chatbot.common.security.error.CharacterType
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.datetime.Clock
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [UserService] with secure user registration.
 *
 * This implementation handles user registration with password validation
 * and secure password hashing. All users automatically belong to the virtual
 * "All Users" group for access to public resources.
 */
class UserServiceImpl(
    private val userDao: UserDao,
    private val passwordService: PasswordService,
    private val transactionScope: TransactionScope
) : UserService {

    companion object {
        private val logger: Logger = LogManager.getLogger(UserServiceImpl::class.java)
    }

    override suspend fun registerUser(
        username: String,
        password: String,
        email: String?
    ): Either<RegisterUserError, User> = transactionScope.transaction {
        either {
            logger.info("Registering new user: $username")

            // Validate input
            ensure(!username.isBlank()) { RegisterUserError.InvalidInput("Username cannot be blank") }

            ensure(email?.isBlank() != true) { RegisterUserError.InvalidInput("Email cannot be blank if provided") }

            // Validate password strength
            withError({ passwordError ->
                when (passwordError) {
                    is PasswordValidationError.Empty ->
                        RegisterUserError.PasswordTooWeak("Password cannot be empty")

                    is PasswordValidationError.OnlyWhitespace ->
                        RegisterUserError.PasswordTooWeak("Password cannot contain only whitespace")

                    is PasswordValidationError.TooShort ->
                        RegisterUserError.PasswordTooWeak("Password must be at least ${passwordError.minLength} characters long")

                    is PasswordValidationError.TooLong ->
                        RegisterUserError.PasswordTooWeak("Password must be no more than ${passwordError.maxLength} characters long")

                    is PasswordValidationError.MissingCharacterTypes ->
                        RegisterUserError.PasswordTooWeak(
                            "Password must contain: ${
                                passwordError.missingTypes.joinToString(", ") { characterType ->
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
                        RegisterUserError.PasswordTooWeak(passwordError.reason)
                }
            }) {
                passwordService.validatePasswordStrength(password).bind()
            }

            // Hash the password
            val hashedPassword = passwordService.hashPassword(password)

            // Create the user account
            val newUser = withError({ userError ->
                when (userError) {
                    is UserError.UsernameAlreadyExists ->
                        RegisterUserError.UsernameAlreadyExists(userError.username)

                    is UserError.EmailAlreadyExists ->
                        RegisterUserError.EmailAlreadyExists(userError.email)

                    else -> RegisterUserError.InvalidInput("Failed to create user account")
                }
            }) {
                userDao.insertUser(username, hashedPassword, email).bind()
            }

            logger.info("Successfully registered user: $username (ID: ${newUser.id})")
            newUser.toUser()
        }
    }

    override suspend fun getUserByUsername(username: String): Either<UserNotFoundError.ByUsername, User> =
        userDao.getUserByUsername(username).mapLeft { UserNotFoundError.ByUsername(username) }.map { it.toUser() }

    override suspend fun getUserById(id: Long): Either<UserNotFoundError.ById, User> =
        userDao.getUserById(id).mapLeft { UserNotFoundError.ById(id) }.map { it.toUser() }

    override suspend fun updateLastLogin(userId: Long): Either<UserNotFoundError.ById, Unit> =
        userDao.updateLastLogin(userId, Clock.System.now().toEpochMilliseconds()).mapLeft { UserNotFoundError.ById(userId) }

    override suspend fun getAllUsers(): List<User> =
        userDao.getAllUsers().map { it.toUser() }
}
