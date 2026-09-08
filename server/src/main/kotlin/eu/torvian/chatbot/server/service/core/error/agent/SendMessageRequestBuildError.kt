package eu.torvian.chatbot.server.service.core.error.agent

/**
 * Logical errors that can occur while building a
 * [eu.torvian.chatbot.common.models.agent.SendMessageRequest] from a `send_message` operator-tool
 * call.
 *
 * These errors are deliberately user-facing: the orchestrator converts them into tool-level ERROR
 * results so the calling LLM hears a clear message (e.g. "Chat session 5 not found or not owned by
 * the current user") instead of crashing the turn. They are logical errors only — never thrown.
 */
sealed class SendMessageRequestBuildError {

    /**
     * The tool-call input could not be decoded or was missing/blank a required parameter.
     *
     * @property reason Human-readable description of the malformed input.
     */
    data class InvalidInput(val reason: String) : SendMessageRequestBuildError()

    /**
     * The target chat session does not exist (no ownership row found).
     *
     * @property sessionId The target session id supplied by the model.
     */
    data class SessionNotFound(val sessionId: Long) : SendMessageRequestBuildError()

    /**
     * The target chat session exists but is owned by a different user.
     *
     * Surfaced to the LLM with the same message as [SessionNotFound] so the server never leaks
     * whether a foreign session exists.
     *
     * @property sessionId The target session id supplied by the model.
     */
    data class SessionNotAccessible(val sessionId: Long) : SendMessageRequestBuildError()
}