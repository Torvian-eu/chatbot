package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ServerBuiltInToolApi
import eu.torvian.chatbot.common.api.resources.ServerBuiltInToolResource
import eu.torvian.chatbot.common.models.tool.ServerBuiltInToolDefinition
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [ServerBuiltInToolApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper to interact
 * with the backend's Server Built-In Tool endpoints, mapping responses to
 * [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorServerBuiltInToolApiClient(client: HttpClient) : BaseApiResourceClient(client), ServerBuiltInToolApi {

    override suspend fun getServerBuiltInTools(): Either<ApiResourceError, List<ServerBuiltInToolDefinition>> {
        return safeApiCall {
            client.get(ServerBuiltInToolResource()).body()
        }
    }

    override suspend fun updateServerBuiltInTool(
        tool: ServerBuiltInToolDefinition
    ): Either<ApiResourceError, ServerBuiltInToolDefinition> {
        return safeApiCall {
            client.put(ServerBuiltInToolResource.ById(toolId = tool.id)) {
                setBody(tool)
            }.body()
        }
    }

    override suspend fun resetServerBuiltInToolsToDefaults(): Either<ApiResourceError, List<ServerBuiltInToolDefinition>> {
        return safeApiCall {
            client.post(ServerBuiltInToolResource.Reset()).body()
        }
    }
}
