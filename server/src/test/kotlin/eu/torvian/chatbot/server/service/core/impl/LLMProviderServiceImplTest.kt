package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.api.llm.DiscoveredProviderModel
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.*
import eu.torvian.chatbot.server.data.dao.error.LLMProviderError
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.service.core.error.provider.*
import eu.torvian.chatbot.server.service.llm.LLMApiClient
import eu.torvian.chatbot.server.service.llm.ModelDiscoveryError
import eu.torvian.chatbot.server.service.llm.ModelDiscoveryResult
import eu.torvian.chatbot.server.service.security.CredentialManager
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [LLMProviderServiceImpl].
 *
 * This test suite verifies that [LLMProviderServiceImpl] correctly orchestrates
 * calls to the underlying DAOs and handles business logic validation.
 * All dependencies ([LLMProviderDao], [ModelDao], [CredentialManager], [TransactionScope]) are mocked using MockK.
 */
class LLMProviderServiceImplTest {

    // Mocked dependencies
    private lateinit var llmProviderDao: LLMProviderDao
    private lateinit var providerOwnershipDao: ProviderOwnershipDao
    private lateinit var providerAccessDao: ProviderAccessDao
    private lateinit var modelDao: ModelDao
    private lateinit var userGroupDao: UserGroupDao
    private lateinit var userGroupService: UserGroupService
    private lateinit var userService: UserService
    private lateinit var llmApiClient: LLMApiClient
    private lateinit var credentialManager: CredentialManager
    private lateinit var transactionScope: TransactionScope

    // Class under test
    private lateinit var llmProviderService: LLMProviderServiceImpl

    // Test data
    private val testProvider1 = LLMProvider(
        id = 1L,
        apiKeyId = "test-key-id-1",
        name = "OpenAI",
        description = "OpenAI Provider",
        baseUrl = "https://api.openai.com/v1",
        type = LLMProviderType.OPENAI
    )

    private val testProvider2 = LLMProvider(
        id = 2L,
        apiKeyId = null,
        name = "Ollama",
        description = "Local Ollama Provider",
        baseUrl = "http://localhost:11434",
        type = LLMProviderType.OLLAMA
    )

    private val testModel1 = LLMModel(
        id = 1L,
        name = "gpt-3.5-turbo",
        providerId = 1L,
        active = true,
        displayName = "GPT-3.5 Turbo",
        type = LLMModelType.CHAT
    )

    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        llmProviderDao = mockk()
        providerOwnershipDao = mockk()
        modelDao = mockk()
        credentialManager = mockk()
        transactionScope = mockk()
        providerAccessDao = mockk()
        userGroupDao = mockk()
        userGroupService = mockk()
        userService = mockk()
        llmApiClient = mockk()

        // Create the service instance with mocked dependencies
        llmProviderService = LLMProviderServiceImpl(
            llmProviderDao,
            providerOwnershipDao,
            providerAccessDao,
            modelDao,
            userGroupService,
            userService,
            llmApiClient,
            credentialManager,
            transactionScope,
        )

