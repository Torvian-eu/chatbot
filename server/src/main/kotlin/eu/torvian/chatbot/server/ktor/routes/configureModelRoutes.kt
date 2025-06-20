package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.ModelResources
import eu.torvian.chatbot.common.models.AddModelRequest
import eu.torvian.chatbot.common.models.AddModelSettingsRequest
import eu.torvian.chatbot.common.models.ApiKeyStatusResponse
import eu.torvian.chatbot.common.models.LLMModel
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.model.AddModelError
import eu.torvian.chatbot.server.service.core.error.model.DeleteModelError
import eu.torvian.chatbot.server.service.core.error.model.GetModelError
import eu.torvian.chatbot.server.service.core.error.model.UpdateModelError
import eu.torvian.chatbot.server.service.core.error.settings.AddSettingsError
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlin.to

/**
 * Configures routes related to Models (/api/v1/models) using Ktor Resources.
 */
fun Route.configureModelRoutes(llmModelService: LLMModelService, modelSettingsService: ModelSettingsService) {
    // GET /api/v1/models - List all models
    get<ModelResources> {
        call.respond(llmModelService.getAllModels())
    }

    // POST /api/v1/models - Add new model
    post<ModelResources> {
        val request = call.receive<AddModelRequest>()
        call.respondEither(
            llmModelService.addModel(
                request.name,
                request.providerId,
                request.active,
                request.displayName
            ), HttpStatusCode.Created
        ) { error ->
            when (error) {
                is AddModelError.InvalidInput ->
                    apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid model input", "reason" to error.reason)

                is AddModelError.ProviderNotFound ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Provider not found for model",
                        "providerId" to error.providerId.toString()
                    )

                is AddModelError.ModelNameAlreadyExists ->
                    apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Model name already exists", "name" to error.name)
            }
        }
    }

    // GET /api/v1/models/{modelId} - Get model by ID
    get<ModelResources.ById> { resource ->
        val modelId = resource.modelId
        call.respondEither(llmModelService.getModelById(modelId)) { error ->
            when (error) {
                is GetModelError.ModelNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Model not found", "modelId" to error.id.toString())
            }
        }
    }

    // PUT /api/v1/models/{modelId} - Update model by ID
    put<ModelResources.ById> { resource ->
        val modelId = resource.modelId
        val model = call.receive<LLMModel>()
        if (model.id != modelId) {
            val errorApiCode = CommonApiErrorCodes.INVALID_ARGUMENT
            val error = apiError(
                apiCode = errorApiCode,
                message = "Model ID in path and body must match",
                "pathId" to modelId.toString(),
                "bodyId" to model.id.toString()
            )
            // Use respond directly for the invalid argument case before calling service
            return@put call.respond(HttpStatusCode.fromValue(error.statusCode), error)
        }
        call.respondEither(llmModelService.updateModel(model)) { error ->
            when (error) {
                is UpdateModelError.ModelNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Model not found", "modelId" to error.id.toString())

                is UpdateModelError.InvalidInput ->
                    apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid model input", "reason" to error.reason)

                is UpdateModelError.ProviderNotFound ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Provider not found for model",
                        "providerId" to error.providerId.toString()
                    )

                is UpdateModelError.ModelNameAlreadyExists ->
                    apiError(CommonApiErrorCodes.ALREADY_EXISTS, "Model name already exists", "name" to error.name)
            }
        }
    }

    // DELETE /api/v1/models/{modelId} - Delete model by ID
    delete<ModelResources.ById> { resource ->
        val modelId = resource.modelId
        call.respondEither(llmModelService.deleteModel(modelId), HttpStatusCode.NoContent) { error ->
            when (error) {
                is DeleteModelError.ModelNotFound ->
                    apiError(CommonApiErrorCodes.NOT_FOUND, "Model not found", "modelId" to error.id.toString())
            }
        }
    }

    // --- Nested settings routes under model ---

    // GET /api/v1/models/{modelId}/settings - List settings for this model
    get<ModelResources.ById.Settings> { resource ->
        val modelId = resource.parent.modelId
        call.respond(modelSettingsService.getSettingsByModelId(modelId))
    }

    // POST /api/v1/models/{modelId}/settings - Add new settings for this model
    post<ModelResources.ById.Settings> { resource ->
        val modelId = resource.parent.modelId
        val request = call.receive<AddModelSettingsRequest>()
        call.respondEither(
            modelSettingsService.addSettings(
                request.name,
                modelId,
                request.systemMessage,
                request.temperature,
                request.maxTokens,
                request.customParamsJson
            ), HttpStatusCode.Created
        ) { error ->
            when (error) {
                is AddSettingsError.ModelNotFound ->
                    apiError(
                        CommonApiErrorCodes.INVALID_ARGUMENT,
                        "Model not found for settings",
                        "modelId" to error.modelId.toString()
                    )

                is AddSettingsError.InvalidInput ->
                    apiError(CommonApiErrorCodes.INVALID_ARGUMENT, "Invalid settings input", "reason" to error.reason)
            }
        }
    }

    // GET /api/v1/models/{modelId}/apikey/status - Get API key status for model
    get<ModelResources.ById.ApiKeyStatus> { resource ->
        val modelId = resource.parent.modelId
        val isConfigured = llmModelService.isApiKeyConfiguredForModel(modelId)
        call.respond(ApiKeyStatusResponse(isConfigured))
    }
}