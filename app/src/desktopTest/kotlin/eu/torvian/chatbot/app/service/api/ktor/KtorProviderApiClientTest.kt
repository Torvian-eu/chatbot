package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ProviderApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.ProviderResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMModelType
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.llm.LLMProviderType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class KtorProviderApiClientTest {
    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): ProviderApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorProviderApiClient(httpClient)
    }

    // --- Helper for creating mock data ---
    private fun mockProvider(
        id: Long,
        name: String,
        type: LLMProviderType,
        apiKeyId: String? = null
    ) = LLMProvider(
        id = id,
        apiKeyId = apiKeyId,
        name = name,
        description = "$name provider",
        baseUrl = "http://localhost/${name.lowercase()}",
        type = type
    )

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
        displayName = name.replace("-", " ").capitalize(),
        type = LLMModelType.CHAT
    )

    // --- Tests for getAllProviders ---
    @Test
    fun `getAllProviders - success`() = runTest {
        val mockProviders = listOf(
            mockProvider(1, "OpenAI", LLMProviderType.OPENAI, "key1"),
            mockProvider(2, "Anthropic", LLMProviderType.ANTHROPIC, "key2")
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(ProviderResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(mockProviders),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getAllProviders()) {
            is Either.Right -> {
                val providers = result.value
                assertEquals(2, providers.size)
                assertEquals("OpenAI", providers[0].name)
                assertEquals(LLMProviderType.OPENAI, providers[0].type)
                assertEquals("Anthropic", providers[1].name)
                assertEquals(LLMProviderType.ANTHROPIC, providers[1].type)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getAllProviders - failure - 500 Internal Server Error`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(ProviderResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getAllProviders()) {
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
    fun `getAllProviders - failure - SerializationException`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(ProviderResource()), request.url.fullPath)
            respond(
                content = """{"providers": "not a list"}""", // Bad JSON
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getAllProviders()) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- Tests for addProvider ---
    @Test
    fun `addProvider - success`() = runTest {
        val mockResponseProvider = mockProvider(
            id = 1,
            name = "New Provider",
            type = LLMProviderType.CUSTOM,
            apiKeyId = "alias-abc" // Backend returns the alias, not the raw key
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(ProviderResource()), request.url.fullPath)
            // Verify the request body contains the expected provider data
            val requestBody = request.body.toByteArray().decodeToString()
            assertTrue(requestBody.contains("New Provider"), "Request body should contain provider name")
            assertTrue(requestBody.contains("Test description"), "Request body should contain description")
            assertTrue(requestBody.contains("http://test.com"), "Request body should contain baseUrl")
            respond(
                content = json.encodeToString(mockResponseProvider),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addProvider(
            name = "New Provider",
            description = "Test description",
            baseUrl = "http://test.com",
            type = LLMProviderType.CUSTOM,
            credential = "test-key"
        )
        when (result) {
            is Either.Right -> {
                val provider = result.value
                assertEquals(1, provider.id)
                assertEquals("New Provider", provider.name)
                assertEquals("alias-abc", provider.apiKeyId)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `addProvider - failure - 400 Bad Request`() = runTest {
        val name = "" // Invalid name
        val description = "Test description"
        val baseUrl = "http://test.com"
        val type = LLMProviderType.CUSTOM
        val credential = "test-key"
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(ProviderResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Name cannot be empty")),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addProvider(
            name = name,
            description = description,
            baseUrl = baseUrl,
            type = type,
            credential = credential
        )
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
    fun `addProvider - failure - 500 Internal Server Error (Secure Storage)`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(ProviderResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Secure storage failure")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addProvider(
            name = "New Provider",
            description = "Test description",
            baseUrl = "http://test.com",
            type = LLMProviderType.CUSTOM,
            credential = "test-key"
        )
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.apiError.code)
                assertEquals("Secure storage failure", error.apiError.message)
            }
        }
    }

    @Test
    fun `addProvider - failure - SerializationException`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(ProviderResource()), request.url.fullPath)
            respond(
                content = """{"provider": "not a provider"}""", // Bad JSON
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.addProvider(
            name = "New Provider",
            description = "Test description",
            baseUrl = "http://test.com",
            type = LLMProviderType.CUSTOM,
            credential = "test-key"
        )
        when (result) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- Tests for getProviderById ---
    @Test
    fun `getProviderById - success`() = runTest {
        val providerId = 123L
        val mockProvider = mockProvider(providerId, "OpenAI", LLMProviderType.OPENAI, "alias-abc")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ProviderResource.ById(providerId = providerId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(mockProvider),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getProviderById(providerId)) {
            is Either.Right -> {
                val provider = result.value
                assertEquals(providerId, provider.id)
                assertEquals("OpenAI", provider.name)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getProviderById - failure - 404 Not Found`() = runTest {
        val providerId = 999L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ProviderResource.ById(providerId = providerId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Provider not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getProviderById(providerId)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Provider not found", error.apiError.message)
            }
        }
    }

    @Test
    fun `getProviderById - failure - SerializationException`() = runTest {
        val providerId = 123L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ProviderResource.ById(providerId = providerId)),
                request.url.fullPath
            )
            respond(
                content = """{"id": 1, "name": "Bad Provider"}""", // Missing required fields
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getProviderById(providerId)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- Tests for updateProvider ---
    @Test
    fun `updateProvider - success`() = runTest {
        val providerId = 123L
        val updatedProvider = mockProvider(
            id = providerId,
            name = "Updated Provider Name",
            type = LLMProviderType.OPENAI,
            apiKeyId = "alias-abc" // apiKeyId should not be updated via this endpoint
        ).copy(description = "New Description")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(ProviderResource.ById(providerId = providerId)),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            assertEquals(json.encodeToString(updatedProvider), requestBody)
            respond(
                content = "", // Unit response
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateProvider(updatedProvider)) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateProvider - failure - 400 Bad Request (Invalid Data)`() = runTest {
        val providerId = 123L
        val updatedProvider = mockProvider(
            id = providerId,
            name = "", // Invalid name
            type = LLMProviderType.OPENAI,
            apiKeyId = "alias-abc"
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Name cannot be empty")),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateProvider(updatedProvider)) {
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
    fun `updateProvider - failure - 404 Not Found`() = runTest {
        val providerId = 999L
        val updatedProvider = mockProvider(
            id = providerId,
            name = "Updated Provider Name",
            type = LLMProviderType.OPENAI,
            apiKeyId = "alias-abc"
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(ProviderResource.ById(providerId = providerId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Provider not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateProvider(updatedProvider)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Provider not found", error.apiError.message)
            }
        }
    }

    // --- Tests for deleteProvider ---
    @Test
    fun `deleteProvider - success`() = runTest {
        val providerId = 456L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(ProviderResource.ById(providerId = providerId)),
                request.url.fullPath
            )
            respond(
                content = "", // Unit response
                status = HttpStatusCode.NoContent
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteProvider(providerId)) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `deleteProvider - failure - 404 Not Found`() = runTest {
        val providerId = 999L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(ProviderResource.ById(providerId = providerId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Provider not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteProvider(providerId)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Provider not found", error.apiError.message)
            }
        }
    }

    @Test
    fun `deleteProvider - failure - 409 Conflict (Resource In Use)`() = runTest {
        val providerId = 456L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(ProviderResource.ById(providerId = providerId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(
                    apiError(
                        CommonApiErrorCodes.RESOURCE_IN_USE,
                        "Provider is referenced by models",
                        "modelNames" to "gpt-3.5-turbo, gpt-4"
                    )
                ),
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteProvider(providerId)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(409, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.RESOURCE_IN_USE.code, error.apiError.code)
                assertEquals("Provider is referenced by models", error.apiError.message)
                assertEquals("gpt-3.5-turbo, gpt-4", error.apiError.details?.get("modelNames"))
            }
        }
    }

    @Test
    fun `deleteProvider - failure - 500 Internal Server Error (Secure Storage)`() = runTest {
        val providerId = 456L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(
                href(ProviderResource.ById(providerId = providerId)),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(
                    apiError(
                        CommonApiErrorCodes.INTERNAL,
                        "Secure storage deletion failure"
                    )
                ),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteProvider(providerId)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.apiError.code)
                assertEquals("Secure storage deletion failure", error.apiError.message)
            }
        }
    }

    // --- Tests for updateProviderCredential ---
    @Test
    fun `updateProviderCredential - success`() = runTest {
        val providerId = 123L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(ProviderResource.ById.Credential(ProviderResource.ById(providerId = providerId))),
                request.url.fullPath
            )
            respond(
                content = "", // Unit response
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateProviderCredential(providerId, "new-test-key")) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateProviderCredential - success - remove credential`() = runTest {
        val providerId = 123L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(ProviderResource.ById.Credential(ProviderResource.ById(providerId = providerId))),
                request.url.fullPath
            )
            respond(
                content = "", // Unit response
                status = HttpStatusCode.OK
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateProviderCredential(providerId, null)) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateProviderCredential - failure - 404 Not Found`() = runTest {
        val providerId = 999L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(ProviderResource.ById.Credential(ProviderResource.ById(providerId = providerId))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Provider not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateProviderCredential(providerId, "new-test-key")) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
                assertEquals("Provider not found", error.apiError.message)
            }
        }
    }

    @Test
    fun `updateProviderCredential - failure - 500 Internal Server Error (Secure Storage)`() = runTest {
        val providerId = 123L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(ProviderResource.ById.Credential(ProviderResource.ById(providerId = providerId))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Secure storage update failure")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateProviderCredential(providerId, "new-test-key")) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.apiError.code)
                assertEquals("Secure storage update failure", error.apiError.message)
            }
        }
    }

    // --- Tests for getModelsByProviderId ---
    @Test
    fun `getModelsByProviderId - success`() = runTest {
        val providerId = 123L
        val mockModels = listOf(
            mockModel(1, "gpt-3.5-turbo", providerId),
            mockModel(2, "gpt-4", providerId)
        )
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ProviderResource.ById.Models(ProviderResource.ById(providerId = providerId))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(mockModels),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getModelsByProviderId(providerId)) {
            is Either.Right -> {
                val models = result.value
                assertEquals(2, models.size)
                assertEquals("gpt-3.5-turbo", models[0].name)
                assertEquals(providerId, models[0].providerId)
                assertEquals("gpt-4", models[1].name)
                assertEquals(providerId, models[1].providerId)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getModelsByProviderId - success - provider has no models`() = runTest {
        val providerId = 123L
        val mockModels = emptyList<LLMModel>() // Empty list response
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ProviderResource.ById.Models(ProviderResource.ById(providerId = providerId))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(mockModels),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getModelsByProviderId(providerId)) {
            is Either.Right -> {
                val models = result.value
                assertTrue(models.isEmpty()) // Expect empty list
            }

            is Either.Left -> fail("Expected success (empty list), but got error: ${result.value}")
        }
    }

    @Test
    fun `getModelsByProviderId - failure - 500 Internal Server Error`() = runTest {
        val providerId = 123L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ProviderResource.ById.Models(ProviderResource.ById(providerId = providerId))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getModelsByProviderId(providerId)) {
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
    fun `getModelsByProviderId - failure - SerializationException`() = runTest {
        val providerId = 123L
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(
                href(ProviderResource.ById.Models(ProviderResource.ById(providerId = providerId))),
                request.url.fullPath
            )
            respond(
                content = """[{"id": 1, "name": "bad-model"}]""", // Missing required fields for LLMModel
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getModelsByProviderId(providerId)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }
}

// Helper for capitalize
private fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
