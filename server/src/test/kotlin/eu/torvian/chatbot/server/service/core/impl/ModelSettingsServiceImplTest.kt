package eu.torvian.chatbot.server.service.core.impl

import arrow.core.left
import arrow.core.right
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.error.SettingsError
import eu.torvian.chatbot.server.service.core.error.settings.*
import eu.torvian.chatbot.server.utils.transactions.TransactionScope
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

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
    private lateinit var transactionScope: TransactionScope

    // Class under test
    private lateinit var modelSettingsService: ModelSettingsServiceImpl

    // Test data
    private val testSettings1 = ModelSettings(
        id = 1L,
        name = "Default",
        modelId = 1L,
        systemMessage = "You are a helpful assistant.",
        temperature = 0.7f,
        maxTokens = 1000,
        customParamsJson = null
    )

    private val testSettings2 = ModelSettings(
        id = 2L,
        name = "Creative",
        modelId = 1L,
        systemMessage = "You are a creative writing assistant.",
        temperature = 1.2f,
        maxTokens = 2000,
        customParamsJson = """{"top_p": 0.9}"""
    )

    @BeforeEach
    fun setUp() {
        // Create mocks for all dependencies
        settingsDao = mockk()
        transactionScope = mockk()

        // Create the service instance with mocked dependencies
        modelSettingsService = ModelSettingsServiceImpl(settingsDao, transactionScope)

        // Mock the transaction scope to execute blocks directly
        coEvery { transactionScope.transaction(any<suspend () -> Any>()) } coAnswers {
            val block = firstArg<suspend () -> Any>()
            block()
        }
    }

    @AfterEach
    fun tearDown() {
        // Clear all mocks after each test to ensure isolation
        clearMocks(settingsDao, transactionScope)
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
        val name = "Default"
        val modelId = 1L
        val systemMessage = "You are a helpful assistant."
        val temperature = 0.7f
        val maxTokens = 1000
        val customParamsJson: String? = null
        
        coEvery { settingsDao.insertSettings(name, modelId, systemMessage, temperature, maxTokens, customParamsJson) } returns testSettings1.right()

        // Act
        val result = modelSettingsService.addSettings(name, modelId, systemMessage, temperature, maxTokens, customParamsJson)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful creation")
        assertEquals(testSettings1, result.getOrNull(), "Should return the created settings")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.insertSettings(name, modelId, systemMessage, temperature, maxTokens, customParamsJson) }
    }

    @Test
    fun `addSettings should return InvalidInput error for blank name`() = runTest {
        // Arrange
        val blankName = "   "
        val modelId = 1L

        // Act
        val result = modelSettingsService.addSettings(blankName, modelId, null, null, null, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is AddSettingsError.InvalidInput, "Should be InvalidInput error")
        assertEquals("Settings name cannot be blank.", (error as AddSettingsError.InvalidInput).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { settingsDao.insertSettings(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addSettings should return InvalidInput error for invalid temperature below range`() = runTest {
        // Arrange
        val name = "Test Settings"
        val modelId = 1L
        val invalidTemperature = -0.1f

        // Act
        val result = modelSettingsService.addSettings(name, modelId, null, invalidTemperature, null, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for invalid temperature")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is AddSettingsError.InvalidInput, "Should be InvalidInput error")
        assertEquals("Temperature must be between 0.0 and 2.0", (error as AddSettingsError.InvalidInput).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { settingsDao.insertSettings(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addSettings should return InvalidInput error for invalid temperature above range`() = runTest {
        // Arrange
        val name = "Test Settings"
        val modelId = 1L
        val invalidTemperature = 2.1f

        // Act
        val result = modelSettingsService.addSettings(name, modelId, null, invalidTemperature, null, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for invalid temperature")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is AddSettingsError.InvalidInput, "Should be InvalidInput error")
        assertEquals("Temperature must be between 0.0 and 2.0", (error as AddSettingsError.InvalidInput).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { settingsDao.insertSettings(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addSettings should return InvalidInput error for invalid maxTokens`() = runTest {
        // Arrange
        val name = "Test Settings"
        val modelId = 1L
        val invalidMaxTokens = 0

        // Act
        val result = modelSettingsService.addSettings(name, modelId, null, null, invalidMaxTokens, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for invalid maxTokens")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is AddSettingsError.InvalidInput, "Should be InvalidInput error")
        assertEquals("Max tokens must be positive", (error as AddSettingsError.InvalidInput).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { settingsDao.insertSettings(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `addSettings should return ModelNotFound error when model does not exist`() = runTest {
        // Arrange
        val name = "Test Settings"
        val modelId = 999L
        val daoError = SettingsError.ModelNotFound(modelId)
        coEvery { settingsDao.insertSettings(name, modelId, null, null, null, null) } returns daoError.left()

        // Act
        val result = modelSettingsService.addSettings(name, modelId, null, null, null, null)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is AddSettingsError.ModelNotFound, "Should be ModelNotFound error")
        assertEquals(modelId, (error as AddSettingsError.ModelNotFound).modelId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.insertSettings(name, modelId, null, null, null, null) }
    }

    // --- updateSettings Tests ---

    @Test
    fun `updateSettings should update settings successfully`() = runTest {
        // Arrange
        val updatedSettings = testSettings1.copy(name = "Updated Default")
        coEvery { settingsDao.updateSettings(updatedSettings) } returns Unit.right()

        // Act
        val result = modelSettingsService.updateSettings(updatedSettings)

        // Assert
        assertTrue(result.isRight(), "Should return Right for successful update")
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.updateSettings(updatedSettings) }
    }

    @Test
    fun `updateSettings should return InvalidInput error for blank name`() = runTest {
        // Arrange
        val settingsWithBlankName = testSettings1.copy(name = "  ")

        // Act
        val result = modelSettingsService.updateSettings(settingsWithBlankName)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for blank name")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSettingsError.InvalidInput, "Should be InvalidInput error")
        assertEquals("Settings name cannot be blank.", (error as UpdateSettingsError.InvalidInput).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { settingsDao.updateSettings(any()) }
    }

    @Test
    fun `updateSettings should return InvalidInput error for invalid temperature`() = runTest {
        // Arrange
        val settingsWithInvalidTemperature = testSettings1.copy(temperature = 3.0f)

        // Act
        val result = modelSettingsService.updateSettings(settingsWithInvalidTemperature)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for invalid temperature")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSettingsError.InvalidInput, "Should be InvalidInput error")
        assertEquals("Temperature must be between 0.0 and 2.0", (error as UpdateSettingsError.InvalidInput).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { settingsDao.updateSettings(any()) }
    }

    @Test
    fun `updateSettings should return InvalidInput error for invalid maxTokens`() = runTest {
        // Arrange
        val settingsWithInvalidMaxTokens = testSettings1.copy(maxTokens = -1)

        // Act
        val result = modelSettingsService.updateSettings(settingsWithInvalidMaxTokens)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for invalid maxTokens")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSettingsError.InvalidInput, "Should be InvalidInput error")
        assertEquals("Max tokens must be positive", (error as UpdateSettingsError.InvalidInput).reason)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 0) { settingsDao.updateSettings(any()) }
    }

    @Test
    fun `updateSettings should return SettingsNotFound error when settings do not exist`() = runTest {
        // Arrange
        val settingsId = 999L
        val updatedSettings = testSettings1.copy(id = settingsId)
        val daoError = SettingsError.SettingsNotFound(settingsId)
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
        coVerify(exactly = 1) { settingsDao.updateSettings(updatedSettings) }
    }

    @Test
    fun `updateSettings should return ModelNotFound error when model does not exist`() = runTest {
        // Arrange
        val modelId = 999L
        val updatedSettings = testSettings1.copy(modelId = modelId)
        val daoError = SettingsError.ModelNotFound(modelId)
        coEvery { settingsDao.updateSettings(updatedSettings) } returns daoError.left()

        // Act
        val result = modelSettingsService.updateSettings(updatedSettings)

        // Assert
        assertTrue(result.isLeft(), "Should return Left for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Error should not be null")
        assertTrue(error is UpdateSettingsError.ModelNotFound, "Should be ModelNotFound error")
        assertEquals(modelId, (error as UpdateSettingsError.ModelNotFound).modelId)
        coVerify(exactly = 1) { transactionScope.transaction(any<suspend () -> Any>()) }
        coVerify(exactly = 1) { settingsDao.updateSettings(updatedSettings) }
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
