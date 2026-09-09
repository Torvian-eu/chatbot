package eu.torvian.chatbot.server.data.dao.error.project

/**
 * Errors that can occur when reading or writing project rows.
 */
sealed interface ProjectError {

    /**
     * The requested project row does not exist.
     *
     * @property id The missing project identifier.
     */
    data class NotFound(val id: Long) : ProjectError
}