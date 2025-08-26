package eu.torvian.chatbot.server.service.core.error.message

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
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
}

/**
 * Extension function to convert ProcessNewMessageError to ApiError for HTTP responses.
 */
fun ProcessNewMessageError.toApiError(): ApiError = when (this) {
    is ProcessNewMessageError.ExternalServiceError ->
        apiError(ChatbotApiErrorCodes.EXTERNAL_SERVICE_ERROR, "LLM API Error", "details" to llmError.toString())
}
