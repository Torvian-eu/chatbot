package eu.torvian.chatbot.server.service.core.error.message

import eu.torvian.chatbot.common.api.ApiError
import eu.torvian.chatbot.common.api.ChatbotApiErrorCodes
import eu.torvian.chatbot.common.api.CommonApiErrorCodes
import eu.torvian.chatbot.common.api.apiError
import eu.torvian.chatbot.server.service.core.chat.compaction.ConversationCompactionError
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
     * Indicates that the enabled automated compaction policy aborted the turn before the primary call.
     *
     * Covers invalid/incompatible compaction configuration, auxiliary generation/timeout/output
     * failures, insufficient reduction, source races, and chunk persistence failures. The oversized
     * uncompacted primary request is never sent.
     *
     * @property error Typed compaction failure category.
     */
    data class ConversationCompactionFailed(val error: ConversationCompactionError) : ProcessNewMessageError

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
    is ProcessNewMessageError.ConversationCompactionFailed -> error.toApiError()
    is ProcessNewMessageError.UnexpectedError ->
        apiError(CommonApiErrorCodes.INTERNAL, "Unexpected error", "details" to message)
}

/**
 * Maps a compaction failure to the public API error shape.
 *
 * Configuration and compatibility categories surface as the existing model-configuration error;
 * generation/output/reduction/source/persistence failures surface as
 * [ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED]. Provider details are deliberately sanitized
 * to the compact error reason, never the raw provider body.
 *
 * @receiver The typed compaction error.
 * @return The corresponding [ApiError].
 */
fun ConversationCompactionError.toApiError(): ApiError = when (this) {
    is ConversationCompactionError.InvalidConfiguration ->
        apiError(ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR, reason)

    is ConversationCompactionError.UnsupportedConfiguration ->
        apiError(ChatbotApiErrorCodes.MODEL_CONFIGURATION_ERROR, reason)

    is ConversationCompactionError.GenerationFailed ->
        apiError(ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED, reason)

    ConversationCompactionError.TimedOut ->
        apiError(ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED, "Conversation compaction timed out")

    is ConversationCompactionError.InvalidOutput ->
        apiError(ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED, reason)

    is ConversationCompactionError.InsufficientReduction ->
        apiError(
            ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED,
            "Compaction did not reduce the context enough",
            "sourceTokenCount" to sourceTokenCount.toString(),
            "resultTokenCount" to resultTokenCount.toString(),
            "thresholdTokens" to thresholdTokens.toString()
        )

    is ConversationCompactionError.SourceChanged ->
        apiError(ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED, reason)

    is ConversationCompactionError.PersistenceFailed ->
        apiError(ChatbotApiErrorCodes.CONVERSATION_COMPACTION_FAILED, reason)
}
