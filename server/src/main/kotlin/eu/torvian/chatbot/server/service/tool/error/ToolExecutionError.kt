package eu.torvian.chatbot.server.service.tool.error

/**
 * Base interface for all tool execution errors.
 * These are logical errors that occur during tool execution.
 */
sealed interface ToolExecutionError {
    /**
     * The tool input is invalid or doesn't match the expected schema.
     * @property message Description of the validation error.
     */
    data class InvalidInput(val message: String) : ToolExecutionError

    /**
     * The tool configuration is invalid or incomplete.
     * @property message Description of the configuration error.
     */
    data class InvalidConfiguration(val message: String) : ToolExecutionError

    /**
     * The tool execution failed due to an external service error.
     * @property message Description of the external error.
     */
    data class ExternalServiceError(val message: String) : ToolExecutionError

    /**
     * The tool execution timed out.
     * @property message Description of the timeout.
     */
    data class Timeout(val message: String) : ToolExecutionError

    /**
     * The tool type is not supported or no executor is registered.
     * @property toolType The unsupported tool type.
     */
    data class UnsupportedToolType(val toolType: String) : ToolExecutionError

    /**
     * A general execution error occurred.
     * @property message Description of the error.
     */
    data class ExecutionFailed(val message: String) : ToolExecutionError
}

