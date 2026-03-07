package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.llm.*
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.SettingsOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [SettingsOwnershipDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [SettingsOwnershipDao]:
 * - Getting the owner of a settings profile
 * - Setting ownership of a settings profile
 * - Handling error cases (settings not found, foreign key violations, unique constraint violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class SettingsOwnershipDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var settingsOwnershipDao: SettingsOwnershipDao
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

    private val testUser1 = UserEntity(
        id = 1L,
        username = "testuser1",
        passwordHash = "hashedpassword1",
        email = "test1@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        updatedAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        lastLogin = null
    )

    private val testUser2 = UserEntity(
        id = 2L,
        username = "testuser2",
        passwordHash = "hashedpassword2",
        email = "test2@example.com",
        status = UserStatus.ACTIVE,
        createdAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        updatedAt = Instant.fromEpochMilliseconds(TestDefaults.DEFAULT_INSTANT_MILLIS),
        lastLogin = null
    )

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

        settingsOwnershipDao = container.get()
        testDataManager = container.get()

        // Set up test data with provider, model, and settings
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider),
                llmModels = listOf(testModel),
                modelSettings = listOf(testSettings1, testSettings2, testSettings3)
            )
        )
        testDataManager.createTables(setOf(Table.USERS, Table.MODEL_SETTINGS_OWNERS))

        // Insert test users
        testDataManager.insertUser(testUser1)
        testDataManager.insertUser(testUser2)
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getOwner should return ResourceNotFound when settings do not exist`() = runTest {
        val nonExistentSettingsId = 999L
        val result = settingsOwnershipDao.getOwner(nonExistentSettingsId)

        assertEquals(GetOwnerError.ResourceNotFound(nonExistentSettingsId.toString()).left(), result)
    }

    @Test
    fun `getOwner should return ResourceNotFound when settings exist but have no owner`() = runTest {
        val result = settingsOwnershipDao.getOwner(testSettings1.id)

        assertEquals(GetOwnerError.ResourceNotFound(testSettings1.id.toString()).left(), result)
    }

    @Test
    fun `getOwner should return owner user ID when settings have owner`() = runTest {
        // Set ownership
        settingsOwnershipDao.setOwner(testSettings1.id, testUser1.id)

        val result = settingsOwnershipDao.getOwner(testSettings1.id)

        assertEquals(testUser1.id.right(), result)
    }

    @Test
    fun `setOwner should successfully create ownership link`() = runTest {
        val result = settingsOwnershipDao.setOwner(testSettings1.id, testUser1.id)

        assertTrue(result.isRight(), "setOwner should succeed")

        // Verify ownership was set
        val ownerResult = settingsOwnershipDao.getOwner(testSettings1.id)
        assertEquals(testUser1.id.right(), ownerResult)
    }

    @Test
    fun `setOwner should return AlreadyOwned when trying to set owner for already owned settings`() = runTest {
        // Set initial ownership
        settingsOwnershipDao.setOwner(testSettings1.id, testUser1.id)

        // Try to set ownership again (same user)
        val result1 = settingsOwnershipDao.setOwner(testSettings1.id, testUser1.id)
        assertEquals(SetOwnerError.AlreadyOwned.left(), result1)

        // Try to set ownership to different user
        val result2 = settingsOwnershipDao.setOwner(testSettings1.id, testUser2.id)
        assertEquals(SetOwnerError.AlreadyOwned.left(), result2)
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when settings do not exist`() = runTest {
        val nonExistentSettingsId = 999L
        val result = settingsOwnershipDao.setOwner(nonExistentSettingsId, testUser1.id)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(nonExistentSettingsId.toString(), testUser1.id).left(),
            result
        )
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when user does not exist`() = runTest {
        val nonExistentUserId = 999L
        val result = settingsOwnershipDao.setOwner(testSettings1.id, nonExistentUserId)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(testSettings1.id.toString(), nonExistentUserId).left(),
            result
        )
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when both settings and user do not exist`() = runTest {
        val nonExistentSettingsId = 999L
        val nonExistentUserId = 888L
        val result = settingsOwnershipDao.setOwner(nonExistentSettingsId, nonExistentUserId)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(nonExistentSettingsId.toString(), nonExistentUserId).left(),
            result
        )
    }

    @Test
    fun `multiple users can own different settings`() = runTest {
        // Set different ownership
        settingsOwnershipDao.setOwner(testSettings1.id, testUser1.id)
        settingsOwnershipDao.setOwner(testSettings2.id, testUser2.id)
        settingsOwnershipDao.setOwner(testSettings3.id, testUser1.id)

        // Verify ownership
        assertEquals(testUser1.id.right(), settingsOwnershipDao.getOwner(testSettings1.id))
        assertEquals(testUser2.id.right(), settingsOwnershipDao.getOwner(testSettings2.id))
        assertEquals(testUser1.id.right(), settingsOwnershipDao.getOwner(testSettings3.id))
    }
}
