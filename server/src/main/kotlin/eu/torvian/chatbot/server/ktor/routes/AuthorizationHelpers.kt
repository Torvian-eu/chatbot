package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.Raise
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.PermissionSpec
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.error.AuthorizationError
import eu.torvian.chatbot.server.service.security.error.toApiError

/**
 * Helper function to require a permission for the current user.
 *
 * @param authorizationService The authorization service to use
 * @param userId The ID of the user to check
 * @param permission The permission to check
 */
suspend inline fun Raise<ApiError>.requirePermission(
    authorizationService: AuthorizationService,
    userId: Long,
    permission: PermissionSpec
) {
    withError({ ae: AuthorizationError -> ae.toApiError() }) {
        authorizationService.requirePermission(userId, permission).bind()
    }
}
