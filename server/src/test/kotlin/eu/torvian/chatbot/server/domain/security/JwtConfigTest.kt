package eu.torvian.chatbot.server.domain.security

import com.auth0.jwt.exceptions.JWTVerificationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JwtConfigTest {

    private val jwtConfig = JwtConfig(
        secret = "test-secret-key-for-testing-purposes-only"
    )

    @Test
    fun `generateAccessToken should create valid JWT token`() {
        // Given
        val userId = 123L
        val sessionId = 456L

        // When
        val token = jwtConfig.generateAccessToken(userId, sessionId)

        // Then
        assertTrue(token.isNotEmpty())

        // Verify token can be decoded
        val decodedJWT = jwtConfig.userVerifier.verify(token)
        assertEquals(userId.toString(), decodedJWT.subject)
        assertEquals(sessionId, decodedJWT.getClaim("sessionId").asLong())
        assertEquals("user", decodedJWT.getClaim("principalType").asString())
        assertEquals("access", decodedJWT.getClaim("tokenType").asString())
        assertEquals(jwtConfig.issuer, decodedJWT.issuer)
        assertEquals(jwtConfig.userAudience, decodedJWT.audience.first())
    }

    @Test
    fun `generateRefreshToken should create valid refresh token`() {
        // Given
        val userId = 123L
        val sessionId = 456L

        // When
        val token = jwtConfig.generateRefreshToken(userId, sessionId)

        // Then
        assertTrue(token.isNotEmpty())

        // Verify token can be decoded
        val decodedJWT = jwtConfig.userVerifier.verify(token)
        assertEquals(userId.toString(), decodedJWT.subject)
        assertEquals(sessionId, decodedJWT.getClaim("sessionId").asLong())
        assertEquals("user", decodedJWT.getClaim("principalType").asString())
        assertEquals("refresh", decodedJWT.getClaim("tokenType").asString())
        assertEquals(jwtConfig.issuer, decodedJWT.issuer)
        assertEquals(jwtConfig.userAudience, decodedJWT.audience.first())
    }

    @Test
    fun `generateServiceAccessToken should create valid worker token`() {
        // Given
        val workerId = 42L
        val ownerUserId = 7L
        val scopes = listOf("messages:read", "messages:write")

        // When
        val token = jwtConfig.generateServiceAccessToken(workerId, ownerUserId, scopes)

        // Then
        assertTrue(token.isNotEmpty())

        val decodedJWT = jwtConfig.workerVerifier.verify(token)
        assertEquals("worker:$workerId", decodedJWT.subject)
        assertEquals("service", decodedJWT.getClaim("principalType").asString())
        assertEquals("access", decodedJWT.getClaim("tokenType").asString())
        assertEquals(workerId, decodedJWT.getClaim("workerId").asLong())
        assertEquals(ownerUserId, decodedJWT.getClaim("ownerUserId").asLong())
        assertEquals(scopes, decodedJWT.getClaim("scope").asList(String::class.java))
        assertEquals(jwtConfig.workerAudience, decodedJWT.audience.first())
    }

    @Test
    fun `generateAccessToken should create different tokens for same input`() {
        // Given
        val userId = 123L
        val sessionId = 456L

        // When
        val token1 = jwtConfig.generateAccessToken(userId, sessionId)
        Thread.sleep(1000) // Ensure different issued at time (JWT uses seconds)
        val token2 = jwtConfig.generateAccessToken(userId, sessionId)

        // Then
        assertNotEquals(token1, token2) // Different due to different issuedAt times
    }

    @Test
    fun `verifier should reject token with wrong signature`() {
        // Given
        val wrongConfig = JwtConfig(secret = "wrong-secret")
        val token = wrongConfig.generateAccessToken(123L, 456L)

        // When & Then
        assertThrows<JWTVerificationException> {
            jwtConfig.userVerifier.verify(token)
        }
    }

    @Test
    fun `verifier should reject token with wrong issuer`() {
        // Given
        val wrongConfig = JwtConfig(
            secret = jwtConfig.secret,
            issuer = "wrong-issuer"
        )
        val token = wrongConfig.generateAccessToken(123L, 456L)

        // When & Then
        assertThrows<JWTVerificationException> {
            jwtConfig.userVerifier.verify(token)
        }
    }

    @Test
    fun `verifier should reject token with wrong audience`() {
        // Given
        val wrongConfig = JwtConfig(secret = jwtConfig.secret, userAudience = "wrong-audience")
        val token = wrongConfig.generateAccessToken(123L, 456L)

        // When & Then
        assertThrows<JWTVerificationException> {
            jwtConfig.userVerifier.verify(token)
        }
    }

    @Test
    fun `worker verifier should reject token with wrong audience`() {
        // Given
        val wrongConfig = JwtConfig(secret = jwtConfig.secret, workerAudience = "wrong-worker-audience")
        val token = wrongConfig.generateServiceAccessToken(42L, 7L, emptyList())

        // When & Then
        assertThrows<JWTVerificationException> {
            jwtConfig.workerVerifier.verify(token)
        }
    }

    @Test
    fun `getTokenExpirationInstant should return future instant`() {
        // Given
        val beforeCall = System.currentTimeMillis()

        // When
        val expirationInstant = jwtConfig.getTokenExpirationInstant()

        // Then
        val afterCall = System.currentTimeMillis()
        val expectedMinExpiration = beforeCall + jwtConfig.tokenExpirationMs
        val expectedMaxExpiration = afterCall + jwtConfig.tokenExpirationMs

        assertTrue(expirationInstant.toEpochMilliseconds() >= expectedMinExpiration)
        assertTrue(expirationInstant.toEpochMilliseconds() <= expectedMaxExpiration)
    }

    @Test
    fun `default configuration should have reasonable values`() {
        // Given
        val defaultConfig = JwtConfig(secret = "test-secret")

        // Then
        assertEquals("chatbot-server", defaultConfig.issuer)
        assertEquals("chatbot-users", defaultConfig.userAudience)
        assertEquals("chatbot-workers", defaultConfig.workerAudience)
        assertEquals("chatbot-realm", defaultConfig.realm)
        assertEquals(24 * 60 * 60 * 1000L, defaultConfig.tokenExpirationMs) // 24 hours
    }
}
