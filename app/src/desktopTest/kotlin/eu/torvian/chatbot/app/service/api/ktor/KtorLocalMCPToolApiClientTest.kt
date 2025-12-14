package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.LocalMCPToolApi
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.LocalMCPToolResource
import eu.torvian.chatbot.common.api.resources.href
import eu.torvian.chatbot.common.models.api.mcp.CreateMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.DeleteMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class KtorLocalMCPToolApiClientTest {
    private val json = Json {
        prettyPrint = true
    }

    private fun createTestClient(mockEngine: MockEngine): LocalMCPToolApi {
        val httpClient = HttpClient(mockEngine) {
            configureHttpClient("http://localhost", json)
        }
        return KtorLocalMCPToolApiClient(httpClient)
    }

    // --- Helper for creating mock data ---
    private val now = Clock.System.now()

    private fun mockMCPTool(
        id: Long,
        serverId: Long,
        name: String = "test_tool",
        description: String = "Test tool",
        mcpToolName: String = "test_tool"
    ) = LocalMCPToolDefinition(
        id = id,
        name = name,
        description = description,
        config = buildJsonObject { },
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { })
        },
        outputSchema = null,
        isEnabled = true,
        createdAt = now,
        updatedAt = now,
        serverId = serverId,
        mcpToolName = mcpToolName,
        isEnabledByDefault = null
    )

    // --- Tests for createMCPToolsForServer ---
    @Test
    fun `createMCPToolsForServer - success with multiple tools`() = runTest {
        val serverId = 123L
        val toolsToCreate = listOf(
            mockMCPTool(0, serverId, "tool1", "Tool 1", "mcp_tool1"),
            mockMCPTool(0, serverId, "tool2", "Tool 2", "mcp_tool2")
        )
        val createdTools = listOf(
            mockMCPTool(1, serverId, "tool1", "Tool 1", "mcp_tool1"),
            mockMCPTool(2, serverId, "tool2", "Tool 2", "mcp_tool2")
        )
        val response = CreateMCPToolsResponse(tools = createdTools)

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            assertEquals(href(LocalMCPToolResource.Batch()), req.url.fullPath)
            respond(
                content = json.encodeToString(CreateMCPToolsResponse.serializer(), response),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.createMCPToolsForServer(serverId, toolsToCreate)) {
            is Either.Right -> {
                assertEquals(2, result.value.size)
                assertEquals(1L, result.value[0].id)
                assertEquals("tool1", result.value[0].name)
                assertEquals(2L, result.value[1].id)
                assertEquals("tool2", result.value[1].name)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `createMCPToolsForServer - failure - 404 server not found`() = runTest {
        val serverId = 999L

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "MCP server not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.createMCPToolsForServer(serverId, emptyList())) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
            }
        }
    }

    // --- Tests for refreshMCPToolsForServer ---
    @Test
    fun `refreshMCPToolsForServer - success with adds, updates, and deletes`() = runTest {
        val serverId = 123L
        val currentTools = listOf(
            mockMCPTool(0, serverId, "tool1", "Tool 1 Updated"),
            mockMCPTool(0, serverId, "new_tool", "New Tool")
        )
        val addedTool = mockMCPTool(1, serverId, "new_tool", "New Tool")
        val updatedTool = mockMCPTool(2, serverId, "tool1", "Tool 1 Updated")
        val deletedTool = mockMCPTool(3, serverId, "old_tool", "Old Tool")
        val response = RefreshMCPToolsResponse(
            addedTools = listOf(addedTool),
            updatedTools = listOf(updatedTool),
            deletedTools = listOf(deletedTool)
        )

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            assertEquals(href(LocalMCPToolResource.Refresh()), req.url.fullPath)
            respond(
                content = json.encodeToString(RefreshMCPToolsResponse.serializer(), response),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.refreshMCPToolsForServer(serverId, currentTools)) {
            is Either.Right -> {
                assertEquals(1, result.value.addedTools.size)
                assertEquals(1, result.value.updatedTools.size)
                assertEquals(1, result.value.deletedTools.size)
                assertEquals(addedTool, result.value.addedTools.first())
                assertEquals(updatedTool, result.value.updatedTools.first())
                assertEquals(deletedTool, result.value.deletedTools.first())
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `refreshMCPToolsForServer - success with no changes`() = runTest {
        val serverId = 123L
        val response = RefreshMCPToolsResponse(
            addedTools = emptyList(),
            updatedTools = emptyList(),
            deletedTools = emptyList()
        )

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            respond(
                content = json.encodeToString(RefreshMCPToolsResponse.serializer(), response),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.refreshMCPToolsForServer(serverId, emptyList())) {
            is Either.Right -> {
                assertEquals(0, result.value.addedTools.size)
                assertEquals(0, result.value.updatedTools.size)
                assertEquals(0, result.value.deletedTools.size)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    // --- Tests for getMCPToolsForServer ---
    @Test
    fun `getMCPToolsForServer - success with multiple tools`() = runTest {
        val serverId = 123L
        val tools = listOf(
            mockMCPTool(1, serverId, "tool1", "Tool 1"),
            mockMCPTool(2, serverId, "tool2", "Tool 2")
        )

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method)
            assertEquals(href(LocalMCPToolResource.ByServerId(serverId = serverId)), req.url.fullPath)
            respond(
                content = json.encodeToString(tools),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getMCPToolsForServer(serverId)) {
            is Either.Right -> {
                assertEquals(2, result.value.size)
                assertEquals("tool1", result.value[0].name)
                assertEquals("tool2", result.value[1].name)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getMCPToolsForServer - success with empty list`() = runTest {
        val serverId = 123L

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method)
            respond(
                content = json.encodeToString(emptyList<LocalMCPToolDefinition>()),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getMCPToolsForServer(serverId)) {
            is Either.Right -> {
                assertEquals(0, result.value.size)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    // --- Tests for getMCPToolById ---
    @Test
    fun `getMCPToolById - success`() = runTest {
        val toolId = 42L
        val tool = mockMCPTool(toolId, 123L, "test_tool", "Test Tool")

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method)
            assertEquals(href(LocalMCPToolResource.ById(toolId = toolId)), req.url.fullPath)
            respond(
                content = json.encodeToString(LocalMCPToolDefinition.serializer(), tool),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getMCPToolById(toolId)) {
            is Either.Right -> {
                assertEquals(toolId, result.value.id)
                assertEquals("test_tool", result.value.name)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `getMCPToolById - failure - 404 not found`() = runTest {
        val toolId = 999L

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Tool not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getMCPToolById(toolId)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
            }
        }
    }

    // --- Tests for updateMCPTool ---
    @Test
    fun `updateMCPTool - success`() = runTest {
        val toolId = 42L
        val updatedTool = mockMCPTool(toolId, 123L, "updated_tool", "Updated Tool")

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Put, req.method)
            assertEquals(href(LocalMCPToolResource.ById(toolId = toolId)), req.url.fullPath)
            respond(
                content = json.encodeToString(LocalMCPToolDefinition.serializer(), updatedTool),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateMCPTool(updatedTool)) {
            is Either.Right -> {
                assertEquals("updated_tool", result.value.name)
                assertEquals("Updated Tool", result.value.description)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `updateMCPTool - failure - 404 not found`() = runTest {
        val toolId = 999L
        val tool = mockMCPTool(toolId, 123L)

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Put, req.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "Tool not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.updateMCPTool(tool)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
            }
        }
    }

    // --- Tests for deleteMCPToolsForServer ---
    @Test
    fun `deleteMCPToolsForServer - success with multiple deletions`() = runTest {
        val serverId = 123L
        val response = DeleteMCPToolsResponse(count = 5)

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Delete, req.method)
            assertEquals(href(LocalMCPToolResource.ByServerId(serverId = serverId)), req.url.fullPath)
            respond(
                content = json.encodeToString(DeleteMCPToolsResponse.serializer(), response),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteMCPToolsForServer(serverId)) {
            is Either.Right -> {
                assertEquals(5, result.value)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `deleteMCPToolsForServer - success with no deletions`() = runTest {
        val serverId = 123L
        val response = DeleteMCPToolsResponse(count = 0)

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Delete, req.method)
            respond(
                content = json.encodeToString(DeleteMCPToolsResponse.serializer(), response),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteMCPToolsForServer(serverId)) {
            is Either.Right -> {
                assertEquals(0, result.value)
            }

            is Either.Left -> fail("Expected success, but got error: ${result.value}")
        }
    }

    @Test
    fun `deleteMCPToolsForServer - failure - 404 server not found`() = runTest {
        val serverId = 999L

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Delete, req.method)
            respond(
                content = json.encodeToString(apiError(CommonApiErrorCodes.NOT_FOUND, "MCP server not found")),
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.deleteMCPToolsForServer(serverId)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.ServerError
                assertEquals(404, error.apiError.statusCode)
                assertEquals(CommonApiErrorCodes.NOT_FOUND.code, error.apiError.code)
            }
        }
    }

    // --- Tests for serialization errors ---
    @Test
    fun `createMCPToolsForServer - failure - SerializationException`() = runTest {
        val serverId = 123L

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Post, req.method)
            respond(
                content = """{"invalid": "response"}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.createMCPToolsForServer(serverId, emptyList())) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
            }
        }
    }

    @Test
    fun `getMCPToolsForServer - failure - SerializationException`() = runTest {
        val serverId = 123L

        val mockEngine = MockEngine { req ->
            assertEquals(HttpMethod.Get, req.method)
            respond(
                content = """[{"invalid": "tool"}]""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val apiClient = createTestClient(mockEngine)
        when (val result = apiClient.getMCPToolsForServer(serverId)) {
            is Either.Right -> fail("Expected failure, but got success: ${result.value}")
            is Either.Left -> {
                val error = result.value as ApiResourceError.SerializationError
                assertTrue(error.message.contains("Serialization Error"))
            }
        }
    }
}

