package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ProviderApi
import eu.torvian.chatbot.common.api.resources.ProviderResource
import eu.torvian.chatbot.common.api.resources.ProviderResource.ById
import eu.torvian.chatbot.common.api.resources.ProviderResource.ById.Credential
import eu.torvian.chatbot.common.api.resources.ProviderResource.ById.Models
import eu.torvian.chatbot.common.models.api.llm.AddProviderRequest
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.common.models.llm.LLMProvider
import eu.torvian.chatbot.common.models.api.llm.UpdateProviderCredentialRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*

/**
 * Ktor HttpClient implementation of the [ProviderApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's provider endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorProviderApiClient(client: HttpClient) : BaseApiResourceClient(client), ProviderApi {

    override suspend fun getAllProviders(): Either<ApiResourceError, List<LLMProvider>> {
        return safeApiCall {
            client.get(ProviderResource()).body<List<LLMProvider>>()
        }
    }

    override suspend fun addProvider(request: AddProviderRequest): Either<ApiResourceError, LLMProvider> {
        return safeApiCall {
            client.post(ProviderResource()) {
                setBody(request)
            }.body<LLMProvider>()
        }
    }

    override suspend fun getProviderById(providerId: Long): Either<ApiResourceError, LLMProvider> {
        return safeApiCall {
            client.get(ById(providerId = providerId)).body<LLMProvider>()
        }
    }

    override suspend fun updateProvider(provider: LLMProvider): Either<ApiResourceError, Unit> {
        return safeApiCall {
            // Note: OpenAPI specifies the body is the full LLMProvider object,
            // and the path ID must match the body ID.
            // The backend service should handle updating only the allowed fields.
            client.put(ById(providerId = provider.id)) {
                setBody(provider)
            }.body<Unit>()
        }
    }

    override suspend fun deleteProvider(providerId: Long): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.delete(ById(providerId = providerId)).body<Unit>()
        }
    }

    override suspend fun updateProviderCredential(
        providerId: Long,
        request: UpdateProviderCredentialRequest
    ): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.put(Credential(ById(providerId = providerId))) {
                setBody(request)
            }.body<Unit>()
        }
    }

    override suspend fun getModelsByProviderId(providerId: Long): Either<ApiResourceError, List<LLMModel>> {
        return safeApiCall {
            client.get(Models(ById(providerId = providerId))).body<List<LLMModel>>()
        }
    }
}