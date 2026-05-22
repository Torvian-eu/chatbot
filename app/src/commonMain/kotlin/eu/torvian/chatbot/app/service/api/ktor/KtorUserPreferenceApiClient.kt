package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.UserPreferenceApi
import eu.torvian.chatbot.common.api.resources.MeResource
import eu.torvian.chatbot.common.models.api.me.PreferenceDetailDTO
import eu.torvian.chatbot.common.models.api.me.UserPreferenceDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [UserPreferenceApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's /api/v1/me preferences endpoints.
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorUserPreferenceApiClient(client: HttpClient) : BaseApiResourceClient(client), UserPreferenceApi {

    override suspend fun getPreferences(): Either<ApiResourceError, Map<String, String>> {
        return safeApiCall {
            client.get(MeResource.Preferences()).body<Map<String, String>>()
        }
    }

    override suspend fun getDetailedPreferences(): Either<ApiResourceError, Map<String, PreferenceDetailDTO>> {
        return safeApiCall {
            client.get(MeResource.Preferences.Details()).body<Map<String, PreferenceDetailDTO>>()
        }
    }

    override suspend fun updatePreference(
        key: String,
        dto: UserPreferenceDTO
    ): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.put(MeResource.Preferences.ByKey(key = key)) {
                setBody(dto)
            }.body<Unit>()
        }
    }

    override suspend fun deletePreference(
        key: String,
        scope: PreferenceScope
    ): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.delete(MeResource.Preferences.ByKey(key = key, scope = scope)).body<Unit>()
        }
    }
}
