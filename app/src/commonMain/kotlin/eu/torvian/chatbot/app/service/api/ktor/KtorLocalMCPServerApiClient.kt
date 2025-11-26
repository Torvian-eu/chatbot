package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.LocalMCPServerApi
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.models.api.mcp.GenerateServerIdResponse
import eu.torvian.chatbot.common.models.api.mcp.ServerIdsResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*

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

    override suspend fun generateServerId(): Either<ApiResourceError, GenerateServerIdResponse> {
        return safeApiCall {
            client.post(LocalMCPServerResource.GenerateId()).body<GenerateServerIdResponse>()
        }
    }

    override suspend fun getServerIds(): Either<ApiResourceError, ServerIdsResponse> {
        return safeApiCall {
            client.get(LocalMCPServerResource.Ids()).body<ServerIdsResponse>()
        }
    }

    override suspend fun deleteServerId(serverId: Long): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.delete(LocalMCPServerResource.ById(id = serverId)).body<Unit>()
        }
    }
}

