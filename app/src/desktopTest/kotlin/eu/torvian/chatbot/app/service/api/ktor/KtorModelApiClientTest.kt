package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.ModelResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.llm.AddModelRequest
import eu.torvian.chatbot.common.models.api.llm.ApiKeyStatusResponse
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class KtorModelApiClientTest {
    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): ModelApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorModelApiClient(httpClient)
    }

    // --- Helper for creating mock data ---
    private fun mockModel(
        id: Long,
        name: String,
        providerId: Long,
        active: Boolean = true
    ) = LLMModel(
        id = id,
        name = name,
        providerId = providerId,
        active = active,
        displayName = name.replace("-", " ").capitalizeWords(),
        type = LLMModelType.CHAT
    )

    private fun mockApiKeyStatusResponse(isConfigured: Boolean) =
        ApiKeyStatusResponse(isConfigured)

    // --- Tests for getAllModels ---
    @Test
    fun `getAllModels - success`() = runTest {
        val mockModels = listOf(
            mockModel(1, "gpt-3.5-turbo", 10),
            mockModel(2, "claude-3-sonnet", 20)
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(ModelResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(mockModels),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getAllModels()
        when (result) {
            is Either.Right -> {
                val models = result.value
                assertEquals(2, models.size)
                assertEquals("gpt-3.5-turbo", models[0].name)
                assertEquals(10, models[0].providerId)
                assertEquals("claude-3-sonnet", models[1].name)
                assertEquals(20, models[1].providerId)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getAllModels - failure - 500 Internal Server Error`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(ModelResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getAllModels()
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.apiError.code)
                assertEquals("Database error", error.apiError.message)
            }
        }
    }

    @Test
    fun `getAllModels - failure - SerializationException`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(ModelResource()), request.url.fullPath)
            respond(
                content = """[{"id": 1, "name": "bad-model"}]""", // Missing providerId, active
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getAllModels()
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- Tests for addModel ---
    @Test
    fun `addModel - success`() = runTest {
        val mockRequest = AddModelRequest(
            name = "new-model",
            providerId = 10,
            type = LLMModelType.CHAT,
            active = true,
            displayName = "New Model"
        )
        val mockResponseModel = mockModel(1, "new-model", 10, true).copy(displayName = "New Model")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(ModelResource()), request.url.fullPath)
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(mockRequest), requestBody)
            respond(
                content = json.encodeToString(mockResponseModel),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addModel(mockRequest)
        when (result) {
            is Either.Right -> {
                val model = result.value
                assertEquals(1, model.id)
                assertEquals("new-model", model.name)
                assertEquals(10, model.providerId)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `addModel - failure - 400 Bad Request (Invalid Data)`() = runTest {
        val mockRequest = AddModelRequest(
            name = "", // Invalid name
            providerId = 10,
            type = LLMModelType.CHAT,
            active = true
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(ModelResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Name cannot be empty")),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addModel(mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
                assertEquals("Name cannot be empty", error.apiError.message)
            }
        }
    }

    @Test
    fun `addModel - failure - 400 Bad Request (Provider Not Found)`() = runTest {
        val mockRequest = AddModelRequest(
            name = "new-model",
            providerId = 999, // Non-existent provider
            type = LLMModelType.CHAT,
            active = true
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(ModelResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Provider not found")),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addModel(mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
                assertEquals("Provider not found", error.apiError.message)
            }
        }
    }

    @Test
    fun `addModel - failure - 409 Conflict (Already Exists)`() = runTest {
        val mockRequest = AddModelRequest(
            name = "gpt-3.5-turbo", // Name already exists
            providerId = 10,
            type = LLMModelType.CHAT,
            active = true
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(ModelResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(
                    apiError(
                        CommonApiErrorCodes.ALREADY_EXISTS,
                        "Model name already exists"
                    )
                ),
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addModel(mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(409, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.apiError.code)
                assertEquals("Model name already exists", error.apiError.message)
            }
        }
    }

    @Test
    fun `addModel - failure - 500 Internal Server Error`() = runTest {
        val mockRequest = AddModelRequest(
            name = "new-model",
            providerId = 10,
            type = LLMModelType.CHAT,
            active = true
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(ModelResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addModel(mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.apiError.code)
                assertEquals("Database error", error.apiError.message)
            }
        }
    }

    @Test
    fun `addModel - failure - SerializationException`() = runTest {
        val mockRequest = AddModelRequest(
            name = "new-model",
            providerId = 10,
            type = LLMModelType.CHAT,
            active = true
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(ModelResource()), request.url.fullPath)
            respond(
                content = """{"id": 1, "name": "partial"}""", // Bad JSON
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addModel(mockRequest)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- Tests for getModelById ---
    @Test
    fun `getModelById - success`() = runTest {
        val modelId = 123L
        val mockModel = mockModel(modelId, "gpt-4", 10, true)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ModelResource.ById(modelId = modelId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(mockModel),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getModelById(modelId)
        when (result) {
            is Either.Right -> {
                val model = result.value
                assertEquals(modelId, model.id)
                assertEquals("gpt-4", model.name)
                assertEquals(10, model.providerId)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getModelById - failure - 404 Not Found`() = runTest {
        val modelId = 999L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ModelResource.ById(modelId = modelId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Model not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getModelById(modelId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Model not found", error.apiError.message)
            }
        }
    }

    @Test
    fun `getModelById - failure - SerializationException`() = runTest {
        val modelId = 123L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ModelResource.ById(modelId = modelId)),
                request.url.fullPath
            )
            respond(
                content = """{"id": 1, "name": "partial"}""", // Missing required fields
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getModelById(modelId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- Tests for updateModel ---
    @Test
    fun `updateModel - success`() = runTest {
        val modelId = 123L
        val updatedModel = mockModel(modelId, "gpt-4", 10, true).copy(active = false, displayName = "GPT-4 (Inactive)")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(ModelResource.ById(modelId = modelId)),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(updatedModel), requestBody)
            respond(
                content = "", // Unit response
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateModel(updatedModel)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateModel - failure - 400 Bad Request (Invalid Data)`() = runTest {
        val modelId = 123L
        val updatedModel = mockModel(modelId, "", 10, true) // Invalid name
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(ModelResource.ById(modelId = modelId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Name cannot be empty")),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateModel(updatedModel)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
                assertEquals("Name cannot be empty", error.apiError.message)
            }
        }
    }

    @Test
    fun `updateModel - failure - 404 Not Found`() = runTest {
        val modelId = 999L
        val updatedModel = mockModel(modelId, "gpt-4", 10, true)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(ModelResource.ById(modelId = modelId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Model not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateModel(updatedModel)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Model not found", error.apiError.message)
            }
        }
    }

    @Test
    fun `updateModel - failure - 409 Conflict (Already Exists)`() = runTest {
        val modelId = 123L
        // Update model 123 to have the name of another existing model
        val updatedModel = mockModel(modelId, "existing-model-name", 10, true)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(ModelResource.ById(modelId = modelId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(
                    apiError(
                        CommonApiErrorCodes.ALREADY_EXISTS,
                        "Model name already exists"
                    )
                ),
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateModel(updatedModel)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(409, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.apiError.code)
                assertEquals("Model name already exists", error.apiError.message)
            }
        }
    }

    // --- Tests for deleteModel ---
    @Test
    fun `deleteModel - success`() = runTest {
        val modelId = 456L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(ModelResource.ById(modelId = modelId)),
                request.url.fullPath
            )
            respond(
                content = "", // Unit response
                status = HttpStatusCode.NoContent
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.deleteModel(modelId)
        when (result) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `deleteModel - failure - 404 Not Found`() = runTest {
        val modelId = 999L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(ModelResource.ById(modelId = modelId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Model not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.deleteModel(modelId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Model not found", error.apiError.message)
            }
        }
    }

    // --- Tests for getModelApiKeyStatus ---
    @Test
    fun `getModelApiKeyStatus - success - configured`() = runTest {
        val modelId = 123L
        val mockResponse = mockApiKeyStatusResponse(isConfigured = true)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ModelResource.ById.ApiKeyStatus(ModelResource.ById(modelId = modelId))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(mockResponse),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getModelApiKeyStatus(modelId)
        when (result) {
            is Either.Right -> {
                val status = result.value
                assertTrue(status.isConfigured)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getModelApiKeyStatus - success - not configured`() = runTest {
        val modelId = 123L
        val mockResponse = mockApiKeyStatusResponse(isConfigured = false)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ModelResource.ById.ApiKeyStatus(ModelResource.ById(modelId = modelId))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(mockResponse),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getModelApiKeyStatus(modelId)
        when (result) {
            is Either.Right -> {
                val status = result.value
                assertEquals(false, status.isConfigured)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getModelApiKeyStatus - failure - 500 Internal Server Error`() = runTest {
        val modelId = 123L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ModelResource.ById.ApiKeyStatus(ModelResource.ById(modelId = modelId))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Secure storage error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.getModelApiKeyStatus(modelId)
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.apiError.code)
                assertEquals("Secure storage error", error.apiError.message)
            }
        }
    }
}

// Helper for capitalizeWords
private fun String.capitalizeWords(): String =
    split(
        " ",
        "-"
    ).joinToString(" ") { it -> it.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() } }
