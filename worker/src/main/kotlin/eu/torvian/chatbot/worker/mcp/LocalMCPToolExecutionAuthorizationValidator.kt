package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.security.SignedRequest
import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolExecutionAuthorization

/**
 * Validates detached app authorization for Local MCP tool execution requests.
 *
 * The validator verifies the detached signature and decodes the authorized execution intent,
 * making the signed [LocalMCPToolExecutionAuthorization] the single source of truth for
 * execution parameters.
 */
interface LocalMCPToolExecutionAuthorizationValidator {
    /**
     * Verifies and decodes the detached authorization.
     *
     * @param signedRequest Detached signature metadata and signed authorization payload from the app.
     * @return Structured authorization decision carrying either a rejection reason or the decoded authorization.
     */
    suspend fun validate(
        signedRequest: SignedRequest
    ): LocalMCPToolExecutionAuthorizationValidationResult
}