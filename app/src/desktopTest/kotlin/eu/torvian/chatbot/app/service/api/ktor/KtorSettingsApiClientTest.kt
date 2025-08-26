package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.ModelResource
import eu.torvian.chatbot.common.api.resources.SettingsResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.ChatModelSettings
import eu.torvian.chatbot.common.models.ModelSettings
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class KtorSettingsApiClientTest {
    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): SettingsApi {
        val httpClient = createHttpClient("http://localhost", json, mockEngine)
        return KtorSettingsApiClient(httpClient)
    }

    // --- Helper for creating mock data ---
    private fun mockModelSettings(
        id: Long,
        modelId: Long,
        name: String,
        systemMessage: String? = null,
        temperature: Float? = null,
        maxTokens: Int? = null,
        customParams: JsonObject? = null
    ) = ChatModelSettings(id, modelId, name, systemMessage, temperature, maxTokens, customParams = customParams)

    // --- Tests for getSettingsByModelId ---
    @Test
    fun `getSettingsByModelId - success`() = runTest {
        val modelId = 123L
        val mockSettingsList = listOf(
            mockModelSettings(1, modelId, "Default") as ModelSettings,
            mockModelSettings(2, modelId, "Creative", temperature = 0.8f) as ModelSettings
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ModelResource.ById.Settings(ModelResource.ById(modelId = modelId))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(mockSettingsList),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getSettingsByModelId(modelId)
        when (result) {
            is Either.Right -> {
                val settings = result.value
                assertEquals(2, settings.size)
                assertEquals("Default", settings[0].name)
                assertEquals(modelId, settings[0].modelId)
                assertEquals("Creative", settings[1].name)
                assertEquals(0.8f, (settings[1] as? ChatModelSettings)?.temperature)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getSettingsByModelId - success - model has no settings`() = runTest {
        val modelId = 123L
        val mockSettingsList = emptyList<ModelSettings>() // Empty list response
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ModelResource.ById.Settings(ModelResource.ById(modelId = modelId))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(mockSettingsList),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getSettingsByModelId(modelId)
        when (result) {
            is Either.Right -> {
                val settings = result.value
                assertTrue(settings.isEmpty()) // Expect empty list
            }

            is Either.Left -> fail("Expected success (empty list), but got error: ${result.value}")
        }
    }

    @Test
    fun `getSettingsByModelId - failure - 500 Internal Server Error`() = runTest {
        val modelId = 123L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ModelResource.ById.Settings(ModelResource.ById(modelId = modelId))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getSettingsByModelId(modelId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value
                assertEquals(500, error.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.code)
                assertEquals("Database error", error.message)
            }
        }
    }

    @Test
    fun `getSettingsByModelId - failure - SerializationException`() = runTest {
        val modelId = 123L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ModelResource.ById.Settings(ModelResource.ById(modelId = modelId))),
                request.url.fullPath
            )
            respond(
                content = """[{"id": 1, "name": "partial"}]""", // Missing required fields (modelId)
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getSettingsByModelId(modelId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value
                assertEquals(500, error.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.code)
                assertTrue(error.message.contains("Data Serialization/Deserialization Error"))
            }
        }
    }

    // --- Tests for addModelSettings ---
    @Test
    fun `addModelSettings - success`() = runTest {
        val modelId = 123L
        val mockSettings = mockModelSettings(0, modelId, "New Settings", temperature = 0.7f, maxTokens = 1000)
        val mockResponseSettings = mockModelSettings(1, modelId, "New Settings", temperature = 0.7f, maxTokens = 1000)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                href(SettingsResource()),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockSettings as ModelSettings), requestBody)
            respond(
                content = json.encodeToString(mockResponseSettings as ModelSettings),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addModelSettings(mockSettings)
        when (result) {
            is Either.Right -> {
                val settings = result.value
                assertEquals(1, settings.id)
                assertEquals(modelId, settings.modelId)
                assertEquals("New Settings", settings.name)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `addModelSettings - failure - 400 Bad Request (Model Not Found)`() = runTest {
        val modelId = 999L // Non-existent model
        val mockSettings = mockModelSettings(0, modelId, "New Settings", temperature = 0.7f, maxTokens = 1000)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                href(SettingsResource()),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Model not found")),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addModelSettings(mockSettings)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value
                assertEquals(400, error.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.code)
                assertEquals("Model not found", error.message)
            }
        }
    }

    @Test
    fun `addModelSettings - failure - 500 Internal Server Error`() = runTest {
        val modelId = 123L
        val mockSettings = mockModelSettings(0, modelId, "New Settings")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                href(SettingsResource()),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addModelSettings(mockSettings)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value
                assertEquals(500, error.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.code)
                assertEquals("Database error", error.message)
            }
        }
    }

    @Test
    fun `addModelSettings - failure - SerializationException`() = runTest {
        val modelId = 123L
        val mockSettings = mockModelSettings(0, modelId, "New Settings")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                href(SettingsResource()),
                request.url.fullPath
            )
            respond(
                content = """{"id": 1, "name": "partial"}""", // Bad JSON
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addModelSettings(mockSettings)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value
                assertEquals(500, error.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.code)
                assertTrue(error.message.contains("Data Serialization/Deserialization Error"))
            }
        }
    }

    // --- Tests for getSettingsById ---
    @Test
    fun `getSettingsById - success`() = runTest {
        val settingsId = 123L
        val mockSettings = mockModelSettings(settingsId, 10, "My Settings", systemMessage = "Be helpful.")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(SettingsResource.ById(settingsId = settingsId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(mockSettings as ModelSettings),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getSettingsById(settingsId)
        when (result) {
            is Either.Right -> {
                val settings = result.value
                assertTrue(settings is ChatModelSettings, "Expected ChatModelSettings type")
                assertEquals(settingsId, settings.id)
                assertEquals("My Settings", settings.name)
                assertEquals("Be helpful.", settings.systemMessage)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getSettingsById - failure - 404 Not Found`() = runTest {
        val settingsId = 999L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(SettingsResource.ById(settingsId = settingsId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Settings profile not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getSettingsById(settingsId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value
                assertEquals(404, error.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
                assertEquals("Settings profile not found", error.message)
            }
        }
    }

    @Test
    fun `getSettingsById - failure - SerializationException`() = runTest {
        val settingsId = 123L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(SettingsResource.ById(settingsId = settingsId)),
                request.url.fullPath
            )
            respond(
                content = """{"id": 1, "name": "partial"}""", // Missing required fields
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getSettingsById(settingsId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value
                assertEquals(500, error.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.code)
                assertTrue(error.message.contains("Data Serialization/Deserialization Error"))
            }
        }
    }

    // --- Tests for updateSettings ---
    @Test
    fun `updateSettings - success`() = runTest {
        val settingsId = 123L
        val updatedSettings = mockModelSettings(settingsId, 10, "Updated Settings", temperature = 0.5f)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SettingsResource.ById(settingsId = settingsId)),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(updatedSettings as ModelSettings), requestBody)
            respond(
                content = "", // Unit response
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSettings(updatedSettings)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateSettings - failure - 404 Not Found`() = runTest {
        val settingsId = 999L
        val updatedSettings = mockModelSettings(settingsId, 10, "Updated Settings")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SettingsResource.ById(settingsId = settingsId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Settings profile not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSettings(updatedSettings)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value
                assertEquals(404, error.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
                assertEquals("Settings profile not found", error.message)
            }
        }
    }

    @Test
    fun `updateSettings - failure - 500 Internal Server Error`() = runTest {
        val settingsId = 123L
        val updatedSettings = mockModelSettings(settingsId, 10, "Updated Settings")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(SettingsResource.ById(settingsId = settingsId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateSettings(updatedSettings)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value
                assertEquals(500, error.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.code)
                assertEquals("Database error", error.message)
            }
        }
    }

    // --- Tests for deleteSettings ---
    @Test
    fun `deleteSettings - success`() = runTest {
        val settingsId = 456L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(SettingsResource.ById(settingsId = settingsId)),
                request.url.fullPath
            )
            respond(
                content = "", // Unit response
                status = HttpStatusCode.NoContent
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.deleteSettings(settingsId)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `deleteSettings - failure - 404 Not Found`() = runTest {
        val settingsId = 999L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(SettingsResource.ById(settingsId = settingsId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Settings profile not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.deleteSettings(settingsId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value
                assertEquals(404, error.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.code)
                assertEquals("Settings profile not found", error.message)
            }
        }
    }
}