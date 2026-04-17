package eu.torvian.chatbot.server.domain.security

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.util.*
import kotlin.time.Instant

/**
 * Configuration class for JWT token generation and validation.
 * 
 * This class encapsulates all JWT-related configuration including signing algorithms,
 * token expiration, and provides utilities for token generation and verification.
 * 
 * @property issuer The JWT issuer claim (identifies who issued the token)
 * @property userAudience The JWT audience for user access/refresh tokens.
 * @property workerAudience The JWT audience for worker/service access tokens.
 * @property realm The authentication realm for Ktor
 * @property secret The secret key used for HMAC signing (should be kept secure)
 * @property tokenExpirationMs Token expiration time in milliseconds
 */
data class JwtConfig(
    val issuer: String = "chatbot-server",
    val userAudience: String = "chatbot-users",
    val workerAudience: String = "chatbot-workers",
    val realm: String = "chatbot-realm",
    val secret: String,
    val tokenExpirationMs: Long = 24 * 60 * 60 * 1000L, // 24 hours
    val refreshExpirationMs: Long = 7 * 24 * 60 * 60 * 1000L // 7 days
) {
    private val algorithm: Algorithm = Algorithm.HMAC256(secret)

    /**
     * JWT verifier configured with this instance's settings.
     * Used by Ktor authentication to validate incoming tokens.
     */
    val userVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(userAudience)
        .build()

    val workerVerifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(workerAudience)
        .build()

    /**
     * Generates a JWT access token for the given user.
     * 
     * @param userId The unique identifier of the user
     * @param sessionId The unique identifier of the user session (for token revocation)
     * @param currentTime The current timestamp (epoch milliseconds)
     * @return A signed JWT token string
     */
    fun generateAccessToken(
        userId: Long,
        sessionId: Long,
        currentTime: Long = System.currentTimeMillis()
    ): String {
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(userAudience)
            .withSubject(userId.toString())
            .withClaim("principalType", "user")
            .withClaim("sessionId", sessionId)
            .withClaim("tokenType", "access")
            .withIssuedAt(Date(currentTime))
            .withExpiresAt(Date(currentTime + tokenExpirationMs))
            .sign(algorithm)
    }

    /**
     * Generates a JWT refresh token for the given user session.
     * 
     * Refresh tokens have a longer expiration time and are used to obtain new access tokens.
     * 
     * @param userId The unique identifier of the user
     * @param sessionId The unique identifier of the user session
     * @param currentTime The current timestamp (epoch milliseconds)
     * @return A signed JWT refresh token string
     */
    fun generateRefreshToken(
        userId: Long,
        sessionId: Long,
        currentTime: Long = System.currentTimeMillis()
    ): String {
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(userAudience)
            .withSubject(userId.toString())
            .withClaim("principalType", "user")
            .withClaim("sessionId", sessionId)
            .withClaim("tokenType", "refresh")
            .withIssuedAt(Date(currentTime))
            .withExpiresAt(Date(currentTime + refreshExpirationMs))
            .sign(algorithm)
    }

    /**
     * Generates a JWT access token for an authenticated worker service principal.
     *
     * @param workerId Durable worker identifier bound to the token subject.
     * @param workerUid Stable public worker UID bound to the authenticated worker.
     * @param ownerUserId Owning user identifier linked to the worker.
     * @param scopes Service scopes granted to the worker token.
     * @param currentTime The current timestamp (epoch milliseconds).
     * @param ttlMs Token lifetime in milliseconds.
     * @return A signed JWT service access token string.
     */
    fun generateServiceAccessToken(
        workerId: Long,
        workerUid: String,
        ownerUserId: Long,
        scopes: List<String>,
        currentTime: Long = System.currentTimeMillis(),
        ttlMs: Long = 15 * 60 * 1000L
    ): String {
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(workerAudience)
            .withSubject("worker:$workerId")
            .withClaim("principalType", "service")
            .withClaim("tokenType", "access")
            .withClaim("workerId", workerId)
            .withClaim("workerUid", workerUid)
            .withClaim("ownerUserId", ownerUserId)
            .withArrayClaim("scope", scopes.toTypedArray())
            .withIssuedAt(Date(currentTime))
            .withExpiresAt(Date(currentTime + ttlMs))
            .sign(algorithm)
    }

    /**
     * Calculates the expiration instant for a new token.
     * 
     * @return The [Instant] when a token generated now would expire
     */
    fun getTokenExpirationInstant(): Instant =
        Instant.fromEpochMilliseconds(System.currentTimeMillis() + tokenExpirationMs)
}
