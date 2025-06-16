package eu.torvian.chatbot.server.data.dao.exposed

import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.data.dao.SettingsDao
import eu.torvian.chatbot.server.data.dao.error.SettingsError
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [SettingsDaoExposed].
 *
 * This test suite verifies the core functionality of the Exposed-based implementation of [SettingsDao]:
 * - Getting settings by ID
 * - Getting all settings
 * - Getting settings by model ID
 * - Inserting new settings
 * - Updating existing settings
 * - Deleting settings
 * - Handling error cases (settings not found, model not found)
 *
 * The tests rely on an in-memory SQLite database managed by [TestDataManager].
 */
class SettingsDaoExposedTest {
    private lateinit var container: DIContainer
    private lateinit var settingsDao: SettingsDao
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testSettings1 = TestDefaults.modelSettings1
    private val testSettings2 = TestDefaults.modelSettings2
    private val testModel1 = TestDefaults.llmModel1
    private val testModel2 = TestDefaults.llmModel2
    private val llmProvider1 = TestDefaults.llmProvider1
    private val llmProvider2 = TestDefaults.llmProvider2

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()

        settingsDao = container.get()
        testDataManager = container.get()

        // Need to create both tables as settings reference the models table via foreign key
        testDataManager.createTables(setOf(Table.LLM_MODELS, Table.LLM_PROVIDERS, Table.MODEL_SETTINGS))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    @Test
    fun `getSettingsById should return settings when they exist`() = runTest {
        // Setup test data - need both model and settings
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1),
                llmProviders = listOf(llmProvider1),
                modelSettings = listOf(testSettings1)
            )
        )

        // Get settings by ID
        val result = settingsDao.getSettingsById(testSettings1.id)

        // Verify
        assertTrue(result.isRight(), "Expected Right result for existing settings")
        val settings = result.getOrNull()
        assertNotNull(settings, "Expected non-null settings")
        assertEquals(testSettings1.id, settings.id, "Expected matching ID")
        assertEquals(testSettings1.modelId, settings.modelId, "Expected matching modelId")
        assertEquals(testSettings1.name, settings.name, "Expected matching name")
        assertEquals(testSettings1.systemMessage, settings.systemMessage, "Expected matching systemMessage")
        assertEquals(testSettings1.temperature, settings.temperature, "Expected matching temperature")
        assertEquals(testSettings1.maxTokens, settings.maxTokens, "Expected matching maxTokens")
        assertEquals(testSettings1.customParamsJson, settings.customParamsJson, "Expected matching customParamsJson")
    }

    @Test
    fun `getSettingsById should return SettingsNotFound when settings do not exist`() = runTest {
        // Get a non-existent settings
        val result = settingsDao.getSettingsById(999)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent settings")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertTrue(error is SettingsError.SettingsNotFound, "Expected SettingsNotFound error")
        assertEquals(999, (error as SettingsError.SettingsNotFound).id, "Expected error with correct ID")
    }

    @Test
    fun `getAllSettings should return empty list when no settings exist`() = runTest {
        // Get all settings when none exist
        val settings = settingsDao.getAllSettings()

        // Verify
        assertTrue(settings.isEmpty(), "Expected empty list when no settings exist")
    }

    @Test
    fun `getAllSettings should return all settings when settings exist`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1, testModel2),
                llmProviders = listOf(llmProvider1, llmProvider2),
                modelSettings = listOf(testSettings1, testSettings2)
            )
        )

        // Get all settings
        val settings = settingsDao.getAllSettings()

        // Verify
        assertEquals(2, settings.size, "Expected 2 settings")
        assertTrue(settings.any { it.id == testSettings1.id }, "Expected to find settings with ID ${testSettings1.id}")
        assertTrue(settings.any { it.id == testSettings2.id }, "Expected to find settings with ID ${testSettings2.id}")

        // Verify settings properties
        val settings1 = settings.find { it.id == testSettings1.id }
        assertNotNull(settings1, "Expected to find settings1")
        assertEquals(testSettings1.modelId, settings1.modelId)
        assertEquals(testSettings1.name, settings1.name)
        assertEquals(testSettings1.systemMessage, settings1.systemMessage)
        assertEquals(testSettings1.temperature, settings1.temperature)
        assertEquals(testSettings1.maxTokens, settings1.maxTokens)
        assertEquals(testSettings1.customParamsJson, settings1.customParamsJson)
    }

    @Test
    fun `getSettingsByModelId should return empty list when no settings exist for model`() = runTest {
        // Setup test data with model but no settings
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1),
                llmProviders = listOf(llmProvider1)
            )
        )

        // Get settings for model
        val settings = settingsDao.getSettingsByModelId(testModel1.id)

        // Verify
        assertTrue(settings.isEmpty(), "Expected empty list when no settings exist for model")
    }

    @Test
    fun `getSettingsByModelId should return settings for specific model`() = runTest {
        // Setup test data with multiple models and settings
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1, testModel2),
                llmProviders = listOf(llmProvider1, llmProvider2),
                modelSettings = listOf(testSettings1, testSettings2)
            )
        )

        // Get settings for model1
        val model1Settings = settingsDao.getSettingsByModelId(testModel1.id)

        // Verify
        assertEquals(1, model1Settings.size, "Expected 1 settings for model1")
        assertEquals(testSettings1.id, model1Settings[0].id, "Expected settings1 for model1")

        // Get settings for model2
        val model2Settings = settingsDao.getSettingsByModelId(testModel2.id)

        // Verify
        assertEquals(1, model2Settings.size, "Expected 1 settings for model2")
        assertEquals(testSettings2.id, model2Settings[0].id, "Expected settings2 for model2")
    }

    @Test
    fun `insertSettings should insert new settings when model exists`() = runTest {
        // Setup test data with model only
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1),
                llmProviders = listOf(llmProvider1)
            )
        )

        // Insert new settings
        val result = settingsDao.insertSettings(
            name = "Test Settings",
            modelId = testModel1.id,
            systemMessage = "Test system message",
            temperature = 0.5f,
            maxTokens = 500,
            customParamsJson = """{"test": "value"}"""
        )

        // Verify
        assertTrue(result.isRight(), "Expected Right result for successful insertion")
        val settings = result.getOrNull()
        assertNotNull(settings, "Expected non-null settings")
        assertEquals("Test Settings", settings.name)
        assertEquals(testModel1.id, settings.modelId)
        assertEquals("Test system message", settings.systemMessage)
        assertEquals(0.5f, settings.temperature)
        assertEquals(500, settings.maxTokens)
        assertEquals("""{"test": "value"}""", settings.customParamsJson)

        // Verify settings were actually inserted in the database
        val retrievedResult = settingsDao.getSettingsById(settings.id)
        assertTrue(retrievedResult.isRight(), "Expected to find the newly inserted settings")
        assertEquals(settings, retrievedResult.getOrNull(), "Expected retrieved settings to match inserted settings")
    }

    @Test
    fun `insertSettings should return ModelNotFound when model does not exist`() = runTest {
        // Try to insert settings for a non-existent model
        val result = settingsDao.insertSettings(
            name = "Test Settings",
            modelId = 999L,
            systemMessage = "Test system message",
            temperature = 0.5f,
            maxTokens = 500,
            customParamsJson = """{"test": "value"}"""
        )

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertTrue(error is SettingsError.ModelNotFound, "Expected ModelNotFound error")
        assertEquals(999L, error.modelId, "Expected error with correct ID")
    }

    @Test
    fun `updateSettings should update existing settings`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1),
                llmProviders = listOf(llmProvider1),
                modelSettings = listOf(testSettings1)
            )
        )

        // Update the settings
        val updatedSettings = testSettings1.copy(
            name = "Updated Settings",
            systemMessage = "Updated system message",
            temperature = 0.9f,
            maxTokens = 1500,
            customParamsJson = """{"updated": "value"}"""
        )

        val result = settingsDao.updateSettings(updatedSettings)

        // Verify update was successful
        assertTrue(result.isRight(), "Expected Right result for successful update")

        // Verify the settings were actually updated
        val retrievedResult = settingsDao.getSettingsById(testSettings1.id)
        assertTrue(retrievedResult.isRight(), "Expected to find the updated settings")
        val retrievedSettings = retrievedResult.getOrNull()
        assertNotNull(retrievedSettings, "Expected non-null settings")
        assertEquals(updatedSettings.name, retrievedSettings.name, "Expected updated name")
        assertEquals(updatedSettings.systemMessage, retrievedSettings.systemMessage, "Expected updated systemMessage")
        assertEquals(updatedSettings.temperature, retrievedSettings.temperature, "Expected updated temperature")
        assertEquals(updatedSettings.maxTokens, retrievedSettings.maxTokens, "Expected updated maxTokens")
        assertEquals(
            updatedSettings.customParamsJson,
            retrievedSettings.customParamsJson,
            "Expected updated customParamsJson"
        )
    }

    @Test
    fun `updateSettings should return SettingsNotFound when settings do not exist`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1),
                llmProviders = listOf(llmProvider1)
            )
        )

        // Try to update non-existent settings
        val nonExistentSettings = ModelSettings(
            id = 999L,
            modelId = testModel1.id,
            name = "Non-existent Settings",
            systemMessage = "Test system message",
            temperature = 0.5f,
            maxTokens = 500,
            customParamsJson = null
        )

        val result = settingsDao.updateSettings(nonExistentSettings)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent settings")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertTrue(error is SettingsError.SettingsNotFound, "Expected SettingsNotFound error")
        assertEquals(999L, (error as SettingsError.SettingsNotFound).id, "Expected error with correct ID")
    }

    @Test
    fun `updateSettings should return ModelNotFound when model does not exist`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1),
                llmProviders = listOf(llmProvider1),
                modelSettings = listOf(testSettings1)
            )
        )

        // Try to update settings with non-existent model
        val settingsWithBadModel = testSettings1.copy(
            modelId = 999L
        )

        val result = settingsDao.updateSettings(settingsWithBadModel)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent model")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertTrue(error is SettingsError.ModelNotFound, "Expected ModelNotFound error")
        assertEquals(999L, error.modelId, "Expected error with correct ID")
    }

    @Test
    fun `deleteSettings should delete existing settings`() = runTest {
        // Setup test data
        testDataManager.setup(
            TestDataSet(
                llmModels = listOf(testModel1),
                llmProviders = listOf(llmProvider1),
                modelSettings = listOf(testSettings1)
            )
        )

        // Delete the settings
        val result = settingsDao.deleteSettings(testSettings1.id)

        // Verify deletion was successful
        assertTrue(result.isRight(), "Expected Right result for successful deletion")

        // Verify the settings were actually deleted
        val retrievedResult = settingsDao.getSettingsById(testSettings1.id)
        assertTrue(retrievedResult.isLeft(), "Expected Left result for deleted settings")
        val error = retrievedResult.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertTrue(error is SettingsError.SettingsNotFound, "Expected SettingsNotFound error")
    }

    @Test
    fun `deleteSettings should return SettingsNotFound when settings do not exist`() = runTest {
        // Try to delete non-existent settings
        val result = settingsDao.deleteSettings(999L)

        // Verify
        assertTrue(result.isLeft(), "Expected Left result for non-existent settings")
        val error = result.leftOrNull()
        assertNotNull(error, "Expected non-null error")
        assertTrue(error is SettingsError.SettingsNotFound, "Expected SettingsNotFound error")
        assertEquals(999L, (error as SettingsError.SettingsNotFound).id, "Expected error with correct ID")
    }
}
