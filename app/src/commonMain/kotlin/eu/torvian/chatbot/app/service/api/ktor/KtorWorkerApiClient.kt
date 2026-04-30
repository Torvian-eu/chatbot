package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.WorkerApi
import eu.torvian.chatbot.common.api.resources.WorkerResource
import eu.torvian.chatbot.common.models.api.worker.UpdateWorkerRequest
import eu.torvian.chatbot.common.models.worker.WorkerDto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor-based implementation of [WorkerApi] for communicating with worker management endpoints.
 *
 * This client uses Ktor's Resources plugin for type-safe HTTP requests and provides
 * comprehensive error handling using Arrow's Either type. All requests are authenticated
 * using JWT tokens managed by the HttpClient configuration.
 *
 * @param httpClient The Ktor HttpClient instance to use for making API calls.
 */
class KtorWorkerApiClient(
    httpClient: HttpClient
) : BaseApiResourceClient(httpClient), WorkerApi {

    override suspend fun getMyWorkers(): Either<ApiResourceError, List<WorkerDto>> =
        safeApiCall {
            client.get(WorkerResource()).body<List<WorkerDto>>()
        }

    override suspend fun updateWorker(
        id: Long,
        displayName: String,
        allowedScopes: List<String>
    ): Either<ApiResourceError, WorkerDto> =
        safeApiCall {
            client.patch(WorkerResource.Id(id = id)) {
                setBody(UpdateWorkerRequest(displayName = displayName, allowedScopes = allowedScopes))
            }.body<WorkerDto>()
        }

    override suspend fun deleteWorker(id: Long): Either<ApiResourceError, Unit> =
        safeApiCall {
            client.delete(WorkerResource.Id(id = id)).body<Unit>()
        }
}