        // Mock the transaction scope to execute blocks directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        // Mock the new DAO methods to return empty results by default
        coEvery { userGroupDao.getGroupsForUser(any()) } returns emptyList()
        coEvery { providerAccessDao.getResourcesAccessibleByGroups(any(), any()) } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(llmProviderDao, modelDao, llmApiClient, credentialManager, transactionScope)
    }

    // --- getAllProviders Tests ---

    @Test
    fun `getAllProviders should return list of providers from DAO`() = runTest {
        // Arrange
        val expectedProviders = listOf(testProvider1, testProvider2)
        coEvery { llmProviderDao.getAllProviders() } returns expectedProviders

        // Act
        val result = llmProviderService.getAllProviders()

        // Assert
        assertEquals(expectedProviders, result, "Should return the providers from DAO")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmProviderDao.getAllProviders() }
    }

    @Test
    fun `getAllProviders should return empty list when no providers exist`() = runTest {
        // Arrange
        coEvery { llmProviderDao.getAllProviders() } returns emptyList()

        // Act
        val result = llmProviderService.getAllProviders()

        // Assert
        assertTrue(result.isEmpty(), "Should return empty list when no providers exist")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmProviderDao.getAllProviders() }
    }

    // --- getProviderById Tests ---

    @Test
    fun `getProviderById should return provider when it exists`() = runTest {
        // Arrange
        val providerId = 1L
        coEvery { llmProviderDao.getProviderById(providerId) } returns testProvider1.right()

        // Act
        val result = llmProviderService.getProviderById(providerId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for existing provider")
        assertEquals(testProvider1, result.getOrNull(), "Should return the correct provider")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmProviderDao.getProviderById(providerId) }
    }

    @Test
    fun `getProviderById should return ProviderNotFound error when provider does not exist`() = runTest {
        // Arrange
        val providerId = 999L
        val daoError = LLMProviderError.LLMProviderNotFound(providerId)
        coEvery { llmProviderDao.getProviderById(providerId) } returns daoError.left()

        // Act
        val result = llmProviderService.getProviderById(providerId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent provider")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<GetProviderError.ProviderNotFound>(error, "Should be ProviderNotFound error")
        assertEquals(providerId, error.id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmProviderDao.getProviderById(providerId) }
    }

    @Test
    fun `discoverProviderModels should return mapped discovered models`() = runTest {
        // Arrange
        val providerId = testProvider1.id
        val apiKey = "decrypted-key"
        val discovered = ModelDiscoveryResult(
            models = listOf(
                ModelDiscoveryResult.DiscoveredModel(
                    id = "gpt-4o",
                    displayName = "GPT-4o",
                    metadata = mapOf("owned_by" to "openai")
                )
            )
        )

        coEvery { llmProviderDao.getProviderById(providerId) } returns testProvider1.right()
        coEvery { credentialManager.getCredential(testProvider1.apiKeyId!!) } returns apiKey.right()
        coEvery { llmApiClient.discoverModels(testProvider1, apiKey) } returns discovered.right()

        // Act
        val result = llmProviderService.discoverProviderModels(providerId)

        // Assert
        assertTrue(result.isRight())
        assertEquals(
            listOf(
                DiscoveredProviderModel(
                    id = "gpt-4o",
                    displayName = "GPT-4o",
                    metadata = mapOf("owned_by" to "openai")
                )
            ),
            result.getOrNull()
        )
        coVerify(exactly = 1) { llmProviderDao.getProviderById(providerId) }
        coVerify(exactly = 1) { credentialManager.getCredential(testProvider1.apiKeyId!!) }
        coVerify(exactly = 1) { llmApiClient.discoverModels(testProvider1, apiKey) }
    }

    @Test
    fun `discoverProviderModels should return ProviderNotFound when provider does not exist`() = runTest {
        // Arrange
        val providerId = 999L
        coEvery { llmProviderDao.getProviderById(providerId) } returns LLMProviderError.LLMProviderNotFound(providerId).left()

        // Act
        val result = llmProviderService.discoverProviderModels(providerId)

        // Assert
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertIs<DiscoverProviderModelsError.ProviderNotFound>(error)
        assertEquals(providerId, error.id)
        coVerify(exactly = 1) { llmProviderDao.getProviderById(providerId) }
        coVerify(exactly = 0) { llmApiClient.discoverModels(any(), any()) }
    }

    @Test
    fun `testProviderConnection should return discovered models on success`() = runTest {
        // Arrange
        val discovered = ModelDiscoveryResult(
            models = listOf(
                ModelDiscoveryResult.DiscoveredModel(
                    id = "gpt-4.1",
                    displayName = "GPT 4.1",
                    metadata = mapOf("owned_by" to "openai")
                )
            )
        )
        coEvery { llmApiClient.discoverModels(any(), "test-credential") } returns discovered.right()

        // Act
        val result = llmProviderService.testProviderConnection(
            baseUrl = "https://api.openai.com/v1",
            type = LLMProviderType.OPENAI,
            credential = "test-credential"
        )

        // Assert
        assertTrue(result.isRight())
        assertEquals(
            listOf(
                DiscoveredProviderModel(
                    id = "gpt-4.1",
                    displayName = "GPT 4.1",
                    metadata = mapOf("owned_by" to "openai")
                )
            ),
            result.getOrNull()
        )
        coVerify(exactly = 1) {
            llmApiClient.discoverModels(
                match {
                    it.baseUrl == "https://api.openai.com/v1" &&
                        it.type == LLMProviderType.OPENAI &&
                        it.apiKeyId == "test-credential"
                },
                "test-credential"
            )
        }
    }

    @Test
    fun `testProviderConnection should return InvalidInput for blank base URL`() = runTest {
        // Act
        val result = llmProviderService.testProviderConnection(
            baseUrl = "   ",
            type = LLMProviderType.OPENAI,
            credential = null
        )

        // Assert
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertIs<TestProviderConnectionError.InvalidInput>(error)
        assertEquals("Provider base URL cannot be blank.", error.reason)
        coVerify(exactly = 0) { llmApiClient.discoverModels(any(), any()) }
    }

    @Test
    fun `testProviderConnection should map authentication failures`() = runTest {
        // Arrange
        coEvery {
            llmApiClient.discoverModels(any(), any())
        } returns ModelDiscoveryError.AuthenticationError("bad credentials").left()

        // Act
        val result = llmProviderService.testProviderConnection(
            baseUrl = "https://api.openai.com/v1",
            type = LLMProviderType.OPENAI,
            credential = "bad"
        )

        // Assert
        assertTrue(result.isLeft())
        val error = result.leftOrNull()
        assertIs<TestProviderConnectionError.AuthenticationFailed>(error)
        assertEquals("bad credentials", error.reason)
    }

    // --- addProvider Tests ---

    @Test
    fun `addProvider should create provider successfully with credential`() = runTest {
        // Arrange
        val name = "OpenAI"
        val description = "OpenAI Provider"
        val baseUrl = "https://api.openai.com/v1"
        val type = LLMProviderType.OPENAI
        val credential = "test-api-key"
        val credentialAlias = "test-key-id"

        coEvery { credentialManager.storeCredential(credential) } returns credentialAlias
        coEvery {
            llmProviderDao.insertProvider(
                credentialAlias,
                name,
                description,
                baseUrl,
                type
            )
        } returns testProvider1.right()
        coEvery { providerOwnershipDao.setOwner(testProvider1.id, 1L) } returns Unit.right()

        // Act
        val result = llmProviderService.addProvider(1L, name, description, baseUrl, type, credential)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful creation")
        assertEquals(testProvider1, result.getOrNull(), "Should return the created provider")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { credentialManager.storeCredential(credential) }
        coVerify(exactly = 1) { llmProviderDao.insertProvider(credentialAlias, name, description, baseUrl, type) }
        coVerify(exactly = 1) { providerOwnershipDao.setOwner(any(), 1L) }
    }

    @Test
    fun `addProvider should create provider successfully without credential`() = runTest {
        // Arrange
        val name = "Ollama"
        val description = "Local Ollama Provider"
        val baseUrl = "http://localhost:11434"
        val type = LLMProviderType.OLLAMA

        coEvery { llmProviderDao.insertProvider(null, name, description, baseUrl, type) } returns testProvider2.right()
        coEvery { providerOwnershipDao.setOwner(testProvider2.id, 1L) } returns Unit.right()

        // Act
        val result = llmProviderService.addProvider(1L, name, description, baseUrl, type, null)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful creation")
        assertEquals(testProvider2, result.getOrNull(), "Should return the created provider")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { credentialManager.storeCredential(any()) }
        coVerify(exactly = 1) { llmProviderDao.insertProvider(null, name, description, baseUrl, type) }
        coVerify(exactly = 1) { providerOwnershipDao.setOwner(testProvider2.id, 1L) }
    }

    @Test
    fun `addProvider should return InvalidInput error for blank name`() = runTest {
        // Arrange
        val blankName = "   "
        val description = "Test Description"
        val baseUrl = "https://api.test.com"
        val type = LLMProviderType.OPENAI

        // Act
        val result = llmProviderService.addProvider(1L, blankName, description, baseUrl, type, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<AddProviderError.InvalidInput>(error, "Should be InvalidInput error")
        assertEquals("Provider name cannot be blank.", error.reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { credentialManager.storeCredential(any()) }
        coVerify(exactly = 0) { llmProviderDao.insertProvider(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addProvider should return InvalidInput error for blank baseUrl`() = runTest {
        // Arrange
        val name = "Test Provider"
        val description = "Test Description"
        val blankBaseUrl = "   "
        val type = LLMProviderType.OPENAI

        // Act
        val result = llmProviderService.addProvider(1L, name, description, blankBaseUrl, type, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank baseUrl")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<AddProviderError.InvalidInput>(error, "Should be InvalidInput error")
        assertEquals("Provider base URL cannot be blank.", error.reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { credentialManager.storeCredential(any()) }
        coVerify(exactly = 0) { llmProviderDao.insertProvider(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addProvider should return InvalidInput error for blank credential when provided`() = runTest {
        // Arrange
        val name = "Test Provider"
        val description = "Test Description"
        val baseUrl = "https://api.test.com"
        val type = LLMProviderType.OPENAI
        val blankCredential = "   "

        // Act
        val result = llmProviderService.addProvider(1L, name, description, baseUrl, type, blankCredential)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank credential")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<AddProviderError.InvalidInput>(error, "Should be InvalidInput error")
        assertEquals(
            "Provider credential cannot be blank when provided.",
            error.reason
        )
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { credentialManager.storeCredential(any()) }
        coVerify(exactly = 0) { llmProviderDao.insertProvider(any(), any(), any(), any(), any()) }
    }

    // --- updateProvider Tests ---

    @Test
    fun `updateProvider should update provider successfully`() = runTest {
        // Arrange
        val updatedProvider = testProvider1.copy(name = "Updated OpenAI")
        coEvery { llmProviderDao.updateProvider(updatedProvider) } returns Unit.right()

        // Act
        val result = llmProviderService.updateProvider(updatedProvider)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmProviderDao.updateProvider(updatedProvider) }
    }

    @Test
    fun `updateProvider should return InvalidInput error for blank name`() = runTest {
        // Arrange
        val providerWithBlankName = testProvider1.copy(name = "  ")

        // Act
        val result = llmProviderService.updateProvider(providerWithBlankName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateProviderError.InvalidInput>(error, "Should be InvalidInput error")
        assertEquals("Provider name cannot be blank.", error.reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { llmProviderDao.updateProvider(any()) }
    }

    @Test
    fun `updateProvider should return InvalidInput error for blank baseUrl`() = runTest {
        // Arrange
        val providerWithBlankBaseUrl = testProvider1.copy(baseUrl = "  ")

        // Act
        val result = llmProviderService.updateProvider(providerWithBlankBaseUrl)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank baseUrl")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateProviderError.InvalidInput>(error, "Should be InvalidInput error")
        assertEquals("Provider base URL cannot be blank.", error.reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { llmProviderDao.updateProvider(any()) }
    }

    @Test
    fun `updateProvider should return ProviderNotFound error when provider does not exist`() = runTest {
        // Arrange
        val providerId = 999L
        val updatedProvider = testProvider1.copy(id = providerId)
        val daoError = LLMProviderError.LLMProviderNotFound(providerId)
        coEvery { llmProviderDao.updateProvider(updatedProvider) } returns daoError.left()

        // Act
        val result = llmProviderService.updateProvider(updatedProvider)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent provider")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateProviderError.ProviderNotFound>(error, "Should be ProviderNotFound error")
        assertEquals(providerId, error.id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmProviderDao.updateProvider(updatedProvider) }
    }

    @Test
    fun `updateProvider should return ApiKeyAlreadyInUse error when API key is in use`() = runTest {
        // Arrange
        val apiKeyId = "duplicate-key-id"
        val updatedProvider = testProvider1.copy(apiKeyId = apiKeyId)
        val daoError = LLMProviderError.ApiKeyAlreadyInUse(apiKeyId)
        coEvery { llmProviderDao.updateProvider(updatedProvider) } returns daoError.left()

        // Act
        val result = llmProviderService.updateProvider(updatedProvider)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for API key already in use")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateProviderError.ApiKeyAlreadyInUse>(error, "Should be ApiKeyAlreadyInUse error")
        assertEquals(apiKeyId, error.apiKeyId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmProviderDao.updateProvider(updatedProvider) }
    }

    // --- deleteProvider Tests ---

    @Test
    fun `deleteProvider should delete provider successfully when not in use`() = runTest {
        // Arrange
        val providerId = 1L
        coEvery { modelDao.getModelsByProviderId(providerId) } returns emptyList()
        coEvery { llmProviderDao.getProviderById(providerId) } returns testProvider1.right()
        coEvery { llmProviderDao.deleteProvider(providerId) } returns Unit.right()
        coEvery { credentialManager.deleteCredential(testProvider1.apiKeyId!!) } returns Unit.right()

        // Act
        val result = llmProviderService.deleteProvider(providerId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelsByProviderId(providerId) }
        coVerify(exactly = 1) { llmProviderDao.getProviderById(providerId) }
        coVerify(exactly = 1) { llmProviderDao.deleteProvider(providerId) }
        coVerify(exactly = 1) { credentialManager.deleteCredential(testProvider1.apiKeyId!!) }
    }

    @Test
    fun `deleteProvider should delete provider successfully without credential cleanup when no API key`() = runTest {
        // Arrange
        val providerId = 2L
        coEvery { modelDao.getModelsByProviderId(providerId) } returns emptyList()
        coEvery { llmProviderDao.getProviderById(providerId) } returns testProvider2.right()
        coEvery { llmProviderDao.deleteProvider(providerId) } returns Unit.right()

        // Act
        val result = llmProviderService.deleteProvider(providerId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelsByProviderId(providerId) }
        coVerify(exactly = 1) { llmProviderDao.getProviderById(providerId) }
        coVerify(exactly = 1) { llmProviderDao.deleteProvider(providerId) }
        coVerify(exactly = 0) { credentialManager.deleteCredential(any()) }
    }

    @Test
    fun `deleteProvider should return ProviderInUse error when provider is still in use`() = runTest {
        // Arrange
        val providerId = 1L
        val modelsUsingProvider = listOf(testModel1)
        coEvery { modelDao.getModelsByProviderId(providerId) } returns modelsUsingProvider

        // Act
        val result = llmProviderService.deleteProvider(providerId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left when provider is in use")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<DeleteProviderError.ProviderInUse>(error, "Should be ProviderInUse error")
        assertEquals(providerId, error.id)
        assertEquals(listOf(testModel1.name), error.modelNames)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelsByProviderId(providerId) }
        coVerify(exactly = 0) { llmProviderDao.getProviderById(any()) }
        coVerify(exactly = 0) { llmProviderDao.deleteProvider(any()) }
        coVerify(exactly = 0) { credentialManager.deleteCredential(any()) }
    }

    @Test
    fun `deleteProvider should return ProviderNotFound error when provider does not exist`() = runTest {
        // Arrange
        val providerId = 999L
        val daoError = LLMProviderError.LLMProviderNotFound(providerId)
        coEvery { modelDao.getModelsByProviderId(providerId) } returns emptyList()
        coEvery { llmProviderDao.getProviderById(providerId) } returns daoError.left()

        // Act
        val result = llmProviderService.deleteProvider(providerId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent provider")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<DeleteProviderError.ProviderNotFound>(error, "Should be ProviderNotFound error")
        assertEquals(providerId, error.id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelsByProviderId(providerId) }
        coVerify(exactly = 1) { llmProviderDao.getProviderById(providerId) }
        coVerify(exactly = 0) { llmProviderDao.deleteProvider(any()) }
        coVerify(exactly = 0) { credentialManager.deleteCredential(any()) }
    }

    // --- updateProviderCredential Tests ---

    @Test
    fun `updateProviderCredential should update credential successfully`() = runTest {
        // Arrange
        val providerId = 1L
        val newCredential = "new-api-key"
        val newAlias = "new-key-id"
        val updatedProvider = testProvider1.copy(apiKeyId = newAlias)

        coEvery { llmProviderDao.getProviderById(providerId) } returns testProvider1.right()
        coEvery { credentialManager.storeCredential(newCredential) } returns newAlias
        coEvery { llmProviderDao.updateProvider(updatedProvider) } returns Unit.right()
        coEvery { credentialManager.deleteCredential(testProvider1.apiKeyId!!) } returns Unit.right()

        // Act
        val result = llmProviderService.updateProviderCredential(providerId, newCredential)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful credential update")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmProviderDao.getProviderById(providerId) }
        coVerify(exactly = 1) { credentialManager.storeCredential(newCredential) }
        coVerify(exactly = 1) { llmProviderDao.updateProvider(updatedProvider) }
        coVerify(exactly = 1) { credentialManager.deleteCredential(testProvider1.apiKeyId!!) }
    }

    @Test
    fun `updateProviderCredential should remove credential when newCredential is null`() = runTest {
        // Arrange
        val providerId = 1L
        val updatedProvider = testProvider1.copy(apiKeyId = null)

        coEvery { llmProviderDao.getProviderById(providerId) } returns testProvider1.right()
        coEvery { llmProviderDao.updateProvider(updatedProvider) } returns Unit.right()
        coEvery { credentialManager.deleteCredential(testProvider1.apiKeyId!!) } returns Unit.right()

        // Act
        val result = llmProviderService.updateProviderCredential(providerId, null)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful credential removal")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmProviderDao.getProviderById(providerId) }
        coVerify(exactly = 0) { credentialManager.storeCredential(any()) }
        coVerify(exactly = 1) { llmProviderDao.updateProvider(updatedProvider) }
        coVerify(exactly = 1) { credentialManager.deleteCredential(testProvider1.apiKeyId!!) }
    }

    @Test
    fun `updateProviderCredential should return InvalidInput error for blank credential`() = runTest {
        // Arrange
        val providerId = 1L
        val blankCredential = "   "

        // Act
        val result = llmProviderService.updateProviderCredential(providerId, blankCredential)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank credential")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateProviderCredentialError.InvalidInput>(error, "Should be InvalidInput error")
        assertEquals(
            "Provider credential cannot be blank.",
            error.reason
        )
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { llmProviderDao.getProviderById(any()) }
        coVerify(exactly = 0) { credentialManager.storeCredential(any()) }
        coVerify(exactly = 0) { llmProviderDao.updateProvider(any()) }
        coVerify(exactly = 0) { credentialManager.deleteCredential(any()) }
    }

    @Test
    fun `updateProviderCredential should return ProviderNotFound error when provider does not exist`() = runTest {
        // Arrange
        val providerId = 999L
        val newCredential = "new-api-key"
        val daoError = LLMProviderError.LLMProviderNotFound(providerId)
        coEvery { llmProviderDao.getProviderById(providerId) } returns daoError.left()

        // Act
        val result = llmProviderService.updateProviderCredential(providerId, newCredential)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent provider")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<UpdateProviderCredentialError.ProviderNotFound>(error, "Should be ProviderNotFound error")
        assertEquals(providerId, error.id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmProviderDao.getProviderById(providerId) }
        coVerify(exactly = 0) { credentialManager.storeCredential(any()) }
        coVerify(exactly = 0) { llmProviderDao.updateProvider(any()) }
        coVerify(exactly = 0) { credentialManager.deleteCredential(any()) }
    }
}
