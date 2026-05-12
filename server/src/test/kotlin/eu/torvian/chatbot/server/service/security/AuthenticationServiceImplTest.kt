package eu.torvian.chatbot.server.service.security

import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.common.security.AccountValidationPolicy
import eu.torvian.chatbot.common.security.SecurityAuditStatus
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.error.UserError
import eu.torvian.chatbot.server.data.dao.error.UserSessionError
import eu.torvian.chatbot.server.data.entities.*
import eu.torvian.chatbot.server.data.entities.mappers.toUser
import eu.torvian.chatbot.server.domain.config.AccountSecurityMode
import eu.torvian.chatbot.server.domain.security.JwtConfig
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.auth.UserNotFoundError
import eu.torvian.chatbot.server.service.security.error.*
import io.ktor.server.auth.jwt.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.time.Instant

class AuthenticationServiceImplTest {

    private val userService = mockk<UserService>()
    private val passwordService = mockk<PasswordService>()
    private val userSessionDao = mockk<UserSessionDao>()
    private val userTrustedDeviceDao = mockk<UserTrustedDeviceDao>()
    private val securityAuditDao = mockk<SecurityAuditDao>()
    private val userDao = mockk<UserDao>()
    private val workerDao = mockk<WorkerDao>()
    private val authorizationService = mockk<AuthorizationService>()
    private val transactionScope = mockk<TransactionScope>()
    private val deviceVerificationTokenDao = mockk<DeviceVerificationTokenDao>()

    private val jwtConfig = JwtConfig(
        secret = "test-secret-key-for-testing-purposes-only",
        tokenExpirationMs = 15 * 60 * 1000L,
        refreshExpirationMs = 7 * 24 * 60 * 60 * 1000L
    )

    private val failedLoginAttemptDao = mockk<FailedLoginAttemptDao>()

    // Create authService with the shared failedLoginAttemptDao mock
    private val authService = AuthenticationServiceImpl(
        userService,
        passwordService,
        jwtConfig,
        userSessionDao,
        userTrustedDeviceDao,
        securityAuditDao,
        userDao,
        workerDao,
        authorizationService,
        transactionScope,
        AccountSecurityMode.DISABLED,
        failedLoginAttemptDao,
        AccountValidationPolicy(),
        deviceVerificationTokenDao
    )

