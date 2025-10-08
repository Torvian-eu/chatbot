package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.SettingsAccessDao
import eu.torvian.chatbot.server.data.dao.SettingsOwnershipDao
import eu.torvian.chatbot.server.data.dao.UserGroupDao
import eu.torvian.chatbot.server.data.dao.error.GetOwnerError
import eu.torvian.chatbot.server.data.dao.error.SettingsError
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.settings.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ModelSettingsServiceImpl].
 *
 * This test suite verifies that [ModelSettingsServiceImpl] correctly orchestrates
 * calls to the underlying DAO and handles business logic validation.
 * All dependencies ([SettingsDao], [TransactionScope]) are mocked using MockK.
 */
class ModelSettingsServiceImplTest {

    // Mocked dependencies
    private lateinit var settingsDao: SettingsDao
    private lateinit var llmModelService: LLMModelService
    private lateinit var transactionScope: TransactionScope
    private lateinit var settingsOwnershipDao: SettingsOwnershipDao
    private lateinit var settingsAccessDao: SettingsAccessDao
    private lateinit var userGroupDao: UserGroupDao

    // Class under test
    private lateinit var modelSettingsService: ModelSettingsServiceImpl

    // Test data
    private val testModel1 = LLMModel(
        id = 1L,
        name = "gpt-3.5-turbo",
        providerId = 1L,
        active = true,
        displayName = "GPT-3.5 Turbo",
        type = LLMModelType.CHAT
    )

    private val testSettings1 = ChatModelSettings(
        id = 1L,
        name = "Default",
        modelId = 1L,
        systemMessage = "You are a helpful assistant.",
        temperature = 0.7f,
        maxTokens = 1000,
        customParams = null
    )

    private val testSettings2 = ChatModelSettings(
        id = 2L,
        name = "Creative",
        modelId = 1L,
        systemMessage = "You are a creative writing assistant.",
        temperature = 1.2f,
        maxTokens = 2000,
        customParams = Json.decodeFromString("""{"top_p": 0.9}""")
    )

    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        settingsDao = mockk()
        llmModelService = mockk()
        transactionScope = mockk()
        settingsOwnershipDao = mockk()
        settingsAccessDao = mockk()
        userGroupDao = mockk()

        // Create the service instance with mocked dependencies
        modelSettingsService = ModelSettingsServiceImpl(
            settingsDao,
            llmModelService,
            transactionScope,
            settingsOwnershipDao
        )

        // Mock the transaction scope to execute blocks directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }

