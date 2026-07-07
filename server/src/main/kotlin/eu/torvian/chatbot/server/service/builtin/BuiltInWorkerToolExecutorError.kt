package eu.torvian.chatbot.server.service.builtin

/**
 * Error types for [BuiltInWorkerToolExecutor].
 */
sealed interface BuiltInWorkerToolExecutorError {
    /** Description of the error, suitable for surfacing in the chat loop. */
    val message: String

    /** The tool input is invalid or doesn't match the expected schema. */
    data class InvalidInput(override val message: String) : BuiltInWorkerToolExecutorError

    /** The tool execution timed out. */
    data class Timeout(override val message: String) : BuiltInWorkerToolExecutorError

    /** A general execution error occurred. */
    data class OtherError(override val message: String) : BuiltInWorkerToolExecutorError
}

