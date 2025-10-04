package eu.torvian.chatbot.common.api

/**
 * Represents a permission specification consisting of an action and a subject.
 *
 * This is used to define permission requirements without needing a database ID,
 * unlike the full Permission model which includes an ID field.
 *
 * @property action The action to be performed (e.g., "manage", "create", "read")
 * @property subject The subject/resource the action applies to (e.g., "users", "sessions")
 */
data class PermissionSpec(
    val action: String,
    val subject: String
)

/**
 * Centralized constants for authorization permissions used throughout the application.
 *
 * This object provides a single source of truth for all permission strings used in the
 * authorization system, preventing typos and ensuring consistency across the codebase.
 *
 * The permissions follow an action-subject pattern where:
 * - **Action**: What operation is being performed (manage, create, read, update, delete)
 * - **Subject**: What resource the action applies to (users, providers, models, etc.)
 */
object CommonPermissions {

    /**
     * Available actions that can be performed on resources.
     */
    object Actions {
        const val MANAGE = "manage"
        const val CREATE = "create"
        const val READ = "read"
        const val UPDATE = "update"
        const val DELETE = "delete"
    }

    /**
     * Available subjects/resources that actions can be performed on.
     */
    object Subjects {
        const val USERS = "users"
        const val ROLES = "roles"
        const val PUBLIC_PROVIDER = "public_provider"
        const val PUBLIC_MODEL = "public_model"
        const val PUBLIC_SETTINGS = "public_settings"
        const val SESSIONS = "sessions"
        const val MESSAGES = "messages"
        const val GROUPS = "groups"
    }

    // Predefined permission specifications for common operations
    val MANAGE_USERS = PermissionSpec(Actions.MANAGE, Subjects.USERS)
    val MANAGE_ROLES = PermissionSpec(Actions.MANAGE, Subjects.ROLES)
    val CREATE_PUBLIC_PROVIDER = PermissionSpec(Actions.CREATE, Subjects.PUBLIC_PROVIDER)
    val CREATE_PUBLIC_MODEL = PermissionSpec(Actions.CREATE, Subjects.PUBLIC_MODEL)
    val CREATE_PUBLIC_SETTINGS = PermissionSpec(Actions.CREATE, Subjects.PUBLIC_SETTINGS)
    val READ_SESSIONS = PermissionSpec(Actions.READ, Subjects.SESSIONS)
    val CREATE_SESSIONS = PermissionSpec(Actions.CREATE, Subjects.SESSIONS)
    val MANAGE_SESSIONS = PermissionSpec(Actions.MANAGE, Subjects.SESSIONS)
    val READ_MESSAGES = PermissionSpec(Actions.READ, Subjects.MESSAGES)
    val CREATE_MESSAGES = PermissionSpec(Actions.CREATE, Subjects.MESSAGES)
    val MANAGE_GROUPS = PermissionSpec(Actions.MANAGE, Subjects.GROUPS)
}
