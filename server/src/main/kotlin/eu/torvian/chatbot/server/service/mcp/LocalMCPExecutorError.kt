package eu.torvian.chatbot.server.service.mcp

/**
 * Error types for LocalMCPExecutor.
 */
sealed interface LocalMCPExecutorError {
    val message: String

    /**
     * The tool input is invalid or doesn't match the expected schema.
     * @property message Description of the validation error.
     */
    data class InvalidInput(override val message: String) : LocalMCPExecutorError

    /**
     * The tool execution timed out.
     * @property message Description of the timeout.
     */
    data class Timeout(override val message: String) : LocalMCPExecutorError

    /**
     * A general execution error occurred.
     * @property message Description of the error.
     */
    data class OtherError(override val message: String) : LocalMCPExecutorError
}