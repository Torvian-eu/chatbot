package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.LocalMCPServerApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPEnvironmentVariableDto
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Clock

/**
 * Tests server-owned CRUD behavior of [KtorLocalMCPServerApiClient].
 */
class KtorLocalMCPServerApiClientTest {
    private val json = Json { prettyPrint = true }

    /**
     * Builds an API client backed by the provided [mockEngine].
     *
     * @param mockEngine Ktor mock engine used to assert and return HTTP responses.
     * @return Configured [LocalMCPServerApi] instance.
     */
    private fun createTestClient(mockEngine: MockEngine): LocalMCPServerApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorLocalMCPServerApiClient(httpClient)
    }

    /**
     * Shared fixture used by the CRUD tests.
     */
    private val now = Clock.System.now()

    /**
     * Creates a representative Local MCP server DTO fixture.
     *
     * @param id Server identifier to set in the fixture.
     * @return A DTO that includes regular and secret env vars.
     */
    private fun dto(id: Long): LocalMCPServerDto = LocalMCPServerDto(
        id = id,
        userId = 10L,
        workerId = 99L,
        name = "Filesystem",
        description = "File access tools",
        command = "npx",
        arguments = listOf("-y", "@modelcontextprotocol/server-filesystem", "C:/data"),
        workingDirectory = "C:/data",
        isEnabled = true,
        autoStartOnEnable = true,
        autoStartOnLaunch = false,
        autoStopAfterInactivitySeconds = 300,
        toolNamePrefix = "fs_",
        environmentVariables = listOf(LocalMCPEnvironmentVariableDto("API_URL", "https://example.test")),
        secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("TOKEN", "secret-token")),
        createdAt = now,
        updatedAt = now
    )

    @Test
    fun `getServers - success preserves regular and secret env vars`() = runTest {
        val responsePayload = listOf(dto(1L))
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals(href(LocalMCPServerResource()), request.url.encodedPath)
            respond(
                content = json.encodeToString(responsePayload),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val api = createTestClient(mockEngine)
        when (val result = api.getServers()) {
            is Either.Left -> fail("Expected success but got ${result.value}")
            is Either.Right -> {
                val server = result.value.single()
                assertEquals(99L, server.workerId)
                assertEquals("https://example.test", server.environmentVariables.find { it.key == "API_URL" }?.value)
                assertEquals("secret-token", server.secretEnvironmentVariables.find { it.key == "TOKEN" }?.value)
            }
        }
    }

    @Test
    fun `createServer - success sends root endpoint and returns canonical payload`() = runTest {
        val created = dto(55L)
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(href(LocalMCPServerResource()), request.url.encodedPath)
            respond(
                content = json.encodeToString(created),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val api = createTestClient(mockEngine)
        val createRequest = CreateLocalMCPServerRequest(
            workerId = created.workerId,
            name = created.name,
            description = created.description,
            command = created.command,
            arguments = created.arguments,
            workingDirectory = created.workingDirectory,
            isEnabled = created.isEnabled,
            autoStartOnEnable = created.autoStartOnEnable,
            autoStartOnLaunch = created.autoStartOnLaunch,
            autoStopAfterInactivitySeconds = created.autoStopAfterInactivitySeconds,
            toolNamePrefix = created.toolNamePrefix,
            environmentVariables = created.environmentVariables,
            secretEnvironmentVariables = created.secretEnvironmentVariables
        )
        when (val result = api.createServer(createRequest)) {
            is Either.Left -> fail("Expected success but got ${result.value}")
            is Either.Right -> assertEquals(55L, result.value.id)
        }
    }

    @Test
    fun `updateServer - server error maps to ApiResourceError_ServerError`() = runTest {
        val mockEngine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals(href(LocalMCPServerResource.ById(id = 42L)), request.url.encodedPath)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "MCP server not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val api = createTestClient(mockEngine)
        val updateRequest = UpdateLocalMCPServerRequest(
            workerId = 99L,
            name = "Updated",
            description = "Updated description",
            command = "npx",
            arguments = listOf("-y", "tool"),
            workingDirectory = "C:/data",
            isEnabled = true,
            autoStartOnEnable = true,
            autoStartOnLaunch = false,
            autoStopAfterInactivitySeconds = 300,
            toolNamePrefix = "fs_",
            environmentVariables = listOf(LocalMCPEnvironmentVariableDto("API_URL", "https://example.test")),
            secretEnvironmentVariables = listOf(LocalMCPEnvironmentVariableDto("TOKEN", "secret-token"))
        )
        when (val result = api.updateServer(serverId = 42L, request = updateRequest)) {
            is Either.Right -> fail("Expected failure but got ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
            }
        }
    }
}



