package eu.torvian.chatbot.server.service.security

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.withError
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.UserSessionDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.dao.error.WorkerError
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.domain.security.LoginResult
import eu.torvian.chatbot.server.domain.security.UserContext
import eu.torvian.chatbot.server.domain.security.WorkerContext
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError
import eu.torvian.chatbot.server.service.security.error.RefreshTokenError
import eu.torvian.chatbot.server.service.security.error.TokenValidationError
import io.ktor.server.auth.jwt.*
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * Implementation of [TokenService] for JWT token operations.
 *
 * Handles token refresh and validation including credential validation for both
 * user and worker/service principals.
 */
class TokenServiceImpl(
    private val userService: UserService,
    private val jwtConfig: JwtConfig,
    private val userSessionDao: UserSessionDao,
    private val workerDao: WorkerDao,
    private val authorizationService: AuthorizationService,
    private val transactionScope: TransactionScope
) : TokenService {

    companion object {
        private val logger: Logger = LogManager.getLogger(TokenServiceImpl::class.java)
    }

    override suspend fun refreshToken(
        refreshToken: String,
        ipAddress: String?
    ): Either<RefreshTokenError, LoginResult> =
        transactionScope.transaction {
            either {
                // First, decode and verify the refresh token (non-suspend operations)
                // This block remains as is, as withError is not suitable for early return from Either.catch
                val decodedJWT = catch({
                    jwtConfig.userVerifier.verify(refreshToken)
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

                // Delete old session
                withError({ _: UserSessionError.SessionNotFound ->
                    RefreshTokenError.InvalidSession("Session not found")
                }) {
                    userSessionDao.deleteSession(sessionId).bind()
                }

                // Create new session, preserving the restricted status from the old session
                val newSessionExpirationMs = jwtConfig.refreshExpirationMs
                val newSessionExpiresAt = currentTime.toEpochMilliseconds() + newSessionExpirationMs
                val newSession = withError({ _: UserSessionError.ForeignKeyViolation ->
                    RefreshTokenError.InvalidSession("User not found")
                }) {
                    userSessionDao.insertSession(
                        userId,
                        session.deviceId,
                        newSessionExpiresAt,
                        ipAddress,
                        session.isRestricted
                    ).bind()
                }

                // Generate new tokens, preserving the restricted status
                val newAccessToken = jwtConfig.generateAccessToken(
                    userId = userId,
                    sessionId = newSession.id,
                    isRestricted = session.isRestricted,
                    currentTime = currentTime.toEpochMilliseconds()
                )
                val newRefreshToken = jwtConfig.generateRefreshToken(
                    userId = userId,
                    sessionId = newSession.id,
                    currentTime = currentTime.toEpochMilliseconds()
                )
                val tokenExpiresAt = currentTime.plus(jwtConfig.tokenExpirationMs.milliseconds)

                // Retrieve user permissions
                val permissions = authorizationService.getUserPermissions(userId)

                logger.info("Successfully refreshed tokens for user: $userId")

                LoginResult(
                    user = user,
                    accessToken = newAccessToken,
                    refreshToken = newRefreshToken,
                    expiresAt = tokenExpiresAt,
                    permissions = permissions,
                    isRestricted = session.isRestricted,
                    deviceId = null // Device ID not available during token refresh; client uses its own device ID
                )
            }
        }

    override suspend fun validateCredential(credential: JWTCredential): UserContext? = transactionScope.transaction {
        either {
            logger.debug("Validating JWT credential for subject: ${credential.payload.subject}")
            // Ktor already verified the signature and expiration. We validate the business logic.

            val principalType = credential.payload.getClaim("principalType")?.asString()
            ensure(principalType == "user") {
                TokenValidationError.InvalidClaims
            }

            // Ensure only access tokens can authenticate protected routes.
            val tokenType = credential.payload.getClaim("tokenType")?.asString()
            ensure(tokenType == "access") {
                TokenValidationError.InvalidClaims
            }

            // Extract claims from the payload
            val userId = credential.payload.subject?.toLongOrNull()
                ?: raise(TokenValidationError.InvalidClaims)
            val sessionId = credential.payload.getClaim("sessionId")?.asLong()
                ?: raise(TokenValidationError.InvalidClaims)
            val issuedAt = Instant.fromEpochMilliseconds(credential.payload.issuedAt?.time ?: 0L)
            val expiresAt = Instant.fromEpochMilliseconds(credential.payload.expiresAt?.time ?: 0L)

            // Validate session exists in the database
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
                TokenValidationError.InvalidSession("User not found for session")
            }) {
                userService.getUserById(userId).bind()
            }

            ensure(user.status == UserStatus.ACTIVE) {
                TokenValidationError.AccountLocked("Account is disabled")
            }

            // Update session last accessed time
            userSessionDao.updateLastAccessed(sessionId, currentTime.toEpochMilliseconds()).bind()

            val userContext = UserContext(
                user = user,
                sessionId = sessionId,
                tokenIssuedAt = issuedAt,
                tokenExpiresAt = expiresAt,
                isRestricted = session.isRestricted
            )

            logger.debug("Credential validation successful for user: ${user.username}")
            userContext
        }.getOrElse {  // Return UserContext on success, null on any validation error
            logger.debug("Credential validation failed: {}", it)
            null
        }
    }

    override suspend fun validateWorkerCredential(credential: JWTCredential): WorkerContext? =
        transactionScope.transaction {
            either {
                logger.debug("Validating worker credential (subject={})", credential.payload.subject)

                val principalType = credential.payload.getClaim("principalType")?.asString()
                if (principalType != "service") {
                    logger.debug("Worker credential rejected: invalid principal type ({})", principalType)
                    raise(TokenValidationError.InvalidClaims)
                }

                val tokenType = credential.payload.getClaim("tokenType")?.asString()
                if (tokenType != "access") {
                    logger.debug("Worker credential rejected: invalid token type ({})", tokenType)
                    raise(TokenValidationError.InvalidClaims)
                }

                val workerId = credential.payload.getClaim("workerId")?.asLong()
                    ?: raise(TokenValidationError.InvalidClaims)
                val claimedWorkerUid = credential.payload.getClaim("workerUid")?.asString()
                val ownerUserId = credential.payload.getClaim("ownerUserId")?.asLong()
                    ?: raise(TokenValidationError.InvalidClaims)
                val scopes = credential.payload.getClaim("scope")?.asList(String::class.java) ?: emptyList()
                val issuedAt = Instant.fromEpochMilliseconds(credential.payload.issuedAt?.time ?: 0L)
                val expiresAt = Instant.fromEpochMilliseconds(credential.payload.expiresAt?.time ?: 0L)

                val worker = withError({ _: WorkerError.NotFound ->
                    logger.warn("Worker credential rejected: worker not found (workerId={})", workerId)
                    TokenValidationError.InvalidSession("Worker not found")
                }) {
                    workerDao.getWorkerById(workerId).bind()
                }

                if (worker.ownerUserId != ownerUserId) {
                    logger.warn("Worker credential rejected: owner mismatch (workerId={})", workerId)
                    raise(TokenValidationError.InvalidClaims)
                }
                if (claimedWorkerUid != null && claimedWorkerUid != worker.workerUid) {
                    logger.warn("Worker credential rejected: worker UID mismatch (workerId={})", workerId)
                    raise(TokenValidationError.InvalidClaims)
                }

                val context = WorkerContext(
                    workerId = worker.id,
                    workerUid = worker.workerUid,
                    ownerUserId = ownerUserId,
                    scopes = scopes,
                    tokenIssuedAt = issuedAt,
                    tokenExpiresAt = expiresAt
                )

                logger.debug("Worker credential validation successful (workerId={})", worker.id)
                context
            }.getOrElse {
                logger.debug("Worker credential validation failed: {}", it)
                null
            }
        }
}
