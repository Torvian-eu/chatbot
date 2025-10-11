package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.SettingsResource
import eu.torvian.chatbot.common.models.api.access.GrantAccessRequest
import eu.torvian.chatbot.common.models.api.access.RevokeAccessRequest
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.access.*
import eu.torvian.chatbot.server.service.core.error.settings.*
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
 * Configures routes related to Settings (/api/v1/settings) using Ktor Resources.
 */
fun Route.configureSettingsRoutes(
    modelSettingsService: ModelSettingsService,
    authorizationService: AuthorizationService
) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/settings - Get all settings (filtered by user access)
        get<SettingsResource> {
            val userId = call.getUserId()

            // If user has MANAGE_LLM_MODEL_SETTINGS permission, show all settings
            if (authorizationService.hasPermission(userId, CommonPermissions.MANAGE_LLM_MODEL_SETTINGS)) {
                call.respond(modelSettingsService.getAllSettings())
            }
            // Otherwise, show only settings accessible to the user
            else {
                call.respond(modelSettingsService.getAllAccessibleSettings(userId, AccessMode.READ))
            }
        }

        // GET /api/v1/settings/{settingsId} - Get settings by ID
        get<SettingsResource.ById> { resource ->
            val userId = call.getUserId()
            val settingsId = resource.settingsId

            either {
                // Check READ access
                requireSettingsAccess(authorizationService, userId, settingsId, AccessMode.READ)

                // Get settings
                withError({ error: GetSettingsByIdError -> error.toApiError() }) {
                    modelSettingsService.getSettingsById(settingsId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // POST /api/v1/settings - Add new settings profile
        post<SettingsResource> {
            val userId = call.getUserId()
            val settings = call.receive<ModelSettings>()
            either {
                // Require CREATE or MANAGE permission for settings
                requireAnyPermission(
                    authorizationService,
                    userId,
                    CommonPermissions.MANAGE_LLM_MODEL_SETTINGS,
                    CommonPermissions.CREATE_LLM_MODEL_SETTINGS
                )

                withError({ error: AddSettingsError -> error.toApiError() }) {
                    modelSettingsService.addSettings(userId, settings).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.Created)
            }
        }

        // PUT /api/v1/settings/{settingsId} - Update settings by ID
        put<SettingsResource.ById> { resource ->
            val userId = call.getUserId()
            val settingsId = resource.settingsId
            val settings = call.receive<ModelSettings>()

            if (settings.id != settingsId) {
                val error = apiError(
                    apiCode = CommonApiErrorCodes.INVALID_ARGUMENT,
                    message = "Settings ID in path and body must match",
                    "pathId" to settingsId.toString(),
                    "bodyId" to settings.id.toString()
                )
                return@put call.respond(HttpStatusCode.fromValue(error.statusCode), error)
            }

            either {
                // Check WRITE access
                requireSettingsAccess(authorizationService, userId, settingsId, AccessMode.WRITE)

                // Update settings
                withError({ error: UpdateSettingsError -> error.toApiError() }) {
                    modelSettingsService.updateSettings(settings).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // DELETE /api/v1/settings/{settingsId} - Delete settings by ID
        delete<SettingsResource.ById> { resource ->
            val userId = call.getUserId()
            val settingsId = resource.settingsId

            either {
                // Check WRITE access
                requireSettingsAccess(authorizationService, userId, settingsId, AccessMode.WRITE)

                // Delete settings
                withError({ error: DeleteSettingsError -> error.toApiError() }) {
                    modelSettingsService.deleteSettings(settingsId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.NoContent)
            }
        }

        // --- Access Management for settings ---

        // POST /api/v1/settings/{settingsId}/access - Grant access to a group
        post<SettingsResource.ById.Access> { resource ->
            val userId = call.getUserId()
            val settingsId = resource.parent.settingsId
            val request = call.receive<GrantAccessRequest>()

            either {
                requireSettingsAccess(authorizationService, userId, settingsId, AccessMode.MANAGE)

                // Grant access
                val accessMode = AccessMode.of(request.accessMode)
                withError({ error: GrantResourceAccessError -> error.toApiError() }) {
                    modelSettingsService.grantSettingsAccess(
                        settingsId,
                        request.groupId,
                        accessMode
                    ).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // DELETE /api/v1/settings/{settingsId}/access - Revoke access from a group
        delete<SettingsResource.ById.Access> { resource ->
            val userId = call.getUserId()
            val settingsId = resource.parent.settingsId
            val request = call.receive<RevokeAccessRequest>()

            either {
                requireSettingsAccess(authorizationService, userId, settingsId, AccessMode.MANAGE)

                // Revoke access
                val accessMode = AccessMode.of(request.accessMode)
                withError({ error: RevokeResourceAccessError -> error.toApiError() }) {
                    modelSettingsService.revokeSettingsAccess(
                        settingsId,
                        request.groupId,
                        accessMode
                    ).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // --- Convenience Endpoints ---

        // POST /api/v1/settings/{settingsId}/make-public - Make settings public
        post<SettingsResource.ById.MakePublic> { resource ->
            val userId = call.getUserId()
            val settingsId = resource.parent.settingsId

            either {
                requireSettingsAccess(authorizationService, userId, settingsId, AccessMode.MANAGE)

                // Make public
                withError({ error: MakeResourcePublicError -> error.toApiError() }) {
                    modelSettingsService.makeSettingsPublic(settingsId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // POST /api/v1/settings/{settingsId}/make-private - Make settings private
        post<SettingsResource.ById.MakePrivate> { resource ->
            val userId = call.getUserId()
            val settingsId = resource.parent.settingsId

            either {
                requireSettingsAccess(authorizationService, userId, settingsId, AccessMode.MANAGE)

                // Make private
                withError({ error: MakeResourcePrivateError -> error.toApiError() }) {
                    modelSettingsService.makeSettingsPrivate(settingsId).bind()
                }
            }.let { result ->
                call.respondEither(result, HttpStatusCode.OK)
            }
        }

        // --- Details Endpoints ---

        // GET /api/v1/settings/{settingsId}/details - Get settings details
        get<SettingsResource.ById.Details> { resource ->
            val userId = call.getUserId()
            val settingsId = resource.parent.settingsId

            either {
                // Require MANAGE access to view details
                requireSettingsAccess(authorizationService, userId, settingsId, AccessMode.MANAGE)

                // Get settings details
                withError({ error: GetSettingsByIdError -> error.toApiError() }) {
                    modelSettingsService.getSettingsDetails(settingsId).bind()
                }
            }.let { result ->
                call.respondEither(result)
            }
        }

        // GET /api/v1/settings/details - List all settings with details (filtered by user access)
        get<SettingsResource.Details> {
            val userId = call.getUserId()

            either {
                withError({ error: GetSettingsByIdError -> error.toApiError() }) {
                    val settings =
                        // If user has MANAGE_LLM_MODEL_SETTINGS permission, show all settings
                        if (authorizationService.hasPermission(userId, CommonPermissions.MANAGE_LLM_MODEL_SETTINGS)) {
                            modelSettingsService.getAllSettings()
                        }
                        // Otherwise, show only settings owned or accessible to the user
                        else {
                            modelSettingsService.getAllAccessibleSettings(userId, AccessMode.MANAGE)
                        }

                    // Get details for each settings profile
                    settings.map { s ->
                        modelSettingsService.getSettingsDetails(s.id).bind()
                    }
                }
            }.let { result ->
                call.respondEither(result)
            }
        }
    }
}