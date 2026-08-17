package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.OperatorToolApi
import eu.torvian.chatbot.common.api.resources.OperatorToolResource
import eu.torvian.chatbot.common.models.tool.OperatorToolDefinition
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [OperatorToolApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's Operator Tool endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorOperatorToolApiClient(client: HttpClient) : BaseApiResourceClient(client), OperatorToolApi {

    override suspend fun getOperatorTools(): Either<ApiResourceError, List<OperatorToolDefinition>> {
        return safeApiCall {
            client.get(OperatorToolResource()).body()
        }
    }

    override suspend fun updateOperatorTool(
        tool: OperatorToolDefinition
    ): Either<ApiResourceError, OperatorToolDefinition> {
        return safeApiCall {
            client.put(OperatorToolResource.ById(toolId = tool.id)) {
                setBody(tool)
            }.body()
        }
    }

    override suspend fun resetOperatorToolsToDefaults(): Either<ApiResourceError, List<OperatorToolDefinition>> {
        return safeApiCall {
            client.post(OperatorToolResource.Reset()).body()
        }
    }
}
