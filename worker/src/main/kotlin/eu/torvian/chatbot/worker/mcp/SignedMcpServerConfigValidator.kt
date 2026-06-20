package eu.torvian.chatbot.worker.mcp

import eu.torvian.chatbot.common.models.api.mcp.LocalMCPServerDto
import eu.torvian.chatbot.common.security.SignedRequest

/**
 * Validates relayed Local MCP server configurations against detached signed requests.
 */
interface SignedMcpServerConfigValidator {
    /**
     * Verifies the detached signature and confirms the signed payload matches the relayed DTO.
     *
     * @param server Persisted Local MCP server DTO relayed to the worker.
     * @param signedRequest Detached signed request snapshot associated with [server], when available.
     * @return Authorization result describing whether the worker may trust the relayed configuration.
     */
    suspend fun validate(
        server: LocalMCPServerDto,
        signedRequest: SignedRequest?
    ): SignedMcpServerConfigValidationResult
}