        // Mock the new DAO methods to return empty results by default
        coEvery { userGroupDao.getGroupsForUser(any()) } returns emptyList()
        coEvery { settingsAccessDao.getResourcesAccessibleByGroups(any(), any()) } returns emptyList()
        coEvery { settingsOwnershipDao.getOwner(any()) } returns GetOwnerError.ResourceNotFound("Not found").left()
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(settingsDao, llmModelService, transactionScope)
    }

    // --- getSettingsById Tests ---

    @Test
    fun `getSettingsById should return settings when they exist`() = runTest {
        // Arrange
        val settingsId = 1L
        coEvery { settingsDao.getSettingsById(settingsId) } returns testSettings1.right()

        // Act
        val result = modelSettingsService.getSettingsById(settingsId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for existing settings")
        assertEquals(testSettings1, result.getOrNull(), "Should return the correct settings")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.getSettingsById(settingsId) }
    }

    @Test
    fun `getSettingsById should return SettingsNotFound error when settings do not exist`() = runTest {
        // Arrange
        val settingsId = 999L
        val daoError = SettingsError.SettingsNotFound(settingsId)
        coEvery { settingsDao.getSettingsById(settingsId) } returns daoError.left()

        // Act
        val result = modelSettingsService.getSettingsById(settingsId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent settings")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is GetSettingsByIdError.SettingsNotFound, "Should be SettingsNotFound error")
        assertEquals(settingsId, (error as GetSettingsByIdError.SettingsNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.getSettingsById(settingsId) }
    }

    // --- getAllSettings Tests ---

    @Test
    fun `getAllSettings should return list of settings from DAO`() = runTest {
        // Arrange
        val expectedSettings = listOf(testSettings1, testSettings2)
        coEvery { settingsDao.getAllSettings() } returns expectedSettings

        // Act
        val result = modelSettingsService.getAllSettings()

        // Assert
        assertEquals(expectedSettings, result, "Should return the settings from DAO")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.getAllSettings() }
    }

    @Test
    fun `getAllSettings should return empty list when no settings exist`() = runTest {
        // Arrange
        coEvery { settingsDao.getAllSettings() } returns emptyList()

        // Act
        val result = modelSettingsService.getAllSettings()

        // Assert
        assertTrue(result.isEmpty(), "Should return empty list when no settings exist")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.getAllSettings() }
    }

    // --- getSettingsByModelId Tests ---

    @Test
    fun `getSettingsByModelId should return settings for model`() = runTest {
        // Arrange
        val modelId = 1L
        val expectedSettings = listOf(testSettings1, testSettings2)
        coEvery { settingsDao.getSettingsByModelId(modelId) } returns expectedSettings

        // Act
        val result = modelSettingsService.getSettingsByModelId(modelId)

        // Assert
        assertEquals(expectedSettings, result, "Should return settings for the model")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.getSettingsByModelId(modelId) }
    }

    @Test
    fun `getSettingsByModelId should return empty list when model has no settings`() = runTest {
        // Arrange
        val modelId = 999L
        coEvery { settingsDao.getSettingsByModelId(modelId) } returns emptyList()

        // Act
        val result = modelSettingsService.getSettingsByModelId(modelId)

        // Assert
        assertTrue(result.isEmpty(), "Should return empty list when model has no settings")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.getSettingsByModelId(modelId) }
    }

    // --- addSettings Tests ---

    @Test
    fun `addSettings should create settings successfully with valid parameters`() = runTest {
        // Arrange
        val settings = testSettings1

        coEvery { llmModelService.getModelById(settings.modelId) } returns testModel1.right()
        coEvery { settingsDao.insertSettings(settings) } returns testSettings1.right()
        coEvery { settingsOwnershipDao.setOwner(testSettings1.id, 1L) } returns Unit.right()

        // Act
        val result = modelSettingsService.addSettings(1L, settings)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful creation")
        assertEquals(testSettings1, result.getOrNull(), "Should return the created settings")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmModelService.getModelById(settings.modelId) }
        coVerify(exactly = 1) { settingsDao.insertSettings(settings) }
    }


    @Test
    fun `addSettings should return ModelNotFound error when model does not exist`() = runTest {
        // Arrange
        val modelId = 999L
        val settings = ChatModelSettings(
            id = 0L,
            modelId = modelId,
            name = "Test Settings",
            systemMessage = null,
            temperature = null,
            maxTokens = null,
            customParams = null
        )
        val getModelError = GetModelError.ModelNotFound(modelId)
        coEvery { llmModelService.getModelById(modelId) } returns getModelError.left()

        // Act
        val result = modelSettingsService.addSettings(1L, settings)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is AddSettingsError.ModelNotFound, "Should be ModelNotFound error")
        assertEquals(modelId, (error as AddSettingsError.ModelNotFound).modelId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmModelService.getModelById(modelId) }
        coVerify(exactly = 0) { settingsDao.insertSettings(any()) }
    }

    // --- updateSettings Tests ---

    @Test
    fun `updateSettings should update settings successfully`() = runTest {
        // Arrange
        val updatedSettings = testSettings1.copy(name = "Updated Default")
        coEvery { llmModelService.getModelById(updatedSettings.modelId) } returns testModel1.right()
        coEvery { settingsDao.updateSettings(updatedSettings) } returns Unit.right()

        // Act
        val result = modelSettingsService.updateSettings(updatedSettings)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmModelService.getModelById(updatedSettings.modelId) }
        coVerify(exactly = 1) { settingsDao.updateSettings(updatedSettings) }
    }

    @Test
    fun `updateSettings should return SettingsNotFound error when settings do not exist`() = runTest {
        // Arrange
        val settingsId = 999L
        val updatedSettings = testSettings1.copy(id = settingsId)
        val daoError = SettingsError.SettingsNotFound(settingsId)
        coEvery { llmModelService.getModelById(updatedSettings.modelId) } returns testModel1.right()
        coEvery { settingsDao.updateSettings(updatedSettings) } returns daoError.left()

        // Act
        val result = modelSettingsService.updateSettings(updatedSettings)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent settings")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSettingsError.SettingsNotFound, "Should be SettingsNotFound error")
        assertEquals(settingsId, (error as UpdateSettingsError.SettingsNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmModelService.getModelById(updatedSettings.modelId) }
        coVerify(exactly = 1) { settingsDao.updateSettings(updatedSettings) }
    }

    @Test
    fun `updateSettings should return ModelNotFound error when model does not exist`() = runTest {
        // Arrange
        val modelId = 999L
        val updatedSettings = testSettings1.copy(modelId = modelId)
        val getModelError = GetModelError.ModelNotFound(modelId)
        coEvery { llmModelService.getModelById(modelId) } returns getModelError.left()

        // Act
        val result = modelSettingsService.updateSettings(updatedSettings)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSettingsError.ModelNotFound, "Should be ModelNotFound error")
        assertEquals(modelId, (error as UpdateSettingsError.ModelNotFound).modelId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { llmModelService.getModelById(modelId) }
        coVerify(exactly = 0) { settingsDao.updateSettings(any()) }
    }

    // --- deleteSettings Tests ---

    @Test
    fun `deleteSettings should delete settings successfully`() = runTest {
        // Arrange
        val settingsId = 1L
        coEvery { settingsDao.deleteSettings(settingsId) } returns Unit.right()

        // Act
        val result = modelSettingsService.deleteSettings(settingsId)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful deletion")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.deleteSettings(settingsId) }
    }

    @Test
    fun `deleteSettings should return SettingsNotFound error when settings do not exist`() = runTest {
        // Arrange
        val settingsId = 999L
        val daoError = SettingsError.SettingsNotFound(settingsId)
        coEvery { settingsDao.deleteSettings(settingsId) } returns daoError.left()

        // Act
        val result = modelSettingsService.deleteSettings(settingsId)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent settings")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is DeleteSettingsError.SettingsNotFound, "Should be SettingsNotFound error")
        assertEquals(settingsId, (error as DeleteSettingsError.SettingsNotFound).id)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.deleteSettings(settingsId) }
    }
}
