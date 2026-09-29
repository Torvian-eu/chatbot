package eu.torvian.chatbot.server.data.dao.error

/**
 * Represents possible domain-specific errors that can occur during instruction data operations.
 */
sealed interface InstructionError {

    /**
     * Indicates that an instruction with the specified ID was not found.
     *
     * @property id The missing instruction identifier.
     */
    data class NotFound(val id: Long) : InstructionError

    /**
     * Indicates that storage refused the operation because the row is still referenced elsewhere.
     *
     * The only restricting reference to an instruction row is the agent-role link, so a delete that
     * fails this way means at least one role still links the row.
     *
     * @property message The underlying constraint failure, kept for logging.
     */
    data class ForeignKeyViolation(val message: String) : InstructionError
}
