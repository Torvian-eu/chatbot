package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.resources.ModelPresetResource
import eu.torvian.chatbot.common.models.api.llm.CreateModelPresetRequest
import eu.torvian.chatbot.common.models.api.llm.UpdateModelPresetRequest
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.ModelPresetService
import eu.torvian.chatbot.server.service.core.error.preset.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * Configures routes related to user-owned Model Presets (/api/v1/model-presets) using Ktor Resources.
 *
 * Model presets are personal configuration, so every operation is scoped to the authenticated user and
 * the [ModelPresetService] verifies ownership before returning or mutating a preset. A foreign or
 * nonexistent preset collapses to the same not-found error, so no existence leak exists. Deleting a
 * preset never deletes, disables or edits an agent role: bound roles simply lose their configuration
 * reference and become non-sendable.
 *
 * Available endpoints:
 * - GET /api/v1/model-presets - List model presets owned by the user (ordered by id ascending)
 * - POST /api/v1/model-presets - Create a new model preset (the caller becomes the owner)
 * - GET /api/v1/model-presets/{presetId} - Get a specific model preset
 * - PUT /api/v1/model-presets/{presetId} - Update a specific model preset (full replacement)
 * - DELETE /api/v1/model-presets/{presetId} - Delete a specific model preset (bound roles survive)
 *
 * @param modelPresetService Service backing the model-preset CRUD operations.
 * @param authorizationService Authorization service retained for parity with the other resource routes;
 *            ownership enforcement is delegated to [ModelPresetService].
 */
fun Route.configureModelPresetRoutes(
    modelPresetService: ModelPresetService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/model-presets - List all model presets owned by the requesting user
        get<ModelPresetResource> {
            val userId = call.getUserId()
            call.respond(modelPresetService.getAllPresetsForUser(userId))
        }

        // GET /api/v1/model-presets/{presetId} - Get model preset by ID (ownership checked)
        get<ModelPresetResource.ById> { resource ->
            val userId = call.getUserId()
            val result = either {
                withError({ e: ModelPresetError -> e.toApiError() }) {
                    modelPresetService.getPresetById(userId, resource.presetId).bind()
                }
            }
            call.respondEither(result)
        }

        // POST /api/v1/model-presets - Create a new model preset owned by the requesting user
        post<ModelPresetResource> {
            val userId = call.getUserId()
            val request = call.receive<CreateModelPresetRequest>()

            val result = either {
                withError({ e: CreateModelPresetError -> e.toApiError() }) {
                    modelPresetService.createPreset(userId, request).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.Created)
        }

        // PUT /api/v1/model-presets/{presetId} - Update model preset (ownership checked; full
        // replacement of name/displayName/description and both references)
        put<ModelPresetResource.ById> { resource ->
            val userId = call.getUserId()
            val request = call.receive<UpdateModelPresetRequest>()

            val result = either {
                withError({ e: UpdateModelPresetError -> e.toApiError() }) {
                    modelPresetService.updatePreset(userId, resource.presetId, request).bind()
                }
            }
            call.respondEither(result)
        }

        // DELETE /api/v1/model-presets/{presetId} - Delete model preset (ownership checked; bound agent
        // roles survive with a nulled reference and become non-sendable)
        delete<ModelPresetResource.ById> { resource ->
            val userId = call.getUserId()

            val result = either {
                withError({ e: DeleteModelPresetError -> e.toApiError() }) {
                    modelPresetService.deletePreset(userId, resource.presetId).bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }
    }
}
