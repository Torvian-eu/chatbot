package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.SettingsApi
import eu.torvian.chatbot.common.api.resources.ModelResource
import eu.torvian.chatbot.common.api.resources.ModelResource.ById.Settings
import eu.torvian.chatbot.common.api.resources.SettingsResource
import eu.torvian.chatbot.common.api.resources.SettingsResource.ById
import eu.torvian.chatbot.common.models.ModelSettings
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [SettingsApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's settings endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorSettingsApiClient(client: HttpClient) : BaseApiResourceClient(client), SettingsApi {

    override suspend fun getSettingsByModelId(modelId: Long): Either<ApiResourceError, List<ModelSettings>> {
        return safeApiCall {
            client.get(Settings(ModelResource.ById(modelId = modelId))).body<List<ModelSettings>>()
        }
    }

    override suspend fun addModelSettings(settings: ModelSettings): Either<ApiResourceError, ModelSettings> {
        return safeApiCall {
            client.post(SettingsResource()) {
                setBody(settings)
            }.body<ModelSettings>()
        }
    }

    override suspend fun getSettingsById(settingsId: Long): Either<ApiResourceError, ModelSettings> {
        return safeApiCall {
            client.get(ById(settingsId = settingsId)).body<ModelSettings>()
        }
    }

    override suspend fun updateSettings(settings: ModelSettings): Either<ApiResourceError, Unit> {
        return safeApiCall {
            // Note: OpenAPI specifies the body is the full ModelSettings object,
            // and the path ID must match the body ID.
            // The backend service should handle updating only the allowed fields.
            client.put(ById(settingsId = settings.id)) {
                setBody(settings)
            }.body<Unit>()
        }
    }

    override suspend fun deleteSettings(settingsId: Long): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.delete(ById(settingsId = settingsId)).body<Unit>()
        }
    }

    override suspend fun getAllSettings(): Either<ApiResourceError, List<ModelSettings>> {
        return safeApiCall {
            client.get(SettingsResource()).body<List<ModelSettings>>()
        }
    }
}