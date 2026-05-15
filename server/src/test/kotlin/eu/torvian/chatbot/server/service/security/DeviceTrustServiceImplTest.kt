package eu.torvian.chatbot.server.service.security

import arrow.core.right
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.entities.DeviceVerificationTokenEntity
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.data.entities.UserTrustedDeviceEntity
import eu.torvian.chatbot.server.service.security.error.RequestDeviceVerificationError
import eu.torvian.chatbot.server.service.security.error.RevokeTrustedDeviceError
import eu.torvian.chatbot.server.service.security.error.VerifyDeviceError
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class DeviceTrustServiceImplTest {

    private val userDao = mockk<UserDao>()
    private val userTrustedDeviceDao = mockk<UserTrustedDeviceDao>()
    private val userSessionDao = mockk<UserSessionDao>()
    private val securityAuditDao = mockk<SecurityAuditDao>()
    private val deviceVerificationTokenDao = mockk<DeviceVerificationTokenDao>()
    private val securityNotificationService = mockk<SecurityNotificationService>()
    private val transactionScope = mockk<TransactionScope>()

    private val deviceTrustService = DeviceTrustServiceImpl(
        userDao = userDao,
        userTrustedDeviceDao = userTrustedDeviceDao,
        userSessionDao = userSessionDao,
        securityAuditDao = securityAuditDao,
        deviceVerificationTokenDao = deviceVerificationTokenDao,
        securityNotificationService = securityNotificationService,
        transactionScope = transactionScope
    )

    private val testUser = UserEntity(
        id = 1L,
        username = "testuser",
        passwordHash = "hashedpassword",
        email = "test@example.com",
        status = eu.torvian.chatbot.common.models.user.UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        updatedAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
        lastLogin = null
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
            userDao,
            userTrustedDeviceDao,
            userSessionDao,
            securityAuditDao,
            deviceVerificationTokenDao,
            securityNotificationService,
            transactionScope
        )
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @Test
    fun `getTrustedDevices should return devices for non-restricted session`() = runTest {
        // Given
        val userId = testUser.id
        val devices = listOf(testTrustedDevice)
        coEvery { userTrustedDeviceDao.getTrustedDevices(userId) } returns devices

        // When
        val result = deviceTrustService.getTrustedDevices(userId, requesterIsRestricted = false)

        // Then
        assertTrue(result.isRight())
        val deviceInfos = result.getOrNull()!!
        assertEquals(1, deviceInfos.size)
        assertEquals(testTrustedDevice.deviceId, deviceInfos[0].deviceId)
        coVerify { userTrustedDeviceDao.getTrustedDevices(userId) }
    }

    @Test
    fun `getTrustedDevices should return InsufficientPermissions for restricted session`() = runTest {
        // Given
        val userId = testUser.id

        // When
        val result = deviceTrustService.getTrustedDevices(userId, requesterIsRestricted = true)

        // Then
        assertTrue(result.isLeft())
        assertEquals(RevokeTrustedDeviceError.InsufficientPermissions(), result.leftOrNull())
        coVerify(exactly = 0) { userTrustedDeviceDao.getTrustedDevices(any()) }
    }

    @Test
    fun `revokeTrustedDevice should successfully delete device`() = runTest {
        // Given
        val userId = testUser.id
        val deviceId = "device-001"
        coEvery { userTrustedDeviceDao.deleteTrustedDevice(userId, deviceId) } returns 1

        // When
        val result = deviceTrustService.revokeTrustedDevice(userId, deviceId, requesterIsRestricted = false)

        // Then
        assertTrue(result.isRight())
        coVerify { userTrustedDeviceDao.deleteTrustedDevice(userId, deviceId) }
    }

    @Test
    fun `revokeTrustedDevice should return DeviceNotFound when device does not exist`() = runTest {
        // Given
        val userId = testUser.id
        val deviceId = "nonexistent-device"
        coEvery { userTrustedDeviceDao.deleteTrustedDevice(userId, deviceId) } returns 0

        // When
        val result = deviceTrustService.revokeTrustedDevice(userId, deviceId, requesterIsRestricted = false)

        // Then
        assertTrue(result.isLeft())
        assertEquals(RevokeTrustedDeviceError.DeviceNotFound(deviceId), result.leftOrNull())
    }

    @Test
    fun `revokeTrustedDevice should return InsufficientPermissions for restricted session`() = runTest {
        // Given
        val userId = testUser.id
        val deviceId = "device-001"

        // When
        val result = deviceTrustService.revokeTrustedDevice(userId, deviceId, requesterIsRestricted = true)

        // Then
        assertEquals(RevokeTrustedDeviceError.InsufficientPermissions(), result.leftOrNull())
        coVerify(exactly = 0) { userTrustedDeviceDao.deleteTrustedDevice(any(), any()) }
    }

    @Test
    fun `requestDeviceVerificationEmail should successfully send verification email`() = runTest {
        // Given
        val userId = testUser.id
        val deviceId = "device-001"
        val tokenEntity = DeviceVerificationTokenEntity(
            id = 1L,
            userId = userId,
            deviceId = deviceId,
            token = "test-token",
            expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 3600000),
            createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )
        coEvery { userDao.getUserById(userId) } returns testUser.right()
        coEvery { deviceVerificationTokenDao.getLastTokenCreatedAt(userId, deviceId) } returns null
        coEvery { securityNotificationService.sendDeviceVerification(any(), any()) } returns Unit.right()
        coEvery { deviceVerificationTokenDao.createToken(any(), any(), any(), any(), any()) } returns tokenEntity

        // When
        val result = deviceTrustService.requestDeviceVerificationEmail(userId, deviceId)

        // Then
        assertTrue(result.isRight())
        coVerify { userDao.getUserById(userId) }
        coVerify { securityNotificationService.sendDeviceVerification(testUser.email!!, any()) }
        coVerify { deviceVerificationTokenDao.createToken(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `requestDeviceVerificationEmail should return UserHasNoEmail when user has no email`() = runTest {
        // Given
        val userWithoutEmail = testUser.copy(email = null)
        coEvery { userDao.getUserById(testUser.id) } returns userWithoutEmail.right()

        // When
        val result = deviceTrustService.requestDeviceVerificationEmail(testUser.id, "device-001")

        // Then
        assertEquals(RequestDeviceVerificationError.UserHasNoEmail, result.leftOrNull())
    }

    @Test
    fun `requestDeviceVerificationEmail should return RateLimitExceeded when rate limited`() = runTest {
        // Given
        val userId = testUser.id
        val deviceId = "device-001"
        val rateLimitMillis = 60 * 60 * 1000L
        val lastTokenTime = System.currentTimeMillis() - (rateLimitMillis / 2) // 30 min ago
        coEvery { userDao.getUserById(userId) } returns testUser.right()
        coEvery { deviceVerificationTokenDao.getLastTokenCreatedAt(userId, deviceId) } returns lastTokenTime

        // When
        val result = deviceTrustService.requestDeviceVerificationEmail(userId, deviceId)

        // Then
        assertTrue(result.isLeft())
        val error = result.leftOrNull() as RequestDeviceVerificationError.RateLimitExceeded
        assertTrue(error.retryAfterMillis > 0)
    }

    @Test
    fun `verifyDeviceByToken should successfully verify device`() = runTest {
        // Given
        val token = "test-token-123"
        val tokenEntity = DeviceVerificationTokenEntity(
            id = 1L,
            userId = testUser.id,
            deviceId = "device-001",
            token = token,
            expiresAt = Instant.fromEpochMilliseconds(System.currentTimeMillis() + 3600000),
            createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        )
        coEvery { deviceVerificationTokenDao.findToken(token) } returns tokenEntity
        coEvery { userTrustedDeviceDao.getTrustedDevice(testUser.id, "device-001") } returns null
        coEvery {
            userTrustedDeviceDao.insertTrustedDevice(
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } returns testTrustedDevice
        coEvery { userSessionDao.unrestrictSessions(testUser.id, "device-001") } returns 1
        coEvery { securityAuditDao.getUnacknowledgedByUserIdAndDeviceId(testUser.id, "device-001") } returns emptyList()
        coEvery { deviceVerificationTokenDao.deleteToken(token) } returns 1

        // When
        val result = deviceTrustService.verifyDeviceByToken(token)

        // Then
        assertTrue(result.isRight())
        coVerify { userTrustedDeviceDao.insertTrustedDevice(testUser.id, "device-001", null, any(), any()) }
        coVerify { userSessionDao.unrestrictSessions(testUser.id, "device-001") }
        coVerify { deviceVerificationTokenDao.deleteToken(token) }
    }

    @Test
    fun `verifyDeviceByToken should return InvalidOrExpiredToken when token not found`() = runTest {
        // Given
        coEvery { deviceVerificationTokenDao.findToken("invalid-token") } returns null

        // When
        val result = deviceTrustService.verifyDeviceByToken("invalid-token")

        // Then
        assertEquals(VerifyDeviceError.InvalidOrExpiredToken, result.leftOrNull())
    }
}
