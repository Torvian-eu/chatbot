package eu.torvian.chatbot.app.utils.permissions

import eu.torvian.chatbot.common.api.PermissionSpec
import eu.torvian.chatbot.common.models.Permission

/**
 * Checks if a list of permissions contains a specific permission.
 *
 * @param spec The permission specification to check for
 * @return true if the permission is present, false otherwise
 */
fun List<Permission>.hasPermission(spec: PermissionSpec): Boolean {
    return any { it.action == spec.action && it.subject == spec.subject }
}

/**
 * Checks if a list of permissions contains a specific permission by action and subject.
 *
 * @param action The action to check for (e.g., "manage", "create")
 * @param subject The subject/resource to check for (e.g., "users", "public_provider")
 * @return true if the permission is present, false otherwise
 */
fun List<Permission>.hasPermission(action: String, subject: String): Boolean {
    return any { it.action == action && it.subject == subject }
}

/**
 * Checks if a list of permissions contains any of the specified permissions.
 *
 * @param specs The permission specifications to check for
 * @return true if at least one permission is present, false otherwise
 */
fun List<Permission>.hasAnyPermission(vararg specs: PermissionSpec): Boolean {
    return specs.any { spec -> hasPermission(spec) }
}

/**
 * Checks if a list of permissions contains all of the specified permissions.
 *
 * @param specs The permission specifications to check for
 * @return true if all permissions are present, false otherwise
 */
fun List<Permission>.hasAllPermissions(vararg specs: PermissionSpec): Boolean {
    return specs.all { spec -> hasPermission(spec) }
}

