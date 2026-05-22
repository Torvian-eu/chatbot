package eu.torvian.chatbot.server.ktor.routes

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.MeResource
import eu.torvian.chatbot.common.models.api.me.UserPreferenceDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.UserPreferenceService
import eu.torvian.chatbot.server.service.core.error.preferences.toApiError
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.delete
import io.ktor.server.resources.put
import io.ktor.server.routing.*
import java.util.*

/**
 * Configures authenticated self-service routes for user preferences under /api/v1/me.
 */
fun Route.configureMeRoutes(userPreferenceService: UserPreferenceService) {
    authenticate(AuthSchemes.USER_JWT) {
        // GET /api/v1/me/preferences - Resolve the effective preference map for the current user.
        get<MeResource.Preferences> {
            val userId = call.getUserId()

            val result = either {
                val deviceId = parseDeviceHeader().bind()
                userPreferenceService.getResolvedPreferences(userId, deviceId)
                    .mapLeft { it.toApiError() }
                    .bind()
            }
            call.respondEither(result)
        }

        // GET /api/v1/me/preferences/details - Get detailed preferences showing global and device values.
        get<MeResource.Preferences.Details> {
            val userId = call.getUserId()

            val result = either {
                val deviceId = parseDeviceHeader().bind()
                userPreferenceService.getDetailedPreferences(userId, deviceId)
                    .mapLeft { it.toApiError() }
                    .bind()
            }
            call.respondEither(result)
        }

        // PUT /api/v1/me/preferences/{key} - Store a global or device-scoped preference value.
        put<MeResource.Preferences.ByKey> { resource ->
            val userId = call.getUserId()
            val request = call.receive<UserPreferenceDTO>()

            val result = either {
                val deviceId = parseDeviceHeader().bind()
                userPreferenceService.updatePreference(userId, deviceId, resource.key, request)
                    .mapLeft { it.toApiError() }
                    .bind()
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // DELETE /api/v1/me/preferences/{key} - Remove a preference value.
        delete<MeResource.Preferences.ByKey> { resource ->
            val userId = call.getUserId()

            // Extract scope from query parameter, default to DEVICE if not specified
            val scope = resource.scope ?: PreferenceScope.DEVICE

            val result = either {
                val deviceId = parseDeviceHeader().bind()
                userPreferenceService.deletePreference(userId, deviceId, resource.key, scope)
                    .mapLeft { it.toApiError() }
                    .bind()
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }
    }
}

/**
 * Reads the optional `X-Device-Id` header and validates that it contains a UUID.
 *
 * Missing headers are allowed, because device-scoped updates are rejected by the service layer when
 * the device is not registered. Invalid UUID strings are rejected immediately so the server never
 * stores or queries with malformed device identifiers.
 *
 * @return A validated device identifier, null when the header is omitted,
 *         or an [ApiError] describing why the header was rejected.
 */
private fun RoutingContext.parseDeviceHeader(): Either<ApiError, String?> {
    val rawHeader = call.request.headers["X-Device-Id"] ?: return null.right()

    return runCatching { UUID.fromString(rawHeader) }
        .map { it.toString().right() }
        .getOrElse {
            apiError(
                CommonApiErrorCodes.INVALID_ARGUMENT,
                "X-Device-Id must be a valid UUID",
                "field" to "X-Device-Id"
            ).left()
        }
}
