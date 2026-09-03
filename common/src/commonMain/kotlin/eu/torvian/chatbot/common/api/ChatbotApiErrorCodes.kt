package eu.torvian.chatbot.common.api

/**
 * Specific API error codes for the Chatbot domain.
 */
object ChatbotApiErrorCodes {
    /**
     * Indicates that the requested operation failed due to invalid, incomplete,
     * or misconfigured settings related to an LLM (Large Language Model) or its provider.
     *
     * Example: Attempting to process a message for a session whose linked model
     * or settings cannot be found or are incorrectly configured (e.g., missing system message,
     * invalid parameters for the model type).
     * Corresponds to HTTP Status Code 400 Bad Request.
     */
    val MODEL_CONFIGURATION_ERROR = ApiErrorCode("model-configuration-error", 400)

    /**
     * Indicates that an error occurred while communicating with an external
     * service that is essential for the operation, such as an external LLM API.
     * This signifies a failure external to your application's core logic,
     * but one that prevents the request from being fulfilled.
     *
     * Example: A request to the OpenAI/Anthropic/etc. API failed due to network issues,
     * an invalid API key (if not caught earlier), or an internal error within the LLM service itself.
     * Corresponds to HTTP Status Code 500 Internal Server Error, as the failure
     * occurred downstream and was not caused by the client's request format or state.
     */
    val EXTERNAL_SERVICE_ERROR = ApiErrorCode("external-service-error", 500)

    /**
     * Indicates that an enabled automated conversation compaction could not be completed: the auxiliary
     * summarization call failed or timed out, its output was invalid, the resulting context still exceeds
     * the configured threshold, the source thread changed during persistence, or chunk persistence failed.
     * The active turn is terminated and the oversized uncompacted primary request is never sent.
     * Corresponds to HTTP Status Code 500 Internal Server Error.
     */
    val CONVERSATION_COMPACTION_FAILED = ApiErrorCode("conversation-compaction-failed", 500)
}
