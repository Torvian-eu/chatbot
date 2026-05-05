package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import eu.torvian.chatbot.server.data.dao.LLMProviderDao
import eu.torvian.chatbot.server.data.dao.ModelDao
import eu.torvian.chatbot.server.data.dao.ModelOwnershipDao
import eu.torvian.chatbot.server.data.dao.ModelAccessDao
import eu.torvian.chatbot.server.data.dao.UserGroupDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.InsertModelError
import eu.torvian.chatbot.server.data.dao.error.LLMProviderError
import eu.torvian.chatbot.server.data.dao.error.ModelError
import eu.torvian.chatbot.server.service.core.UserGroupService
import eu.torvian.chatbot.server.service.core.UserService
import eu.torvian.chatbot.server.data.dao.error.UpdateModelError as DaoUpdateModelError
import eu.torvian.chatbot.server.service.core.error.model.*
import eu.torvian.chatbot.common.misc.transaction.TransactionScope
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.test.assertIs

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
    private lateinit var modelOwnershipDao: ModelOwnershipDao
    private lateinit var modelAccessDao: ModelAccessDao
    private lateinit var userGroupDao: UserGroupDao
    private lateinit var userGroupService: UserGroupService
    private lateinit var userService: UserService

    // Class under test
    private lateinit var llmModelService: LLMModelServiceImpl

    // Test data
    private val testModel1 = LLMModel(
        id = 1L,
        name = "gpt-3.5-turbo",
        providerId = 1L,
        active = true,
        displayName = "GPT-3.5 Turbo",
        type = LLMModelType.CHAT
    )

    private val testModel2 = LLMModel(
        id = 2L,
        name = "gpt-4",
        providerId = 1L,
        active = true,
        displayName = "GPT-4",
        type = LLMModelType.CHAT
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
        modelOwnershipDao = mockk()
        modelAccessDao = mockk()
        userGroupDao = mockk()
        userGroupService = mockk()
        userService = mockk()

        // Create the service instance with mocked dependencies
        llmModelService = LLMModelServiceImpl(
            modelDao,
            llmProviderDao,
            transactionScope,
            modelOwnershipDao,
            modelAccessDao,
            userGroupService,
            userService
        )

        // Mock the transaction scope to execute blocks directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        // Mock the new DAO methods to return empty results by default
        coEvery { userGroupDao.getGroupsForUser(any()) } returns emptyList()
        coEvery { modelAccessDao.getResourcesAccessibleByGroups(any(), any()) } returns emptyList()
        coEvery { modelOwnershipDao.getOwner(any()) } returns GetOwnerError.ResourceNotFound("Not found").left()
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(modelDao, llmProviderDao, transactionScope, modelOwnershipDao, modelAccessDao, userGroupDao)
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
        assertIs<GetModelError.ModelNotFound>(error, "Should be ModelNotFound error")
        assertEquals(modelId, error.id)
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
        val ownerId = 1L
        val name = "gpt-3.5-turbo"
        val providerId = 1L
        val type = LLMModelType.CHAT
        val active = true
        val displayName = "GPT-3.5 Turbo"
        coEvery { modelDao.insertModel(name, providerId, type, active, displayName, null) } returns testModel1.right()
        coEvery { modelOwnershipDao.setOwner(testModel1.id, ownerId) } returns Unit.right()

        // Act
        val result = llmModelService.addModel(ownerId, name, providerId, type, active, displayName, null)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful creation")
        assertEquals(testModel1, result.getOrNull(), "Should return the created model")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.insertModel(name, providerId, type, active, displayName, null) }
        coVerify(exactly = 1) { modelOwnershipDao.setOwner(testModel1.id, ownerId) }
    }

    @Test
    fun `addModel should return InvalidInput error for blank name`() = runTest {
        // Arrange
        val ownerId = 1L
        val blankName = "   "
        val providerId = 1L
        val type = LLMModelType.CHAT

        // Act
        val result = llmModelService.addModel(ownerId, blankName, providerId, type, true, null, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<AddModelError.InvalidInput>(error, "Should be InvalidInput error")
        assertEquals("Model name cannot be blank.", error.reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { modelDao.insertModel(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { modelOwnershipDao.setOwner(any(), any()) }
    }

    @Test
    fun `addModel should return ProviderNotFound error when provider does not exist`() = runTest {
        // Arrange
        val ownerId = 1L
        val name = "test-model"
        val providerId = 999L
        val type = LLMModelType.CHAT
        val daoError = InsertModelError.ProviderNotFound(providerId)
        coEvery { modelDao.insertModel(name, providerId, type, true, null, null) } returns daoError.left()

        // Act
        val result = llmModelService.addModel(ownerId, name, providerId, type, true, null, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent provider")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<AddModelError.ProviderNotFound>(error, "Should be ProviderNotFound error")
        assertEquals(providerId, error.providerId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.insertModel(name, providerId, type, true, null, null) }
        coVerify(exactly = 0) { modelOwnershipDao.setOwner(any(), any()) }
    }

    @Test
    fun `addModel should return ModelNameAlreadyExists error when name is duplicate`() = runTest {
        // Arrange
        val ownerId = 1L
        val name = "existing-model"
        val providerId = 1L
        val type = LLMModelType.CHAT
        val daoError = InsertModelError.ModelNameAlreadyExists(name)
        coEvery { modelDao.insertModel(name, providerId, type, true, null, null) } returns daoError.left()

        // Act
        val result = llmModelService.addModel(ownerId, name, providerId, type, true, null, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for duplicate name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertIs<AddModelError.ModelNameAlreadyExists>(error, "Should be ModelNameAlreadyExists error")
        assertEquals(name, error.name)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { modelDao.insertModel(name, providerId, type, true, null, null) }
        coVerify(exactly = 0) { modelOwnershipDao.setOwner(any(), any()) }
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
        assertIs<UpdateModelError.InvalidInput>(error, "Should be InvalidInput error")
        assertEquals("Model name cannot be blank.", error.reason)
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
        assertIs<UpdateModelError.ModelNotFound>(error, "Should be ModelNotFound error")
        assertEquals(modelId, error.id)
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
        assertIs<DeleteModelError.ModelNotFound>(error, "Should be ModelNotFound error")
        assertEquals(modelId, error.id)
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
