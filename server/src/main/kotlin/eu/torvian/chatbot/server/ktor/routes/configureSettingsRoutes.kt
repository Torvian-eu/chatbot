package eu.torvian.chatbot.server.ktor.routes

import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.SettingsResource
import eu.torvian.chatbot.common.models.llm.ModelSettings
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.service.core.ModelSettingsService
import eu.torvian.chatbot.server.service.core.error.settings.AddSettingsError
import eu.torvian.chatbot.server.service.core.error.settings.DeleteSettingsError
import eu.torvian.chatbot.server.service.core.error.settings.GetSettingsByIdError
import eu.torvian.chatbot.server.service.core.error.settings.UpdateSettingsError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlin.to

/**
 * Configures routes related to Settings (/api/v1/settings) using Ktor Resources.
 */
fun Route.configureSettingsRoutes(modelSettingsService: ModelSettingsService) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/settings - Get all settings
        get<SettingsResource> {
            val allSettings = modelSettingsService.getAllSettings()
            call.respond(allSettings)
        }

        // GET /api/v1/settings/{settingsId} - Get settings by ID
        get<SettingsResource.ById> { resource ->
            val settingsId = resource.settingsId
            call.respondEither(modelSettingsService.getSettingsById(settingsId)) { error ->
                when (error) {
                    is GetSettingsByIdError.SettingsNotFound ->
                        apiError(
                            CommonApiErrorCodes.NOT_FOUND,
                            "Settings not found",
                            "settingsId" to error.id.toString()
                        )
                }
            }
        }

        // POST /api/v1/settings - Add new settings profile
        post<SettingsResource> {
            val settings = call.receive<ModelSettings>()
            call.respondEither(
                modelSettingsService.addSettings(settings), HttpStatusCode.Created
            ) { error ->
                when (error) {
                    is AddSettingsError.ModelNotFound ->
                        apiError(
                            CommonApiErrorCodes.INVALID_ARGUMENT,
                            "Model not found for settings",
                            "modelId" to error.modelId.toString()
                        )

                    is AddSettingsError.InvalidInput ->
                        apiError(
                            CommonApiErrorCodes.INVALID_ARGUMENT,
                            "Invalid settings input",
                            "reason" to error.reason
                        )
                }
            }
        }

        // PUT /api/v1/settings/{settingsId} - Update settings by ID
        put<SettingsResource.ById> { resource ->
            val settingsId = resource.settingsId
            val settings = call.receive<ModelSettings>()
            if (settings.id != settingsId) {
                val errorApiCode = CommonApiErrorCodes.INVALID_ARGUMENT
                val error = apiError(
                    apiCode = errorApiCode,
                    message = "Settings ID in path and body must match",
                    "pathId" to settingsId.toString(),
                    "bodyId" to settings.id.toString()
                )
                // Use respond directly for the invalid argument case before calling service
                return@put call.respond(HttpStatusCode.fromValue(error.statusCode), error)
            }
            call.respondEither(modelSettingsService.updateSettings(settings)) { error ->
                when (error) {
                    is UpdateSettingsError.SettingsNotFound ->
                        apiError(
                            CommonApiErrorCodes.NOT_FOUND,
                            "Settings not found",
                            "settingsId" to error.id.toString()
                        )

                    is UpdateSettingsError.ModelNotFound ->
                        apiError(
                            CommonApiErrorCodes.INVALID_ARGUMENT,
                            "Model not found for settings",
                            "modelId" to error.modelId.toString()
                        )

                    is UpdateSettingsError.InvalidInput ->
                        apiError(
                            CommonApiErrorCodes.INVALID_ARGUMENT,
                            "Invalid settings input",
                            "reason" to error.reason
                        )
                }
            }
        }

        // DELETE /api/v1/settings/{settingsId} - Delete settings by ID
        delete<SettingsResource.ById> { resource ->
            val settingsId = resource.settingsId
            call.respondEither(
                modelSettingsService.deleteSettings(settingsId),
                HttpStatusCode.NoContent
            ) { error ->
                when (error) {
                    is DeleteSettingsError.SettingsNotFound ->
                        apiError(
                            CommonApiErrorCodes.NOT_FOUND,
                            "Settings not found",
                            "settingsId" to error.id.toString()
                        )
                }
            }
        }
    }
}