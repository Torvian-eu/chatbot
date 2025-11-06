package eu.torvian.chatbot.server.service.core.error.message

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.server.service.llm.LLMCompletionError

/**
 * Represents possible errors during the process of receiving and responding to a new user message.
 */
sealed interface ProcessNewMessageError {
    /**
     * Indicates a failure occurred when calling the external LLM API.
     * Wraps the external service error details.
     * Maps from exceptions or specific error responses from LLMApiClient.
     *
     * @property llmError The specific LLM error that occurred
     */
    data class ExternalServiceError(val llmError: LLMCompletionError) : ProcessNewMessageError

    /**
     * Indicates a failure occurred when executing a tool.
     * Wraps the tool execution error details.
     * Maps from ToolExecutor.executeTool results.
     *
     * @property toolCallId The ID of the tool call that failed
     * @property errorMessage The error message from the tool execution
     */
    data class ToolExecutionError(val toolCallId: String, val errorMessage: String) : ProcessNewMessageError

    /**
     * Indicates an unexpected error occurred during message processing.
     * This is a catch-all for any unhandled exceptions or errors.
     *
     * @property message The error message
     */
    data class UnexpectedError(val message: String) : ProcessNewMessageError
}

/**
 * Extension function to convert ProcessNewMessageError to ApiError for HTTP responses.
 */
fun ProcessNewMessageError.toApiError(): ApiError = when (this) {
    is ProcessNewMessageError.ExternalServiceError ->
        apiError(ChatbotApiErrorCodes.EXTERNAL_SERVICE_ERROR, "LLM API Error", "details" to llmError.toString())
    is ProcessNewMessageError.ToolExecutionError ->
        apiError(ChatbotApiErrorCodes.EXTERNAL_SERVICE_ERROR, "Tool execution error", "toolCallId" to toolCallId)
    is ProcessNewMessageError.UnexpectedError ->
        apiError(CommonApiErrorCodes.INTERNAL, "Unexpected error", "details" to message)
}
