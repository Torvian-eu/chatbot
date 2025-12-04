package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.LocalMCPToolApi
import eu.torvian.chatbot.common.api.resources.LocalMCPToolResource
import eu.torvian.chatbot.common.models.api.mcp.CreateMCPToolsRequest
import eu.torvian.chatbot.common.models.api.mcp.CreateMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.DeleteMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsRequest
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.tool.LocalMCPToolDefinition
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [LocalMCPToolApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's Local MCP Tool endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorLocalMCPToolApiClient(client: HttpClient) : BaseApiResourceClient(client), LocalMCPToolApi {

    override suspend fun getAllMCPTools(): Either<ApiResourceError, List<LocalMCPToolDefinition>> {
        return safeApiCall {
            client.get(LocalMCPToolResource()).body<List<LocalMCPToolDefinition>>()
        }
    }

    override suspend fun createMCPToolsForServer(
        serverId: Long,
        tools: List<LocalMCPToolDefinition>
    ): Either<ApiResourceError, List<LocalMCPToolDefinition>> {
        val request = CreateMCPToolsRequest(serverId = serverId, tools = tools)
        return safeApiCall {
            client.post(LocalMCPToolResource.Batch()) {
                setBody(request)
            }.body<CreateMCPToolsResponse>().tools
        }
    }

    override suspend fun refreshMCPToolsForServer(
        serverId: Long,
        currentTools: List<LocalMCPToolDefinition>
    ): Either<ApiResourceError, RefreshMCPToolsResponse> {
        val request = RefreshMCPToolsRequest(serverId = serverId, currentTools = currentTools)
        return safeApiCall {
            client.post(LocalMCPToolResource.Refresh()) {
                setBody(request)
            }.body<RefreshMCPToolsResponse>()
        }
    }

    override suspend fun getMCPToolsForServer(serverId: Long): Either<ApiResourceError, List<LocalMCPToolDefinition>> {
        return safeApiCall {
            client.get(LocalMCPToolResource.ByServerId(serverId = serverId)).body<List<LocalMCPToolDefinition>>()
        }
    }

    override suspend fun getMCPToolById(toolId: Long): Either<ApiResourceError, LocalMCPToolDefinition> {
        return safeApiCall {
            client.get(LocalMCPToolResource.ById(toolId = toolId)).body<LocalMCPToolDefinition>()
        }
    }

    override suspend fun updateMCPTool(
        tool: LocalMCPToolDefinition
    ): Either<ApiResourceError, LocalMCPToolDefinition> {
        return safeApiCall {
            client.put(LocalMCPToolResource.ById(toolId = tool.id)) {
                setBody(tool)
            }.body<LocalMCPToolDefinition>()
        }
    }

    override suspend fun deleteMCPToolsForServer(serverId: Long): Either<ApiResourceError, Int> {
        return safeApiCall {
            client.delete(LocalMCPToolResource.ByServerId(serverId = serverId)).body<DeleteMCPToolsResponse>().count
        }
    }
}

