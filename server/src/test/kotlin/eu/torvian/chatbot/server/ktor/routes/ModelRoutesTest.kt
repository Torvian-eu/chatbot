package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.resources.ModelResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.misc.di.DIContainer
import eu.torvian.chatbot.common.misc.di.get
import eu.torvian.chatbot.common.models.*
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
import kotlin.test.*

/**
 * Integration tests for Model API routes.
 *
 * This test suite verifies the HTTP endpoints for LLM model management:
 * - GET /api/v1/models - List all models
 * - POST /api/v1/models - Add new model
 * - GET /api/v1/models/{modelId} - Get model by ID
 * - PUT /api/v1/models/{modelId} - Update model by ID
 * - DELETE /api/v1/models/{modelId} - Delete model by ID
 * - GET /api/v1/models/{modelId}/settings - List settings for this model
 * - POST /api/v1/models/{modelId}/settings - Add new settings for this model
 * - GET /api/v1/models/{modelId}/apikey/status - Get API key status for model
 */
class ModelRoutesTest {
    private lateinit var container: DIContainer
    private lateinit var modelTestApplication: KtorTestApp
    private lateinit var testDataManager: TestDataManager

    // Test data
    private val testProvider1 = TestDefaults.llmProvider1
    private val testProvider2 = TestDefaults.llmProvider2
    private val testModel1 = TestDefaults.llmModel1 // Uses testProvider1
    private val testModel2 = TestDefaults.llmModel2 // Uses testProvider2
    private val testSettings1 = TestDefaults.modelSettings1 // For testModel1

    @BeforeEach
    fun setUp() = runTest {
        container = defaultTestContainer()
        val apiRoutesKtor: ApiRoutesKtor = container.get()

        modelTestApplication = myTestApplication(
            container = container,
            routing = {
                apiRoutesKtor.configureModelRoutes(this)
            }
        )

        testDataManager = container.get()
        // Need LLMProviders for models, LLMModels for models and settings, ModelSettings for settings
        testDataManager.createTables(
            setOf(
                Table.LLM_PROVIDERS,
                Table.LLM_MODELS,
                Table.MODEL_SETTINGS,
                Table.API_SECRETS
            )
        )
    }

    @AfterEach
    fun tearDown() = runTest {
        testDataManager.cleanup()
        container.close()
    }

    // --- GET /api/v1/models Tests ---

