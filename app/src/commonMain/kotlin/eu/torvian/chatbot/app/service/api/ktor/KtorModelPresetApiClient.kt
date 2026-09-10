package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ModelPresetApi
import eu.torvian.chatbot.common.api.resources.ModelPresetResource
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.common.models.llm.ModelPresetDto
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor-based implementation of [ModelPresetApi] for the user-owned model-preset endpoints.
 *
 * Uses Ktor Resources for type-safe URL construction (`/api/v1/model-presets`,
 * `/api/v1/model-presets/{presetId}`) and wraps every request in [BaseApiResourceClient.safeApiCall]
 * so failures surface as [ApiResourceError] values instead of exceptions.
 *
 * @property httpClient The authenticated Ktor [HttpClient] used for all requests.
 */
class KtorModelPresetApiClient(
    httpClient: HttpClient
) : BaseApiResourceClient(httpClient), ModelPresetApi {

    override suspend fun getAllPresets(): Either<ApiResourceError, List<ModelPresetDto>> =
        safeApiCall {
            client.get(ModelPresetResource()).body<List<ModelPresetDto>>()
        }

    override suspend fun getPresetById(presetId: Long): Either<ApiResourceError, ModelPresetDto> =
        safeApiCall {
            client.get(ModelPresetResource.ById(presetId = presetId)).body<ModelPresetDto>()
        }

    override suspend fun createPreset(request: CreateModelPresetRequest): Either<ApiResourceError, ModelPresetDto> =
        safeApiCall {
            client.post(ModelPresetResource()) {
                setBody(request)
            }.body<ModelPresetDto>()
        }

    override suspend fun updatePreset(
        presetId: Long,
        request: UpdateModelPresetRequest
    ): Either<ApiResourceError, ModelPresetDto> =
        safeApiCall {
            client.put(ModelPresetResource.ById(presetId = presetId)) {
                setBody(request)
            }.body<ModelPresetDto>()
        }

    override suspend fun deletePreset(presetId: Long): Either<ApiResourceError, Unit> =
        safeApiCall {
            // The endpoint answers 204 with an empty body, so the response has nothing to decode.
            client.delete(ModelPresetResource.ById(presetId = presetId))
        }
}
