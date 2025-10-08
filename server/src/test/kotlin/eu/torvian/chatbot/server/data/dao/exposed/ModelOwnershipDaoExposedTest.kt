package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.ModelOwnershipDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SetOwnerError
import eu.torvian.chatbot.server.data.entities.UserEntity
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [ModelOwnershipDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [ModelOwnershipDao]:
 * - Getting the owner of a model
 * - Setting ownership of a model
 * - Handling error cases (model not found, foreign key violations, unique constraint violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class ModelOwnershipDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var modelOwnershipDao: ModelOwnershipDao
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

        modelOwnershipDao = container.get()
        testDataManager = container.get()

        // Set up test data with provider and models
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider),
                llmModels = listOf(testModel1, testModel2, testModel3)
            )
        )
        testDataManager.createTables(setOf(Table.USERS, Table.LLM_MODEL_OWNERS))

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
    fun `getOwner should return ResourceNotFound when model does not exist`() = runTest {
        val nonExistentModelId = 999L
        val result = modelOwnershipDao.getOwner(nonExistentModelId)

        assertEquals(GetOwnerError.ResourceNotFound(nonExistentModelId.toString()).left(), result)
    }

    @Test
    fun `getOwner should return ResourceNotFound when model exists but has no owner`() = runTest {
        val result = modelOwnershipDao.getOwner(testModel1.id)

        assertEquals(GetOwnerError.ResourceNotFound(testModel1.id.toString()).left(), result)
    }

    @Test
    fun `getOwner should return owner user ID when model has owner`() = runTest {
        // Set ownership
        modelOwnershipDao.setOwner(testModel1.id, testUser1.id)

        val result = modelOwnershipDao.getOwner(testModel1.id)

        assertEquals(testUser1.id.right(), result)
    }

    @Test
    fun `setOwner should successfully create ownership link`() = runTest {
        val result = modelOwnershipDao.setOwner(testModel1.id, testUser1.id)

        assertTrue(result.isRight(), "setOwner should succeed")

        // Verify ownership was set
        val ownerResult = modelOwnershipDao.getOwner(testModel1.id)
        assertEquals(testUser1.id.right(), ownerResult)
    }

    @Test
    fun `setOwner should return AlreadyOwned when trying to set owner for already owned model`() = runTest {
        // Set initial ownership
        modelOwnershipDao.setOwner(testModel1.id, testUser1.id)

        // Try to set ownership again (same user)
        val result1 = modelOwnershipDao.setOwner(testModel1.id, testUser1.id)
        assertEquals(SetOwnerError.AlreadyOwned.left(), result1)

        // Try to set ownership to different user
        val result2 = modelOwnershipDao.setOwner(testModel1.id, testUser2.id)
        assertEquals(SetOwnerError.AlreadyOwned.left(), result2)
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when model does not exist`() = runTest {
        val nonExistentModelId = 999L
        val result = modelOwnershipDao.setOwner(nonExistentModelId, testUser1.id)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(nonExistentModelId.toString(), testUser1.id).left(),
            result
        )
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when user does not exist`() = runTest {
        val nonExistentUserId = 999L
        val result = modelOwnershipDao.setOwner(testModel1.id, nonExistentUserId)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(testModel1.id.toString(), nonExistentUserId).left(),
            result
        )
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when both model and user do not exist`() = runTest {
        val nonExistentModelId = 999L
        val nonExistentUserId = 888L
        val result = modelOwnershipDao.setOwner(nonExistentModelId, nonExistentUserId)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(nonExistentModelId.toString(), nonExistentUserId).left(),
            result
        )
    }

    @Test
    fun `multiple users can own different models`() = runTest {
        // Set different ownership
        modelOwnershipDao.setOwner(testModel1.id, testUser1.id)
        modelOwnershipDao.setOwner(testModel2.id, testUser2.id)
        modelOwnershipDao.setOwner(testModel3.id, testUser1.id)

        // Verify ownership
        assertEquals(testUser1.id.right(), modelOwnershipDao.getOwner(testModel1.id))
        assertEquals(testUser2.id.right(), modelOwnershipDao.getOwner(testModel2.id))
        assertEquals(testUser1.id.right(), modelOwnershipDao.getOwner(testModel3.id))
    }
}