    @Test
    fun `GET models should return list of models successfully`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1, testProvider2),
                llmModels = listOf(testModel1, testModel2)
            )
        )
        val expectedModels = listOf(testModel1, testModel2)

        // Act & Assert
        val response = client.get(href(ModelResource()))
        assertEquals(HttpStatusCode.OK, response.status)
        val models = response.body<List<LLMModel>>()
        assertEquals(expectedModels, models)
    }

    @Test
    fun `GET models should return empty list if no models exist`() = modelTestApplication {
        // Arrange (no models inserted)

        // Act & Assert
        val response = client.get(href(ModelResource()))
        assertEquals(HttpStatusCode.OK, response.status)
        val models = response.body<List<LLMModel>>()
        assertEquals(emptyList(), models)
    }

    // --- POST /api/v1/models Tests ---

    @Test
    fun `POST models should add a new model successfully`() = modelTestApplication {
        // Arrange
        testDataManager.insertLLMProvider(testProvider1) // Need provider for model
        val newModelName = "New Model"
        val newModelDisplayName = "New Model Display"
        val newModelActive = true

        val createRequest = AddModelRequest(
            name = newModelName,
            providerId = testProvider1.id,
            active = newModelActive,
            displayName = newModelDisplayName
        )

        // Act
        val response = client.post(href(ModelResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Created, response.status)
        val createdModel = response.body<LLMModel>()
        assertEquals(newModelName, createdModel.name)
        assertEquals(testProvider1.id, createdModel.providerId)
        assertEquals(newModelActive, createdModel.active)
        assertEquals(newModelDisplayName, createdModel.displayName)

        // Verify the model was actually created in the database
        val retrievedModel = testDataManager.getLLMModel(createdModel.id)
        assertNotNull(retrievedModel)
        assertEquals(createdModel, retrievedModel)
    }

    @Test
    fun `POST models should return 400 for blank name`() = modelTestApplication {
        // Arrange
        testDataManager.insertLLMProvider(testProvider1)
        val createRequest = AddModelRequest(
            name = "   ",
            providerId = testProvider1.id,
            active = true,
            displayName = null
        )

        // Act
        val response = client.post(href(ModelResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid model input", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Model name cannot be blank.", error.details?.get("reason"))
    }

    @Test
    fun `POST models should return 400 for non-existent provider`() = modelTestApplication {
        // Arrange
        val nonExistentProviderId = 999L
        val createRequest = AddModelRequest(
            name = "Model For NonExistent Provider",
            providerId = nonExistentProviderId,
            active = true,
            displayName = null
        )

        // Act
        val response = client.post(href(ModelResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Provider not found for model", error.message)
        assert(error.details?.containsKey("providerId") == true)
        assertEquals(nonExistentProviderId.toString(), error.details?.get("providerId"))
    }

    @Test
    fun `POST models should return 409 if model name already exists`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1)
            )
        )

        val createRequest = AddModelRequest(
            name = testModel1.name, // Use existing name
            providerId = testProvider1.id,
            active = true,
            displayName = "Another Display Name"
        )

        // Act
        val response = client.post(href(ModelResource())) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status) // 409 Conflict
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.code)
        assertEquals(409, error.statusCode)
        assertEquals("Model name already exists", error.message)
        assert(error.details?.containsKey("name") == true)
        assertEquals(testModel1.name, error.details?.get("name"))
    }

    // --- GET /api/v1/models/{modelId} Tests ---

    @Test
    fun `GET model by ID should return model successfully`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1)
            )
        )

        // Act
        val response = client.get(href(ModelResource.ById(modelId = testModel1.id)))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val model = response.body<LLMModel>()
        assertEquals(testModel1, model)
    }

    @Test
    fun `GET model with non-existent ID should return 404`() = modelTestApplication {
        // Arrange
        val nonExistentId = 999L

        // Act
        val response = client.get(href(ModelResource.ById(modelId = nonExistentId)))

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Model not found", error.message)
        assert(error.details?.containsKey("modelId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("modelId"))
    }

    // --- PUT /api/v1/models/{modelId} Tests ---

    @Test
    fun `PUT model should update model successfully`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1)
            )
        )
        val updatedModel = testModel1.copy(
            name = "updated-gpt-4",
            active = false,
            displayName = "Updated GPT-4 Display"
            // providerId should not be changed via this endpoint according to the service logic
        )

        // Act
        val response = client.put(href(ModelResource.ById(modelId = testModel1.id))) {
            contentType(ContentType.Application.Json)
            setBody(updatedModel)
        }

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)

        // Verify the model was actually updated in the database
        val retrievedModel = testDataManager.getLLMModel(testModel1.id)
        assertNotNull(retrievedModel)
        // Check only the fields that are expected to be updated by this endpoint
        assertEquals(updatedModel.name, retrievedModel.name)
        assertEquals(updatedModel.active, retrievedModel.active)
        assertEquals(updatedModel.displayName, retrievedModel.displayName)
        // Ensure fields not meant to be updated remain the same
        assertEquals(testModel1.providerId, retrievedModel.providerId)
    }

    @Test
    fun `PUT model with non-existent ID should return 404`() = modelTestApplication {
        // Arrange
        val nonExistentId = 999L
        val updatedModel = testModel1.copy(id = nonExistentId) // Use a copy with the non-existent ID

        // Act
        val response = client.put(href(ModelResource.ById(modelId = nonExistentId))) {
            contentType(ContentType.Application.Json)
            setBody(updatedModel)
        }

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Model not found", error.message)
        assert(error.details?.containsKey("modelId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("modelId"))
    }

    @Test
    fun `PUT model with mismatched ID should return 400`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1)
            )
        )
        val mismatchedId = 999L
        val updatedModel = testModel1.copy(id = mismatchedId) // Body ID is different from path ID

        // Act
        val response = client.put(href(ModelResource.ById(modelId = testModel1.id))) {
            contentType(ContentType.Application.Json)
            setBody(updatedModel)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Model ID in path and body must match", error.message)
        assert(error.details?.containsKey("pathId") == true)
        assertEquals(testModel1.id.toString(), error.details?.get("pathId"))
        assert(error.details?.containsKey("bodyId") == true)
        assertEquals(mismatchedId.toString(), error.details?.get("bodyId"))
    }

    @Test
    fun `PUT model should return 400 for blank name`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1)
            )
        )
        val updatedModel = testModel1.copy(name = "   ")

        // Act
        val response = client.put(href(ModelResource.ById(modelId = testModel1.id))) {
            contentType(ContentType.Application.Json)
            setBody(updatedModel)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid model input", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Model name cannot be blank.", error.details?.get("reason"))
    }

    @Test
    fun `PUT model should return 409 if updated name already exists for another model`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1, testProvider2),
                llmModels = listOf(testModel1, testModel2) // name="gpt-4", name="claude-3"
            )
        )

        val updatedModel =
            testModel1.copy(name = testModel2.name) // Try to change testModel1's name to testModel2's name

        // Act
        val response = client.put(href(ModelResource.ById(modelId = testModel1.id))) {
            contentType(ContentType.Application.Json)
            setBody(updatedModel)
        }

        // Assert
        assertEquals(HttpStatusCode.Conflict, response.status) // 409 Conflict
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.code)
        assertEquals(409, error.statusCode)
        assertEquals("Model name already exists", error.message)
        assert(error.details?.containsKey("name") == true)
        assertEquals(testModel2.name, error.details?.get("name"))
    }

    // --- DELETE /api/v1/models/{modelId} Tests ---

    @Test
    fun `DELETE model should remove the model successfully`() = modelTestApplication {
        // Arrange

        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1)
            )
        )

        // Act
        val response = client.delete(href(ModelResource.ById(modelId = testModel1.id)))

        // Assert
        assertEquals(HttpStatusCode.NoContent, response.status)

        // Verify the model was actually deleted
        val retrievedModel = testDataManager.getLLMModel(testModel1.id)
        assertNull(retrievedModel)
    }

    @Test
    fun `DELETE model with non-existent ID should return 404`() = modelTestApplication {
        // Arrange
        val nonExistentId = 999L

        // Act
        val response = client.delete(href(ModelResource.ById(modelId = nonExistentId)))

        // Assert
        assertEquals(HttpStatusCode.NotFound, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
        assertEquals(404, error.statusCode)
        assertEquals("Model not found", error.message)
        assert(error.details?.containsKey("modelId") == true)
        assertEquals(nonExistentId.toString(), error.details?.get("modelId"))
    }

    // --- GET /api/v1/models/{modelId}/settings Tests ---

    @Test
    fun `GET model settings should return list of settings successfully`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1),
                modelSettings = listOf(testSettings1) // Settings for testModel1
            )
        )

        // Act
        val response = client.get(href(ModelResource.ById.Settings(ModelResource.ById(modelId = testModel1.id))))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val settingsList = response.body<List<ModelSettings>>()
        assertEquals(1, settingsList.size)
        assertEquals(testSettings1, settingsList.first())
    }

    @Test
    fun `GET model settings should return empty list if model has no settings`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1) // Insert model but no settings for it
            )
        )

        // Act
        val response = client.get(href(ModelResource.ById.Settings(ModelResource.ById(modelId = testModel1.id))))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val settingsList = response.body<List<ModelSettings>>()
        assertEquals(emptyList(), settingsList)
    }

    @Test
    fun `GET model settings for non-existent model ID should return empty list`() = modelTestApplication {
        // Arrange
        val nonExistentId = 999L

        // Act
        // Note: The service layer currently returns an empty list if the model doesn't exist,
        // rather than a Not Found error. The test reflects this current behavior.
        val response = client.get(href(ModelResource.ById.Settings(ModelResource.ById(modelId = nonExistentId))))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status) // Expect OK with empty list based on service impl
        val settingsList = response.body<List<ModelSettings>>()
        assertEquals(emptyList(), settingsList)
    }

    // --- POST /api/v1/models/{modelId}/settings Tests ---

    @Test
    fun `POST model settings should add new settings successfully`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1) // Need model for settings
            )
        )

        val newSettingsName = "New Settings"
        val newSystemMessage = "You are a helpful assistant."
        val newTemperature = 0.7f
        val newMaxTokens = 500
        val newCustomParams = """{"top_p": 0.9}"""

        val createRequest = AddModelSettingsRequest(
            name = newSettingsName,
            systemMessage = newSystemMessage,
            temperature = newTemperature,
            maxTokens = newMaxTokens,
            customParamsJson = newCustomParams
        )

        // Act
        val response = client.post(href(ModelResource.ById.Settings(ModelResource.ById(modelId = testModel1.id)))) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.Created, response.status)
        val createdSettings = response.body<ModelSettings>()
        assertEquals(newSettingsName, createdSettings.name)
        assertEquals(testModel1.id, createdSettings.modelId)
        assertEquals(newSystemMessage, createdSettings.systemMessage)
        assertEquals(newTemperature, createdSettings.temperature)
        assertEquals(newMaxTokens, createdSettings.maxTokens)
        assertEquals(newCustomParams, createdSettings.customParamsJson)

        // Verify the settings were actually created in the database
        val retrievedSettings = testDataManager.getModelSettings(createdSettings.id)
        assertNotNull(retrievedSettings)
        assertEquals(createdSettings, retrievedSettings)
    }

    @Test
    fun `POST model settings should return 400 for blank name`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(testProvider1),
                llmModels = listOf(testModel1)
            )
        )

        val createRequest = AddModelSettingsRequest(
            name = "   ",
            systemMessage = "sys",
            temperature = 0.5f,
            maxTokens = 100,
            customParamsJson = null
        )

        // Act
        val response = client.post(href(ModelResource.ById.Settings(ModelResource.ById(modelId = testModel1.id)))) {
            contentType(ContentType.Application.Json)
            setBody(createRequest)
        }

        // Assert
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.body<ApiError>()
        assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
        assertEquals(400, error.statusCode)
        assertEquals("Invalid settings input", error.message)
        assert(error.details?.containsKey("reason") == true)
        assertEquals("Settings name cannot be blank.", error.details?.get("reason"))
    }

    @Test
    fun `POST model settings should return 400 for non-existent model ID`() = modelTestApplication {
        // Arrange
        val nonExistentModelId = 999L
        val createRequest = AddModelSettingsRequest(
            name = "Settings for NonExistent Model",
            systemMessage = "sys",
            temperature = 0.5f,
            maxTokens = 100,
            customParamsJson = null
        )

        // Act
        val response =
            client.post(href(ModelResource.ById.Settings(ModelResource.ById(modelId = nonExistentModelId)))) {
                contentType(ContentType.Application.Json)
                setBody(createRequest)
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

    // --- GET /api/v1/models/{modelId}/apikey/status Tests ---

    @Test
    fun `GET model apikey status should return true if provider has credential`() = modelTestApplication {
        // Arrange
        testDataManager.setup(
            TestDataSet(
                apiSecrets = listOf(TestDefaults.apiSecret1),
                llmProviders = listOf(testProvider1), // testProvider1 has apiSecret1
                llmModels = listOf(testModel1) // testModel1 uses testProvider1
            )
        )

        // Act
        val response = client.get(href(ModelResource.ById.ApiKeyStatus(ModelResource.ById(modelId = testModel1.id))))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val status = response.body<ApiKeyStatusResponse>()
        assertTrue(status.isConfigured, "API key should be configured")
    }

    @Test
    fun `GET model apikey status should return false if provider does not have credential`() = modelTestApplication {
        // Arrange
        val providerWithoutSecret = testProvider1.copy(apiKeyId = null)
        testDataManager.setup(
            TestDataSet(
                llmProviders = listOf(providerWithoutSecret), // Provider has no secret
                llmModels = listOf(testModel1.copy(providerId = providerWithoutSecret.id)) // Model uses this provider
            )
        )

        // Act
        val response = client.get(href(ModelResource.ById.ApiKeyStatus(ModelResource.ById(modelId = testModel1.id))))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status)
        val status = response.body<ApiKeyStatusResponse>()
        assertFalse(status.isConfigured, "API key should not be configured")
    }

    @Test
    fun `GET model apikey status for non-existent model ID should return false`() = modelTestApplication {
        // Arrange
        val nonExistentId = 999L

        // Act
        // Note: The service layer currently returns false if the model doesn't exist,
        // rather than a Not Found error. The test reflects this current behavior.
        val response = client.get(href(ModelResource.ById.ApiKeyStatus(ModelResource.ById(modelId = nonExistentId))))

        // Assert
        assertEquals(HttpStatusCode.OK, response.status) // Expect OK with false based on service impl
        val status = response.body<ApiKeyStatusResponse>()
        assertFalse(status.isConfigured, "API key should not be configured for non-existent model")
    }
}