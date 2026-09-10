package eu.torvian.chatbot.server.data.dao.error.preset

/**
 * Errors that can occur when reading or writing model-preset rows.
 */
sealed interface ModelPresetError {

    /**
     * The requested model-preset row does not exist.
     *
     * @property id The missing preset identifier.
     */
    data class NotFound(val id: Long) : ModelPresetError
}
