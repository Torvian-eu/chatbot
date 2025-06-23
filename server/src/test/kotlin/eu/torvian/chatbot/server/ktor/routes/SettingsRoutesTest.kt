package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.SettingsResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.ModelSettings
import eu.torvian.chatbot.server.testutils.data.Table
import eu.torvian.chatbot.server.testutils.data.TestDataManager
import eu.torvian.chatbot.server.testutils.data.TestDataSet
import eu.torvian.chatbot.server.testutils.data.TestDefaults
import eu.torvian.chatbot.server.testutils.koin.defaultTestContainer
import eu.torvian.chatbot.server.testutils.ktor.KtorTestApp
import eu.torvian.chatbot.server.testutils.ktor.myTestApplication
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Integration tests for Settings API routes.
 *
 * This test suite verifies the HTTP endpoints for settings management:
 * - GET /api/v1/settings/{settingsId} - Get settings by ID
 * - PUT /api/v1/settings/{settingsId} - Update settings by ID
 * - DELETE /api/v1/settings/{settingsId} - Delete settings by ID
 */
class SettingsRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var settingsTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testSettings1 = ModelSettings(
        id = 1L,
        modelId = 1L,
        name = "Test Settings 1",
        systemMessage = "Test system message",
        temperature = 0.7f,
        maxTokens = 1000,
        customParamsJson = """{"key": "value"}"""
    )

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        settingsTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureSettingsRoutes(this)
            }
        )

        testDataManager = container.get()
        testDataManager.setup(
            dataSet = TestDataSet(
                llmProviders = listOf(TestDefaults.llmProvider1),
                llmModels = listOf(TestDefaults.llmModel1)
            )
        )
        testDataManager.createTables(setOf(Table.MODEL_SETTINGS))
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    // --- GET /api/v1/settings/{settingsId} Tests ---

    @Test
    fun `GET settings by ID should return settings successfully`() = settingsTestApplication {
        // Arrange
        testDataManager.insertModelSettings(testSettings1)

        // Act
        val response = client.get(href(SettingsResource.ById(settingsId = testSettings1.id)))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val settings = response.body<ModelSettings>()
        assertEquals(testSettings1, settings)
    }

    @Test
    fun `GET settings with non-existent ID should return 404`() = settingsTestApplication {
        // Arrange
        val nonExistentId = 999L

        // Act
        val response = client.get(href(SettingsResource.ById(settingsId = nonExistentId)))

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Settings not found", error.message)
        assert(error.details?.containsKey("settingsId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("settingsId"))
    }

    // --- PUT /api/v1/settings/{settingsId} Tests ---

    @Test
    fun `PUT settings should update settings successfully`() = settingsTestApplication {
        // Arrange
        testDataManager.insertModelSettings(testSettings1)
        val updatedSettings = testSettings1.copy(
            name = "Updated Settings",
            systemMessage = "Updated system message",
            temperature = 0.8f,
            maxTokens = 1500
        )

        // Act
        val response = client.put(href(SettingsResource.ById(settingsId = testSettings1.id))) {
            contentType(ContentType.Application.Json)
            setBody(updatedSettings)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the settings were actually updated in the database
        val retrievedSettings = testDataManager.getModelSettings(testSettings1.id)
        assertNotNull(retrievedSettings)
        assertEquals(updatedSettings.name, retrievedSettings.name)
        assertEquals(updatedSettings.systemMessage, retrievedSettings.systemMessage)
        assertEquals(updatedSettings.temperature, retrievedSettings.temperature)
        assertEquals(updatedSettings.maxTokens, retrievedSettings.maxTokens)
    }

    @Test
    fun `PUT settings with non-existent ID should return 404`() = settingsTestApplication {
        // Arrange
        val nonExistentId = 999L
        val updatedSettings = testSettings1.copy(id = nonExistentId)

        // Act
        val response = client.put(href(SettingsResource.ById(settingsId = nonExistentId))) {
            contentType(ContentType.Application.Json)
            setBody(updatedSettings)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Settings not found", error.message)
        assert(error.details?.containsKey("settingsId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("settingsId"))
    }

    @Test
    fun `PUT settings with mismatched ID should return 400`() = settingsTestApplication {
        // Arrange
        testDataManager.insertModelSettings(testSettings1)
        val mismatchedId = 999L
        val updatedSettings = testSettings1.copy(id = mismatchedId)

        // Act
        val response = client.put(href(SettingsResource.ById(settingsId = testSettings1.id))) {
            contentType(ContentType.Application.Json)
            setBody(updatedSettings)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Settings ID in path and body must match", error.message)
        assert(error.details?.containsKey("pathId") == true)
        assertEquals(testSettings1.id.toString(), error.details?.get("pathId"))
        assert(error.details?.containsKey("bodyId") == true)
        assertEquals(mismatchedId.toString(), error.details?.get("bodyId"))
    }

    @Test
    fun `PUT settings with non-existent model ID should return 400`() = settingsTestApplication {
        // Arrange
        testDataManager.insertModelSettings(testSettings1)
        val nonExistentModelId = 999L
        val updatedSettings = testSettings1.copy(modelId = nonExistentModelId)

        // Act
        val response = client.put(href(SettingsResource.ById(settingsId = testSettings1.id))) {
            contentType(ContentType.Application.Json)
            setBody(updatedSettings)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Model not found for settings", error.message)
        assert(error.details?.containsKey("modelId") == true)
        assertEquals(nonExistentModelId.toString(), error.details?.get("modelId"))
    }

    // --- DELETE /api/v1/settings/{settingsId} Tests ---

    @Test
    fun `DELETE settings should remove the settings successfully`() = settingsTestApplication {
        // Arrange
        testDataManager.insertModelSettings(testSettings1)

        // Act
        val response = client.delete(href(SettingsResource.ById(settingsId = testSettings1.id)))

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)

        // Verify the settings were actually deleted
        val retrievedSettings = testDataManager.getModelSettings(testSettings1.id)
        assertNull(retrievedSettings)
    }

    @Test
    fun `DELETE settings with non-existent ID should return 404`() = settingsTestApplication {
        // Arrange
        val nonExistentId = 999L

        // Act
        val response = client.delete(href(SettingsResource.ById(settingsId = nonExistentId)))

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Settings not found", error.message)
        assert(error.details?.containsKey("settingsId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("settingsId"))
    }
}
