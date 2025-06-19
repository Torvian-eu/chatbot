package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.common.models.LLMProvider
import eu.torvian.chatbot.common.models.LLMProviderType
import eu.torvian.chatbot.server.data.dao.LLMProviderDao
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.error.InsertModelError
import eu.torvian.chatbot.server.data.dao.error.LLMProviderError
import eu.torvian.chatbot.server.data.dao.error.ModelError
import eu.torvian.chatbot.server.data.dao.error.UpdateModelError as DaoUpdateModelError
import eu.torvian.chatbot.server.service.core.error.model.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Unit tests for [LLMModelServiceImpl].
 *
 * This test suite verifies that [LLMModelServiceImpl] correctly orchestrates
 * calls to the underlying DAOs and handles business logic validation.
 * All dependencies ([ModelDao], [LLMProviderDao], [TransactionScope]) are mocked using MockK.
 */
class LLMModelServiceImplTest {

    // Mocked dependencies
    private lateinit var modelDao: ModelDao
    private lateinit var llmProviderDao: LLMProviderDao
    private lateinit var transactionScope: TransactionScope

    // Class under test
    private lateinit var llmModelService: LLMModelServiceImpl

    // Test data
    private val testModel1 = LLMModel(
        id = 1L,
        name = "gpt-3.5-turbo",
        providerId = 1L,
        active = true,
        displayName = "GPT-3.5 Turbo"
    )

    private val testModel2 = LLMModel(
        id = 2L,
        name = "gpt-4",
        providerId = 1L,
        active = true,
        displayName = "GPT-4"
    )

    private val testProvider = LLMProvider(
        id = 1L,
        apiKeyId = "test-key-id",
        name = "OpenAI",
        description = "OpenAI Provider",
        baseUrl = "https://api.openai.com/v1",
        type = LLMProviderType.OPENAI
    )

    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        modelDao = mockk()
        llmProviderDao = mockk()
        transactionScope = mockk()

        // Create the service instance with mocked dependencies
        llmModelService = LLMModelServiceImpl(modelDao, llmProviderDao, transactionScope)

