package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ChatApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.MessageResource
import eu.torvian.chatbot.common.api.resources.SessionResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.core.ChatMessage
import eu.torvian.chatbot.common.models.api.core.ProcessNewMessageRequest
import eu.torvian.chatbot.common.models.api.core.UpdateMessageRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class KtorChatApiClientTest {

    private val json = Json {
        prettyPrint = true
    }

    /**
     * Helper function to create a test client with a mock engine.
     */
    private fun createTestClient(mockEngine: MockEngine): ChatApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorChatApiClient(httpClient)
    }

    @Test
    fun `processNewMessage - success`() = runTest {
        val now = Clock.System.now()
        val mockEngine = MockEngine { request ->
            // Assert the request details (URL, method, body, etc.)
            assertEquals(HttpMethod.Post, request.method)
            // Ensure the request matches the session/messages resource
            assertEquals(
                href(SessionResource.ById.Messages(SessionResource.ById(sessionId = 123))),
                request.url.fullPath
            )
            // Deserialize and assert the request body (if needed)
            val requestBody = request.body.toByteArray().decodeToString()
            val processNewMessageRequest = json.decodeFromString<ProcessNewMessageRequest>(requestBody)
            assertEquals("Hello", processNewMessageRequest.content)
            assertEquals(null, processNewMessageRequest.parentMessageId)
            // Simulate a successful response
            val response = listOf(
                ChatMessage.UserMessage(1, 123, "Hello", now, now, null, emptyList()),
                ChatMessage.AssistantMessage(2, 123, "Hi there!", now, now, 1, emptyList(), 1, 2)
            )
            respond(
                content = json.encodeToString(response),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.processNewMessage(123, ProcessNewMessageRequest("Hello"))
        when (result) {
            is Either.Right -> {
                val messages = result.value
                assertEquals(2, messages.size)
                assertEquals("Hello", messages[0].content)
                assertEquals("Hi there!", messages[1].content)
            }

            is Either.Left -> {
                fail("Expected success, but got error: ${result.value}")
            }
        }
    }

    @Test
    fun `processNewMessage - failure - 400 Bad Request`() = runTest {
        val mockEngine = MockEngine { request ->
            // Assert request details (URL, method) - same as success
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                href(SessionResource.ById.Messages(SessionResource.ById(SessionResource(), 123))),
                request.url.fullPath
            )

            // Simulate a 400 Bad Request error
            respond(
                content = json.encodeToString(
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Invalid message content"
                    )
                ),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)

        val result = apiClient.processNewMessage(123, ProcessNewMessageRequest("Bad Content")) // Test case
        when (result) {
            is Either.Right -> {
                fail("Expected failure, but got success: ${result.value}")
            }

            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
                assertEquals("Invalid message content", error.apiError.message)
            }
        }
    }

    @Test
    fun `processNewMessage - failure - 500 Internal Server Error`() = runTest {
        val mockEngine = MockEngine { request ->
            // Assert request details (URL, method)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                href(SessionResource.ById.Messages(SessionResource.ById(SessionResource(), 123))),
                request.url.fullPath
            )

            // Simulate a 500 Internal Server Error
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INTERNAL, "Internal Server Error")),
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)

        val result = apiClient.processNewMessage(123, ProcessNewMessageRequest("Some Content"))
        when (result) {
            is Either.Right -> {
                fail("Expected failure, but got success: ${result.value}")
            }

            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(500, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INTERNAL.code, error.apiError.code)
                assertEquals("Internal Server Error", error.apiError.message)
            }
        }
    }

    @Test
    fun `processNewMessage - failure - SerializationException`() = runTest {
        val mockEngine = MockEngine { request ->
            // Assert request details (URL, method) - same as success
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(
                href(SessionResource.ById.Messages(SessionResource.ById(SessionResource(), 123))),
                request.url.fullPath
            )

            // Simulate a 400 Bad Request error
            respond(
                content = """{"invalid_json": "oops"}""", // bad content
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)

        val result = apiClient.processNewMessage(123, ProcessNewMessageRequest("Bad Content")) // Test case
        when (result) {
            is Either.Right -> {
                fail("Expected failure, but got success: ${result.value}")
            }

            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
                assertTrue(error.description.contains("Failed to parse API response"))
            }
        }
    }

    @Test
    fun `updateMessageContent - success`() = runTest {
        val now = Clock.System.now()
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(MessageResource.ById.Content(MessageResource.ById(MessageResource(), 456))),
                request.url.fullPath
            )
            val requestBody = request.body.toByteArray().decodeToString()
            val updateMessageRequest =
                json.decodeFromString<UpdateMessageRequest>(requestBody)
            assertEquals("Updated content", updateMessageRequest.content)
            val response = ChatMessage.UserMessage(456, 123, "Updated content", now, now, null, emptyList())
            respond(
                content = json.encodeToString<ChatMessage>(response),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result = apiClient.updateMessageContent(
            456,
            UpdateMessageRequest("Updated content")
        )
        when (result) {
            is Either.Right -> {
                val message = result.value
                assertEquals(456, message.id)
                assertEquals("Updated content", message.content)
            }

            is Either.Left -> {
                fail("Expected success, but got error: ${result.value}")
            }
        }
    }

    @Test
    fun `updateMessageContent - failure - 400`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(
                href(MessageResource.ById.Content(MessageResource.ById(messageId = 456))),
                request.url.fullPath
            )
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid content")),
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)
        val result =
            apiClient.updateMessageContent(456, UpdateMessageRequest("Bad content"))
        when (result) {
            is Either.Right -> {
                fail("Expected failure, but got success: ${result.value}")
            }

            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(400, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.INVALID_ARGUMENT.code, error.apiError.code)
                assertEquals("Invalid content", error.apiError.message)
            }
        }
    }
}