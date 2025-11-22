package eu.torvian.chatbot.server.service.security

import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserSessionDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError
import eu.torvian.chatbot.server.service.security.error.LoginError
import eu.torvian.chatbot.server.service.security.error.LogoutError
import eu.torvian.chatbot.server.service.security.error.LogoutAllError
import eu.torvian.chatbot.server.service.security.error.RefreshTokenError
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import io.ktor.server.auth.jwt.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthenticationServiceImplTest {

    private val userService = mockk<UserService>()
    private val passwordService = mockk<PasswordService>()
    private val userSessionDao = mockk<UserSessionDao>()
    private val userDao = mockk<UserDao>()
    private val authorizationService = mockk<AuthorizationService>()
    private val transactionScope = mockk<TransactionScope>()

    private val jwtConfig = JwtConfig(
        secret = "test-secret-key-for-testing-purposes-only"
    )

    private val authService = AuthenticationServiceImpl(
        userService, passwordService, jwtConfig, userSessionDao, userDao, authorizationService, transactionScope
    )

    private val testUser = UserEntity(
        id = 1L,
        username = "testuser",
        passwordHash = "hashedpassword",
        email = "test@example.com",
        status = UserStatus.ACTIVE,
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
        clearMocks(userService, passwordService, userSessionDao, userDao, authorizationService, transactionScope)

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

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns true

        coEvery { userSessionDao.insertSession(testUser.id, any()) } returns testSession.right()
        coEvery { userService.updateLastLogin(testUser.id) } returns Unit.right()
        coEvery { authorizationService.getUserPermissions(testUser.id) } returns emptyList()

        // When
        val result = authService.login(username, password)

        // Then
        assertTrue(result.isRight())
        val loginResult = result.getOrNull()!!
        assertEquals(testUser.toUser(), loginResult.user)
        assertTrue(loginResult.accessToken.isNotEmpty())
        assertEquals(loginResult.refreshToken.isNotEmpty(), true)
        assertEquals(emptyList(), loginResult.permissions)

        coVerify { userDao.getUserByUsername(username) }
        verify { passwordService.verifyPassword(password, testUser.passwordHash) }
        coVerify { userSessionDao.insertSession(testUser.id, any()) }
        coVerify { userService.updateLastLogin(testUser.id) }
        coVerify { authorizationService.getUserPermissions(testUser.id) }
    }

    @Test
    fun `login should return UserNotFound when user does not exist`() = runTest {
        // Given
        val username = "nonexistent"
        val password = "password"

        coEvery { userDao.getUserByUsername(username) } returns UserError.UserNotFoundByUsername(username).left()

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

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns false

        // When
        val result = authService.login(username, password)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LoginError.InvalidCredentials, result.leftOrNull())
    }

    @Test
    fun `login should return AccountLocked when account is disabled`() = runTest {
        // Given
        val username = "testuser"
        val password = "anypassword"
        val disabledUser = testUser.copy(status = UserStatus.DISABLED)

        coEvery { userDao.getUserByUsername(username) } returns disabledUser.right()

        // When
        val result = authService.login(username, password)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LoginError.AccountLocked("Account is disabled"), result.leftOrNull())

        coVerify { userDao.getUserByUsername(username) }
        // Password verification should not be attempted for disabled accounts
        verify(exactly = 0) { passwordService.verifyPassword(any(), any()) }
    }

    @Test
    fun `login should return SessionCreationFailed when session creation fails`() = runTest {
        // Given
        val username = "testuser"
        val password = "correctpassword"

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
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
    fun `logout should successfully delete specific session`() = runTest {
        // Given
        val sessionId = 100L
        coEvery { userSessionDao.deleteSession(sessionId) } returns Unit.right()

        // When
        val result = authService.logout(sessionId)

        // Then
        assertTrue(result.isRight())
        coVerify { userSessionDao.deleteSession(sessionId) }
    }

    @Test
    fun `logout should return SessionNotFound when session does not exist`() = runTest {
        // Given
        val sessionId = 100L
        coEvery { userSessionDao.deleteSession(sessionId) } returns UserSessionError.SessionNotFound(sessionId).left()

        // When
        val result = authService.logout(sessionId)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LogoutError.SessionNotFound(sessionId), result.leftOrNull())
    }

    @Test
    fun `logoutAll should successfully delete all user sessions`() = runTest {
        // Given
        val userId = 1L
        coEvery { userSessionDao.deleteSessionsByUserId(userId) } returns 2

        // When
        val result = authService.logoutAll(userId)

        // Then
        assertTrue(result.isRight())
        coVerify { userSessionDao.deleteSessionsByUserId(userId) }
    }

    @Test
    fun `logoutAll should return NoSessionsFound when no sessions exist for user`() = runTest {
        // Given
        val userId = 1L
        coEvery { userSessionDao.deleteSessionsByUserId(userId) } returns 0

        // When
        val result = authService.logoutAll(userId)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LogoutAllError.NoSessionsFound(userId), result.leftOrNull())
    }

    @Test
    fun `validateCredential should successfully validate valid credential`() = runTest {
        // Given
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id)
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        coEvery { userSessionDao.getSessionById(testSession.id) } returns testSession.right()
        coEvery { userService.getUserById(testUser.id) } returns testUser.toUser().right()
        coEvery { userSessionDao.updateLastAccessed(testSession.id, any()) } returns Unit.right()

        // When
        val result = authService.validateCredential(credential)

        // Then
        assertNotNull(result)
        assertEquals(testUser.toUser(), result.user)
        assertEquals(testSession.id, result.sessionId)

        coVerify { userSessionDao.getSessionById(testSession.id) }
        coVerify { userService.getUserById(testUser.id) }
        coVerify { userSessionDao.updateLastAccessed(testSession.id, any()) }
    }

    @Test
    fun `validateCredential should return null when session not found`() = runTest {
        // Given
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id)
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        coEvery { userSessionDao.getSessionById(testSession.id) } returns
                UserSessionError.SessionNotFound(testSession.id).left()

        // When
        val result = authService.validateCredential(credential)

        // Then
        assertNull(result)
        coVerify { userSessionDao.getSessionById(testSession.id) }
    }

    @Test
    fun `validateCredential should return null when session expired`() = runTest {
        // Given
        val expiredSession =
            testSession.copy(expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() - 1000))
        val token = jwtConfig.generateAccessToken(testUser.id, expiredSession.id)
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        coEvery { userSessionDao.getSessionById(expiredSession.id) } returns expiredSession.right()

        // When
        val result = authService.validateCredential(credential)

        // Then
        assertNull(result)
        coVerify { userSessionDao.getSessionById(expiredSession.id) }
    }

    @Test
    fun `validateCredential should return null when user not found`() = runTest {
        // Given
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id)
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        coEvery { userSessionDao.getSessionById(testSession.id) } returns testSession.right()
        coEvery { userService.getUserById(testUser.id) } returns UserNotFoundError.ById(testUser.id).left()

        // When
        val result = authService.validateCredential(credential)

        // Then
        assertNull(result)
        coVerify { userSessionDao.getSessionById(testSession.id) }
        coVerify { userService.getUserById(testUser.id) }
    }

    @Test
    fun `validateCredential should return null for credential with invalid claims`() = runTest {
        // Create a new token without sessionId by manually creating it
        // We'll use a completely invalid subject to simulate invalid claims
        val tokenWithInvalidClaims = JWT.create()
            .withSubject("invalid-subject") // This will cause toLongOrNull() to return null
            .withIssuedAt(java.util.Date())
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 3600000))
            .withClaim("sessionId", testSession.id)
            .withIssuer("chatbot-server")
            .withAudience("chatbot-users")
            .sign(Algorithm.HMAC256(jwtConfig.secret))

        val decodedJWT = JWT.decode(tokenWithInvalidClaims)
        val credential = JWTCredential(decodedJWT)

        // When
        val result = authService.validateCredential(credential)

        // Then
        assertNull(result)
    }

    @Test
    fun `validateCredential should return null for locked or disabled account`() = runTest {
        // Given
        val disabledUser = testUser.copy(status = UserStatus.DISABLED)
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id)
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        coEvery { userSessionDao.getSessionById(testSession.id) } returns testSession.right()
        coEvery { userService.getUserById(testUser.id) } returns disabledUser.toUser().right()

        // When
        val result = authService.validateCredential(credential)

        // Then
        assertNull(result)
    }

    @Test
    fun `refreshToken should successfully generate new tokens`() = runTest {
        // Given
        val refreshToken = jwtConfig.generateRefreshToken(testUser.id, testSession.id)

        coEvery { userSessionDao.getSessionById(testSession.id) } returns testSession.right()
        coEvery { userService.getUserById(testUser.id) } returns testUser.toUser().right()
        coEvery { userSessionDao.updateLastAccessed(testSession.id, any()) } returns Unit.right()
        coEvery { userSessionDao.deleteSession(testSession.id) } returns Unit.right()
        coEvery { userSessionDao.insertSession(testUser.id, any()) } returns testSession.right()
        coEvery { authorizationService.getUserPermissions(testUser.id) } returns emptyList()

        // When
        val result = authService.refreshToken(refreshToken)

        // Then
        assertTrue(result.isRight())
        val loginResult = result.getOrNull()!!
        assertEquals(testUser.toUser(), loginResult.user)
        assertTrue(loginResult.accessToken.isNotEmpty())
        assertEquals(loginResult.refreshToken.isNotEmpty(), true)
        assertEquals(emptyList(), loginResult.permissions)
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
