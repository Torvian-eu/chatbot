package eu.torvian.chatbot.server.service.security

import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.security.AccountValidationPolicy
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.entities.UserDeviceEntity
import eu.torvian.chatbot.server.data.entities.UserSessionEntity
import eu.torvian.chatbot.server.data.entities.UserTrustedDeviceEntity
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.domain.config.AccountSecurityMode
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.security.error.LoginError
import eu.torvian.chatbot.server.service.security.error.LogoutAllError
import eu.torvian.chatbot.server.service.security.error.LogoutError
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class AuthenticationServiceImplTest {

    private val userService = mockk<UserService>()
    private val passwordService = mockk<PasswordService>()
    private val userSessionDao = mockk<UserSessionDao>()
    private val userTrustedDeviceDao = mockk<UserTrustedDeviceDao>()
    private val userDeviceDao = mockk<UserDeviceDao>()
    private val securityAuditDao = mockk<SecurityAuditDao>()
    private val userDao = mockk<UserDao>()
    private val authorizationService = mockk<AuthorizationService>()
    private val transactionScope = mockk<TransactionScope>()
    private val failedLoginAttemptDao = mockk<FailedLoginAttemptDao>()

    private val jwtConfig = JwtConfig(
        secret = "test-secret-key-for-testing-purposes-only",
        tokenExpirationMs = 15 * 60 * 1000L,
        refreshExpirationMs = 7 * 24 * 60 * 60 * 1000L
    )

    // Create authService with the new signature
    private val authService = AuthenticationServiceImpl(
        userService = userService,
        passwordService = passwordService,
        jwtConfig = jwtConfig,
        userSessionDao = userSessionDao,
        userTrustedDeviceDao = userTrustedDeviceDao,
        userDeviceDao = userDeviceDao,
        securityAuditDao = securityAuditDao,
        userDao = userDao,
        authorizationService = authorizationService,
        transactionScope = transactionScope,
        accountSecurityMode = AccountSecurityMode.DISABLED,
        failedLoginAttemptDao = failedLoginAttemptDao,
        authPolicy = AccountValidationPolicy()
    )

    private fun createAuthService(
        accountSecurityMode: AccountSecurityMode = AccountSecurityMode.DISABLED
    ) = AuthenticationServiceImpl(
        userService = userService,
        passwordService = passwordService,
        jwtConfig = jwtConfig,
        userSessionDao = userSessionDao,
        userTrustedDeviceDao = userTrustedDeviceDao,
        userDeviceDao = userDeviceDao,
        securityAuditDao = securityAuditDao,
        userDao = userDao,
        authorizationService = authorizationService,
        transactionScope = transactionScope,
        accountSecurityMode = accountSecurityMode,
        failedLoginAttemptDao = failedLoginAttemptDao,
        authPolicy = AccountValidationPolicy()
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
        deviceId = "test-device-id",
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastAccessed = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 24 * 60 * 60 * 1000), // 24 hours
        ipAddress = "127.0.0.1",
        isRestricted = false
    )

    private val testTrustedDevice = UserTrustedDeviceEntity(
        id = 300L,
        userId = testUser.id,
        deviceId = "device-001",
        lastIpAddress = "10.0.0.1",
        firstSeenAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastUsedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
    )

    private val testDeviceRecord = UserDeviceEntity(
        id = 400L,
        userId = testUser.id,
        clientDeviceId = "device-001",
        deviceName = "device-001",
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastUsedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
    )

    @BeforeEach
    fun setUp() {
        clearMocks(
            userService,
            passwordService,
            userSessionDao,
            userTrustedDeviceDao,
            userDeviceDao,
            securityAuditDao,
            userDao,
            authorizationService,
            transactionScope,
            failedLoginAttemptDao
        )

        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        // Mock failedLoginAttemptDao methods to return defaults
        coEvery { failedLoginAttemptDao.countFailuresByUsername(any(), any()) } returns 0
        coEvery { failedLoginAttemptDao.countFailuresByIp(any(), any()) } returns 0
        coEvery { failedLoginAttemptDao.recordFailure(any(), any(), any()) } returns Unit
        coEvery { failedLoginAttemptDao.clearFailures(any()) } returns Unit
        coEvery { failedLoginAttemptDao.cleanupOldRecords(any()) } returns Unit
        coEvery { userDeviceDao.getDeviceByClientId(any(), any()) } returns null
        coEvery { userDeviceDao.insertDevice(any(), any(), any()) } returns testDeviceRecord
        coEvery { userDeviceDao.updateDeviceUsage(any(), any()) } returns true
    }

    @Test
    fun `login should successfully authenticate user with valid credentials`() = runTest {
        // Given
        val username = "testuser"
        val password = "correctpassword"
        val deviceId = "device-001"

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns true

        coEvery { userSessionDao.insertSession(testUser.id, any(), any(), any()) } returns testSession.right()
        coEvery { userService.updateLastLogin(testUser.id) } returns Unit.right()
        coEvery { authorizationService.getUserPermissions(testUser.id) } returns emptyList()

        // When
        val result = authService.login(username, password, "127.0.0.1", deviceId)

        // Then
        assertTrue(result.isRight())
        val loginResult = result.getOrNull()!!
        assertEquals(testUser.toUser(), loginResult.user)
        assertTrue(loginResult.accessToken.isNotEmpty())
        assertEquals(loginResult.refreshToken.isNotEmpty(), true)
        assertEquals(emptyList(), loginResult.permissions)
        coVerify { userDao.getUserByUsername(username) }
        verify { passwordService.verifyPassword(password, testUser.passwordHash) }
        coVerify { userSessionDao.insertSession(testUser.id, any(), any(), any()) }
        coVerify { userService.updateLastLogin(testUser.id) }
        coVerify { authorizationService.getUserPermissions(testUser.id) }
        val accessExpiryMs = JWT.decode(loginResult.accessToken).expiresAt.time
        val loginExpiryMs = loginResult.expiresAt.toEpochMilliseconds()
        assertTrue(loginExpiryMs >= accessExpiryMs)
        assertTrue(loginExpiryMs - accessExpiryMs < 1000)
        assertNotEquals(testSession.expiresAt.toEpochMilliseconds(), loginResult.expiresAt.toEpochMilliseconds())
    }

    @Test
    fun `login should allow a new device in warning mode and flag the session as restricted`() = runTest {
        val warningAuthService = createAuthService(AccountSecurityMode.WARNING)
        val username = "testuser"
        val password = "correctpassword"
        val deviceId = "new-device-001"
        val ipAddress = "10.0.0.1"

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns true
        // User already has trusted devices, so this is not the first device
        coEvery { userTrustedDeviceDao.getTrustedDevicesCount(testUser.id) } returns 1
        coEvery { userTrustedDeviceDao.getTrustedDevice(testUser.id, deviceId) } returns null
        coEvery {
            securityAuditDao.insertAuditRecord(
                userId = testUser.id,
                deviceId = deviceId,
                ipAddress = ipAddress,
                createdAt = any()
            )
        } returns mockk()
        coEvery {
            userSessionDao.insertSession(
                testUser.id,
                deviceId,
                any(),
                ipAddress,
                any()
            )
        } returns testSession.right()
        coEvery { userService.updateLastLogin(testUser.id) } returns Unit.right()
        coEvery { authorizationService.getUserPermissions(testUser.id) } returns emptyList()

        val result = warningAuthService.login(username, password, ipAddress, deviceId)

        assertTrue(result.isRight())
        assertTrue(result.getOrNull()!!.isRestricted)
        coVerify { securityAuditDao.insertAuditRecord(testUser.id, deviceId, ipAddress, any()) }
    }

    @Test
    fun `login should block a new device in strict mode with verification required`() = runTest {
        val strictAuthService = createAuthService(AccountSecurityMode.STRICT)
        val username = "testuser"
        val password = "correctpassword"
        val deviceId = "new-device-002"
        val ipAddress = "10.0.0.2"

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns true
        // User already has trusted devices, so this is not the first device
        coEvery { userTrustedDeviceDao.getTrustedDevicesCount(testUser.id) } returns 1
        coEvery { userTrustedDeviceDao.getTrustedDevice(testUser.id, deviceId) } returns null
        // Audit record is inserted outside the transaction for STRICT mode
        coEvery {
            securityAuditDao.insertAuditRecord(
                userId = testUser.id,
                deviceId = deviceId,
                ipAddress = ipAddress,
                createdAt = any()
            )
        } returns mockk()

        val result = strictAuthService.login(username, password, ipAddress, deviceId)

        assertTrue(result.isLeft())
        assertEquals(LoginError.VerificationRequired(testUser.id), result.leftOrNull())
        coVerify(exactly = 0) { userSessionDao.insertSession(any(), any(), any(), any()) }
        // Verify audit record was inserted outside the transaction
        coVerify { securityAuditDao.insertAuditRecord(testUser.id, deviceId, ipAddress, any()) }
    }

    @Test
    fun `login should auto-trust first device in warning mode without restriction`() = runTest {
        val warningAuthService = createAuthService(AccountSecurityMode.WARNING)
        val username = "testuser"
        val password = "correctpassword"
        val deviceId = "first-device-ever"
        val ipAddress = "10.0.0.1"

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns true
        // This is the first device ever for this user
        coEvery { userTrustedDeviceDao.getTrustedDevicesCount(testUser.id) } returns 0
        coEvery {
            userTrustedDeviceDao.insertTrustedDevice(
                userId = testUser.id,
                deviceId = deviceId,
                ipAddress = ipAddress,
                firstSeenAt = any(),
                lastUsedAt = any()
            )
        } returns testTrustedDevice
        coEvery {
            userSessionDao.insertSession(
                testUser.id,
                deviceId,
                any(),
                ipAddress,
                false
            )
        } returns testSession.right()
        coEvery { userService.updateLastLogin(testUser.id) } returns Unit.right()
        coEvery { authorizationService.getUserPermissions(testUser.id) } returns emptyList()

        val result = warningAuthService.login(username, password, ipAddress, deviceId)

        assertTrue(result.isRight())
        // First device should NOT be restricted (TOFU)
        assertTrue(!result.getOrNull()!!.isRestricted)
        // Verify the device was inserted into trusted devices
        coVerify { userTrustedDeviceDao.insertTrustedDevice(testUser.id, deviceId, ipAddress, any(), any()) }
    }

    @Test
    fun `login should auto-trust first device in strict mode without restriction`() = runTest {
        val strictAuthService = createAuthService(AccountSecurityMode.STRICT)
        val username = "testuser"
        val password = "correctpassword"
        val deviceId = "first-device-ever"
        val ipAddress = "10.0.0.1"

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns true
        // This is the first device ever for this user
        coEvery { userTrustedDeviceDao.getTrustedDevicesCount(testUser.id) } returns 0
        coEvery {
            userTrustedDeviceDao.insertTrustedDevice(
                userId = testUser.id,
                deviceId = deviceId,
                ipAddress = ipAddress,
                firstSeenAt = any(),
                lastUsedAt = any()
            )
        } returns testTrustedDevice
        coEvery {
            userSessionDao.insertSession(
                testUser.id,
                deviceId,
                any(),
                ipAddress,
                false
            )
        } returns testSession.right()
        coEvery { userService.updateLastLogin(testUser.id) } returns Unit.right()
        coEvery { authorizationService.getUserPermissions(testUser.id) } returns emptyList()

        val result = strictAuthService.login(username, password, ipAddress, deviceId)

        assertTrue(result.isRight())
        // First device should NOT be restricted (TOFU) - even in STRICT mode
        assertTrue(!result.getOrNull()!!.isRestricted)
        // Verify the device was inserted into trusted devices
        coVerify { userTrustedDeviceDao.insertTrustedDevice(testUser.id, deviceId, ipAddress, any(), any()) }
    }

    @Test
    fun `login should return UserNotFound when user does not exist`() = runTest {
        // Given
        val username = "nonexistent"
        val password = "password"
        val deviceId = "device-001"

        coEvery { userDao.getUserByUsername(username) } returns UserError.UserNotFoundByUsername(username).left()

        // When
        val result = authService.login(username, password, null, deviceId)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LoginError.UserNotFound, result.leftOrNull())
    }

    @Test
    fun `login should return InvalidCredentials when password is wrong`() = runTest {
        // Given
        val username = "testuser"
        val password = "wrongpassword"
        val deviceId = "device-001"

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns false

        // When
        val result = authService.login(username, password, null, deviceId)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LoginError.InvalidCredentials, result.leftOrNull())
    }

    @Test
    fun `login should return AccountLocked when account is disabled`() = runTest {
        // Given
        val username = "testuser"
        val password = "anypassword"
        val deviceId = "device-001"
        val disabledUser = testUser.copy(status = UserStatus.DISABLED)

        coEvery { userDao.getUserByUsername(username) } returns disabledUser.right()

        // When
        val result = authService.login(username, password, null, deviceId)

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
        val deviceId = "device-001"

        coEvery { userDao.getUserByUsername(username) } returns testUser.right()
        every { passwordService.verifyPassword(password, testUser.passwordHash) } returns true

        coEvery { userSessionDao.insertSession(testUser.id, any(), any(), any()) } returns
                UserSessionError.ForeignKeyViolation("User not found").left()

        // When
        val result = authService.login(username, password, null, deviceId)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LoginError.UserNotFound, result.leftOrNull())
    }

    @Test
    fun `logout should successfully delete specific session`() = runTest {
        // Given
        val userId = 1L
        val sessionId = 100L
        val requesterSessionId = sessionId // Same session - user logging out themselves
        coEvery { userSessionDao.getSessionById(sessionId) } returns testSession.copy(userId = userId).right()
        coEvery { userSessionDao.deleteSession(sessionId) } returns Unit.right()

        // When
        val result = authService.logout(userId, sessionId, requesterSessionId, requesterIsRestricted = false)

        // Then
        assertTrue(result.isRight())
        coVerify { userSessionDao.getSessionById(sessionId) }
        coVerify { userSessionDao.deleteSession(sessionId) }
    }

    @Test
    fun `logout should return SessionNotFound when session does not exist`() = runTest {
        // Given
        val userId = 1L
        val sessionId = 100L
        coEvery { userSessionDao.getSessionById(sessionId) } returns UserSessionError.SessionNotFound(sessionId).left()

        // When
        val result = authService.logout(userId, sessionId, sessionId, requesterIsRestricted = false)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LogoutError.SessionNotFound(sessionId), result.leftOrNull())
    }

    @Test
    fun `logout should return SessionNotFound when session belongs to different user`() = runTest {
        // Given
        val userId = 1L
        val sessionId = 100L
        // Session belongs to a different user (userId = 999)
        coEvery { userSessionDao.getSessionById(sessionId) } returns testSession.copy(userId = 999L).right()

        // When
        val result = authService.logout(userId, sessionId, sessionId, requesterIsRestricted = false)

        // Then
        assertTrue(result.isLeft())
        assertEquals(LogoutError.SessionNotFound(sessionId), result.leftOrNull())
    }

    @Test
    fun `logout should return InsufficientPermissions when restricted session tries to revoke other session`() =
        runTest {
            // Given
            val userId = 1L
            val sessionId = 100L
            val requesterSessionId = 200L // Different session
            coEvery { userSessionDao.getSessionById(sessionId) } returns testSession.copy(userId = userId).right()

            // When
            val result = authService.logout(userId, sessionId, requesterSessionId, requesterIsRestricted = true)

            // Then
            assertTrue(result.isLeft())
            assertEquals(LogoutError.InsufficientPermissions, result.leftOrNull())
        }

    @Test
    fun `logoutAll should successfully delete all user sessions`() = runTest {
        // Given
        val userId = 1L
        coEvery { userSessionDao.deleteSessionsByUserId(userId) } returns 2

        // When
        val result = authService.logoutAll(userId, requesterIsRestricted = false)

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
        val result = authService.logoutAll(userId, requesterIsRestricted = false)

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
            testSession.copy(
                id = 101L,
                lastAccessed = Instant.fromEpochMilliseconds(testSession.lastAccessed.toEpochMilliseconds() + 1_000)
            )
        )
        coEvery { userSessionDao.getSessionsByUserId(userId) } returns sessions

        // When
        val result = authService.getUserSessions(userId)

        // Then
        assertTrue(result.isRight())
        assertEquals(sessions, result.getOrNull())
        coVerify { userSessionDao.getSessionsByUserId(userId) }
    }
}
