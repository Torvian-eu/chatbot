package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ModelPresetApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.ModelPresetResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/**
 * Tests for [KtorModelPresetApiClient] covering the five model-preset endpoints and error mapping.
 */
class KtorModelPresetApiClientTest {

    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): ModelPresetApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorModelPresetApiClient(httpClient)
    }

    private fun mockPreset(
        id: Long,
        name: String,
        modelId: Long? = 1L,
        modelSettingsId: Long? = 2L
    ) = ModelPresetDto(
        id = id,
        name = name,
        displayName = null,
        description = "",
        modelId = modelId,
        modelSettingsId = modelSettingsId,
        createdAt = Instant.fromEpochSeconds(id),
        updatedAt = Instant.fromEpochSeconds(id)
    )

    // --- getAllPresets ---

    @Test
    fun `getAllPresets - success`() = runTest {
        val presets = listOf(mockPreset(1, "primary"), mockPreset(2, "cheap"))
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(ModelPresetResource()), request.url.fullPath)
            respond(
                content = json.encodeToString(presets),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getAllPresets()) {
            is Either.Right -> {
                assertEquals(2, result.value.size)
                assertEquals("primary", result.value[0].name)
                assertEquals(1L, result.value[0].modelId)
                assertEquals(2L, result.value[0].modelSettingsId)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getAllPresets - failure - 500 Internal Server Error`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Database error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getAllPresets()) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.apiError.code)
            }
        }
    }

    @Test
    fun `getAllPresets - failure - SerializationException`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(ModelPresetResource()), request.url.fullPath)
            respond(
                content = """{"presets": "not a list"}""", // Bad JSON
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getAllPresets()) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    // --- getPresetById ---

    @Test
    fun `getPresetById - success`() = runTest {
        val preset = mockPreset(7, "primary")
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(ModelPresetResource.ById(presetId = 7L)), request.url.fullPath)
            respond(
                content = json.encodeToString(preset),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getPresetById(7L)) {
            is Either.Right -> assertEquals("primary", result.value.name)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getPresetById - failure - 404 Not Found`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Preset not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getPresetById(999L)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
            }
        }
    }

    // --- createPreset ---

    @Test
    fun `createPreset - success sends both references`() = runTest {
        val request = CreateModelPresetRequest(
            name = "primary",
            displayName = "Primary",
            description = "Main chat preset",
            modelId = 1L,
            modelSettingsId = 2L
        )
        val created = mockPreset(10, "primary")
        val mockEngine = MockEngine { mockRequest ->
            assertEquals(HttpMethod.Post, mockRequest.method)
            assertEquals(href(ModelPresetResource()), mockRequest.url.fullPath)
            val body = mockRequest.body.toByteArray().decodeToString()
            assertTrue(body.contains("primary"), "Request body should contain the preset name")
            assertTrue(body.contains("modelSettingsId"), "Request body should contain modelSettingsId")
            respond(
                content = json.encodeToString(created),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.createPreset(request)) {
            is Either.Right -> assertEquals("primary", result.value.name)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `createPreset - success omits null references`() = runTest {
        val request = CreateModelPresetRequest(name = "name only")
        val created = mockPreset(10, "name only", modelId = null, modelSettingsId = null)
        val mockEngine = MockEngine { mockRequest ->
            val body = mockRequest.body.toByteArray().decodeToString()
            assertTrue(body.contains("name only"), "Request body should contain the preset name")
            // The encoder drops defaulted properties, so "no model"/"no settings profile" is sent as
            // an absent field — which the server decodes back to null and therefore clears.
            assertTrue(!body.contains("modelId"), "Body should omit a null modelId: $body")
            assertTrue(!body.contains("modelSettingsId"), "Body should omit a null modelSettingsId: $body")
            respond(
                content = json.encodeToString(created),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.createPreset(request)) {
            is Either.Right -> {
                assertNull(result.value.modelId)
                assertNull(result.value.modelSettingsId)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `createPreset - failure - 409 Conflict`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.CONFLICT, "Preset name already exists")),
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.createPreset(CreateModelPresetRequest(name = "primary"))) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(409, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.CONFLICT.code, error.apiError.code)
            }
        }
    }

    // --- updatePreset ---

    @Test
    fun `updatePreset - success`() = runTest {
        val request = UpdateModelPresetRequest(
            name = "primary v2",
            modelId = 1L,
            modelSettingsId = 3L
        )
        val updated = mockPreset(10, "primary v2", modelSettingsId = 3L)
        val mockEngine = MockEngine { mockRequest ->
            assertEquals(HttpMethod.Put, mockRequest.method)
            assertEquals(href(ModelPresetResource.ById(presetId = 10L)), mockRequest.url.fullPath)
            val body = mockRequest.body.toByteArray().decodeToString()
            assertTrue(body.contains("primary v2"), "Request body should contain the new name")
            respond(
                content = json.encodeToString(updated),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updatePreset(10L, request)) {
            is Either.Right -> {
                assertEquals("primary v2", result.value.name)
                assertEquals(3L, result.value.modelSettingsId)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updatePreset - failure - 404 Not Found`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Preset not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updatePreset(999L, UpdateModelPresetRequest(name = "X"))) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
            }
        }
    }

    // --- deletePreset ---

    @Test
    fun `deletePreset - success decodes the empty 204 body`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals(href(ModelPresetResource.ById(presetId = 10L)), request.url.fullPath)
            respond(content = "", status = HttpStatusCode.NoContent)
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deletePreset(10L)) {
            is Either.Right -> assertEquals(Unit, result.value)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `deletePreset - failure - 404 Not Found`() = runTest {
        val mockEngine = MockEngine { _ ->
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Preset not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deletePreset(999L)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
            }
        }
    }
}
