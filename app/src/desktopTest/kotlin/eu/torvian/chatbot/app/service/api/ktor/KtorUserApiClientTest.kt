package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.UserApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.UserResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.admin.CreateUserRequest
import eu.torvian.chatbot.common.models.user.User
import eu.torvian.chatbot.common.models.user.UserStatus
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock

/**
 * Mock-engine tests for the admin user-creation API call.
 *
 * Verifies the request contract (method, path, body with the password-change flag) and the
 * response handling for both success and server-error passthrough.
 */
class KtorUserApiClientTest {
    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): UserApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorUserApiClient(httpClient)
    }

    // --- Helper for creating mock data ---
    private val now = Clock.System.now()
    private fun mockUser(id: Long, username: String, requiresPasswordChange: Boolean) = User(
        id = id,
        username = username,
        email = "$username@example.com",
        status = UserStatus.ACTIVE,
        createdAt = now,
        lastLogin = null,
        requiresPasswordChange = requiresPasswordChange
    )

    // --- Tests for createUser ---

    @Test
    fun `createUser - success sends password change flag and parses created user`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(UserResource()), request.url.fullPath)
            val requestBody = request.body.toByteArray().decodeToString()
            assertTrue(requestBody.contains("newuser"), "Request body should contain username")
            // Defaulted properties are omitted by the encoder; decoding restores the effective
            // contract, which the server-side default completes
            val sentRequest = json.decodeFromString(CreateUserRequest.serializer(), requestBody)
            assertEquals(true, sentRequest.requiresPasswordChange)
            respond(
                content = json.encodeToString(mockUser(1, "newuser", requiresPasswordChange = true)),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)

        val request = CreateUserRequest(
            username = "newuser",
            password = "Gsfaf^3gd",
            email = "newuser@example.com"
        )

        when (val result = apiClient.createUser(request)) {
            is Either.Right -> {
                val user = result.value
                assertEquals(1, user.id)
                assertEquals("newuser", user.username)
                assertEquals(UserStatus.ACTIVE, user.status)
                assertTrue(user.requiresPasswordChange)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `createUser - explicit requiresPasswordChange false is forwarded`() = runTest {
        val mockEngine = MockEngine { request ->
            val requestBody = request.body.toByteArray().decodeToString()
            val sentRequest = json.decodeFromString(CreateUserRequest.serializer(), requestBody)
            assertEquals(false, sentRequest.requiresPasswordChange)
            respond(
                content = json.encodeToString(mockUser(1, "newuser", requiresPasswordChange = false)),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)

        val request = CreateUserRequest(
            username = "newuser",
            password = "Gsfaf^3gd",
            requiresPasswordChange = false
        )

        when (val result = apiClient.createUser(request)) {
            is Either.Right -> assertEquals(false, result.value.requiresPasswordChange)
            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `createUser - failure - 409 Conflict passthrough`() = runTest {
        val mockEngine = MockEngine { request ->
            respond(
                content = json.encodeToString(
                    apiError(
                        CommonApiErrorCodes.ALREADY_EXISTS,
                        "Username already exists",
                        "field" to "username",
                        "username" to "newuser"
                    )
                ),
                status = HttpStatusCode.Conflict,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val apiClient = createTestClient(mockEngine)

        val request = CreateUserRequest(username = "newuser", password = "Gsfaf^3gd")

        when (val result = apiClient.createUser(request)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(409, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.ALREADY_EXISTS.code, error.apiError.code)
                assertEquals("username", error.apiError.details?.get("field"))
            }
        }
    }
}
