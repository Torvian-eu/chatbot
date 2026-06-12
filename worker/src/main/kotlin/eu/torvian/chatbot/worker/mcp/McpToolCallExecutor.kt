package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPToolCallResult
import eu.torvian.chatbot.common.security.SignedRequest

/**
 * Executes worker-side MCP tool calls validated against a detached signed authorization.
 *
 * The executor validates the signed authorization and executes the tool using the decoded
 * authorization as the single source of truth for execution parameters.
 */
interface McpToolCallExecutor {

    /**
     * Executes one MCP tool call by validating the signed authorization and executing the tool.
     *
     * @param signedRequest Detached signature metadata and signed authorization payload from the app.
     * @return Final mapped tool-call result for protocol response emission.
     */
    suspend fun execute(signedRequest: SignedRequest): LocalMCPToolCallResult
}

