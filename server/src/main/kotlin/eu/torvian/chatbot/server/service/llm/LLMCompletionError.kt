package eu.torvian.chatbot.server.service.llm

/**
 * Sealed class representing possible errors during an LLM chat completion request.
 * Provides structured error types instead of a generic string.
 */
sealed class LLMCompletionError {
    /**
     * Error related to network communication, inability to connect, or reading response body.
     * 
     * @property message Descriptive error message
     * @property cause The underlying exception that caused this error, if available
     */
    data class NetworkError(val message: String, val cause: Throwable?) : LLMCompletionError()

    /**
     * Error returned by the LLM provider's API (non-2xx HTTP status).
     * Contains the status code and potentially details from the error body.
     * 
     * @property statusCode The HTTP status code of the error response (e.g., 400, 401, 500)
     * @property message The error message, potentially extracted from the API response
     * @property errorBody The raw error response body for debugging purposes
     */
    data class ApiError(val statusCode: Int, val message: String?, val errorBody: String?) : LLMCompletionError()

    /**
     * Error indicating that the API returned a successful status (2xx), but the body
     * could not be parsed or did not match the expected structure.
     * 
     * @property message Descriptive error message about the parsing failure
     * @property cause The underlying exception that caused the parsing failure, if available
     */
    data class InvalidResponseError(val message: String, val cause: Throwable? = null) : LLMCompletionError()

    /**
     * Error indicating authentication failed (e.g., invalid API key, missing credentials).
     * 
     * @property message Descriptive error message about the authentication failure
     */
    data class AuthenticationError(val message: String = "Authentication failed") : LLMCompletionError()

    /**
     * Error indicating a problem with the input configuration (e.g., unsupported provider type,
     * missing required parameters in ModelSettings or LLMProvider config).
     * 
     * @property message Descriptive error message about the configuration issue
     */
    data class ConfigurationError(val message: String) : LLMCompletionError()

    /**
     * A catch-all error for unexpected issues not covered by other types.
     * 
     * @property message Descriptive error message
     * @property cause The underlying exception that caused this error, if available
     */
    data class OtherError(val message: String, val cause: Throwable? = null) : LLMCompletionError()
}
