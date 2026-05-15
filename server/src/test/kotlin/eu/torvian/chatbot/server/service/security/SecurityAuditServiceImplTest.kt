package eu.torvian.chatbot.server.service.security

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.security.SecurityAuditStatus
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.entities.*
import eu.torvian.chatbot.server.service.security.error.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.time.Instant

class SecurityAuditServiceImplTest {

    private val securityAuditDao = mockk<SecurityAuditDao>()
    private val userTrustedDeviceDao = mockk<UserTrustedDeviceDao>()
    private val userSessionDao = mockk<UserSessionDao>()
    private val transactionScope = mockk<TransactionScope>()

    private val securityAuditService = SecurityAuditServiceImpl(
        securityAuditDao = securityAuditDao,
        userTrustedDeviceDao = userTrustedDeviceDao,
        userSessionDao = userSessionDao,
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
        clearMocks(securityAuditDao, userTrustedDeviceDao, userSessionDao, transactionScope)
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @Test
    fun `getSecurityAlerts should return unacknowledged alerts`() = runTest {
        // Given
        val userId = testUser.id
        val alerts = listOf(
            SecurityAuditEntity(
                id = 1L,
                userId = userId,
                deviceId = "device-001",
                ipAddress = "192.168.1.1",
                createdAt = Instant.fromEpochMilliseconds(System.currentTimeMillis()),
                status = SecurityAuditStatus.PENDING
            )
        )
        coEvery { securityAuditDao.getUnacknowledgedByUserId(userId) } returns alerts

        // When
        val result = securityAuditService.getSecurityAlerts(userId, requesterIsRestricted = false)

        // Then
        assertTrue(result.isRight())
        assertEquals(alerts, result.getOrNull())
        coVerify { securityAuditDao.getUnacknowledgedByUserId(userId) }
    }

    @Test
    fun `getSecurityAlerts should return InsufficientPermissions if requester is restricted`() = runTest {
        // Given
        val userId = testUser.id

        // When
        val result = securityAuditService.getSecurityAlerts(userId, requesterIsRestricted = true)

        // Then
        assertTrue(result.isLeft())
        assertIs<GetSecurityAlertsError.InsufficientPermissions>(result.leftOrNull())
        coVerify(exactly = 0) { securityAuditDao.getUnacknowledgedByUserId(any()) }
    }

    @Test
    fun `resolveSingleAlert should mark as TRUSTED and add to trusted devices`() = runTest {
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
        coEvery { userTrustedDeviceDao.insertTrustedDevice(userId, deviceId, alert.ipAddress, any(), any()) } returns testTrustedDevice
        coEvery { userSessionDao.unrestrictSessions(userId, deviceId) } returns 2
        coEvery { securityAuditDao.updateStatus(alertId, SecurityAuditStatus.TRUSTED, any()) } returns 1

        // When
        val result = securityAuditService.resolveSingleAlert(userId, alertId, SecurityAuditStatus.TRUSTED, requesterIsRestricted = false)

        // Then
        assertTrue(result.isRight())
        coVerify { securityAuditDao.getAuditRecordById(alertId) }
        coVerify { userTrustedDeviceDao.insertTrustedDevice(userId, deviceId, alert.ipAddress, any(), any()) }
        coVerify { userSessionDao.unrestrictSessions(userId, deviceId) }
        coVerify { securityAuditDao.updateStatus(alertId, SecurityAuditStatus.TRUSTED, any()) }
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
            securityAuditService.resolveSingleAlert(userId, alertId, SecurityAuditStatus.TRUSTED, requesterIsRestricted = false)

        // Then
        assertTrue(result.isRight())
        coVerify { securityAuditDao.getAuditRecordById(alertId) }
        coVerify(exactly = 0) { userTrustedDeviceDao.insertTrustedDevice(any(), any(), any(), any(), any()) }
        coVerify { securityAuditDao.updateStatus(alertId, SecurityAuditStatus.TRUSTED, any()) }
    }

    @Test
    fun `resolveSingleAlert should return InsufficientPermissions for restricted session`() = runTest {
        // Given
        val userId = testUser.id
        val alertId = 1L

        // When
        val result =
            securityAuditService.resolveSingleAlert(userId, alertId, SecurityAuditStatus.TRUSTED, requesterIsRestricted = true)

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
            securityAuditService.resolveSingleAlert(userId, alertId, SecurityAuditStatus.TRUSTED, requesterIsRestricted = false)

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
            securityAuditService.resolveSingleAlert(userId, alertId, SecurityAuditStatus.TRUSTED, requesterIsRestricted = false)

        // Then
        assertEquals(ResolveAlertError.AlertNotFound(alertId), result.leftOrNull())
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
        val result = securityAuditService.resolveSingleAlert(
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
}
