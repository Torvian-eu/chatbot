package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.LocalMCPServerApi
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.models.api.mcp.CreateLocalMCPServerRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.models.api.mcp.RefreshMCPToolsResponse
import eu.torvian.chatbot.common.models.api.mcp.TestLocalMCPServerConnectionResponse
import eu.torvian.chatbot.common.models.api.mcp.UpdateLocalMCPServerRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [LocalMCPServerApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's Local MCP Server endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorLocalMCPServerApiClient(client: HttpClient) : BaseApiResourceClient(client), LocalMCPServerApi {

    override suspend fun createServer(request: CreateLocalMCPServerRequest): Either<ApiResourceError, LocalMCPServerDto> =
        safeApiCall {
            client.post(LocalMCPServerResource()) {
                setBody(request)
            }.body<LocalMCPServerDto>()
        }

    override suspend fun getServers(): Either<ApiResourceError, List<LocalMCPServerDto>> {
        return safeApiCall {
            client.get(LocalMCPServerResource()).body<List<LocalMCPServerDto>>()
        }
    }

    override suspend fun getServerById(serverId: Long): Either<ApiResourceError, LocalMCPServerDto> =
        safeApiCall {
            client.get(LocalMCPServerResource.ById(id = serverId)).body<LocalMCPServerDto>()
        }

    override suspend fun updateServer(
        serverId: Long,
        request: UpdateLocalMCPServerRequest
    ): Either<ApiResourceError, LocalMCPServerDto> =
        safeApiCall {
            client.put(LocalMCPServerResource.ById(id = serverId)) { setBody(request) }
                .body<LocalMCPServerDto>()
        }

    override suspend fun deleteServer(serverId: Long): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.delete(LocalMCPServerResource.ById(id = serverId)).body<Unit>()
        }
    }

    override suspend fun startServer(serverId: Long): Either<ApiResourceError, Unit> = safeApiCall {
        client.post(LocalMCPServerResource.ById.Start(parent = byId(serverId))).body<Unit>()
    }

    override suspend fun stopServer(serverId: Long): Either<ApiResourceError, Unit> = safeApiCall {
        client.post(LocalMCPServerResource.ById.Stop(parent = byId(serverId))).body<Unit>()
    }

    override suspend fun testConnection(serverId: Long): Either<ApiResourceError, TestLocalMCPServerConnectionResponse> =
        safeApiCall {
            client.post(LocalMCPServerResource.ById.TestConnection(parent = byId(serverId)))
                .body<TestLocalMCPServerConnectionResponse>()
        }

    override suspend fun refreshTools(serverId: Long): Either<ApiResourceError, RefreshMCPToolsResponse> = safeApiCall {
        client.post(LocalMCPServerResource.ById.RefreshTools(parent = byId(serverId))).body<RefreshMCPToolsResponse>()
    }

    /**
     * Builds the typed by-id resource once for nested runtime-control resources.
     *
     * @param serverId Identifier of the target Local MCP server.
     * @return By-id resource that anchors nested runtime-control resources.
     */
    private fun byId(serverId: Long): LocalMCPServerResource.ById = LocalMCPServerResource.ById(id = serverId)
}