    private fun createAuthService(
        accountSecurityMode: AccountSecurityMode = AccountSecurityMode.DISABLED
    ) = AuthenticationServiceImpl(
        userService,
        passwordService,
        jwtConfig,
        userSessionDao,
        userTrustedDeviceDao,
        securityAuditDao,
        userDao,
        workerDao,
        authorizationService,
        transactionScope,
        accountSecurityMode,
        failedLoginAttemptDao,
        AccountValidationPolicy(),
        deviceVerificationTokenDao
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

    @BeforeEach
    fun setUp() {
        clearMocks(
            userService,
            passwordService,
            userSessionDao,
            userTrustedDeviceDao,
            securityAuditDao,
            userDao,
            workerDao,
            authorizationService,
            transactionScope,
            failedLoginAttemptDao,
            deviceVerificationTokenDao
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
        val token = jwtConfig.generateServiceAccessToken(
            worker.id,
            worker.workerUid,
            worker.ownerUserId,
            listOf("messages:read")
        )
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

    @Test
    fun `validateCredential should successfully validate valid credential`() = runTest {
        // Given
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id, isRestricted = false)
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
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id, isRestricted = false)
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
        val token = jwtConfig.generateAccessToken(testUser.id, expiredSession.id, isRestricted = false)
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
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id, isRestricted = false)
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
        val token = jwtConfig.generateAccessToken(testUser.id, testSession.id, isRestricted = false)
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
        coEvery { userSessionDao.insertSession(testUser.id, any(), any(), any()) } returns testSession.right()
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
        val accessToken = jwtConfig.generateAccessToken(testUser.id, testSession.id, isRestricted = false)

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

    // --- Security Alert Tests ---

    @Test
    fun `getSecurityAlerts should return unacknowledged alerts for user`() = runTest {
        // Given
        val userId = testUser.id
        val alerts = listOf(
            SecurityAuditEntity(
                id = 1L,
                userId = userId,
                deviceId = "device-001",
                ipAddress = "10.0.0.1",
                createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                status = SecurityAuditStatus.PENDING
            )
        )
        coEvery { securityAuditDao.getUnacknowledgedByUserId(userId) } returns alerts

        // When
        val result = authService.getSecurityAlerts(userId, requesterIsRestricted = false)

        // Then
        assertEquals(alerts, result.getOrNull())
        coVerify { securityAuditDao.getUnacknowledgedByUserId(userId) }
    }

    @Test
    fun `getSecurityAlerts should return InsufficientPermissions for restricted session`() = runTest {
        // Given
        val userId = testUser.id

        // When
        val result = authService.getSecurityAlerts(userId, requesterIsRestricted = true)

        // Then
        assertEquals(GetSecurityAlertsError.InsufficientPermissions(), result.leftOrNull())
        coVerify(exactly = 0) { securityAuditDao.getUnacknowledgedByUserId(any()) }
    }

    @Test
    fun `resolveSingleAlert should successfully trust a security alert`() = runTest {
        // Given
        val userId = testUser.id
        val alertId = 1L
        val deviceId = "device-001"
        val alert = SecurityAuditEntity(
            id = alertId,
            userId = userId,
            deviceId = deviceId,
            ipAddress = "10.0.0.1",
            createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            status = SecurityAuditStatus.PENDING
        )
        coEvery { securityAuditDao.getAuditRecordById(alertId) } returns alert
        coEvery { userTrustedDeviceDao.getTrustedDevice(userId, deviceId) } returns null
        coEvery {
            userTrustedDeviceDao.insertTrustedDevice(
                userId = userId,
                deviceId = deviceId,
                ipAddress = "10.0.0.1",
                firstSeenAt = any(),
                lastUsedAt = any()
            )
        } returns testTrustedDevice
        coEvery { securityAuditDao.updateStatus(alertId, SecurityAuditStatus.TRUSTED, any()) } returns 1
        coEvery { userSessionDao.unrestrictSessions(userId, deviceId) } returns 1

        // When
        val result =
            authService.resolveSingleAlert(userId, alertId, SecurityAuditStatus.TRUSTED, requesterIsRestricted = false)

        // Then
        assertTrue(result.isRight())
        coVerify { securityAuditDao.getAuditRecordById(alertId) }
        coVerify { userTrustedDeviceDao.insertTrustedDevice(userId, deviceId, "10.0.0.1", any(), any()) }
        coVerify { securityAuditDao.updateStatus(alertId, SecurityAuditStatus.TRUSTED, any()) }
    }

    @Test
    fun `resolveSingleAlert should successfully dismiss a security alert`() = runTest {
        // Given
        val userId = testUser.id
        val alertId = 1L
        val deviceId = "device-001"
        val alert = SecurityAuditEntity(
            id = alertId,
            userId = userId,
            deviceId = deviceId,
            ipAddress = "10.0.0.1",
            createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            status = SecurityAuditStatus.PENDING
        )
        coEvery { securityAuditDao.getAuditRecordById(alertId) } returns alert
        coEvery { securityAuditDao.updateStatus(alertId, SecurityAuditStatus.DISMISSED, any()) } returns 1

        // When
        val result = authService.resolveSingleAlert(
            userId,
            alertId,
            SecurityAuditStatus.DISMISSED,
            requesterIsRestricted = false
        )

        // Then
        assertTrue(result.isRight())
        coVerify { securityAuditDao.getAuditRecordById(alertId) }
        coVerify(exactly = 0) { userTrustedDeviceDao.insertTrustedDevice(any(), any(), any(), any(), any()) }
        coVerify { securityAuditDao.updateStatus(alertId, SecurityAuditStatus.DISMISSED, any()) }
    }

    @Test
    fun `resolveSingleAlert should return InsufficientPermissions for restricted session`() = runTest {
        // Given
        val userId = testUser.id
        val alertId = 1L

        // When
        val result =
            authService.resolveSingleAlert(userId, alertId, SecurityAuditStatus.TRUSTED, requesterIsRestricted = true)

        // Then
        assertEquals(ResolveAlertError.InsufficientPermissions(), result.leftOrNull())
        coVerify(exactly = 0) { securityAuditDao.getAuditRecordById(any()) }
    }

    @Test
    fun `resolveSingleAlert should return AlertNotFound when alert does not exist`() = runTest {
        // Given
        val userId = testUser.id
        val alertId = 999L
        coEvery { securityAuditDao.getAuditRecordById(alertId) } returns null

        // When
        val result =
            authService.resolveSingleAlert(userId, alertId, SecurityAuditStatus.TRUSTED, requesterIsRestricted = false)

        // Then
        assertTrue(result.isLeft())
        assertEquals(ResolveAlertError.AlertNotFound(alertId), result.leftOrNull())
    }

    @Test
    fun `resolveSingleAlert should return AlertNotFound when alert belongs to different user`() = runTest {
        // Given
        val userId = testUser.id
        val alertId = 1L
        val otherUserAlert = SecurityAuditEntity(
            id = alertId,
            userId = 999L, // Different user
            deviceId = "device-001",
            ipAddress = "10.0.0.1",
            createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            status = SecurityAuditStatus.PENDING
        )
        coEvery { securityAuditDao.getAuditRecordById(alertId) } returns otherUserAlert

        // When
        val result =
            authService.resolveSingleAlert(userId, alertId, SecurityAuditStatus.TRUSTED, requesterIsRestricted = false)

        // Then
        assertEquals(ResolveAlertError.AlertNotFound(alertId), result.leftOrNull())
    }

    @Test
    fun `resolveSingleAlert should not insert device if already trusted`() = runTest {
        // Given
        val userId = testUser.id
        val alertId = 1L
        val deviceId = "device-001"
        val alert = SecurityAuditEntity(
            id = alertId,
            userId = userId,
            deviceId = deviceId,
            ipAddress = "10.0.0.1",
            createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
            status = SecurityAuditStatus.PENDING
        )
        coEvery { securityAuditDao.getAuditRecordById(alertId) } returns alert
        coEvery { userTrustedDeviceDao.getTrustedDevice(userId, deviceId) } returns testTrustedDevice
        coEvery { securityAuditDao.updateStatus(alertId, SecurityAuditStatus.TRUSTED, any()) } returns 1
        coEvery { userSessionDao.unrestrictSessions(userId, deviceId) } returns 0

        // When
        val result =
            authService.resolveSingleAlert(userId, alertId, SecurityAuditStatus.TRUSTED, requesterIsRestricted = false)

        // Then
        assertTrue(result.isRight())
        coVerify { securityAuditDao.getAuditRecordById(alertId) }
        coVerify(exactly = 0) { userTrustedDeviceDao.insertTrustedDevice(any(), any(), any(), any(), any()) }
        coVerify { securityAuditDao.updateStatus(alertId, SecurityAuditStatus.TRUSTED, any()) }
    }
}
