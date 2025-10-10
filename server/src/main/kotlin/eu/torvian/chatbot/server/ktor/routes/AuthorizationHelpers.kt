package eu.torvian.chatbot.server.ktor.routes

import arrow.core.raise.Raise
import arrow.core.raise.withError
import eu.torvian.chatbot.common.api.AccessMode
import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.CommonPermissions
import eu.torvian.chatbot.common.api.PermissionSpec
import eu.torvian.chatbot.server.service.security.AuthorizationService
import eu.torvian.chatbot.server.service.security.ResourceType
import eu.torvian.chatbot.server.service.security.error.AuthorizationError
import eu.torvian.chatbot.server.service.security.error.ResourceAuthorizationError
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

/**
 * Helper function to require any of the specified permissions for the current user.
 *
 * @param authorizationService The authorization service to use
 * @param userId The ID of the user to check
 * @param permissions The permissions to check
 */
suspend inline fun Raise<ApiError>.requireAnyPermission(
    authorizationService: AuthorizationService,
    userId: Long,
    vararg permissions: PermissionSpec
) {
    withError({ ae: AuthorizationError -> ae.toApiError() }) {
        authorizationService.requireAnyPermission(userId, *permissions).bind()
    }
}

/**
 * Helper function to require all of the specified permissions for the current user.
 *
 * @param authorizationService The authorization service to use
 * @param userId The ID of the user to check
 * @param permissions The permissions to check
 */
suspend inline fun Raise<ApiError>.requireAllPermissions(
    authorizationService: AuthorizationService,
    userId: Long,
    permissions: List<PermissionSpec>
) {
    withError({ ae: AuthorizationError -> ae.toApiError() }) {
        authorizationService.requireAllPermissions(userId, permissions).bind()
    }
}

/**
 * Helper function to require access to a provider resource.
 *
 * @param authorizationService The authorization service to use
 * @param userId The ID of the user requesting access
 * @param providerId The ID of the provider resource
 * @param accessMode The access mode required (READ or WRITE)
 */
suspend inline fun Raise<ApiError>.requireProviderAccess(
    authorizationService: AuthorizationService,
    userId: Long,
    providerId: Long,
    accessMode: AccessMode
) {
    if (authorizationService.hasPermission(userId, CommonPermissions.MANAGE_LLM_PROVIDERS)) {
        return // Admins have implicit access to all resources
    }
    withError({ rae: ResourceAuthorizationError -> rae.toApiError() }) {
        authorizationService.requireAccess(userId, ResourceType.PROVIDER, providerId, accessMode).bind()
    }
}

/**
 * Helper function to require access to a model resource.
 *
 * @param authorizationService The authorization service to use
 * @param userId The ID of the user requesting access
 * @param modelId The ID of the model resource
 * @param accessMode The access mode required (READ or WRITE)
 */
suspend inline fun Raise<ApiError>.requireModelAccess(
    authorizationService: AuthorizationService,
    userId: Long,
    modelId: Long,
    accessMode: AccessMode
) {
    if (authorizationService.hasPermission(userId, CommonPermissions.MANAGE_LLM_MODELS)) {
        return // Admins have implicit access to all resources
    }
    withError({ rae: ResourceAuthorizationError -> rae.toApiError() }) {
        authorizationService.requireAccess(userId, ResourceType.MODEL, modelId, accessMode).bind()
    }
}

/**
 * Helper function to require access to a settings resource.
 *
 * @param authorizationService The authorization service to use
 * @param userId The ID of the user requesting access
 * @param settingsId The ID of the settings resource
 * @param accessMode The access mode required (READ or WRITE)
 */
suspend inline fun Raise<ApiError>.requireSettingsAccess(
    authorizationService: AuthorizationService,
    userId: Long,
    settingsId: Long,
    accessMode: AccessMode
) {
    if (authorizationService.hasPermission(userId, CommonPermissions.MANAGE_LLM_MODEL_SETTINGS)) {
        return // Admins have implicit access to all resources
    }
    withError({ rae: ResourceAuthorizationError -> rae.toApiError() }) {
        authorizationService.requireAccess(userId, ResourceType.SETTINGS, settingsId, accessMode).bind()
    }
}
