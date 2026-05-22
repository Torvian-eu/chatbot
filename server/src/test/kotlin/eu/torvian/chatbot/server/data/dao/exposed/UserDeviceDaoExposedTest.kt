package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserDeviceDao
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that the device registry DAO persists and updates client device records.
 */
class UserDeviceDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var testDataManager: TestDataManager
    private lateinit var userDao: UserDao
    private lateinit var userDeviceDao: UserDeviceDao

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        testDataManager = container.get()
        userDao = container.get()
        userDeviceDao = container.get()

        testDataManager.createTables(setOf(Table.USERS, Table.USER_DEVICES))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `insertDevice stores the device and can be looked up by client id`() = runTest {
        val user = insertUser()

        val inserted = userDeviceDao.insertDevice(user.id, "device-001", "Laptop")

        assertEquals(user.id, inserted.userId)
        assertEquals("device-001", inserted.clientDeviceId)
        assertEquals("Laptop", inserted.deviceName)
        assertTrue(inserted.createdAt.toEpochMilliseconds() <= inserted.lastUsedAt.toEpochMilliseconds())

        val fetched = userDeviceDao.getDeviceByClientId(user.id, "device-001")
        assertNotNull(fetched)
        assertEquals(inserted.id, fetched.id)
        assertEquals("Laptop", fetched.deviceName)
    }

    @Test
    fun `insertDevice stores device with null name`() = runTest {
        val user = insertUser()

        val inserted = userDeviceDao.insertDevice(user.id, "device-null", null)

        assertEquals(user.id, inserted.userId)
        assertEquals("device-null", inserted.clientDeviceId)
        assertNull(inserted.deviceName)

        val fetched = userDeviceDao.getDeviceByClientId(user.id, "device-null")
        assertNotNull(fetched)
        assertNull(fetched.deviceName)
    }

    @Test
    fun `updateDeviceUsage updates the last used timestamp`() = runTest {
        val user = insertUser()
        val inserted = userDeviceDao.insertDevice(user.id, "device-002", "Tablet")
        val updatedAt = System.currentTimeMillis() + 10_000

        val updated = userDeviceDao.updateDeviceUsage(inserted.id, updatedAt)

        assertTrue(updated)
        val fetched = userDeviceDao.getDeviceByClientId(user.id, "device-002")
        assertNotNull(fetched)
        assertEquals(updatedAt, fetched.lastUsedAt.toEpochMilliseconds())
    }

    private suspend fun insertUser(): UserEntity {
        val user = userDao.insertUser(
            username = "device-user",
            passwordHash = "hashed-password",
            email = "device-user@example.com",
            status = UserStatus.ACTIVE
        ).getOrNull()
        assertNotNull(user)
        return user
    }
}

