package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.LocalMCPServerApi
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.models.api.mcp.CreateServerRequest
import eu.torvian.chatbot.common.models.api.mcp.CreateServerResponse
import eu.torvian.chatbot.common.models.api.mcp.ServerIdsResponse
import eu.torvian.chatbot.common.models.api.mcp.SetServerEnabledRequest
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

    override suspend fun createServer(isEnabled: Boolean): Either<ApiResourceError, CreateServerResponse> =
        safeApiCall {
            client.post(LocalMCPServerResource.Create()) {
                setBody(CreateServerRequest(isEnabled))
            }.body<CreateServerResponse>()
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

    override suspend fun setServerEnabled(serverId: Long, isEnabled: Boolean): Either<ApiResourceError, Unit> =
        safeApiCall {
            client.put(LocalMCPServerResource.ById.SetEnabled(
                parent = LocalMCPServerResource.ById(id = serverId)
            )) {
                setBody(SetServerEnabledRequest(isEnabled))
            }.body<Unit>()
        }
}

