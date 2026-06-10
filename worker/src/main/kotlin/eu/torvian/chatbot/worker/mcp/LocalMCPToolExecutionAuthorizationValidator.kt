package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallRequest

/**
 * Validates that a relayed Local MCP tool-call request carries a fresh detached app authorization and that
 * the signed payload still matches the execution command the worker was asked to run.
 */
interface LocalMCPToolExecutionAuthorizationValidator {
    /**
     * Verifies the detached authorization embedded in [request].
     *
     * @param request Worker-facing Local MCP execution request.
     * @return Structured authorization decision that callers can log or surface to the server.
     */
    suspend fun validate(
        request: LocalMCPToolCallRequest
    ): LocalMCPToolExecutionAuthorizationValidationResult
}