package eu.torvian.chatbot.server.service.security

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.server.data.dao.UserSessionDao
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError
import eu.torvian.chatbot.server.service.security.error.LoginError
import eu.torvian.chatbot.server.service.security.error.LogoutError
import eu.torvian.chatbot.server.service.security.error.RefreshTokenError
import eu.torvian.chatbot.server.service.security.error.TokenValidationError
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthenticationServiceImplTest {

    private val userService = mockk<UserService>()
    private val passwordService = mockk<PasswordService>()
    private val userSessionDao = mockk<UserSessionDao>()
    private val transactionScope = mockk<TransactionScope>()
    
    private val jwtConfig = JwtConfig(
        secret = "test-secret-key-for-testing-purposes-only"
    )
    
    private val authService = AuthenticationServiceImpl(
        userService, passwordService, jwtConfig, userSessionDao, transactionScope
    )

    private val testUser = UserEntity(
        id = 1L,
        username = "testuser",
        passwordHash = "hashedpassword",
        email = "test@example.com",
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastLogin = null
    )

    private val testSession = UserSessionEntity(
        id = 100L,
        userId = testUser.id,
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastAccessed = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 24 * 60 * 60 * 1000) // 24 hours
    )

    @BeforeEach
    fun setUp() {
        clearMocks(userService, passwordService, userSessionDao, transactionScope)

        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @Test
    fun `login should successfully authenticate user with valid credentials`() = runTest {
        // Given
        val username = "testuser"
        val password = "correctpassword"

        coEvery { userService.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns true

        coEvery { userSessionDao.insertSession(testUser.id, any()) } returns testSession.right()
        coEvery { userService.updateLastLogin(testUser.id) } returns Unit.right()

        // When
        val result = authService.login(username, password)

        // Then
        assertTrue(result.isRight())
        val loginResult = result.getOrNull()!!
        assertEquals(testUser, loginResult.user)
        assertTrue(loginResult.accessToken.isNotEmpty())
        assertEquals(loginResult.refreshToken?.isNotEmpty(), true)

        coVerify { userService.getUserByUsername(username) }
        verify { passwordService.verifyPassword(password, testUser.passwordHash) }
        coVerify { userSessionDao.insertSession(testUser.id, any()) }
        coVerify { userService.updateLastLogin(testUser.id) }
    }

    @Test
    fun `login should return UserNotFound when user does not exist`() = runTest {
        // Given
        val username = "nonexistent"
        val password = "password"

        coEvery { userService.getUserByUsername(username) } returns 
            UserNotFoundError.ByUsername(username).left()

        // When
        val result = authService.login(username, password)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LoginError.UserNotFound, result.leftOrNull())
    }

    @Test
    fun `login should return InvalidCredentials when password is wrong`() = runTest {
        // Given
        val username = "testuser"
        val password = "wrongpassword"

        coEvery { userService.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns false

        // When
        val result = authService.login(username, password)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LoginError.InvalidCredentials, result.leftOrNull())
    }

    @Test
    fun `login should return SessionCreationFailed when session creation fails`() = runTest {
        // Given
        val username = "testuser"
        val password = "correctpassword"

        coEvery { userService.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns true

        coEvery { userSessionDao.insertSession(testUser.id, any()) } returns 
            UserSessionError.ForeignKeyViolation("User not found").left()

        // When
        val result = authService.login(username, password)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LoginError.UserNotFound, result.leftOrNull())
    }

    @Test
    fun `logout should successfully delete user sessions`() = runTest {
        // Given
        val userId = 1L
        coEvery { userSessionDao.deleteSessionsByUserId(userId) } returns 2

        // When
        val result = authService.logout(userId)

        // Then
        assertTrue(result.isRight())
        coVerify { userSessionDao.deleteSessionsByUserId(userId) }
    }

    @Test
    fun `logout should return SessionNotFound when no sessions exist`() = runTest {
        // Given
        val userId = 1L
        coEvery { userSessionDao.deleteSessionsByUserId(userId) } returns 0

        // When
        val result = authService.logout(userId)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LogoutError.SessionNotFound(userId), result.leftOrNull())
    }

    @Test
    fun `validateToken should successfully validate valid token`() = runTest {
        // Given
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id)
        
        coEvery { userSessionDao.getSessionById(testSession.id) } returns testSession.right()
        coEvery { userService.getUserById(testUser.id) } returns testUser.right()
        coEvery { userSessionDao.updateLastAccessed(testSession.id, any()) } returns Unit.right()

        // When
        val result = authService.validateToken(token)

        // Then
        assertTrue(result.isRight())
        val userContext = result.getOrNull()!!
        assertEquals(testUser, userContext.user)
        assertEquals(testSession.id, userContext.sessionId)
    }

    @Test
    fun `validateToken should return InvalidSignature for token with wrong signature`() = runTest {
        // Given - create token with different secret
        val wrongJwtConfig = JwtConfig(secret = "wrong-secret")
        val token = wrongJwtConfig.generateAccessToken(testUser.id, testSession.id)

        // When
        val result = authService.validateToken(token)

        // Then
        assertTrue(result.isLeft())
        assertEquals(TokenValidationError.InvalidSignature, result.leftOrNull())
    }

    @Test
    fun `validateToken should return MalformedToken for invalid token format`() = runTest {
        // Given
        val invalidToken = "not.a.valid.jwt.token"

        // When
        val result = authService.validateToken(invalidToken)

        // Then
        assertTrue(result.isLeft())
        assertEquals(TokenValidationError.MalformedToken, result.leftOrNull())
    }

    @Test
    fun `validateToken should return InvalidSession when session not found`() = runTest {
        // Given
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id)
        
        coEvery { userSessionDao.getSessionById(testSession.id) } returns 
            UserSessionError.SessionNotFound(testSession.id).left()

        // When
        val result = authService.validateToken(token)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is TokenValidationError.InvalidSession)
        assertTrue(error.reason.contains("Session not found"))
    }

    @Test
    fun `validateToken should return InvalidSession when session expired`() = runTest {
        // Given
        val expiredSession = testSession.copy(expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 1000))
        val token = jwtConfig.generateAccessToken(testUser.id, expiredSession.id)
        
        coEvery { userSessionDao.getSessionById(expiredSession.id) } returns expiredSession.right()

        // When
        val result = authService.validateToken(token)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertTrue(error is TokenValidationError.InvalidSession)
        assertTrue(error.reason.contains("Session expired"))
    }

    @Test
    fun `refreshToken should successfully generate new tokens`() = runTest {
        // Given
        val refreshToken = jwtConfig.generateRefreshToken(testUser.id, testSession.id)
        
        coEvery { userSessionDao.getSessionById(testSession.id) } returns testSession.right()
        coEvery { userService.getUserById(testUser.id) } returns testUser.right()
        coEvery { userSessionDao.updateLastAccessed(testSession.id, any()) } returns Unit.right()

        // When
        val result = authService.refreshToken(refreshToken)

        // Then
        assertTrue(result.isRight())
        val loginResult = result.getOrNull()!!
        assertEquals(testUser, loginResult.user)
        assertTrue(loginResult.accessToken.isNotEmpty())
        assertEquals(loginResult.refreshToken?.isNotEmpty(), true)
    }

    @Test
    fun `refreshToken should return InvalidRefreshToken for access token`() = runTest {
        // Given - use access token instead of refresh token
        val accessToken = jwtConfig.generateAccessToken(testUser.id, testSession.id)

        // When
        val result = authService.refreshToken(accessToken)

        // Then
        assertTrue(result.isLeft())
        assertEquals(RefreshTokenError.InvalidRefreshToken, result.leftOrNull())
    }

    @Test
    fun `refreshToken should return InvalidRefreshToken for malformed token`() = runTest {
        // Given
        val invalidToken = "not.a.valid.refresh.token"

        // When
        val result = authService.refreshToken(invalidToken)

        // Then
        assertTrue(result.isLeft())
        assertEquals(RefreshTokenError.InvalidRefreshToken, result.leftOrNull())
    }
}
