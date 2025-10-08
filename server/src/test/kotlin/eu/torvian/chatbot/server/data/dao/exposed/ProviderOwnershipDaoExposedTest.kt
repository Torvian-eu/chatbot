package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.common.models.user.UserStatus
import eu.torvian.chatbot.server.data.dao.ProviderOwnershipDao
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
 * Tests for [ProviderOwnershipDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [ProviderOwnershipDao]:
 * - Getting the owner of a provider
 * - Setting ownership of a provider
 * - Handling error cases (provider not found, foreign key violations, unique constraint violations)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class ProviderOwnershipDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var providerOwnershipDao: ProviderOwnershipDao
    private lateinit var testDataManager: TestDataManager

    // Test data
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

        providerOwnershipDao = container.get()
        testDataManager = container.get()

        // Set up test data with users and providers
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1, testProvider2, testProvider3)
            )
        )
        testDataManager.createTables(setOf(Table.USERS, Table.LLM_PROVIDER_OWNERS))

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
    fun `getOwner should return ResourceNotFound when provider does not exist`() = runTest {
        val nonExistentProviderId = 999L
        val result = providerOwnershipDao.getOwner(nonExistentProviderId)

        assertEquals(GetOwnerError.ResourceNotFound(nonExistentProviderId.toString()).left(), result)
    }

    @Test
    fun `getOwner should return ResourceNotFound when provider exists but has no owner`() = runTest {
        val result = providerOwnershipDao.getOwner(testProvider1.id)

        assertEquals(GetOwnerError.ResourceNotFound(testProvider1.id.toString()).left(), result)
    }

    @Test
    fun `getOwner should return owner user ID when provider has owner`() = runTest {
        // Set ownership
        providerOwnershipDao.setOwner(testProvider1.id, testUser1.id)

        val result = providerOwnershipDao.getOwner(testProvider1.id)

        assertEquals(testUser1.id.right(), result)
    }

    @Test
    fun `setOwner should successfully create ownership link`() = runTest {
        val result = providerOwnershipDao.setOwner(testProvider1.id, testUser1.id)

        assertTrue(result.isRight(), "setOwner should succeed")

        // Verify ownership was set
        val ownerResult = providerOwnershipDao.getOwner(testProvider1.id)
        assertEquals(testUser1.id.right(), ownerResult)
    }

    @Test
    fun `setOwner should return AlreadyOwned when trying to set owner for already owned provider`() = runTest {
        // Set initial ownership
        providerOwnershipDao.setOwner(testProvider1.id, testUser1.id)

        // Try to set ownership again (same user)
        val result1 = providerOwnershipDao.setOwner(testProvider1.id, testUser1.id)
        assertEquals(SetOwnerError.AlreadyOwned.left(), result1)

        // Try to set ownership to different user
        val result2 = providerOwnershipDao.setOwner(testProvider1.id, testUser2.id)
        assertEquals(SetOwnerError.AlreadyOwned.left(), result2)
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when provider does not exist`() = runTest {
        val nonExistentProviderId = 999L
        val result = providerOwnershipDao.setOwner(nonExistentProviderId, testUser1.id)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(nonExistentProviderId.toString(), testUser1.id).left(),
            result
        )
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when user does not exist`() = runTest {
        val nonExistentUserId = 999L
        val result = providerOwnershipDao.setOwner(testProvider1.id, nonExistentUserId)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(testProvider1.id.toString(), nonExistentUserId).left(),
            result
        )
    }

    @Test
    fun `setOwner should return ForeignKeyViolation when both provider and user do not exist`() = runTest {
        val nonExistentProviderId = 999L
        val nonExistentUserId = 888L
        val result = providerOwnershipDao.setOwner(nonExistentProviderId, nonExistentUserId)

        assertEquals(
            SetOwnerError.ForeignKeyViolation(nonExistentProviderId.toString(), nonExistentUserId).left(),
            result
        )
    }

    @Test
    fun `multiple users can own different providers`() = runTest {
        // Set different ownership
        providerOwnershipDao.setOwner(testProvider1.id, testUser1.id)
        providerOwnershipDao.setOwner(testProvider2.id, testUser2.id)
        providerOwnershipDao.setOwner(testProvider3.id, testUser1.id)

        // Verify ownership
        assertEquals(testUser1.id.right(), providerOwnershipDao.getOwner(testProvider1.id))
        assertEquals(testUser2.id.right(), providerOwnershipDao.getOwner(testProvider2.id))
        assertEquals(testUser1.id.right(), providerOwnershipDao.getOwner(testProvider3.id))
    }
}

