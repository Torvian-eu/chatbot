package eu.torvian.chatbot.server.service.core.impl

import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import eu.torvian.chatbot.common.models.api.me.UserPreferenceDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import eu.torvian.chatbot.server.data.dao.UserDeviceDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
import eu.torvian.chatbot.server.data.entities.UserDeviceEntity
import eu.torvian.chatbot.server.data.entities.UserPreferenceEntity
import eu.torvian.chatbot.server.service.core.error.preferences.PreferenceError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Verifies the preference service merges scopes correctly and enforces service-level validation.
 */
class UserPreferenceServiceImplTest {
    private val userDeviceDao = mockk<UserDeviceDao>()
    private val userPreferenceDao = mockk<UserPreferenceDao>()
    private val transactionScope = mockk<TransactionScope>()

    private val service = UserPreferenceServiceImpl(userDeviceDao, userPreferenceDao, transactionScope)

    @BeforeEach
    fun setUp() {
        coEvery { transactionScope.transaction<Any>(any()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @Test
    fun `getResolvedPreferences lets device values override global values`() = runTest {
        val userId = 42L
        val clientDeviceId = "device-001"
        val device = userDeviceDaoDevice(userId, clientDeviceId)
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())

        coEvery { userDeviceDao.getDeviceByClientId(userId, clientDeviceId) } returns device
        coEvery { userPreferenceDao.getPreferencesForUser(userId, device.id) } returns listOf(
            UserPreferenceEntity(2L, userId, device.id, "device-001", "theme", "dark", now),
            UserPreferenceEntity(1L, userId, null, "GLOBAL", "theme", "light", now),
            UserPreferenceEntity(3L, userId, null, "GLOBAL", "language", "en", now)
        )

        val result = service.getResolvedPreferences(userId, clientDeviceId)

        assertTrue(result.isRight())
        val preferences = result.getOrNull()!!
        assertEquals("dark", preferences["theme"])
        assertEquals("en", preferences["language"])
        coVerify(exactly = 1) { userPreferenceDao.getPreferencesForUser(userId, device.id) }
    }

    @Test
    fun `updatePreference rejects mismatched keys before touching the DAO`() = runTest {
        val request = UserPreferenceDTO(
            key = "sidebar",
            value = "collapsed",
            scope = PreferenceScope.GLOBAL
        )

        val result = service.updatePreference(
            userId = 42L,
            clientDeviceId = null,
            pathKey = "theme",
            request = request
        )

        assertTrue(result.isLeft())
        assertEquals(
            PreferenceError.InvalidInput("Preference key in the body must match the path parameter"),
            result.leftOrNull()
        )
        coVerify(exactly = 0) { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updatePreference returns DeviceNotRegistered when device scope is requested without a registry row`() = runTest {
        val request = UserPreferenceDTO(
            key = "theme",
            value = "dark",
            scope = PreferenceScope.DEVICE
        )

        coEvery { userDeviceDao.getDeviceByClientId(42L, "device-001") } returns null

        val result = service.updatePreference(
            userId = 42L,
            clientDeviceId = "device-001",
            pathKey = "theme",
            request = request
        )

        assertTrue(result.isLeft())
        assertIs<PreferenceError.DeviceNotRegistered>(result.leftOrNull())
        coVerify(exactly = 0) { userPreferenceDao.upsertPreference(any(), any(), any(), any(), any()) }
    }

    private fun userDeviceDaoDevice(userId: Long, clientDeviceId: String): UserDeviceEntity =
        UserDeviceEntity(
            id = 7L,
            userId = userId,
            clientDeviceId = clientDeviceId,
            deviceName = "Laptop",
            createdAt = Instant.fromEpochMilliseconds(1_000L),
            lastUsedAt = Instant.fromEpochMilliseconds(2_000L)
        )
}
