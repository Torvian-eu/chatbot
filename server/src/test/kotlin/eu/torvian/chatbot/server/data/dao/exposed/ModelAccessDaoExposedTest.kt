package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.left
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.ModelAccessDao
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
 * Tests for [ModelAccessDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [ModelAccessDao]:
 * - Getting access groups for a model
 * - Checking if groups have access to a model
 * - Granting access to a model for groups
 * - Revoking access from a model for groups
 * - Getting resources accessible by groups
 * - Handling different access modes (read, write)
 * - Handling error cases (foreign key violations, unique constraint violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class ModelAccessDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var modelAccessDao: ModelAccessDao
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

    private val testGroup1 = UserGroupEntity(id = 1L, name = "Group 1", description = "Test group 1")
    private val testGroup2 = UserGroupEntity(id = 2L, name = "Group 2", description = "Test group 2")
    private val testGroup3 = UserGroupEntity(id = 3L, name = "Group 3", description = "Test group 3")

    private val testModel1 = LLMModel(
        id = 1L,
        name = "gpt-4",
        providerId = 1L,
        active = true,
        displayName = "GPT-4",
        type = LLMModelType.CHAT,
        capabilities = null
    )

    private val testModel2 = LLMModel(
        id = 2L,
        name = "claude-3",
        providerId = 1L,
        active = true,
        displayName = "Claude 3",
        type = LLMModelType.CHAT,
        capabilities = null
    )

    private val testModel3 = LLMModel(
        id = 3L,
        name = "gpt-3.5-turbo",
        providerId = 1L,
        active = true,
        displayName = "GPT-3.5 Turbo",
        type = LLMModelType.CHAT,
        capabilities = null
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        modelAccessDao = container.get()
        testDataManager = container.get()

        // Set up test data with provider and models
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider),
                llmModels = listOf(testModel1, testModel2, testModel3)
            )
        )
        testDataManager.createTables(setOf(Table.USER_GROUPS, Table.LLM_MODEL_ACCESS))

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
        val result = modelAccessDao.getAccessGroups(testModel1.id, "read")

        assertTrue(result.isEmpty(), "Should return empty list when no groups have access")
    }

    @Test
    fun `getAccessGroups should return groups with specific access mode`() = runTest {
        // Grant read access to group 1 and 2
        modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "read")
        modelAccessDao.grantAccess(testModel1.id, testGroup2.id, "read")

        // Grant write access to group 3
        modelAccessDao.grantAccess(testModel1.id, testGroup3.id, "write")

        val readGroups = modelAccessDao.getAccessGroups(testModel1.id, "read")
        val writeGroups = modelAccessDao.getAccessGroups(testModel1.id, "write")

        assertEquals(2, readGroups.size, "Should return 2 groups with read access")
        assertEquals(1, writeGroups.size, "Should return 1 group with write access")

        val readGroupIds = readGroups.map { it.id }.toSet()
        val writeGroupIds = writeGroups.map { it.id }.toSet()

        assertEquals(setOf(testGroup1.id, testGroup2.id), readGroupIds)
        assertEquals(setOf(testGroup3.id), writeGroupIds)
    }

    @Test
    fun `hasAccess should return false when no groups have access`() = runTest {
        val result = modelAccessDao.hasAccess(testModel1.id, listOf(testGroup1.id), "read")

        assertFalse(result, "Should return false when no groups have access")
    }

    @Test
    fun `hasAccess should return false for empty group list`() = runTest {
        val result = modelAccessDao.hasAccess(testModel1.id, emptyList(), "read")

        assertFalse(result, "Should return false for empty group list")
    }

    @Test
    fun `hasAccess should return true when at least one group has access`() = runTest {
        // Grant access to group 1
        modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "read")

        val result1 = modelAccessDao.hasAccess(testModel1.id, listOf(testGroup1.id), "read")
        assertTrue(result1, "Should return true when group has access")

        val result2 = modelAccessDao.hasAccess(testModel1.id, listOf(testGroup1.id, testGroup2.id), "read")
        assertTrue(result2, "Should return true when at least one group has access")
    }

    @Test
    fun `hasAccess should return false when groups have different access mode`() = runTest {
        // Grant write access to group 1
        modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "write")

        val result = modelAccessDao.hasAccess(testModel1.id, listOf(testGroup1.id), "read")

        assertFalse(result, "Should return false when checking for different access mode")
    }

    @Test
    fun `grantAccess should successfully grant access`() = runTest {
        val result = modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "read")

        assertTrue(result.isRight(), "grantAccess should succeed")

        // Verify access was granted
        val hasAccess = modelAccessDao.hasAccess(testModel1.id, listOf(testGroup1.id), "read")
        assertTrue(hasAccess, "Group should have access after granting")
    }

    @Test
    fun `grantAccess should allow same group to have multiple access modes`() = runTest {
        // Grant read access
        val result1 = modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "read")
        assertTrue(result1.isRight(), "grantAccess for read should succeed")

        // Grant write access to same group
        val result2 = modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "write")
        assertTrue(result2.isRight(), "grantAccess for write should succeed")

        // Verify both access modes
        assertTrue(modelAccessDao.hasAccess(testModel1.id, listOf(testGroup1.id), "read"))
        assertTrue(modelAccessDao.hasAccess(testModel1.id, listOf(testGroup1.id), "write"))
    }

    @Test
    fun `grantAccess should return AlreadyGranted when access already exists`() = runTest {
        // Grant access first time
        modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "read")

        // Try to grant same access again
        val result = modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "read")

        assertEquals(GrantAccessError.AlreadyGranted.left(), result)
    }

    @Test
    fun `grantAccess should return ForeignKeyViolation when model does not exist`() = runTest {
        val nonExistentModelId = 999L
        val result = modelAccessDao.grantAccess(nonExistentModelId, testGroup1.id, "read")

        assertEquals(
            GrantAccessError.ForeignKeyViolation(nonExistentModelId.toString(), testGroup1.id).left(),
            result
        )
    }

    @Test
    fun `grantAccess should return ForeignKeyViolation when group does not exist`() = runTest {
        val nonExistentGroupId = 999L
        val result = modelAccessDao.grantAccess(testModel1.id, nonExistentGroupId, "read")

        assertEquals(
            GrantAccessError.ForeignKeyViolation(testModel1.id.toString(), nonExistentGroupId).left(),
            result
        )
    }

    @Test
    fun `revokeAccess should successfully revoke access`() = runTest {
        // Grant access first
        modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "read")

        // Revoke access
        val result = modelAccessDao.revokeAccess(testModel1.id, testGroup1.id, "read")

        assertTrue(result.isRight(), "revokeAccess should succeed")

        // Verify access was revoked
        val hasAccess = modelAccessDao.hasAccess(testModel1.id, listOf(testGroup1.id), "read")
        assertFalse(hasAccess, "Group should not have access after revoking")
    }

    @Test
    fun `revokeAccess should only revoke specific access mode`() = runTest {
        // Grant both read and write access
        modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "read")
        modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "write")

        // Revoke only read access
        val result = modelAccessDao.revokeAccess(testModel1.id, testGroup1.id, "read")

        assertTrue(result.isRight(), "revokeAccess should succeed")

        // Verify only read access was revoked
        assertFalse(modelAccessDao.hasAccess(testModel1.id, listOf(testGroup1.id), "read"))
        assertTrue(modelAccessDao.hasAccess(testModel1.id, listOf(testGroup1.id), "write"))
    }

    @Test
    fun `revokeAccess should return AccessNotGranted when access does not exist`() = runTest {
        val result = modelAccessDao.revokeAccess(testModel1.id, testGroup1.id, "read")

        assertEquals(RevokeAccessError.AccessNotGranted.left(), result)
    }

    @Test
    fun `getResourcesAccessibleByGroups should return empty list for empty group list`() = runTest {
        val result = modelAccessDao.getResourcesAccessibleByGroups(emptyList(), "read")

        assertTrue(result.isEmpty(), "Should return empty list for empty group list")
    }

    @Test
    fun `getResourcesAccessibleByGroups should return models accessible by groups`() = runTest {
        // Grant access to different models
        modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "read")
        modelAccessDao.grantAccess(testModel2.id, testGroup1.id, "read")
        modelAccessDao.grantAccess(testModel2.id, testGroup2.id, "read")
        modelAccessDao.grantAccess(testModel3.id, testGroup3.id, "read")

        // Get models accessible by group 1
        val group1Models = modelAccessDao.getResourcesAccessibleByGroups(listOf(testGroup1.id), "read")
        assertEquals(2, group1Models.size)
        assertEquals(setOf(testModel1.id, testModel2.id), group1Models.toSet())

        // Get models accessible by groups 1 and 2
        val group1And2Models = modelAccessDao.getResourcesAccessibleByGroups(
            listOf(testGroup1.id, testGroup2.id), "read"
        )
        assertEquals(2, group1And2Models.size)
        assertEquals(setOf(testModel1.id, testModel2.id), group1And2Models.toSet())

        // Get models accessible by all groups
        val allGroupsModels = modelAccessDao.getResourcesAccessibleByGroups(
            listOf(testGroup1.id, testGroup2.id, testGroup3.id), "read"
        )
        assertEquals(3, allGroupsModels.size)
        assertEquals(setOf(testModel1.id, testModel2.id, testModel3.id), allGroupsModels.toSet())
    }

    @Test
    fun `getResourcesAccessibleByGroups should filter by access mode`() = runTest {
        // Grant read access to model 1 and write access to model 2
        modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "read")
        modelAccessDao.grantAccess(testModel2.id, testGroup1.id, "write")

        val readModels = modelAccessDao.getResourcesAccessibleByGroups(listOf(testGroup1.id), "read")
        val writeModels = modelAccessDao.getResourcesAccessibleByGroups(listOf(testGroup1.id), "write")

        assertEquals(listOf(testModel1.id), readModels)
        assertEquals(listOf(testModel2.id), writeModels)
    }

    @Test
    fun `multiple groups can have access to same model with different modes`() = runTest {
        // Grant different access modes to different groups for same model
        modelAccessDao.grantAccess(testModel1.id, testGroup1.id, "read")
        modelAccessDao.grantAccess(testModel1.id, testGroup2.id, "write")
        modelAccessDao.grantAccess(testModel1.id, testGroup3.id, "read")

        val readGroups = modelAccessDao.getAccessGroups(testModel1.id, "read")
        val writeGroups = modelAccessDao.getAccessGroups(testModel1.id, "write")

        assertEquals(2, readGroups.size)
        assertEquals(1, writeGroups.size)

        assertTrue(modelAccessDao.hasAccess(testModel1.id, listOf(testGroup1.id), "read"))
        assertTrue(modelAccessDao.hasAccess(testModel1.id, listOf(testGroup2.id), "write"))
        assertTrue(modelAccessDao.hasAccess(testModel1.id, listOf(testGroup3.id), "read"))
    }
}
