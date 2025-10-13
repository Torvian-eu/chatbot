package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.ModelResource
import eu.torvian.chatbot.common.models.api.access.GrantAccessRequest
import eu.torvian.chatbot.common.models.api.access.RevokeAccessRequest
import eu.torvian.chatbot.common.models.api.llm.AddModelRequest
import eu.torvian.chatbot.common.models.api.llm.ApiKeyStatusResponse
import eu.torvian.chatbot.common.models.llm.LLMModel
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.LLMModelService
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.access.*
import eu.torvian.chatbot.server.service.core.error.model.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlin.let
import kotlin.to

/**
 * Configures routes related to Models (/api/v1/models) using Ktor Resources.
 */
fun Route.configureModelRoutes(
    llmModelService: LLMModelService,
    modelSettingsService: ModelSettingsService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/models - List all models (filtered by user access)
        get<ModelResource> {
            val userId = call.getUserId()

            // If user has MANAGE_LLM_MODELS permission, show all models
            if (authorizationService.hasPermission(userId, CommonPermissions.MANAGE_LLM_MODELS)) {
                call.respond(llmModelService.getAllModels())
            }
            // Otherwise, show only models accessible to the user
            else {
                call.respond(llmModelService.getAllAccessibleModels(userId, AccessMode.READ))
            }
        }

        // POST /api/v1/models - Add new model
        post<ModelResource> {
            val userId = call.getUserId()
            val request = call.receive<AddModelRequest>()
            either {
                // Require CREATE or MANAGE permission for models
                requireAnyPermission(
                    authorizationService,
                    userId,
                    CommonPermissions.MANAGE_LLM_MODELS,
                    CommonPermissions.CREATE_LLM_MODEL
                )

                withError({ error: AddModelError -> error.toApiError() }) {
                    llmModelService.addModel(
                        userId,
                        request.name,
                        request.providerId,
                        request.type,
                        request.active,
                        request.displayName,
                        request.capabilities
                    ).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.Created)
            }
        }

        // GET /api/v1/models/{modelId} - Get model by ID
        get<ModelResource.ById> { resource ->
            val userId = call.getUserId()
            val modelId = resource.modelId

            either {
                // Check READ access
                requireModelAccess(authorizationService, userId, modelId, AccessMode.READ)

                // Get model
                withError({ error: GetModelError -> error.toApiError() }) {
                    llmModelService.getModelById(modelId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // PUT /api/v1/models/{modelId} - Update model by ID
        put<ModelResource.ById> { resource ->
            val userId = call.getUserId()
            val modelId = resource.modelId
            val model = call.receive<LLMModel>()

            if (model.id != modelId) {
                val error = apiError(
                    apiCode = CommonApiErrorCodes.INVALID_ARGUMENT,
                    message = "Model ID in path and body must match",
                    "pathId" to modelId.toString(),
                    "bodyId" to model.id.toString()
                )
                return@put call.respond(HttpStatusCode.fromValue(error.statusCode), error)
            }

            either {
                // Check WRITE access
                requireModelAccess(authorizationService, userId, modelId, AccessMode.WRITE)

                // Update model
                withError({ error: UpdateModelError -> error.toApiError() }) {
                    llmModelService.updateModel(model).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // DELETE /api/v1/models/{modelId} - Delete model by ID
        delete<ModelResource.ById> { resource ->
            val userId = call.getUserId()
            val modelId = resource.modelId

            either {
                // Check WRITE access
                requireModelAccess(authorizationService, userId, modelId, AccessMode.WRITE)

                // Delete model
                withError({ error: DeleteModelError -> error.toApiError() }) {
                    llmModelService.deleteModel(modelId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.NoContent)
            }
        }

        // --- Nested settings routes under model ---

        // GET /api/v1/models/{modelId}/settings - List settings for this model
        get<ModelResource.ById.Settings> { resource ->
            val userId = call.getUserId()
            val modelId = resource.parent.modelId

            // If user has MANAGE_LLM_MODEL_SETTINGS permission, show all settings
            if (authorizationService.hasPermission(userId, CommonPermissions.MANAGE_LLM_MODEL_SETTINGS)) {
                call.respond(modelSettingsService.getSettingsByModelId(modelId))
            }
            // Otherwise, show only settings accessible to the user
            else {
                call.respond(modelSettingsService.getAccessibleSettingsByModelId(userId, modelId, AccessMode.READ))
            }
        }

        // GET /api/v1/models/{modelId}/apikey/status - Get API key status for model
        get<ModelResource.ById.ApiKeyStatus> { resource ->
            val userId = call.getUserId()
            val modelId = resource.parent.modelId
            either {
                // Check READ access
                requireModelAccess(authorizationService, userId, modelId, AccessMode.READ)

                // Get API key status
                val isConfigured = llmModelService.isApiKeyConfiguredForModel(modelId)
                ApiKeyStatusResponse(isConfigured)
            }.let { result ->
                call.respondEither(result)
            }
        }

        // --- Access Management ---

        // POST /api/v1/models/{modelId}/access - Grant access to a group
        post<ModelResource.ById.Access> { resource ->
            val userId = call.getUserId()
            val modelId = resource.parent.modelId
            val request = call.receive<GrantAccessRequest>()

            either {
                requireModelAccess(authorizationService, userId, modelId, AccessMode.MANAGE)

                // Grant access
                val accessMode = AccessMode.of(request.accessMode)
                withError({ error: GrantResourceAccessError -> error.toApiError() }) {
                    llmModelService.grantModelAccess(
                        modelId,
                        request.groupId,
                        accessMode
                    ).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // DELETE /api/v1/models/{modelId}/access - Revoke access from a group
        delete<ModelResource.ById.Access> { resource ->
            val userId = call.getUserId()
            val modelId = resource.parent.modelId
            val request = call.receive<RevokeAccessRequest>()

            either {
                requireModelAccess(authorizationService, userId, modelId, AccessMode.MANAGE)

                // Revoke access
                val accessMode = AccessMode.of(request.accessMode)
                withError({ error: RevokeResourceAccessError -> error.toApiError() }) {
                    llmModelService.revokeModelAccess(
                        modelId,
                        request.groupId,
                        accessMode
                    ).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // --- Convenience Endpoints ---

        // POST /api/v1/models/{modelId}/make-public - Make model public
        post<ModelResource.ById.MakePublic> { resource ->
            val userId = call.getUserId()
            val modelId = resource.parent.modelId

            either {
                // Require MANAGE access to change visibility
                requireModelAccess(authorizationService, userId, modelId, AccessMode.MANAGE)

                // Make public
                withError({ error: MakeResourcePublicError -> error.toApiError() }) {
                    llmModelService.makeModelPublic(modelId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // POST /api/v1/models/{modelId}/make-private - Make model private
        post<ModelResource.ById.MakePrivate> { resource ->
            val userId = call.getUserId()
            val modelId = resource.parent.modelId

            either {
                // Require MANAGE access to change visibility
                requireModelAccess(authorizationService, userId, modelId, AccessMode.MANAGE)

                // Make private
                withError({ error: MakeResourcePrivateError -> error.toApiError() }) {
                    llmModelService.makeModelPrivate(modelId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // --- Details endpoints ---

        // GET /api/v1/models/{modelId}/details - Get model details
        get<ModelResource.ById.Details> { resource ->
            val userId = call.getUserId()
            val modelId = resource.parent.modelId

            either {
                // Require MANAGE access to view details
                requireModelAccess(authorizationService, userId, modelId, AccessMode.MANAGE)

                // Get model details
                withError({ error: GetModelError -> error.toApiError() }) {
                    llmModelService.getModelDetails(modelId).bind()
                }
            }.let { result ->
                call.respondEither(result)
            }
        }

        // GET /api/v1/models/details - List all models with details (filtered by user access)
        get<ModelResource.Details> {
            val userId = call.getUserId()

            either {
                withError({ error: GetModelError -> error.toApiError() }) {
                    val models =
                        // If user has MANAGE_LLM_MODELS permission, show all models
                        if (authorizationService.hasPermission(userId, CommonPermissions.MANAGE_LLM_MODELS)) {
                            llmModelService.getAllModels()
                        }
                        // Otherwise, show only models owned or accessible to the user
                        else {
                            llmModelService.getAllAccessibleModels(userId, AccessMode.MANAGE)
                        }
                    // Get details for each model
                    models.map { model ->
                        llmModelService.getModelDetails(model.id).bind()
                    }
                }
            }.let { result ->
                call.respondEither(result)
            }
        }
    }
}