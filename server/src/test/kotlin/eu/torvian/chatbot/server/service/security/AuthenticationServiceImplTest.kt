package eu.torvian.chatbot.server.service.security

import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.UserTrustedIpDao
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserSessionDao
import eu.torvian.chatbot.server.data.dao.WorkerDao
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.data.entities.UserTrustedIpEntity
import eu.torvian.chatbot.server.data.entities.WorkerEntity
import eu.torvian.chatbot.server.domain.config.IpSecurityMode
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError
import eu.torvian.chatbot.server.service.security.error.LoginError
import eu.torvian.chatbot.server.service.security.error.LogoutAllError
import eu.torvian.chatbot.server.service.security.error.LogoutError
import eu.torvian.chatbot.server.service.security.error.RefreshTokenError
import io.ktor.server.auth.jwt.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AuthenticationServiceImplTest {

    private val userService = mockk<UserService>()
    private val passwordService = mockk<PasswordService>()
    private val userSessionDao = mockk<UserSessionDao>()
    private val userTrustedIpDao = mockk<UserTrustedIpDao>()
    private val userDao = mockk<UserDao>()
    private val workerDao = mockk<WorkerDao>()
    private val authorizationService = mockk<AuthorizationService>()
    private val transactionScope = mockk<TransactionScope>()

    private val jwtConfig = JwtConfig(
        secret = "test-secret-key-for-testing-purposes-only",
        tokenExpirationMs = 15 * 60 * 1000L,
        refreshExpirationMs = 7 * 24 * 60 * 60 * 1000L
    )

    private fun createAuthService(ipSecurityMode: IpSecurityMode = IpSecurityMode.DISABLED) = AuthenticationServiceImpl(
        userService,
        passwordService,
        jwtConfig,
        userSessionDao,
        userTrustedIpDao,
        userDao,
        workerDao,
        authorizationService,
        transactionScope,
        ipSecurityMode
    )

    private val authService = createAuthService()

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
        expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 24 * 60 * 60 * 1000), // 24 hours
        ipAddress = "127.0.0.1"
    )

    private val testTrustedIp = UserTrustedIpEntity(
        id = 300L,
        userId = testUser.id,
        ipAddress = "10.0.0.1",
        isTrusted = true,
        isAcknowledged = false,
        firstUsedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastUsedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
    )

    @BeforeEach
    fun setUp() {
        clearMocks(
            userService,
            passwordService,
            userSessionDao,
            userTrustedIpDao,
            userDao,
            workerDao,
            authorizationService,
            transactionScope
        )

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

        coEvery { userSessionDao.insertSession(testUser.id, any(), any()) } returns testSession.right()
        coEvery { userService.updateLastLogin(testUser.id) } returns Unit.right()
        coEvery { authorizationService.getUserPermissions(testUser.id) } returns emptyList()

        // When
        val result = authService.login(username, password, "127.0.0.1")

        // Then
        assertTrue(result.isRight())
        val loginResult = result.getOrNull()!!
        assertEquals(testUser.toUser(), loginResult.user)
        assertTrue(loginResult.accessToken.isNotEmpty())
        assertEquals(loginResult.refreshToken.isNotEmpty(), true)
        assertEquals(emptyList(), loginResult.permissions)
        coVerify { userDao.getUserByUsername(username) }
        verify { passwordService.verifyPassword(password, testUser.passwordHash) }
        coVerify { userSessionDao.insertSession(testUser.id, any(), any()) }
        coVerify { userService.updateLastLogin(testUser.id) }
        coVerify { authorizationService.getUserPermissions(testUser.id) }
        val accessExpiryMs = JWT.decode(loginResult.accessToken).expiresAt.time
        val loginExpiryMs = loginResult.expiresAt.toEpochMilliseconds()
        assertTrue(loginExpiryMs >= accessExpiryMs)
        assertTrue(loginExpiryMs - accessExpiryMs < 1000)
        assertNotEquals(testSession.expiresAt.toEpochMilliseconds(), loginResult.expiresAt.toEpochMilliseconds())
    }

    @Test
    fun `login should allow a new ip in warning mode and flag the user`() = runTest {
        val warningAuthService = createAuthService(IpSecurityMode.WARNING)
        val username = "testuser"
        val password = "correctpassword"
        val ipAddress = "10.0.0.1"

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns true
        coEvery { userTrustedIpDao.getTrustedIp(testUser.id, ipAddress) } returns null
        coEvery {
            userTrustedIpDao.insertTrustedIp(
                testUser.id,
                ipAddress,
                true,
                false,
                any(),
                any()
            )
        } returns testTrustedIp
        coEvery { userSessionDao.insertSession(testUser.id, any(), ipAddress, any()) } returns testSession.right()
        coEvery { userService.updateLastLogin(testUser.id) } returns Unit.right()
        coEvery { authorizationService.getUserPermissions(testUser.id) } returns emptyList()

        val result = warningAuthService.login(username, password, ipAddress)

        assertTrue(result.isRight())
        coVerify { userTrustedIpDao.insertTrustedIp(testUser.id, ipAddress, true, false, any(), any()) }
    }

    @Test
    fun `login should block a new ip in strict mode with verification required`() = runTest {
        val strictAuthService = createAuthService(IpSecurityMode.STRICT)
        val username = "testuser"
        val password = "correctpassword"
        val ipAddress = "10.0.0.2"

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns true
        coEvery { userTrustedIpDao.getTrustedIp(testUser.id, ipAddress) } returns null
        coEvery {
            userTrustedIpDao.insertTrustedIp(
                testUser.id,
                ipAddress,
                false,
                true,
                any(),
                any()
            )
        } returns testTrustedIp.copy(ipAddress = ipAddress, isTrusted = false, isAcknowledged = true)

        val result = strictAuthService.login(username, password, ipAddress)

        assertTrue(result.isLeft())
        assertEquals(LoginError.VerificationRequired, result.leftOrNull())
        coVerify(exactly = 0) { userSessionDao.insertSession(any(), any(), any()) }
    }

    @Test
    fun `acknowledgeTrustedIps should return success`() = runTest {
        coEvery { userTrustedIpDao.acknowledgeTrustedIps(testUser.id) } returns 2

        val result = authService.acknowledgeTrustedIps(testUser.id)

        assertTrue(result.isRight())
        coVerify { userTrustedIpDao.acknowledgeTrustedIps(testUser.id) }
    }

    @Test
    fun `validateWorkerCredential should successfully validate valid worker token`() = runTest {
        // Given
        val worker = WorkerEntity(
            id = 200L,
            workerUid = "worker-200",
            ownerUserId = testUser.id,
            displayName = "worker-1",
            certificatePem = "pem",
            certificateFingerprint = "fingerprint",
            allowedScopes = listOf("messages:read"),
            createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            lastSeenAt = null
        )
        val token = jwtConfig.generateServiceAccessToken(worker.id, worker.workerUid, worker.ownerUserId, listOf("messages:read"))
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        coEvery { workerDao.getWorkerById(worker.id) } returns worker.right()

        // When
        val result = authService.validateWorkerCredential(credential)

        // Then
        assertNotNull(result)
        assertEquals(worker.id, result.workerId)
        assertEquals(worker.workerUid, result.workerUid)
        assertEquals(worker.ownerUserId, result.ownerUserId)
        assertEquals(listOf("messages:read"), result.scopes)
        assertEquals("service", decodedJWT.getClaim("principalType").asString())
        assertEquals("access", decodedJWT.getClaim("tokenType").asString())
        coVerify { workerDao.getWorkerById(worker.id) }
    }

    @Test
    fun `validateWorkerCredential should return null when token type is refresh`() = runTest {
        // Given
        val token = JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withAudience(jwtConfig.workerAudience)
            .withSubject("worker:200")
            .withClaim("principalType", "service")
            .withClaim("tokenType", "refresh")
            .withClaim("workerId", 200L)
            .withClaim("ownerUserId", testUser.id)
            .withArrayClaim("scope", arrayOf("messages:read"))
            .withIssuedAt(java.util.Date())
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.HMAC256(jwtConfig.secret))
        val credential = JWTCredential(JWT.decode(token))

        // When
        val result = authService.validateWorkerCredential(credential)

        // Then
        assertNull(result)
        coVerify(exactly = 0) { workerDao.getWorkerById(any()) }
    }

    @Test
    fun `validateWorkerCredential should return null when principal type is user`() = runTest {
        // Given
        val token = JWT.create()
            .withIssuer(jwtConfig.issuer)
            .withAudience(jwtConfig.workerAudience)
            .withSubject("worker:200")
            .withClaim("principalType", "user")
            .withClaim("tokenType", "access")
            .withClaim("workerId", 200L)
            .withClaim("ownerUserId", testUser.id)
            .withArrayClaim("scope", arrayOf("messages:read"))
            .withIssuedAt(java.util.Date())
            .withExpiresAt(java.util.Date(System.currentTimeMillis() + 3600000))
            .sign(Algorithm.HMAC256(jwtConfig.secret))
        val credential = JWTCredential(JWT.decode(token))

        // When
        val result = authService.validateWorkerCredential(credential)

        // Then
        assertNull(result)
        coVerify(exactly = 0) { workerDao.getWorkerById(any()) }
    }

    @Test
    fun `login should return UserNotFound when user does not exist`() = runTest {
        // Given
        val username = "nonexistent"
        val password = "password"

        coEvery { userDao.getUserByUsername(username) } returns UserError.UserNotFoundByUsername(username).left()

        // When
        val result = authService.login(username, password, null)

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
        val result = authService.login(username, password, null)

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
        val result = authService.login(username, password, null)

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

        coEvery { userSessionDao.insertSession(testUser.id, any(), any()) } returns
                UserSessionError.ForeignKeyViolation("User not found").left()

        // When
        val result = authService.login(username, password, null)

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
    fun `getUserSessions should return sessions from the DAO`() = runTest {
        // Given
        val userId = testUser.id
        val sessions = listOf(
            testSession,
            testSession.copy(id = 101L, lastAccessed = Instant.fromEpochMilliseconds(testSession.lastAccessed.toEpochMilliseconds() + 1_000))
        )
        coEvery { userSessionDao.getSessionsByUserId(userId) } returns sessions

        // When
        val result = authService.getUserSessions(userId)

        // Then
        assertTrue(result.isRight())
        assertEquals(sessions, result.getOrNull())
        coVerify { userSessionDao.getSessionsByUserId(userId) }
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
    fun `validateCredential should return null when token type is refresh`() = runTest {
        // Given
        val token = jwtConfig.generateRefreshToken(testUser.id, testSession.id)
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        // When
        val result = authService.validateCredential(credential)

        // Then
        assertNull(result)
        coVerify(exactly = 0) { userSessionDao.getSessionById(any()) }
        coVerify(exactly = 0) { userService.getUserById(any()) }
    }

    @Test
    fun `validateCredential should return null when token is a worker service token`() = runTest {
        // Given
        val token = jwtConfig.generateServiceAccessToken(
            workerId = 200L,
            workerUid = "worker-200",
            ownerUserId = testUser.id,
            scopes = listOf("messages:read")
        )
        val decodedJWT = JWT.decode(token)
        val credential = JWTCredential(decodedJWT)

        // When
        val result = authService.validateCredential(credential)

        // Then
        assertNull(result)
        coVerify(exactly = 0) { userSessionDao.getSessionById(any()) }
        coVerify(exactly = 0) { userService.getUserById(any()) }
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
        coEvery { userSessionDao.insertSession(testUser.id, any(), any()) } returns testSession.right()
        coEvery { authorizationService.getUserPermissions(testUser.id) } returns emptyList()

        // When
        val result = authService.refreshToken(refreshToken, "127.0.0.1")

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
        val result = authService.refreshToken(accessToken, null)

        // Then
        assertTrue(result.isLeft())
        assertEquals(RefreshTokenError.InvalidRefreshToken, result.leftOrNull())
    }

    @Test
    fun `refreshToken should return InvalidRefreshToken for malformed token`() = runTest {
        // Given
        val invalidToken = "not.a.valid.refresh.token"

        // When
        val result = authService.refreshToken(invalidToken, null)

        // Then
        assertTrue(result.isLeft())
        assertEquals(RefreshTokenError.InvalidRefreshToken, result.leftOrNull())
    }
}
