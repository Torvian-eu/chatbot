package eu.torvian.chatbot.server.service.security

/**
 * Represents a resource type for authorization purposes.
 *
 * This class provides a type-safe way to represent different resource types
 * within the authorization system. It is used to identify the type of resource
 * being accessed in authorization checks.
 *
 * @property key The string key representing the resource type
 */
@JvmInline
value class ResourceType private constructor(val key: String) {
    override fun toString(): String = key

    companion object {
        val SESSION = ResourceType("chat_session")
        val GROUP = ResourceType("chat_group")
        val PROVIDER = ResourceType("llm_provider")
        val MODEL = ResourceType("llm_model")
        val SETTINGS = ResourceType("llm_settings")

        fun of(key: String): ResourceType = ResourceType(key)
    }
}