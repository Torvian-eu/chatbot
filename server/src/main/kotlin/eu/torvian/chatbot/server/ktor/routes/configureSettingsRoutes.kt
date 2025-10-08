package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.either
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.SettingsResource
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.settings.*
import eu.torvian.chatbot.server.service.security.AuthorizationService
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route

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
            val allSettings = modelSettingsService.getAllSettings()
            call.respond(allSettings)
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
    }
}