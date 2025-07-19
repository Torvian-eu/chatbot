package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.resources.ModelResource
import eu.torvian.chatbot.common.api.resources.ModelResource.ById.Settings
import eu.torvian.chatbot.common.api.resources.SettingsResource.ById
import eu.torvian.chatbot.common.models.AddModelSettingsRequest
import eu.torvian.chatbot.common.models.ModelSettings
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [SettingsApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiClient.safeApiCall] helper
 * to interact with the backend's settings endpoints, mapping responses
 * to [Either<ApiError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorSettingsApiClient(client: HttpClient) : BaseApiClient(client), SettingsApi {

    override suspend fun getSettingsByModelId(modelId: Long): Either<ApiError, List<ModelSettings>> {
        return safeApiCall {
            client.get(Settings(ModelResource.ById(modelId = modelId))).body<List<ModelSettings>>()
        }
    }

    override suspend fun addModelSettings(
        modelId: Long,
        request: AddModelSettingsRequest
    ): Either<ApiError, ModelSettings> {
        return safeApiCall {
            client.post(Settings(ModelResource.ById(modelId = modelId))) {
                setBody(request)
            }.body<ModelSettings>()
        }
    }

    override suspend fun getSettingsById(settingsId: Long): Either<ApiError, ModelSettings> {
        return safeApiCall {
            client.get(ById(settingsId = settingsId)).body<ModelSettings>()
        }
    }

    override suspend fun updateSettings(settings: ModelSettings): Either<ApiError, Unit> {
        return safeApiCall {
            // Note: OpenAPI specifies the body is the full ModelSettings object,
            // and the path ID must match the body ID.
            // The backend service should handle updating only the allowed fields.
            client.put(ById(settingsId = settings.id)) {
                setBody(settings)
            }.body<Unit>()
        }
    }

    override suspend fun deleteSettings(settingsId: Long): Either<ApiError, Unit> {
        return safeApiCall {
            client.delete(ById(settingsId = settingsId)).body<Unit>()
        }
    }
}