        // Mock the transaction scope to execute blocks directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(modelDao, llmProviderDao, transactionScope)
    }

    // --- getAllModels Tests ---

    @Test
    fun `getAllModels should return list of models from DAO`() = runTest {
        // Arrange
        val expectedModels = listOf(testModel1, testModel2)
        coEvery { modelDao.getAllModels() } returns expectedModels

        // Act
        val result = llmModelService.getAllModels()

        // Assert
        assertEquals(expectedModels, result, "Should return the models from DAO")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getAllModels() }
    }

    @Test
    fun `getAllModels should return empty list when no models exist`() = runTest {
        // Arrange
        coEvery { modelDao.getAllModels() } returns emptyList()

        // Act
        val result = llmModelService.getAllModels()

        // Assert
        assertTrue(result.isEmpty(), "Should return empty list when no models exist")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getAllModels() }
    }

    // --- getModelById Tests ---

    @Test
    fun `getModelById should return model when it exists`() = runTest {
        // Arrange
        val modelId = 1L
        coEvery { modelDao.getModelById(modelId) } returns testModel1.right()

        // Act
        val result = llmModelService.getModelById(modelId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for existing model")
        assertEquals(testModel1, result.getOrNull(), "Should return the correct model")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelById(modelId) }
    }

    @Test
    fun `getModelById should return ModelNotFound error when model does not exist`() = runTest {
        // Arrange
        val modelId = 999L
        val daoError = ModelError.ModelNotFound(modelId)
        coEvery { modelDao.getModelById(modelId) } returns daoError.left()

        // Act
        val result = llmModelService.getModelById(modelId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is GetModelError.ModelNotFound, "Should be ModelNotFound error")
        assertEquals(modelId, (error as GetModelError.ModelNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelById(modelId) }
    }

    // --- getModelsByProviderId Tests ---

    @Test
    fun `getModelsByProviderId should return models for provider`() = runTest {
        // Arrange
        val providerId = 1L
        val expectedModels = listOf(testModel1, testModel2)
        coEvery { modelDao.getModelsByProviderId(providerId) } returns expectedModels

        // Act
        val result = llmModelService.getModelsByProviderId(providerId)

        // Assert
        assertEquals(expectedModels, result, "Should return models for the provider")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelsByProviderId(providerId) }
    }

    @Test
    fun `getModelsByProviderId should return empty list when provider has no models`() = runTest {
        // Arrange
        val providerId = 999L
        coEvery { modelDao.getModelsByProviderId(providerId) } returns emptyList()

        // Act
        val result = llmModelService.getModelsByProviderId(providerId)

        // Assert
        assertTrue(result.isEmpty(), "Should return empty list when provider has no models")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelsByProviderId(providerId) }
    }

    // --- addModel Tests ---

    @Test
    fun `addModel should create model successfully with valid parameters`() = runTest {
        // Arrange
        val name = "gpt-3.5-turbo"
        val providerId = 1L
        val active = true
        val displayName = "GPT-3.5 Turbo"
        coEvery { modelDao.insertModel(name, providerId, active, displayName) } returns testModel1.right()

        // Act
        val result = llmModelService.addModel(name, providerId, active, displayName)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful creation")
        assertEquals(testModel1, result.getOrNull(), "Should return the created model")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.insertModel(name, providerId, active, displayName) }
    }

    @Test
    fun `addModel should return InvalidInput error for blank name`() = runTest {
        // Arrange
        val blankName = "   "
        val providerId = 1L

        // Act
        val result = llmModelService.addModel(blankName, providerId, true, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is AddModelError.InvalidInput, "Should be InvalidInput error")
        assertEquals("Model name cannot be blank.", (error as AddModelError.InvalidInput).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { modelDao.insertModel(any(), any(), any(), any()) }
    }

    @Test
    fun `addModel should return ProviderNotFound error when provider does not exist`() = runTest {
        // Arrange
        val name = "test-model"
        val providerId = 999L
        val daoError = InsertModelError.ProviderNotFound(providerId)
        coEvery { modelDao.insertModel(name, providerId, true, null) } returns daoError.left()

        // Act
        val result = llmModelService.addModel(name, providerId, true, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent provider")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is AddModelError.ProviderNotFound, "Should be ProviderNotFound error")
        assertEquals(providerId, (error as AddModelError.ProviderNotFound).providerId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.insertModel(name, providerId, true, null) }
    }

    @Test
    fun `addModel should return ModelNameAlreadyExists error when name is duplicate`() = runTest {
        // Arrange
        val name = "existing-model"
        val providerId = 1L
        val daoError = InsertModelError.ModelNameAlreadyExists(name)
        coEvery { modelDao.insertModel(name, providerId, true, null) } returns daoError.left()

        // Act
        val result = llmModelService.addModel(name, providerId, true, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for duplicate name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is AddModelError.ModelNameAlreadyExists, "Should be ModelNameAlreadyExists error")
        assertEquals(name, (error as AddModelError.ModelNameAlreadyExists).name)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.insertModel(name, providerId, true, null) }
    }

    // --- updateModel Tests ---

    @Test
    fun `updateModel should update model successfully`() = runTest {
        // Arrange
        val updatedModel = testModel1.copy(name = "updated-model")
        coEvery { modelDao.updateModel(updatedModel) } returns Unit.right()

        // Act
        val result = llmModelService.updateModel(updatedModel)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.updateModel(updatedModel) }
    }

    @Test
    fun `updateModel should return InvalidInput error for blank name`() = runTest {
        // Arrange
        val modelWithBlankName = testModel1.copy(name = "  ")

        // Act
        val result = llmModelService.updateModel(modelWithBlankName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateModelError.InvalidInput, "Should be InvalidInput error")
        assertEquals("Model name cannot be blank.", (error as UpdateModelError.InvalidInput).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { modelDao.updateModel(any()) }
    }

    @Test
    fun `updateModel should return ModelNotFound error when model does not exist`() = runTest {
        // Arrange
        val modelId = 999L
        val updatedModel = testModel1.copy(id = modelId)
        val daoError = DaoUpdateModelError.ModelNotFound(modelId)
        coEvery { modelDao.updateModel(updatedModel) } returns daoError.left()

        // Act
        val result = llmModelService.updateModel(updatedModel)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateModelError.ModelNotFound, "Should be ModelNotFound error")
        assertEquals(modelId, (error as UpdateModelError.ModelNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.updateModel(updatedModel) }
    }

    // --- deleteModel Tests ---

    @Test
    fun `deleteModel should delete model successfully`() = runTest {
        // Arrange
        val modelId = 1L
        coEvery { modelDao.deleteModel(modelId) } returns Unit.right()

        // Act
        val result = llmModelService.deleteModel(modelId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.deleteModel(modelId) }
    }

    @Test
    fun `deleteModel should return ModelNotFound error when model does not exist`() = runTest {
        // Arrange
        val modelId = 999L
        val daoError = ModelError.ModelNotFound(modelId)
        coEvery { modelDao.deleteModel(modelId) } returns daoError.left()

        // Act
        val result = llmModelService.deleteModel(modelId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is DeleteModelError.ModelNotFound, "Should be ModelNotFound error")
        assertEquals(modelId, (error as DeleteModelError.ModelNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.deleteModel(modelId) }
    }

    // --- isApiKeyConfiguredForModel Tests ---

    @Test
    fun `isApiKeyConfiguredForModel should return true when provider has API key`() = runTest {
        // Arrange
        val modelId = 1L
        coEvery { modelDao.getModelById(modelId) } returns testModel1.right()
        coEvery { llmProviderDao.getProviderById(testModel1.providerId) } returns testProvider.right()

        // Act
        val result = llmModelService.isApiKeyConfiguredForModel(modelId)

        // Assert
        assertTrue(result, "Should return true when provider has API key")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelById(modelId) }
        coVerify(exactly = 1) { llmProviderDao.getProviderById(testModel1.providerId) }
    }

    @Test
    fun `isApiKeyConfiguredForModel should return false when provider has no API key`() = runTest {
        // Arrange
        val modelId = 1L
        val providerWithoutKey = testProvider.copy(apiKeyId = null)
        coEvery { modelDao.getModelById(modelId) } returns testModel1.right()
        coEvery { llmProviderDao.getProviderById(testModel1.providerId) } returns providerWithoutKey.right()

        // Act
        val result = llmModelService.isApiKeyConfiguredForModel(modelId)

        // Assert
        assertFalse(result, "Should return false when provider has no API key")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelById(modelId) }
        coVerify(exactly = 1) { llmProviderDao.getProviderById(testModel1.providerId) }
    }

    @Test
    fun `isApiKeyConfiguredForModel should return false when model does not exist`() = runTest {
        // Arrange
        val modelId = 999L
        val daoError = ModelError.ModelNotFound(modelId)
        coEvery { modelDao.getModelById(modelId) } returns daoError.left()

        // Act
        val result = llmModelService.isApiKeyConfiguredForModel(modelId)

        // Assert
        assertFalse(result, "Should return false when model does not exist")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelById(modelId) }
        coVerify(exactly = 0) { llmProviderDao.getProviderById(any()) }
    }

    @Test
    fun `isApiKeyConfiguredForModel should return false when provider does not exist`() = runTest {
        // Arrange
        val modelId = 1L
        val providerError = LLMProviderError.LLMProviderNotFound(testModel1.providerId)
        coEvery { modelDao.getModelById(modelId) } returns testModel1.right()
        coEvery { llmProviderDao.getProviderById(testModel1.providerId) } returns providerError.left()

        // Act
        val result = llmModelService.isApiKeyConfiguredForModel(modelId)

        // Assert
        assertFalse(result, "Should return false when provider does not exist")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.getModelById(modelId) }
        coVerify(exactly = 1) { llmProviderDao.getProviderById(testModel1.providerId) }
    }
}
