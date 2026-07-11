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
        const val CREATE = "create"
        const val MANAGE = "manage"
    }

    /**
     * Available subjects/resources that actions can be performed on.
     */
    object Subjects {
        const val LLM_PROVIDERS = "llm_providers"
        const val LLM_MODELS = "llm_models"
        const val LLM_MODEL_SETTINGS = "llm_model_settings"
        const val USERS = "users"
        const val ROLES = "roles"
        const val PERMISSIONS = "permissions"
        const val USER_GROUPS = "user_groups"
    }

    // Predefined permission specifications for common operations
    val MANAGE_LLM_PROVIDERS = PermissionSpec(Actions.MANAGE, Subjects.LLM_PROVIDERS)
    val MANAGE_LLM_MODELS = PermissionSpec(Actions.MANAGE, Subjects.LLM_MODELS)
    val MANAGE_LLM_MODEL_SETTINGS = PermissionSpec(Actions.MANAGE, Subjects.LLM_MODEL_SETTINGS)
    val MANAGE_USERS = PermissionSpec(Actions.MANAGE, Subjects.USERS)
    val MANAGE_ROLES = PermissionSpec(Actions.MANAGE, Subjects.ROLES)
    val MANAGE_PERMISSIONS = PermissionSpec(Actions.MANAGE, Subjects.PERMISSIONS)
    val MANAGE_USER_GROUPS = PermissionSpec(Actions.MANAGE, Subjects.USER_GROUPS)

    val CREATE_LLM_PROVIDER = PermissionSpec(Actions.CREATE, Subjects.LLM_PROVIDERS)
    val CREATE_LLM_MODEL = PermissionSpec(Actions.CREATE, Subjects.LLM_MODELS)
    val CREATE_LLM_MODEL_SETTINGS = PermissionSpec(Actions.CREATE, Subjects.LLM_MODEL_SETTINGS)

}
