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
        val decodedJWT = jwtConfig.verifier.verify(token)
        assertEquals(userId.toString(), decodedJWT.subject)
        assertEquals(sessionId, decodedJWT.getClaim("sessionId").asLong())
        assertEquals("access", decodedJWT.getClaim("tokenType").asString())
        assertEquals(jwtConfig.issuer, decodedJWT.issuer)
        assertEquals(jwtConfig.audience, decodedJWT.audience.first())
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
        val decodedJWT = jwtConfig.verifier.verify(token)
        assertEquals(userId.toString(), decodedJWT.subject)
        assertEquals(sessionId, decodedJWT.getClaim("sessionId").asLong())
        assertEquals("refresh", decodedJWT.getClaim("tokenType").asString())
        assertEquals(jwtConfig.issuer, decodedJWT.issuer)
        assertEquals(jwtConfig.audience, decodedJWT.audience.first())
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
            jwtConfig.verifier.verify(token)
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
            jwtConfig.verifier.verify(token)
        }
    }

    @Test
    fun `verifier should reject token with wrong audience`() {
        // Given
        val wrongConfig = JwtConfig(
            secret = jwtConfig.secret,
            audience = "wrong-audience"
        )
        val token = wrongConfig.generateAccessToken(123L, 456L)

        // When & Then
        assertThrows<JWTVerificationException> {
            jwtConfig.verifier.verify(token)
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
        assertEquals("chatbot-users", defaultConfig.audience)
        assertEquals("chatbot-realm", defaultConfig.realm)
        assertEquals(24 * 60 * 60 * 1000L, defaultConfig.tokenExpirationMs) // 24 hours
    }
}
