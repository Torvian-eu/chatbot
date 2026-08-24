package eu.torvian.chatbot.server.ktor.routes

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.right
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.common.api.resources.MeResource
import eu.torvian.chatbot.common.models.api.me.PreferenceKeys
import eu.torvian.chatbot.common.models.api.me.UserPreferenceDTO
import eu.torvian.chatbot.common.models.user.PreferenceScope
import eu.torvian.chatbot.server.domain.security.AuthSchemes
import eu.torvian.chatbot.server.ktor.auth.getUserId
import eu.torvian.chatbot.server.service.core.ServerBuiltInToolNamePrefixService
import eu.torvian.chatbot.server.service.core.UserPreferenceService
import eu.torvian.chatbot.server.service.core.error.preferences.toApiError
import eu.torvian.chatbot.server.service.core.error.serverbuiltin.toApiError
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
 *
 * The generic key/value surface is provided by [UserPreferenceService]. The well-known
 * `server_builtin_tool_name_prefix` key is branched to [ServerBuiltInToolNamePrefixService]: it
 * must be stored in the GLOBAL scope (server-side execution needs one effective value) and its
 * DELETE always resets the global row to the server default, so no new REST endpoints are needed.
 */
fun Route.configureMeRoutes(
    userPreferenceService: UserPreferenceService,
    serverBuiltInToolNamePrefixService: ServerBuiltInToolNamePrefixService
) {
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
                if (resource.key == PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX) {
                    // Mirror the generic preference path's contract: the body key must match the
                    // path key. Otherwise a client could send a body claiming a different key while
                    // the prefix is silently updated, which would make the route surface looser than
                    // the generic key/value endpoint.
                    ensure(request.key == resource.key) {
                        apiError(
                            CommonApiErrorCodes.INVALID_ARGUMENT,
                            "Preference key in the body must match the path parameter"
                        )
                    }
                    // The tool-name prefix governs server-side execution, so only the GLOBAL scope
                    // is meaningful; a device-scoped value would be silently ignored by the
                    // executor, which is worse than rejecting it here.
                    ensure(request.scope == PreferenceScope.GLOBAL) {
                        apiError(
                            CommonApiErrorCodes.INVALID_ARGUMENT,
                            "The server built-in tool name prefix must be stored in the GLOBAL scope"
                        )
                    }
                    serverBuiltInToolNamePrefixService.updatePrefix(userId, request.value)
                        .mapLeft { it.toApiError() }
                        .bind()
                } else {
                    userPreferenceService.updatePreference(userId, deviceId, resource.key, request)
                        .mapLeft { it.toApiError() }
                        .bind()
                }
            }
            call.respondEither(result, HttpStatusCode.NoContent)
        }

        // DELETE /api/v1/me/preferences/{key} - Remove a preference value.
        delete<MeResource.Preferences.ByKey> { resource ->
            val userId = call.getUserId()

            val result = either {
                val deviceId = parseDeviceHeader().bind()
                if (resource.key == PreferenceKeys.SERVER_BUILTIN_TOOL_NAME_PREFIX) {
                    // DELETE always resets the global row (and renames the user's tools back to the
                    // server default); the scope query parameter is ignored for this key.
                    serverBuiltInToolNamePrefixService.deletePrefix(userId)
                        .mapLeft { it.toApiError() }
                        .bind()
                } else {
                    // Extract scope from query parameter, default to DEVICE if not specified.
                    val scope = resource.scope ?: PreferenceScope.DEVICE
                    userPreferenceService.deletePreference(userId, deviceId, resource.key, scope)
                        .mapLeft { it.toApiError() }
                        .bind()
                }
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
