package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.LocalMCPServerApi
import eu.torvian.chatbot.app.service.security.RequestSigningService
import eu.torvian.chatbot.common.api.resources.LocalMCPServerResource
import eu.torvian.chatbot.common.models.api.mcp.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import kotlinx.serialization.json.Json

/**
 * Ktor HttpClient implementation of the [LocalMCPServerApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's Local MCP Server endpoints, mapping responses
 * to [Either<ApiResourceError, T>]. Signed create/update calls reuse the generic detached-signing
 * helpers from [SignedJsonApiResourceClient].
 *
 * @param client Ktor HTTP client used for transport.
 * @param json JSON codec used to serialize signed create/update request bodies.
 * @param requestSigningService Detached request-signing service used for selected MCP mutation calls.
 */
class KtorLocalMCPServerApiClient(
    client: HttpClient,
    json: Json,
    requestSigningService: RequestSigningService
) : SignedJsonApiResourceClient(client, json, requestSigningService), LocalMCPServerApi {

    override suspend fun createServer(request: CreateLocalMCPServerRequest): Either<ApiResourceError, LocalMCPServerDto> =
        safeSignedJsonApiCall(request, CreateLocalMCPServerRequest.serializer()) { signedRequest ->
            client.post(LocalMCPServerResource()) {
                applyDetachedSignedJsonBody(signedRequest)
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
        safeSignedJsonApiCall(request, UpdateLocalMCPServerRequest.serializer()) { signedRequest ->
            client.put(LocalMCPServerResource.ById(id = serverId)) {
                applyDetachedSignedJsonBody(signedRequest)
            }.body<LocalMCPServerDto>()
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

    override suspend fun listRuntimeStatuses(): Either<ApiResourceError, List<LocalMcpServerRuntimeStatusDto>> = safeApiCall {
        client.get(LocalMCPServerResource.RuntimeStatuses()).body<List<LocalMcpServerRuntimeStatusDto>>()
    }

    override suspend fun getRuntimeStatus(
        serverId: Long
    ): Either<ApiResourceError, LocalMcpServerRuntimeStatusDto> = safeApiCall {
        client.get(LocalMCPServerResource.ById.RuntimeStatus(parent = byId(serverId))).body<LocalMcpServerRuntimeStatusDto>()
    }

    override suspend fun testDraftConnection(request: TestLocalMCPServerDraftConnectionRequest): Either<ApiResourceError, TestLocalMCPServerConnectionResponse> =
        safeSignedJsonApiCall(request, TestLocalMCPServerDraftConnectionRequest.serializer()) { signedRequest ->
            client.post(LocalMCPServerResource.TestDraftConnection()) {
                applyDetachedSignedJsonBody(signedRequest)
            }.body<TestLocalMCPServerConnectionResponse>()
        }

    /**
     * Builds the typed by-id resource once for nested runtime-control resources.
     *
     * @param serverId Identifier of the target Local MCP server.
     * @return By-id resource that anchors nested runtime-control resources.
     */
    private fun byId(serverId: Long): LocalMCPServerResource.ById = LocalMCPServerResource.ById(id = serverId)
}
