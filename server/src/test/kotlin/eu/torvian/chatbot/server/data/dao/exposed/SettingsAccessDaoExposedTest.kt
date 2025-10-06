package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.left
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.server.data.dao.SettingsAccessDao
import eu.torvian.chatbot.server.data.dao.error.GrantAccessError
import eu.torvian.chatbot.server.data.dao.error.RevokeAccessError
import eu.torvian.chatbot.server.data.entities.UserGroupEntity
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [SettingsAccessDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [SettingsAccessDao]:
 * - Getting access groups for settings
 * - Checking if groups have access to settings
 * - Granting access to settings for groups
 * - Revoking access from settings for groups
 * - Getting resources accessible by groups
 * - Handling different access modes (read, write)
 * - Handling error cases (foreign key violations, unique constraint violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class SettingsAccessDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var settingsAccessDao: SettingsAccessDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testProvider = LLMProvider(
        id = 1L,
        apiKeyId = "openai-key",
        name = "openai",
        description = "OpenAI Provider",
        baseUrl = "https://api.openai.com/v1",
        type = LLMProviderType.OPENAI
    )

    private val testModel = LLMModel(
        id = 1L,
        name = "gpt-4",
        providerId = 1L,
        active = true,
        displayName = "GPT-4",
        type = LLMModelType.CHAT,
        capabilities = null
    )

    private val testGroup1 = UserGroupEntity(id = 1L, name = "Group 1", description = "Test group 1")
    private val testGroup2 = UserGroupEntity(id = 2L, name = "Group 2", description = "Test group 2")
    private val testGroup3 = UserGroupEntity(id = 3L, name = "Group 3", description = "Test group 3")

    private val testSettings1 = ChatModelSettings(
        id = 1L,
        modelId = 1L,
        name = "Default Settings",
        systemMessage = "You are a helpful assistant.",
        temperature = 0.7f,
        maxTokens = 1000,
        customParams = null
    )

    private val testSettings2 = ChatModelSettings(
        id = 2L,
        modelId = 1L,
        name = "Creative Settings",
        systemMessage = "Be creative and imaginative.",
        temperature = 0.9f,
        maxTokens = 2000,
        customParams = null
    )

    private val testSettings3 = ChatModelSettings(
        id = 3L,
        modelId = 1L,
        name = "Precise Settings",
        systemMessage = "Be precise and accurate.",
        temperature = 0.3f,
        maxTokens = 500,
        customParams = null
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        settingsAccessDao = container.get()
        testDataManager = container.get()

        // Set up test data with provider, model, and settings
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider),
                llmModels = listOf(testModel),
                modelSettings = listOf(testSettings1, testSettings2, testSettings3)
            )
        )
        testDataManager.createTables(setOf(Table.USER_GROUPS, Table.MODEL_SETTINGS_ACCESS))

        // Insert test groups
        testDataManager.insertUserGroup(testGroup1)
        testDataManager.insertUserGroup(testGroup2)
        testDataManager.insertUserGroup(testGroup3)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getAccessGroups should return empty list when no groups have access`() = runTest {
        val result = settingsAccessDao.getAccessGroups(testSettings1.id, "read")

        assertTrue(result.isEmpty(), "Should return empty list when no groups have access")
    }

    @Test
    fun `getAccessGroups should return groups with specific access mode`() = runTest {
        // Grant read access to group 1 and 2
        settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "read")
        settingsAccessDao.grantAccess(testSettings1.id, testGroup2.id, "read")

        // Grant write access to group 3
        settingsAccessDao.grantAccess(testSettings1.id, testGroup3.id, "write")

        val readGroups = settingsAccessDao.getAccessGroups(testSettings1.id, "read")
        val writeGroups = settingsAccessDao.getAccessGroups(testSettings1.id, "write")

        assertEquals(2, readGroups.size, "Should return 2 groups with read access")
        assertEquals(1, writeGroups.size, "Should return 1 group with write access")

        val readGroupIds = readGroups.map { it.id }.toSet()
        val writeGroupIds = writeGroups.map { it.id }.toSet()

        assertEquals(setOf(testGroup1.id, testGroup2.id), readGroupIds)
        assertEquals(setOf(testGroup3.id), writeGroupIds)
    }

    @Test
    fun `hasAccess should return false when no groups have access`() = runTest {
        val result = settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup1.id), "read")

        assertFalse(result, "Should return false when no groups have access")
    }

    @Test
    fun `hasAccess should return false for empty group list`() = runTest {
        val result = settingsAccessDao.hasAccess(testSettings1.id, emptyList(), "read")

        assertFalse(result, "Should return false for empty group list")
    }

    @Test
    fun `hasAccess should return true when at least one group has access`() = runTest {
        // Grant access to group 1
        settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "read")

        val result1 = settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup1.id), "read")
        assertTrue(result1, "Should return true when group has access")

        val result2 = settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup1.id, testGroup2.id), "read")
        assertTrue(result2, "Should return true when at least one group has access")
    }

    @Test
    fun `hasAccess should return false when groups have different access mode`() = runTest {
        // Grant write access to group 1
        settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "write")

        val result = settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup1.id), "read")

        assertFalse(result, "Should return false when checking for different access mode")
    }

    @Test
    fun `grantAccess should successfully grant access`() = runTest {
        val result = settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "read")

        assertTrue(result.isRight(), "grantAccess should succeed")

        // Verify access was granted
        val hasAccess = settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup1.id), "read")
        assertTrue(hasAccess, "Group should have access after granting")
    }

    @Test
    fun `grantAccess should allow same group to have multiple access modes`() = runTest {
        // Grant read access
        val result1 = settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "read")
        assertTrue(result1.isRight(), "grantAccess for read should succeed")

        // Grant write access to same group
        val result2 = settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "write")
        assertTrue(result2.isRight(), "grantAccess for write should succeed")

        // Verify both access modes
        assertTrue(settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup1.id), "read"))
        assertTrue(settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup1.id), "write"))
    }

    @Test
    fun `grantAccess should return AlreadyGranted when access already exists`() = runTest {
        // Grant access first time
        settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "read")

        // Try to grant same access again
        val result = settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "read")

        assertEquals(GrantAccessError.AlreadyGranted.left(), result)
    }

    @Test
    fun `grantAccess should return ForeignKeyViolation when settings do not exist`() = runTest {
        val nonExistentSettingsId = 999L
        val result = settingsAccessDao.grantAccess(nonExistentSettingsId, testGroup1.id, "read")

        assertEquals(
            GrantAccessError.ForeignKeyViolation(nonExistentSettingsId.toString(), testGroup1.id).left(),
            result
        )
    }

    @Test
    fun `grantAccess should return ForeignKeyViolation when group does not exist`() = runTest {
        val nonExistentGroupId = 999L
        val result = settingsAccessDao.grantAccess(testSettings1.id, nonExistentGroupId, "read")

        assertEquals(
            GrantAccessError.ForeignKeyViolation(testSettings1.id.toString(), nonExistentGroupId).left(),
            result
        )
    }

    @Test
    fun `revokeAccess should successfully revoke access`() = runTest {
        // Grant access first
        settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "read")

        // Revoke access
        val result = settingsAccessDao.revokeAccess(testSettings1.id, testGroup1.id, "read")

        assertTrue(result.isRight(), "revokeAccess should succeed")

        // Verify access was revoked
        val hasAccess = settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup1.id), "read")
        assertFalse(hasAccess, "Group should not have access after revoking")
    }

    @Test
    fun `revokeAccess should only revoke specific access mode`() = runTest {
        // Grant both read and write access
        settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "read")
        settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "write")

        // Revoke only read access
        val result = settingsAccessDao.revokeAccess(testSettings1.id, testGroup1.id, "read")

        assertTrue(result.isRight(), "revokeAccess should succeed")

        // Verify only read access was revoked
        assertFalse(settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup1.id), "read"))
        assertTrue(settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup1.id), "write"))
    }

    @Test
    fun `revokeAccess should return AccessNotGranted when access does not exist`() = runTest {
        val result = settingsAccessDao.revokeAccess(testSettings1.id, testGroup1.id, "read")

        assertEquals(RevokeAccessError.AccessNotGranted.left(), result)
    }

    @Test
    fun `getResourcesAccessibleByGroups should return empty list for empty group list`() = runTest {
        val result = settingsAccessDao.getResourcesAccessibleByGroups(emptyList(), "read")

        assertTrue(result.isEmpty(), "Should return empty list for empty group list")
    }

    @Test
    fun `getResourcesAccessibleByGroups should return settings accessible by groups`() = runTest {
        // Grant access to different settings
        settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "read")
        settingsAccessDao.grantAccess(testSettings2.id, testGroup1.id, "read")
        settingsAccessDao.grantAccess(testSettings2.id, testGroup2.id, "read")
        settingsAccessDao.grantAccess(testSettings3.id, testGroup3.id, "read")

        // Get settings accessible by group 1
        val group1Settings = settingsAccessDao.getResourcesAccessibleByGroups(listOf(testGroup1.id), "read")
        assertEquals(2, group1Settings.size)
        assertEquals(setOf(testSettings1.id, testSettings2.id), group1Settings.toSet())

        // Get settings accessible by groups 1 and 2
        val group1And2Settings = settingsAccessDao.getResourcesAccessibleByGroups(
            listOf(testGroup1.id, testGroup2.id), "read"
        )
        assertEquals(2, group1And2Settings.size)
        assertEquals(setOf(testSettings1.id, testSettings2.id), group1And2Settings.toSet())

        // Get settings accessible by all groups
        val allGroupsSettings = settingsAccessDao.getResourcesAccessibleByGroups(
            listOf(testGroup1.id, testGroup2.id, testGroup3.id), "read"
        )
        assertEquals(3, allGroupsSettings.size)
        assertEquals(setOf(testSettings1.id, testSettings2.id, testSettings3.id), allGroupsSettings.toSet())
    }

    @Test
    fun `getResourcesAccessibleByGroups should filter by access mode`() = runTest {
        // Grant read access to settings 1 and write access to settings 2
        settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "read")
        settingsAccessDao.grantAccess(testSettings2.id, testGroup1.id, "write")

        val readSettings = settingsAccessDao.getResourcesAccessibleByGroups(listOf(testGroup1.id), "read")
        val writeSettings = settingsAccessDao.getResourcesAccessibleByGroups(listOf(testGroup1.id), "write")

        assertEquals(listOf(testSettings1.id), readSettings)
        assertEquals(listOf(testSettings2.id), writeSettings)
    }

    @Test
    fun `multiple groups can have access to same settings with different modes`() = runTest {
        // Grant different access modes to different groups for same settings
        settingsAccessDao.grantAccess(testSettings1.id, testGroup1.id, "read")
        settingsAccessDao.grantAccess(testSettings1.id, testGroup2.id, "write")
        settingsAccessDao.grantAccess(testSettings1.id, testGroup3.id, "read")

        val readGroups = settingsAccessDao.getAccessGroups(testSettings1.id, "read")
        val writeGroups = settingsAccessDao.getAccessGroups(testSettings1.id, "write")

        assertEquals(2, readGroups.size)
        assertEquals(1, writeGroups.size)

        assertTrue(settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup1.id), "read"))
        assertTrue(settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup2.id), "write"))
        assertTrue(settingsAccessDao.hasAccess(testSettings1.id, listOf(testGroup3.id), "read"))
    }
}
