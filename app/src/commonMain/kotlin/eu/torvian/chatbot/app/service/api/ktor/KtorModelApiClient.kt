package eu.torvian.chatbot.app.service.api.ktor

import arrow.core.Either
import eu.torvian.chatbot.app.service.api.ApiResourceError
import eu.torvian.chatbot.app.service.api.ModelApi
import eu.torvian.chatbot.common.api.resources.ModelResource
import eu.torvian.chatbot.common.api.resources.ModelResource.ById
import eu.torvian.chatbot.common.api.resources.ModelResource.ById.ApiKeyStatus
import eu.torvian.chatbot.common.models.api.llm.AddModelRequest
import eu.torvian.chatbot.common.models.api.llm.ApiKeyStatusResponse
import eu.torvian.chatbot.common.models.llm.LLMModel
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.resources.*
import io.ktor.client.request.*
import eu.torvian.chatbot.common.api.resources.ModelResource.ById.Details
import eu.torvian.chatbot.common.api.resources.ModelResource.ById.Access
import eu.torvian.chatbot.common.api.resources.ModelResource.ById.MakePublic
import eu.torvian.chatbot.common.api.resources.ModelResource.ById.MakePrivate
import eu.torvian.chatbot.common.models.api.access.GrantAccessRequest
import eu.torvian.chatbot.common.models.api.access.RevokeAccessRequest
import eu.torvian.chatbot.common.models.api.access.LLMModelDetails

/**
 * Ktor HttpClient implementation of the [ModelApi] interface.
 *
 * Uses the configured [HttpClient] and the [BaseApiResourceClient.safeApiCall] helper
 * to interact with the backend's model endpoints, mapping responses
 * to [Either<ApiResourceError, T>].
 *
 * @property client The Ktor HttpClient instance injected for making requests.
 */
class KtorModelApiClient(client: HttpClient) : BaseApiResourceClient(client), ModelApi {

    override suspend fun getAllModels(): Either<ApiResourceError, List<LLMModel>> {
        return safeApiCall {
            client.get(ModelResource()).body<List<LLMModel>>()
        }
    }

    override suspend fun addModel(request: AddModelRequest): Either<ApiResourceError, LLMModel> {
        return safeApiCall {
            client.post(ModelResource()) {
                setBody(request)
            }.body<LLMModel>()
        }
    }

    override suspend fun getModelById(modelId: Long): Either<ApiResourceError, LLMModel> {
        return safeApiCall {
            client.get(ById(modelId = modelId)).body<LLMModel>()
        }
    }

    override suspend fun updateModel(model: LLMModel): Either<ApiResourceError, Unit> {
        return safeApiCall {
            // Note: OpenAPI specifies the body is the full LLMModel object,
            // and the path ID must match the body ID.
            // The backend service should handle updating only the allowed fields.
            client.put(ById(modelId = model.id)) {
                setBody(model)
            }.body<Unit>()
        }
    }

    override suspend fun deleteModel(modelId: Long): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.delete(ById(modelId = modelId)).body<Unit>()
        }
    }

    override suspend fun getModelApiKeyStatus(modelId: Long): Either<ApiResourceError, ApiKeyStatusResponse> {
        return safeApiCall {
            client.get(ApiKeyStatus(ById(modelId = modelId))).body<ApiKeyStatusResponse>()
        }
    }

    override suspend fun getModelDetails(modelId: Long): Either<ApiResourceError, LLMModelDetails> {
        return safeApiCall {
            client.get(Details(ById(modelId = modelId))).body<LLMModelDetails>()
        }
    }

    override suspend fun getAllModelDetails(): Either<ApiResourceError, List<LLMModelDetails>> {
        return safeApiCall {
            client.get(ModelResource.Details()).body<List<LLMModelDetails>>()
        }
    }

    override suspend fun makeModelPublic(modelId: Long): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.post(MakePublic(ById(modelId = modelId))).body<Unit>()
        }
    }

    override suspend fun makeModelPrivate(modelId: Long): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.post(MakePrivate(ById(modelId = modelId))).body<Unit>()
        }
    }

    override suspend fun grantModelAccess(
        modelId: Long,
        request: GrantAccessRequest
    ): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.post(Access(ById(modelId = modelId))) {
                setBody(request)
            }.body<Unit>()
        }
    }

    override suspend fun revokeModelAccess(
        modelId: Long,
        request: RevokeAccessRequest
    ): Either<ApiResourceError, Unit> {
        return safeApiCall {
            client.delete(Access(ById(modelId = modelId))) {
                setBody(request)
            }.body<Unit>()
        }
    }
}