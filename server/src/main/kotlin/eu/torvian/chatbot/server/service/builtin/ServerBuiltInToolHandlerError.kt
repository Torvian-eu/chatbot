package eu.torvian.chatbot.server.service.builtin

/**
 * Logical errors produced by the server built-in tool handlers.
 *
 * Every handler converts its typed service errors into one of these logical failures, which the
 * [DefaultServerBuiltInToolExecutor] then maps into an LLM-readable JSON error object on a terminal
 * ERROR [ToolCall]. This keeps expected failures (parse errors, user-scoped authorization denials,
 * service validation failures) out of the exception path.
 */
sealed class ServerBuiltInToolHandlerError {

    /**
     * The tool input could not be parsed into the required shape.
     *
     * @property message Human-readable explanation of the parse failure.
     */
    data class InvalidInput(val message: String) : ServerBuiltInToolHandlerError()

    /**
     * A user-scoped service operation failed with a specific, LLM-readable error code.
     *
     * @property code Machine-readable error code (e.g. `model_not_found`).
     * @property message Human-readable explanation surfaced to the LLM.
     */
    data class OperationFailed(val code: String, val message: String) : ServerBuiltInToolHandlerError()

    /**
     * The referenced resource does not exist or is not owned/accessible by the current user.
     *
     * Not-found and not-accessible collapse into this single message so handlers never leak the
     * existence of another user's resources (id-enumeration guard).
     *
     * @property message Human-readable explanation surfaced to the LLM.
     */
    data class NotFoundOrNotAccessible(val message: String) : ServerBuiltInToolHandlerError()
}
