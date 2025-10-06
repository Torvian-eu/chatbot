package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.SettingsResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.llm.ChatModelSettings
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.server.testutils.auth.TestAuthHelper
import eu.torvian.chatbot.server.testutils.auth.authenticate
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
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    private lateinit var authHelper: TestAuthHelper
    private lateinit var authToken: String

    // Test data
    private val testSettings1 = ChatModelSettings(
        id = 1L,
        modelId = 1L,
        name = "Test Settings 1",
        systemMessage = "Test system message",
        temperature = 0.7f,
        maxTokens = 1000,
        customParams = Json.decodeFromString("""{"key": "value"}""")
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
        testDataManager.createTables(
            setOf(
                Table.MODEL_SETTINGS,
                Table.USERS,
                Table.USER_SESSIONS,
                Table.CHAT_SESSION_OWNERS
            )
        )

        // Set up authentication
        authHelper = TestAuthHelper(container)
        authToken = authHelper.createUserAndGetToken()
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
        val response = client.get(href(SettingsResource.ById(settingsId = testSettings1.id))) {
            authenticate(authToken)
        }

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
        val response = client.get(href(SettingsResource.ById(settingsId = nonExistentId))) {
            authenticate(authToken)
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

    // --- POST /api/v1/settings Tests ---

    @Test
    fun `POST settings should add new settings successfully`() = settingsTestApplication {
        // Arrange
        // model and provider are already set up in @BeforeEach via TestDataSet
        val newSettings = ChatModelSettings(
            id = 0L,
            modelId = TestDefaults.llmModel1.id,
            name = "New Settings",
            systemMessage = "Hello",
            temperature = 0.6f,
            maxTokens = 512,
            customParams = Json.decodeFromString("""{"foo":"bar"}""")
        )

        // Act
        val response = client.post(href(SettingsResource())) {
            contentType(ContentType.Application.Json)
            setBody(newSettings as ModelSettings)
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.Created, response.status)
        val created = response.body<ModelSettings>()
        // Verify persisted fields
        assertEquals(newSettings.name, created.name)
        assertEquals(newSettings.modelId, created.modelId)
    }

    @Test
    fun `POST settings with non-existent model should return 400`() = settingsTestApplication {
        // Arrange
        val nonExistentModelId = 999L
        val newSettings = ChatModelSettings(
            id = 0L,
            modelId = nonExistentModelId,
            name = "New Settings",
            systemMessage = "Hello",
            temperature = 0.6f,
            maxTokens = 512,
            customParams = Json.decodeFromString("""{"foo":"bar"}""")
        )

        // Act
        val response = client.post(href(SettingsResource())) {
            contentType(ContentType.Application.Json)
            setBody(newSettings as ModelSettings)
            authenticate(authToken)
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

    @Test
    fun `POST settings with mismatched model type should return 400`() = settingsTestApplication {
        // Arrange
        // The TestDefaults.llmModel1 is of type CHAT; create CompletionModelSettings to cause mismatch
        val mismatchedSettings = eu.torvian.chatbot.common.models.llm.CompletionModelSettings(
            id = 0L,
            modelId = TestDefaults.llmModel1.id,
            name = "Completion Settings",
            suffix = "",
            temperature = 0.5f,
            maxTokens = 100,
            topP = null,
            stopSequences = null,
            customParams = null
        )

        // Act
        val response = client.post(href(SettingsResource())) {
            contentType(ContentType.Application.Json)
            setBody(mismatchedSettings as ModelSettings)
            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid settings input", error.message)
        assert(error.details?.containsKey("reason") == true)
        assert(error.details?.get("reason")!!.contains("does not match"))
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
            setBody(updatedSettings as ModelSettings)

            authenticate(authToken)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the settings were actually updated in the database
        val retrievedSettings = testDataManager.getModelSettings(testSettings1.id)
        assertTrue(retrievedSettings is ChatModelSettings, "Expected ChatModelSettings type")
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
            setBody(updatedSettings as ModelSettings)

            authenticate(authToken)
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
            setBody(updatedSettings as ModelSettings)

            authenticate(authToken)
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
            setBody(updatedSettings as ModelSettings)

            authenticate(authToken)
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
        val response = client.delete(href(SettingsResource.ById(settingsId = testSettings1.id))) {
            authenticate(authToken)
        }

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
        val response = client.delete(href(SettingsResource.ById(settingsId = nonExistentId))) {
            authenticate(authToken)
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
}
