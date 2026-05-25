package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.UserDao
import eu.torvian.chatbot.server.data.dao.UserDeviceDao
import eu.torvian.chatbot.server.data.dao.UserPreferenceDao
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
import kotlin.test.assertTrue

/**
 * Verifies that the preference DAO reads and writes both global and device-scoped rows.
 */
class UserPreferenceDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var testDataManager: TestDataManager
    private lateinit var userDao: UserDao
    private lateinit var userDeviceDao: UserDeviceDao
    private lateinit var userPreferenceDao: UserPreferenceDao

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        testDataManager = container.get()
        userDao = container.get()
        userDeviceDao = container.get()
        userPreferenceDao = container.get()

        testDataManager.createTables(setOf(Table.USERS, Table.USER_DEVICES, Table.USER_PREFERENCES))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getPreferencesForUser returns global and device rows`() = runTest {
        val user = insertUser()
        val device = userDeviceDao.insertDevice(user.id, "device-001", "Laptop")

        userPreferenceDao.upsertPreference(user.id, null, null, "theme", "light")
        userPreferenceDao.upsertPreference(user.id, device.id, "device-001", "theme", "dark")

        val rows = userPreferenceDao.getPreferencesForUser(user.id, device.id)

        assertEquals(2, rows.size)
        val globalRow = rows.single { it.scopeId == "GLOBAL" }
        val deviceRow = rows.single { it.scopeId == "device-001" }
        assertEquals("theme", globalRow.prefKey)
        assertEquals("light", globalRow.prefValue)
        assertEquals("theme", deviceRow.prefKey)
        assertEquals("dark", deviceRow.prefValue)
    }

    @Test
    fun `upsertPreference updates the existing row instead of duplicating it`() = runTest {
        val user = insertUser()

        userPreferenceDao.upsertPreference(user.id, null, null, "theme", "light")
        userPreferenceDao.upsertPreference(user.id, null, null, "theme", "dark")

        val rows = userPreferenceDao.getPreferencesForUser(user.id, null)
        assertEquals(1, rows.size)
        assertEquals("dark", rows.single().prefValue)
    }

    @Test
    fun `upsertPreference updates device-scoped preference instead of duplicating`() = runTest {
        val user = insertUser()
        val deviceA = userDeviceDao.insertDevice(user.id, "device-001", "Laptop")

        userPreferenceDao.upsertPreference(user.id, deviceA.id, "device-001", "theme", "light")
        userPreferenceDao.upsertPreference(user.id, deviceA.id, "device-001", "theme", "dark")

        val rows = userPreferenceDao.getPreferencesForUser(user.id, deviceA.id)
        assertEquals(1, rows.size)
        val deviceRow = rows.single { it.scopeId == "device-001" }
        assertEquals("dark", deviceRow.prefValue)
    }

    @Test
    fun `deletePreference removes the specific scoped row`() = runTest {
        val user = insertUser()
        val device = userDeviceDao.insertDevice(user.id, "device-001", "Laptop")

        // Insert both global and device-scoped preferences with the same key
        userPreferenceDao.upsertPreference(user.id, null, null, "theme", "light")
        userPreferenceDao.upsertPreference(user.id, device.id, "device-001", "theme", "dark")

        // Verify both exist
        var rows = userPreferenceDao.getPreferencesForUser(user.id, device.id)
        assertEquals(2, rows.size)

        // Delete the device-scoped preference
        userPreferenceDao.deletePreference(user.id, device.id, "theme")

        // Verify only the global preference remains
        rows = userPreferenceDao.getPreferencesForUser(user.id, device.id)
        assertEquals(1, rows.size)
        val globalRow = rows.single { it.scopeId == "GLOBAL" }
        assertEquals("theme", globalRow.prefKey)
        assertEquals("light", globalRow.prefValue)
    }

    private suspend fun insertUser(): UserEntity {
        val user = userDao.insertUser(
            username = "pref-user",
            passwordHash = "hashed-password",
            email = "pref-user@example.com",
            status = UserStatus.ACTIVE
        ).getOrNull()
        assertNotNull(user)
        assertTrue(user.id > 0)
        return user
    }
}
