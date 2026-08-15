package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during agent-role data operations.
 */
sealed interface AgentRoleError {

    /**
     * Indicates that an agent role with the specified ID was not found.
     *
     * @property id The missing role identifier.
     */
    data class NotFound(val id: Long) : AgentRoleError

    /**
     * Indicates that an agent role with the specified name was not found.
     *
     * @property name The missing role name.
     */
    data class NotFoundByName(val name: String) : AgentRoleError
}
