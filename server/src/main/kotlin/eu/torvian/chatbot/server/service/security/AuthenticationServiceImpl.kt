package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import eu.torvian.chatbot.server.data.dao.UserSessionDao
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.domain.security.LoginResult
import eu.torvian.chatbot.server.domain.security.UserContext
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError
import eu.torvian.chatbot.server.service.security.error.LoginError
import eu.torvian.chatbot.server.service.security.error.LogoutError
import eu.torvian.chatbot.server.service.security.error.RefreshTokenError
import eu.torvian.chatbot.server.service.security.error.TokenValidationError
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import kotlinx.datetime.Instant
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * Implementation of [AuthenticationService] with JWT token generation and validation.
 *
 * This implementation provides complete authentication functionality including:
 * - User credential validation
 * - JWT token generation and validation
 * - Session lifecycle management
 * - Token refresh capabilities
 */
class AuthenticationServiceImpl(
    private val userService: UserService,
    private val passwordService: PasswordService,
    private val jwtConfig: JwtConfig,
    private val userSessionDao: UserSessionDao,
    private val transactionScope: TransactionScope
) : AuthenticationService {

    companion object {
        private val logger: Logger = LogManager.getLogger(AuthenticationServiceImpl::class.java)
        private const val SESSION_EXTENSION_HOURS = 24L * 7 // 7 days
    }

    override suspend fun login(username: String, password: String): Either<LoginError, LoginResult> =
        transactionScope.transaction {
            either {
                logger.info("Attempting login for user: $username")

                // Get user by username
                val user = withError({ _: UserNotFoundError.ByUsername ->
                    LoginError.UserNotFound
                }) {
                    userService.getUserByUsername(username).bind()
                }

                // Verify password
                if (!passwordService.verifyPassword(password, user.passwordHash)) {
                    logger.warn("Invalid password for user: $username")
                    raise(LoginError.InvalidCredentials)
                }

                // Create new session
                val sessionExpiresAt = System.currentTimeMillis() + (SESSION_EXTENSION_HOURS * 60 * 60 * 1000)
                val session: UserSessionEntity = withError({ _: UserSessionError.ForeignKeyViolation ->
                    LoginError.UserNotFound
                }) {
                    userSessionDao.insertSession(user.id, sessionExpiresAt).bind()
                }

                // Generate tokens
                val accessToken = jwtConfig.generateAccessToken(user.id, session.id)
                val refreshToken = jwtConfig.generateRefreshToken(user.id, session.id)
                val tokenExpiresAt = jwtConfig.getTokenExpirationInstant()

                // Update last login
                userService.updateLastLogin(user.id).mapLeft {
                    // Log warning but don't fail the login
                    logger.warn("Failed to update last login for user: $username")
                }

                logger.info("Successfully logged in user: $username (ID: ${user.id})")

                LoginResult(
                    user = user,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAt = tokenExpiresAt
                )
            }
        }

    override suspend fun logout(userId: Long): Either<LogoutError, Unit> =
        transactionScope.transaction {
            either {
                logger.info("Logging out user: $userId")

                // Delete all sessions for the user
                val deletedCount = userSessionDao.deleteSessionsByUserId(userId)

                if (deletedCount == 0) {
                    logger.warn("No sessions found for user: $userId")
                    raise(LogoutError.SessionNotFound(userId))
                }

                logger.info("Successfully logged out user: $userId (deleted $deletedCount sessions)")
            }
        }

    override suspend fun validateToken(token: String): Either<TokenValidationError, UserContext> =
        transactionScope.transaction {
            either {
                // First, decode and verify the JWT token
                val decodedJWT = catch({
                    jwtConfig.verifier.verify(token)
                }) { e ->
                    when (e) {
                        is JWTDecodeException -> {
                            logger.debug("JWT decode failed", e)
                            raise(TokenValidationError.MalformedToken)
                        }

                        is JWTVerificationException -> {
                            logger.debug("JWT verification failed", e)
                            raise(TokenValidationError.InvalidSignature)
                        }

                        else -> {
                            logger.error("Unexpected error during token validation", e)
                            raise(TokenValidationError.InvalidToken)
                        }
                    }
                }

                // Extract claims
                val userId = decodedJWT.subject?.toLongOrNull()
                    ?: raise(TokenValidationError.InvalidClaims)

                val sessionId = decodedJWT.getClaim("sessionId")?.asLong()
                    ?: raise(TokenValidationError.InvalidClaims)

                val issuedAt = Instant.fromEpochMilliseconds(decodedJWT.issuedAt?.time ?: 0L)
                val expiresAt = Instant.fromEpochMilliseconds(decodedJWT.expiresAt?.time ?: 0L)

                // Validate session exists
                val session = withError({ _: UserSessionError.SessionNotFound ->
                    TokenValidationError.InvalidSession("Session not found")
                }) {
                    userSessionDao.getSessionById(sessionId).bind()
                }

                // Validate session is not expired
                val currentTime = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                ensure(session.expiresAt > currentTime) {
                    TokenValidationError.InvalidSession("Session expired")
                }

                // Get user details
                val user = withError({ _: UserNotFoundError.ById ->
                    TokenValidationError.InvalidSession("User not found")
                }) {
                    userService.getUserById(userId).bind()
                }

                // Update session last accessed time
                userSessionDao.updateLastAccessed(sessionId, currentTime.toEpochMilliseconds())

                UserContext(
                    user = user,
                    sessionId = sessionId,
                    tokenIssuedAt = issuedAt,
                    tokenExpiresAt = expiresAt
                )
            }
        }


    override suspend fun refreshToken(refreshToken: String): Either<RefreshTokenError, LoginResult> =
        transactionScope.transaction {
            either {
                // First, decode and verify the refresh token (non-suspend operations)
                // This block remains as is, as withError is not suitable for early return from Either.catch
                val decodedJWT = catch({
                    jwtConfig.verifier.verify(refreshToken)
                }) { e ->
                    when (e) {
                        is JWTDecodeException -> {
                            logger.debug("Refresh token decode failed", e)
                            raise(RefreshTokenError.InvalidRefreshToken)
                        }

                        is JWTVerificationException -> {
                            logger.debug("Refresh token verification failed", e)
                            raise(RefreshTokenError.InvalidRefreshToken)
                        }

                        else -> {
                            logger.error("Unexpected error during token refresh", e)
                            raise(RefreshTokenError.TokenGenerationFailed("Unexpected error occurred"))
                        }
                    }
                }

                // Verify this is actually a refresh token
                val tokenType = decodedJWT.getClaim("tokenType")?.asString()
                ensure(tokenType == "refresh") {
                    RefreshTokenError.InvalidRefreshToken
                }

                // Extract claims
                val userId = decodedJWT.subject?.toLongOrNull()
                    ?: raise(RefreshTokenError.InvalidRefreshToken)

                val sessionId = decodedJWT.getClaim("sessionId")?.asLong()
                    ?: raise(RefreshTokenError.InvalidRefreshToken)

                // Validate session exists
                val session = withError({ _: UserSessionError.SessionNotFound ->
                    RefreshTokenError.InvalidSession("Session not found")
                }) {
                    userSessionDao.getSessionById(sessionId).bind()
                }

                // Validate session is not expired
                val currentTime = Instant.fromEpochMilliseconds(System.currentTimeMillis())
                ensure(session.expiresAt > currentTime) {
                    RefreshTokenError.InvalidSession("Session expired")
                }

                // Get user details
                val user = withError({ _: UserNotFoundError.ById ->
                    RefreshTokenError.InvalidSession("User not found")
                }) {
                    userService.getUserById(userId).bind()
                }

                // Generate new tokens
                val newAccessToken = jwtConfig.generateAccessToken(userId, sessionId)
                val newRefreshToken = jwtConfig.generateRefreshToken(userId, sessionId)
                val tokenExpiresAt = jwtConfig.getTokenExpirationInstant()

                // Update session last accessed time
                userSessionDao.updateLastAccessed(sessionId, currentTime.toEpochMilliseconds())

                logger.info("Successfully refreshed tokens for user: $userId")

                LoginResult(
                    user = user,
                    accessToken = newAccessToken,
                    refreshToken = newRefreshToken,
                    expiresAt = tokenExpiresAt
                )
            }
        }
}
