package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.BuiltInToolApi
import eu.torvian.chatbot.common.api.resources.BuiltInToolResource
import eu.torvian.chatbot.common.models.tool.BuiltInWorkerToolDefinition
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [BuiltInToolApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's Built-in Worker Tool endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorBuiltInToolApiClient(client: HttpClient) : BaseApiResourceClient(client), BuiltInToolApi {

    override suspend fun getBuiltInToolsForWorker(workerId: Long): Either<ApiResourceError, List<BuiltInWorkerToolDefinition>> {
        return safeApiCall {
            client.get(BuiltInToolResource.ByWorkerId(workerId = workerId)).body()
        }
    }

    override suspend fun getBuiltInToolById(toolId: Long): Either<ApiResourceError, BuiltInWorkerToolDefinition> {
        return safeApiCall {
            client.get(BuiltInToolResource.ById(toolId = toolId)).body()
        }
    }

    override suspend fun updateBuiltInTool(
        tool: BuiltInWorkerToolDefinition
    ): Either<ApiResourceError, BuiltInWorkerToolDefinition> {
        return safeApiCall {
            client.put(BuiltInToolResource.ById(toolId = tool.id)) {
                setBody(tool)
            }.body()
        }
    }

    override suspend fun resetBuiltInToolsToDefaults(
        workerId: Long
    ): Either<ApiResourceError, List<BuiltInWorkerToolDefinition>> {
        return safeApiCall {
            client.post(BuiltInToolResource.ResetByWorkerId(workerId = workerId)).body()
        }
    }
}
