package eu.torvian.chatbot.server.data.dao.exposed

import arrow.core.getOrElse
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.LLMProviderDao
import eu.torvian.chatbot.server.data.dao.error.LLMProviderError
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Test class for [LLMProviderDaoExposed].
 */
class LLMProviderDaoExposedTest {

    private lateinit var container: DIContainer
    private lateinit var llmProviderDao: LLMProviderDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testProvider1 = TestDefaults.llmProvider1
    private val testProvider2 = TestDefaults.llmProvider2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        llmProviderDao = container.get()
        testDataManager = container.get()

        testDataManager.createTables(setOf(Table.LLM_PROVIDERS))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getAllProviders should return empty list when no providers exist`() = runTest {
        val providers = llmProviderDao.getAllProviders()
        assertTrue(providers.isEmpty(), "Expected empty list when no providers exist")
    }

    @Test
    fun `getAllProviders should return all providers when they exist`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1, testProvider2)
            )
        )

        // Get all providers
        val providers = llmProviderDao.getAllProviders()

        // Verify
        assertEquals(2, providers.size, "Expected 2 providers")
        assertTrue(providers.any { it.id == testProvider1.id }, "Expected to find provider1")
        assertTrue(providers.any { it.id == testProvider2.id }, "Expected to find provider2")
    }

    @Test
    fun `getProviderById should return provider when it exists`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1)
            )
        )

        // Get provider by ID
        val result = llmProviderDao.getProviderById(testProvider1.id)

        // Verify
        assertTrue(result.isRight(), "Expected Right result")
        val provider = result.getOrElse { throw AssertionError("Expected provider to be found") }
        assertEquals(testProvider1.id, provider.id)
        assertEquals(testProvider1.name, provider.name)
        assertEquals(testProvider1.description, provider.description)
        assertEquals(testProvider1.baseUrl, provider.baseUrl)
        assertEquals(testProvider1.type, provider.type)
        assertEquals(testProvider1.apiKeyId, provider.apiKeyId)
    }

    @Test
    fun `getProviderById should return LLMProviderNotFound when provider does not exist`() = runTest {
        val nonExistentId = 999L

        // Get provider by non-existent ID
        val result = llmProviderDao.getProviderById(nonExistentId)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result")
        result.fold(
            { error ->
                assertIs<LLMProviderError.LLMProviderNotFound>(error)
                assertEquals(nonExistentId, error.id)
            },
            { throw AssertionError("Expected LLMProviderNotFound error") }
        )
    }

    @Test
    fun `insertProvider should create new provider successfully`() = runTest {
        val apiKeyId = "test-api-key"
        val name = "Test Provider"
        val description = "Test provider description"
        val baseUrl = "https://api.test.com/v1"
        val type = LLMProviderType.CUSTOM

        // Insert provider
        val result = llmProviderDao.insertProvider(apiKeyId, name, description, baseUrl, type)

        // Verify
        assertTrue(result.isRight(), "Expected Right result")
        val provider = result.getOrElse { throw AssertionError("Expected provider to be created") }
        
        assertTrue(provider.id > 0, "Expected generated ID to be positive")
        assertEquals(apiKeyId, provider.apiKeyId)
        assertEquals(name, provider.name)
        assertEquals(description, provider.description)
        assertEquals(baseUrl, provider.baseUrl)
        assertEquals(type, provider.type)

        // Verify provider was actually inserted by retrieving it
        val retrievedResult = llmProviderDao.getProviderById(provider.id)
        assertTrue(retrievedResult.isRight(), "Expected to retrieve inserted provider")
    }

    @Test
    fun `insertProvider should create provider with null apiKeyId for local providers`() = runTest {
        val name = "Ollama Local"
        val description = "Local Ollama instance"
        val baseUrl = "http://localhost:11434"
        val type = LLMProviderType.OLLAMA

        // Insert provider with null apiKeyId
        val result = llmProviderDao.insertProvider(null, name, description, baseUrl, type)

        // Verify
        assertTrue(result.isRight(), "Expected Right result")
        val provider = result.getOrElse { throw AssertionError("Expected provider to be created") }
        
        assertTrue(provider.id > 0, "Expected generated ID to be positive")
        assertEquals(null, provider.apiKeyId)
        assertEquals(name, provider.name)
        assertEquals(description, provider.description)
        assertEquals(baseUrl, provider.baseUrl)
        assertEquals(type, provider.type)
    }

    @Test
    fun `insertProvider should return ApiKeyAlreadyInUse when apiKeyId is already used`() = runTest {
        // Setup existing provider
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1)
            )
        )

        // Try to insert provider with same apiKeyId
        val result = llmProviderDao.insertProvider(
            testProvider1.apiKeyId,
            "Different Name",
            "Different description",
            "https://different.url.com",
            LLMProviderType.CUSTOM
        )

        // Verify
        assertTrue(result.isLeft(), "Expected Left result")
        result.fold(
            { error ->
                assertIs<LLMProviderError.ApiKeyAlreadyInUse>(error)
                assertEquals(testProvider1.apiKeyId, error.apiKeyId)
            },
            { throw AssertionError("Expected ApiKeyAlreadyInUse error") }
        )
    }

    @Test
    fun `updateProvider should update existing provider successfully`() = runTest {
        // Setup existing provider
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1)
            )
        )

        // Create updated provider
        val updatedProvider = testProvider1.copy(
            name = "Updated Name",
            description = "Updated description",
            baseUrl = "https://updated.url.com",
            type = LLMProviderType.CUSTOM,
            apiKeyId = "updated-api-key"
        )

        // Update provider
        val result = llmProviderDao.updateProvider(updatedProvider)

        // Verify
        assertTrue(result.isRight(), "Expected Right result")

        // Verify provider was actually updated by retrieving it
        val retrievedResult = llmProviderDao.getProviderById(testProvider1.id)
        assertTrue(retrievedResult.isRight(), "Expected to retrieve updated provider")
        
        val retrievedProvider = retrievedResult.getOrElse { throw AssertionError("Expected provider to be found") }
        assertEquals(updatedProvider.name, retrievedProvider.name)
        assertEquals(updatedProvider.description, retrievedProvider.description)
        assertEquals(updatedProvider.baseUrl, retrievedProvider.baseUrl)
        assertEquals(updatedProvider.type, retrievedProvider.type)
        assertEquals(updatedProvider.apiKeyId, retrievedProvider.apiKeyId)
    }

    @Test
    fun `updateProvider should return LLMProviderNotFound when provider does not exist`() = runTest {
        val nonExistentProvider = testProvider1.copy(id = 999L)

        // Try to update non-existent provider
        val result = llmProviderDao.updateProvider(nonExistentProvider)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result")
        result.fold(
            { error ->
                assertIs<LLMProviderError.LLMProviderNotFound>(error)
                assertEquals(nonExistentProvider.id, error.id)
            },
            { throw AssertionError("Expected LLMProviderNotFound error") }
        )
    }

    @Test
    fun `updateProvider should return ApiKeyAlreadyInUse when apiKeyId conflicts with another provider`() = runTest {
        // Setup two existing providers
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1, testProvider2)
            )
        )

        // Try to update provider1 with provider2's apiKeyId
        val conflictingProvider = testProvider1.copy(apiKeyId = testProvider2.apiKeyId)
        val result = llmProviderDao.updateProvider(conflictingProvider)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result")
        result.fold(
            { error ->
                assertIs<LLMProviderError.ApiKeyAlreadyInUse>(error)
                assertEquals(testProvider2.apiKeyId, error.apiKeyId)
            },
            { throw AssertionError("Expected ApiKeyAlreadyInUse error") }
        )
    }

    @Test
    fun `deleteProvider should delete existing provider successfully`() = runTest {
        // Setup existing provider
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1)
            )
        )

        // Delete provider
        val result = llmProviderDao.deleteProvider(testProvider1.id)

        // Verify
        assertTrue(result.isRight(), "Expected Right result")

        // Verify provider was actually deleted by trying to retrieve it
        val retrievedResult = llmProviderDao.getProviderById(testProvider1.id)
        assertTrue(retrievedResult.isLeft(), "Expected provider to be deleted")
    }

    @Test
    fun `deleteProvider should return LLMProviderNotFound when provider does not exist`() = runTest {
        val nonExistentId = 999L

        // Try to delete non-existent provider
        val result = llmProviderDao.deleteProvider(nonExistentId)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result")
        result.fold(
            { error ->
                assertIs<LLMProviderError.LLMProviderNotFound>(error)
                assertEquals(nonExistentId, error.id)
            },
            { throw AssertionError("Expected LLMProviderNotFound error") }
        )
    }
}
