package eu.torvian.chatbot.app.compose.permissions

import androidx.compose.runtime.Composable
import eu.torvian.chatbot.app.repository.AuthState
import eu.torvian.chatbot.app.utils.permissions.hasPermission
import eu.torvian.chatbot.common.api.PermissionSpec

/**
 * Renders content only if the user has the required permission.
 *
 * This composable should be used to conditionally show UI elements based on user permissions.
 * Note: This is for UX purposes only. Server-side permission checks are still required for security.
 *
 * @param authState The current authentication state
 * @param permission The required permission specification
 * @param content The content to render if the user has the permission
 */
@Composable
fun RequiresPermission(
    authState: AuthState,
    permission: PermissionSpec,
    content: @Composable () -> Unit
) {
    if (authState is AuthState.Authenticated &&
        authState.permissions.hasPermission(permission)) {
        content()
    }
}

/**
 * Renders different content based on whether the user has a permission.
 *
 * This composable provides a way to render different UI based on permission presence.
 * It's useful for showing disabled states or alternative UI for users without permissions.
 *
 * @param authState The current authentication state
 * @param permission The required permission specification
 * @param hasPermission The content to render if the user has the permission
 * @param noPermission The content to render if the user does not have the permission (defaults to empty)
 */
@Composable
fun PermissionGate(
    authState: AuthState,
    permission: PermissionSpec,
    hasPermission: @Composable () -> Unit,
    noPermission: @Composable () -> Unit = {}
) {
    if (authState is AuthState.Authenticated &&
        authState.permissions.hasPermission(permission)) {
        hasPermission()
    } else {
        noPermission()
    }
}

/**
 * Renders content only if the user is authenticated and has any of the required permissions.
 *
 * @param authState The current authentication state
 * @param permissions The list of permission specifications (user needs at least one)
 * @param content The content to render if the user has any of the permissions
 */
@Composable
fun RequiresAnyPermission(
    authState: AuthState,
    permissions: List<PermissionSpec>,
    content: @Composable () -> Unit
) {
    if (authState is AuthState.Authenticated &&
        permissions.any { authState.permissions.hasPermission(it) }) {
        content()
    }
}

/**
 * Renders content only if the user is authenticated and has all of the required permissions.
 *
 * @param authState The current authentication state
 * @param permissions The list of permission specifications (user needs all of them)
 * @param content The content to render if the user has all the permissions
 */
@Composable
fun RequiresAllPermissions(
    authState: AuthState,
    permissions: List<PermissionSpec>,
    content: @Composable () -> Unit
) {
    if (authState is AuthState.Authenticated &&
        permissions.all { authState.permissions.hasPermission(it) }) {
        content()
    }
}

