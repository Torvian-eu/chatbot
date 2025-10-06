package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.left
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.ProviderAccessDao
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
 * Tests for [ProviderAccessDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [ProviderAccessDao]:
 * - Getting access groups for a provider
 * - Checking if groups have access to a provider
 * - Granting access to a provider for groups
 * - Revoking access from a provider for groups
 * - Getting resources accessible by groups
 * - Handling different access modes (read, write)
 * - Handling error cases (foreign key violations, unique constraint violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class ProviderAccessDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var providerAccessDao: ProviderAccessDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testGroup1 = UserGroupEntity(id = 1L, name = "Group 1", description = "Test group 1")
    private val testGroup2 = UserGroupEntity(id = 2L, name = "Group 2", description = "Test group 2")
    private val testGroup3 = UserGroupEntity(id = 3L, name = "Group 3", description = "Test group 3")

    private val testProvider1 = LLMProvider(
        id = 1L,
        apiKeyId = "test-key-1",
        name = "Test Provider 1",
        description = "Test provider 1 description",
        baseUrl = "https://api.test1.com",
        type = LLMProviderType.OPENAI
    )

    private val testProvider2 = LLMProvider(
        id = 2L,
        apiKeyId = "test-key-2",
        name = "Test Provider 2",
        description = "Test provider 2 description",
        baseUrl = "https://api.test2.com",
        type = LLMProviderType.ANTHROPIC
    )

    private val testProvider3 = LLMProvider(
        id = 3L,
        apiKeyId = "test-key-3",
        name = "Test Provider 3",
        description = "Test provider 3 description",
        baseUrl = "https://api.test3.com",
        type = LLMProviderType.OPENAI
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        providerAccessDao = container.get()
        testDataManager = container.get()

        // Set up test data with providers
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1, testProvider2, testProvider3)
            )
        )
        testDataManager.createTables(setOf(Table.USER_GROUPS, Table.LLM_PROVIDER_ACCESS))

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
        val result = providerAccessDao.getAccessGroups(testProvider1.id, "read")

        assertTrue(result.isEmpty(), "Should return empty list when no groups have access")
    }

    @Test
    fun `getAccessGroups should return groups with specific access mode`() = runTest {
        // Grant read access to group 1 and 2
        providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "read")
        providerAccessDao.grantAccess(testProvider1.id, testGroup2.id, "read")

        // Grant write access to group 3
        providerAccessDao.grantAccess(testProvider1.id, testGroup3.id, "write")

        val readGroups = providerAccessDao.getAccessGroups(testProvider1.id, "read")
        val writeGroups = providerAccessDao.getAccessGroups(testProvider1.id, "write")

        assertEquals(2, readGroups.size, "Should return 2 groups with read access")
        assertEquals(1, writeGroups.size, "Should return 1 group with write access")

        val readGroupIds = readGroups.map { it.id }.toSet()
        val writeGroupIds = writeGroups.map { it.id }.toSet()

        assertEquals(setOf(testGroup1.id, testGroup2.id), readGroupIds)
        assertEquals(setOf(testGroup3.id), writeGroupIds)
    }

    @Test
    fun `hasAccess should return false when no groups have access`() = runTest {
        val result = providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup1.id), "read")

        assertFalse(result, "Should return false when no groups have access")
    }

    @Test
    fun `hasAccess should return false for empty group list`() = runTest {
        val result = providerAccessDao.hasAccess(testProvider1.id, emptyList(), "read")

        assertFalse(result, "Should return false for empty group list")
    }

    @Test
    fun `hasAccess should return true when at least one group has access`() = runTest {
        // Grant access to group 1
        providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "read")

        val result1 = providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup1.id), "read")
        assertTrue(result1, "Should return true when group has access")

        val result2 = providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup1.id, testGroup2.id), "read")
        assertTrue(result2, "Should return true when at least one group has access")
    }

    @Test
    fun `hasAccess should return false when groups have different access mode`() = runTest {
        // Grant write access to group 1
        providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "write")

        val result = providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup1.id), "read")

        assertFalse(result, "Should return false when checking for different access mode")
    }

    @Test
    fun `grantAccess should successfully grant access`() = runTest {
        val result = providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "read")

        assertTrue(result.isRight(), "grantAccess should succeed")

        // Verify access was granted
        val hasAccess = providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup1.id), "read")
        assertTrue(hasAccess, "Group should have access after granting")
    }

    @Test
    fun `grantAccess should allow same group to have multiple access modes`() = runTest {
        // Grant read access
        val result1 = providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "read")
        assertTrue(result1.isRight(), "grantAccess for read should succeed")

        // Grant write access to same group
        val result2 = providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "write")
        assertTrue(result2.isRight(), "grantAccess for write should succeed")

        // Verify both access modes
        assertTrue(providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup1.id), "read"))
        assertTrue(providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup1.id), "write"))
    }

    @Test
    fun `grantAccess should return AlreadyGranted when access already exists`() = runTest {
        // Grant access first time
        providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "read")

        // Try to grant same access again
        val result = providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "read")

        assertEquals(GrantAccessError.AlreadyGranted.left(), result)
    }

    @Test
    fun `grantAccess should return ForeignKeyViolation when provider does not exist`() = runTest {
        val nonExistentProviderId = 999L
        val result = providerAccessDao.grantAccess(nonExistentProviderId, testGroup1.id, "read")

        assertEquals(
            GrantAccessError.ForeignKeyViolation(nonExistentProviderId.toString(), testGroup1.id).left(),
            result
        )
    }

    @Test
    fun `grantAccess should return ForeignKeyViolation when group does not exist`() = runTest {
        val nonExistentGroupId = 999L
        val result = providerAccessDao.grantAccess(testProvider1.id, nonExistentGroupId, "read")

        assertEquals(
            GrantAccessError.ForeignKeyViolation(testProvider1.id.toString(), nonExistentGroupId).left(),
            result
        )
    }

    @Test
    fun `revokeAccess should successfully revoke access`() = runTest {
        // Grant access first
        providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "read")

        // Revoke access
        val result = providerAccessDao.revokeAccess(testProvider1.id, testGroup1.id, "read")

        assertTrue(result.isRight(), "revokeAccess should succeed")

        // Verify access was revoked
        val hasAccess = providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup1.id), "read")
        assertFalse(hasAccess, "Group should not have access after revoking")
    }

    @Test
    fun `revokeAccess should only revoke specific access mode`() = runTest {
        // Grant both read and write access
        providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "read")
        providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "write")

        // Revoke only read access
        val result = providerAccessDao.revokeAccess(testProvider1.id, testGroup1.id, "read")

        assertTrue(result.isRight(), "revokeAccess should succeed")

        // Verify only read access was revoked
        assertFalse(providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup1.id), "read"))
        assertTrue(providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup1.id), "write"))
    }

    @Test
    fun `revokeAccess should return AccessNotGranted when access does not exist`() = runTest {
        val result = providerAccessDao.revokeAccess(testProvider1.id, testGroup1.id, "read")

        assertEquals(RevokeAccessError.AccessNotGranted.left(), result)
    }

    @Test
    fun `getResourcesAccessibleByGroups should return empty list for empty group list`() = runTest {
        val result = providerAccessDao.getResourcesAccessibleByGroups(emptyList(), "read")

        assertTrue(result.isEmpty(), "Should return empty list for empty group list")
    }

    @Test
    fun `getResourcesAccessibleByGroups should return providers accessible by groups`() = runTest {
        // Grant access to different providers
        providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "read")
        providerAccessDao.grantAccess(testProvider2.id, testGroup1.id, "read")
        providerAccessDao.grantAccess(testProvider2.id, testGroup2.id, "read")
        providerAccessDao.grantAccess(testProvider3.id, testGroup3.id, "read")

        // Get providers accessible by group 1
        val group1Providers = providerAccessDao.getResourcesAccessibleByGroups(listOf(testGroup1.id), "read")
        assertEquals(2, group1Providers.size)
        assertEquals(setOf(testProvider1.id, testProvider2.id), group1Providers.toSet())

        // Get providers accessible by groups 1 and 2
        val group1And2Providers = providerAccessDao.getResourcesAccessibleByGroups(
            listOf(testGroup1.id, testGroup2.id), "read"
        )
        assertEquals(2, group1And2Providers.size)
        assertEquals(setOf(testProvider1.id, testProvider2.id), group1And2Providers.toSet())

        // Get providers accessible by all groups
        val allGroupsProviders = providerAccessDao.getResourcesAccessibleByGroups(
            listOf(testGroup1.id, testGroup2.id, testGroup3.id), "read"
        )
        assertEquals(3, allGroupsProviders.size)
        assertEquals(setOf(testProvider1.id, testProvider2.id, testProvider3.id), allGroupsProviders.toSet())
    }

    @Test
    fun `getResourcesAccessibleByGroups should filter by access mode`() = runTest {
        // Grant read access to provider 1 and write access to provider 2
        providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "read")
        providerAccessDao.grantAccess(testProvider2.id, testGroup1.id, "write")

        val readProviders = providerAccessDao.getResourcesAccessibleByGroups(listOf(testGroup1.id), "read")
        val writeProviders = providerAccessDao.getResourcesAccessibleByGroups(listOf(testGroup1.id), "write")

        assertEquals(listOf(testProvider1.id), readProviders)
        assertEquals(listOf(testProvider2.id), writeProviders)
    }

    @Test
    fun `multiple groups can have access to same provider with different modes`() = runTest {
        // Grant different access modes to different groups for same provider
        providerAccessDao.grantAccess(testProvider1.id, testGroup1.id, "read")
        providerAccessDao.grantAccess(testProvider1.id, testGroup2.id, "write")
        providerAccessDao.grantAccess(testProvider1.id, testGroup3.id, "read")

        val readGroups = providerAccessDao.getAccessGroups(testProvider1.id, "read")
        val writeGroups = providerAccessDao.getAccessGroups(testProvider1.id, "write")

        assertEquals(2, readGroups.size)
        assertEquals(1, writeGroups.size)

        assertTrue(providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup1.id), "read"))
        assertTrue(providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup2.id), "write"))
        assertTrue(providerAccessDao.hasAccess(testProvider1.id, listOf(testGroup3.id), "read"))
    }
}

